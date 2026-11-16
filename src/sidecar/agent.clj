(ns sidecar.agent
  "Agents attached to buffers.

  An attachment joins three things: a buffer in Emacs, a feed and an
  outbox on disk, and (unless :box? is false) a container that mounts
  them.  The manager

  - writes what happens in the buffer to the feed: its text after each
    change, runs with their output, saves, what the gate decided;
  - takes the agent's edits from the outbox, has Emacs apply each one,
    and answers it;
  - keeps the feed's heartbeat, and watches the cursor and the container.

  It tells the console what it does with events of its own, through the
  same `emit` the Emacs events come through:

      {:event \"agent\" :name .. :file .. :status \"up\"|\"gone\"|\"error\" ..}
      {:event \"edit\" :name .. :file .. :ok true|false :line .. :error ..}"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [sidecar.emacs :as emacs]
            [sidecar.feed :as feed]
            [sidecar.org :as org]
            [sidecar.sandbox :as sandbox]))

(def poll-ms 300)
(def heartbeat-ms 3000)
(def box-check-ms 5000)
(def max-output-chars (* 64 1024))

(defn agent-name
  "A name for the agent on `file`: its title part, as a slug."
  [file]
  (let [base (-> (str (fs/file-name file))
                 (str/replace #"\.org$" "")
                 (str/replace #"^\d{8}T\d{6}--" "")
                 (str/replace #"__.*$" "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? base) "notebook" (subs base 0 (min 40 (count base))))))

(defn manager
  "A manager for `link`, telling `emit` what it does.  `opts`: :root, the
  directory agents' feeds and outboxes go under (default: the cache)."
  [link emit & [opts]]
  {:link link :emit emit
   :root (str (or (:root opts) (emacs/cache-path "agents")))
   :agents (atom {})
   :stopped? (atom false)})

(defn agents [mgr] (vals @(:agents mgr)))

(defn- cap [s]
  (let [s (str s)]
    (if (<= (count s) max-output-chars)
      s
      (let [half (quot max-output-chars 2)]
        (str (subs s 0 half) "\n… " (- (count s) (* 2 half)) " characters left out …\n"
             (subs s (- (count s) half)))))))

(defn- summary
  "What the console is told of attachment `att`."
  [{:keys [name file feed outbox box status behind applied refused error]}]
  {:event "agent" :time (emacs/now) :name name :file file :status status
   :box box :behind behind :applied applied :refused refused :error error
   :seq (:seq @feed) :feed (:dir @feed) :outbox outbox
   :shell (when box (sandbox/shell-command name))})

(defn- tell! [mgr name]
  (when-let [att (get @(:agents mgr) name)]
    ((:emit mgr) (summary att))))

(defn- update-att! [mgr name f & args]
  (swap! (:agents mgr) (fn [m] (if (contains? m name) (apply update m name f args) m))))

;;; Buffer to feed

(defn- by-file [mgr file]
  (first (filter #(= file (:file %)) (agents mgr))))

(defn- for-feed
  "The keys `ks` of Emacs event `e`, as the agent sees it: the notebook by
  its file name, never the host's path."
  [e ks]
  (cond-> (select-keys e (conj ks :event :time))
    (:file e) (assoc :file (str (fs/file-name (:file e))))))

(defn- run-event [e]
  (for-feed e [:phase :status :id :file :line :name :lang :outline :parent :ms :exit :async :terminal]))

(defn handle-event!
  "Write Emacs event `e` to the feed of the agent on its file, if there is one."
  [mgr e]
  (case (:event e)
    "emacs"
    (when (= "up" (:status e))
      ;; a new Emacs, or the same one back: the buffers are not attached there
      (doseq [{:keys [name file]} (agents mgr)]
        (try (emacs/call (:link mgr) 'sidecar-attach file name)
             (catch Exception ex
               (update-att! mgr name assoc :status "error" :error (ex-message ex))
               (tell! mgr name)))))

    (when-let [{:keys [name feed text bodies]} (some->> (:file e) (by-file mgr))]
      (case (:event e)
        "text"
        (let [new (:text e)]
          (when (not= new text)
            (feed/put-notebook! feed new)
            (feed/append! feed {:event "change" :time (:time e)
                                :causes (:causes e)
                                :modified (boolean (:modified e))
                                :headings (org/changed-sections text new)})
            (update-att! mgr name assoc :text new)))

        "run"
        (do (case (:phase e)
              "start" (update-att! mgr name assoc-in [:bodies (:id e)] (:body e))
              "end"   (do (feed/put-output! feed (:id e)
                                            (-> (run-event e)
                                                (dissoc :event :phase)
                                                (assoc :error (:error e)
                                                       :stderr (some-> (:stderr e) cap)
                                                       :output (cap (:output e))
                                                       :input (get bodies (:id e)))))
                          (update-att! mgr name update :bodies dissoc (:id e)))
              nil)
            (feed/append! feed (run-event e)))

        ("save" "revert" "gate")
        (feed/append! feed (for-feed e [:file :line :decision :reason :modified]))

        "kill"
        (do (feed/append! feed (for-feed e [:file]))
            (update-att! mgr name assoc :status "error" :error "The buffer was killed; detach, or attach again")
            (tell! mgr name))
        nil))))

;;; Outbox to buffer

(defn- apply-edit!
  "Have Emacs apply one edit from the outbox; answer it, and say so."
  [mgr {:keys [name file feed]} {:keys [id edit error]}]
  (let [result (cond
                 error {:ok false :error error}
                 (not (#{"append" "replace"} (:op edit))) {:ok false :error "the edit's op is append or replace"}
                 :else
                 (try
                   (let [r (emacs/call (:link mgr) 'sidecar-apply-edit file
                                       (select-keys edit [:op :old :new :text :under]))]
                     {:ok true :line (:line r)})
                   (catch Exception ex
                     ;; "refused:" is Emacs declining to write what Org or
                     ;; Emacs would evaluate; anything else did not fit
                     (let [why (ex-message ex)]
                       (cond-> {:ok false :error (str/replace why #"^refused: " "")}
                         (str/starts-with? why "refused: ") (assoc :unsafe true))))))]
    (feed/put-reply! feed id result)
    (feed/append! feed (merge {:event "edit" :time (emacs/now) :id id :op (:op edit)} result))
    (update-att! mgr name update (if (:ok result) :applied :refused) (fnil inc 0))
    ((:emit mgr) (merge {:event "edit" :time (emacs/now) :name name :file file
                         :op (:op edit) :id id}
                        result))
    (tell! mgr name)))

(defn poll!
  "One round for every attachment: edits, the cursor, the heartbeat, the box."
  [mgr]
  (doseq [{:keys [name feed outbox box] :as att} (agents mgr)]
    (try
      (doseq [edit (feed/take-edits! outbox)]
        (apply-edit! mgr att edit))
      (let [now    (System/currentTimeMillis)
            behind (feed/behind feed (feed/read-cursor outbox))]
        (when (not= behind (:behind att))
          (update-att! mgr name assoc :behind behind)
          (tell! mgr name))
        (when (> (- now (:beat-at att 0)) heartbeat-ms)
          (feed/heartbeat! feed)
          (update-att! mgr name assoc :beat-at now))
        (when (and box (= "up" (:status att)) (> (- now (:box-at att 0)) box-check-ms))
          (update-att! mgr name assoc :box-at now)
          (when-not (sandbox/running? name)
            (update-att! mgr name assoc :status "error" :error "The sandbox is not running")
            (tell! mgr name))))
      (catch Exception ex
        ((:emit mgr) {:event "problem" :kind "sidecar" :time (emacs/now)
                      :message (str "agent " name ": " (ex-message ex))})))))

(defn start-polling!
  "Poll on a thread of its own until the manager is stopped."
  [mgr]
  (doto (Thread. ^Runnable (fn []
                             (while (not @(:stopped? mgr))
                               (poll! mgr)
                               (Thread/sleep (long poll-ms)))))
    (.setName "sidecar-agents")
    (.setDaemon true)
    (.start))
  mgr)

;;; Attach, detach

(defn attach!
  "Attach an agent to the buffer visiting `file`; return its summary.
  `opts`: :name (default: from the file's name), :box? (default true;
  false leaves the feed and outbox for whatever mounts them)."
  [mgr file & [{:keys [name box?] :or {box? true}}]]
  (let [file (str (fs/canonicalize file))
        name (or name (agent-name file))]
    (when-not (re-matches #"[a-z0-9][a-z0-9-]{0,39}" name)
      (throw (ex-info "An agent's name is lower-case letters, digits and dashes" {:name name})))
    (when (get @(:agents mgr) name)
      (throw (ex-info (str "An agent named " name " is attached already") {:name name})))
    (when-let [other (by-file mgr file)]
      (throw (ex-info (str "This notebook has the agent " (:name other)) {:name (:name other)})))
    (let [dir    (fs/path (:root mgr) name)
          outbox (str (fs/path dir "outbox"))
          _      (do (fs/delete-tree (fs/path outbox "edits"))
                     (fs/create-dirs (fs/path outbox "edits")))
          state  (emacs/call (:link mgr) 'sidecar-attach file name)
          feed   (feed/open! (fs/path dir "feed") {:name name :notebook (str (fs/file-name file))})
          _      (feed/put-notebook! feed (:text state))
          box    (when box?
                   (try (sandbox/start! name {:feed (:dir @feed) :outbox outbox})
                        (catch Exception ex
                          (feed/close! feed)
                          (try (emacs/call (:link mgr) 'sidecar-detach file) (catch Exception _))
                          (throw ex))))
          att    {:name name :file file :feed feed :outbox outbox :box box
                  :text (:text state) :bodies {} :status "up"
                  :behind 0 :applied 0 :refused 0}]
      (swap! (:agents mgr) assoc name att)
      (tell! mgr name)
      (summary att))))

(defn detach!
  "End the attachment `name`: the feed says detached, the box is removed.
  The agent's text stays in the buffer, marked until accepted."
  [mgr name]
  (when-let [{:keys [file feed box]} (get @(:agents mgr) name)]
    (swap! (:agents mgr) dissoc name)
    (try (emacs/call (:link mgr) 'sidecar-detach file) (catch Exception _))
    (feed/close! feed)
    (when box (sandbox/stop! name))
    ((:emit mgr) {:event "agent" :time (emacs/now) :name name :file file :status "gone"})
    true))

(defn stop!
  "Detach every agent and stop polling."
  [mgr]
  (reset! (:stopped? mgr) true)
  (doseq [{:keys [name]} (agents mgr)]
    (detach! mgr name)))

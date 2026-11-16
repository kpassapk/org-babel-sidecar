(ns sidecar.box
  "The agent's commands, run inside the sandbox as `sidecar`.

  They read the feed (SIDECAR_FEED, /feed) and write the outbox
  (SIDECAR_OUTBOX, /outbox), and reach nothing else: not Emacs, not the
  host.

      sidecar status
      sidecar notebook                  the path of the notebook's text
      sidecar follow [--wake [MS]] [--once | --count N] [--json] [--after SEQ]
      sidecar ack SEQ
      sidecar output RUN [--json]
      sidecar append [--under 'A / B'] [TEXT]        TEXT or stdin
      sidecar replace --old S|--old-file F [--new S|--new-file F]   new: or stdin"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [sidecar.feed :as feed]))

(def poll-ms 300)
(def stale-ms 15000)

(defn- dirs [opts]
  {:feed   (or (:feed opts) (System/getenv "SIDECAR_FEED") "/feed")
   :outbox (or (:outbox opts) (System/getenv "SIDECAR_OUTBOX") "/outbox")})

(defn- fail! [code & words]
  (binding [*out* *err*] (println (str/join " " words)))
  (throw (ex-info "exit" {:exit code})))

(defn- parse
  "`args` as [opts positional]: `--key value`, and `--flag` for the keys in `flags`.
  A flag in `optional` takes the next argument only if it is a number."
  [args {:keys [flags optional]}]
  (loop [args args, opts {}, pos []]
    (if-let [[a & more] (seq args)]
      (if (and (str/starts-with? a "--") (> (count a) 2))
        (let [k (keyword (subs a 2))]
          (cond
            (contains? flags k)    (recur more (assoc opts k true) pos)
            (contains? optional k) (if (some->> (first more) (re-matches #"\d+"))
                                     (recur (rest more) (assoc opts k (parse-long (first more))) pos)
                                     (recur more (assoc opts k true) pos))
            (empty? more)          (fail! 2 "error:" a "needs a value")
            :else                  (recur (rest more) (assoc opts k (first more)) pos)))
        (recur more opts (conj pos a)))
      [opts pos])))

;;; Reading

(defn- status
  "The feed's status.json, or nil when there is no feed.  The sidecar
  replaces the file every few seconds, and across a container's mount
  the replacement can be seen as a missing file for a moment: a miss is
  asked again before it is believed."
  [{:keys [feed]}]
  (let [path (fs/path feed feed/status-file)]
    (loop [tries 5]
      (or (feed/read-json path)
          (when (and (pos? tries) (fs/exists? feed))
            (Thread/sleep 60)
            (recur (dec tries)))))))

(defn- cursor [{:keys [outbox]}] (feed/read-cursor outbox))

(defn- last-seq
  "The seq of the feed's last event.  status.json is only as new as the
  last heartbeat, so the events are asked."
  [{:keys [feed]} status]
  (max (or (:seq status) 0)
       (or (:seq (last (feed/read-events (fs/path feed feed/events-file)))) 0)))

(defn- notebook-path [{:keys [feed] :as d}]
  (when-let [nb (:notebook (status d))]
    (str (fs/path feed "notebook" nb))))

(defn line
  "One event as `follow` prints it."
  [{:keys [seq event phase status decision file line id name outline causes headings ok error op]}]
  (let [where (when file (str (fs/file-name file) (when line (str ":" line))))
        path  (fn [p] (str "[" (str/join " / " p) "]"))]
    (->> (case event
           "run"    [seq "run" (or status phase) where id (when name (str "#" name))
                     (when (not-empty outline) (path outline))]
           "change" (concat [seq "change" (str/join "+" causes)] (map path headings))
           "gate"   [seq "gate" decision where]
           "edit"   [seq "edit" (if ok "applied" "refused") op (when line (str "line " line)) error]
           [seq event where])
         (remove nil?)
         (str/join " "))))

(defn needs-action?
  "Whether `--wake` shows the event: a run that ended, what the operator
  changed, saved or refused.  Not the agent's own changes coming back, a
  run that has only started, or a block the operator let run (its result
  follows)."
  [{:keys [event phase causes decision]}]
  (case event
    "run"    (= "end" phase)
    "change" (boolean (some #{"operator"} causes))
    "gate"   (contains? #{"declined" "refused"} decision)
    "edit"   false
    true))

(defn- write-cursor! [{:keys [outbox]} epoch seq]
  (feed/put-json! (fs/path outbox feed/cursor-file) {:epoch epoch :seq seq}))

;;; Commands

(defn- cmd-status [d _]
  (let [st (status d)
        c  (cursor d)]
    (when-not st (fail! 1 "error: no feed at" (:feed d)))
    (let [n (last-seq d st)]
      (println (json/generate-string
                (assoc st
                       :seq n
                       :notebook-path (notebook-path d)
                       :cursor c
                       :behind (if (= (:epoch st) (:epoch c)) (max 0 (- n (:seq c))) n)))))))

(defn- cmd-notebook [d _]
  (if-let [p (notebook-path d)]
    (println p)
    (fail! 1 "error: no feed at" (:feed d))))

(defn- cmd-ack [d [arg]]
  (let [seq (some-> arg parse-long)
        st  (status d)
        c   (cursor d)]
    (cond
      (nil? seq) (fail! 2 "usage: sidecar ack SEQ")
      (nil? st)  (fail! 1 "error: no feed at" (:feed d))
      (> seq (last-seq d st)) (fail! 1 "error: the feed's last event is" (last-seq d st))
      (and (= (:epoch st) (:epoch c)) (<= seq (:seq c))) (println "acked" (:seq c))
      :else (do (write-cursor! d (:epoch st) seq) (println "acked" seq)))))

(defn- cmd-output [d args]
  (let [[opts [id]] (parse args {:flags #{:json}})
        out (when (and id (re-matches #"[0-9a-f]{6,64}" id))
              (feed/read-json (fs/path (:feed d) "outputs" (str id ".json"))))]
    (cond
      (nil? id)   (fail! 2 "usage: sidecar output RUN [--json]")
      (nil? out)  (fail! 1 "error: no output for run" id)
      (:json opts) (println (json/generate-string out))
      :else
      (do (println (str "status: " (:status out)
                        (when (:exit out) (str "  exit: " (:exit out)))
                        (when (:ms out) (str "  " (:ms out) " ms"))))
          (when (:error out) (println (str "error: " (:error out))))
          (when (seq (:stderr out)) (println "--- stderr") (println (str/trimr (:stderr out))))
          (println "--- output")
          (println (str/trimr (str (:output out))))))))

(defn- submit!
  "Put `edit` in the outbox and wait for the sidecar's answer."
  [d edit timeout-ms]
  (when-not (:attached (status d))
    (fail! 1 "refused: the notebook is not attached"))
  (let [id    (str (random-uuid))
        reply (fs/path (:feed d) "replies" (str id ".json"))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (feed/put-json! (fs/path (:outbox d) "edits" (str id ".json")) edit)
    (loop []
      (if-let [r (feed/read-json reply)]
        (if (:ok r)
          (println (str "applied at line " (:line r)))
          (fail! 1 (str "refused: " (:error r))))
        (if (> (System/currentTimeMillis) deadline)
          (fail! 2 "no answer: the sidecar has not taken the edit; see `sidecar status`")
          (do (Thread/sleep 50) (recur)))))))

(defn- text-of [opts k]
  (cond
    (contains? opts k) (str (opts k))
    (contains? opts (keyword (str (name k) "-file"))) (slurp (opts (keyword (str (name k) "-file"))))))

(defn- timeout [opts] (* 1000 (or (some-> (:timeout opts) parse-long) 15)))

(defn- cmd-append [d args]
  (let [[opts pos] (parse args {})
        text (if (seq pos) (str/join " " pos) (slurp *in*))]
    (when (str/blank? text) (fail! 2 "usage: sidecar append [--under 'A / B'] [TEXT], or the text on stdin"))
    (submit! d (cond-> {:op "append" :text text}
                 (:under opts) (assoc :under (mapv str/trim (str/split (:under opts) #"\s+/\s+"))))
             (timeout opts))))

(defn- cmd-replace [d args]
  (let [[opts _] (parse args {})
        old (text-of opts :old)
        new (or (text-of opts :new) (slurp *in*))]
    (when (str/blank? old) (fail! 2 "usage: sidecar replace --old S|--old-file F [--new S|--new-file F], or the new text on stdin"))
    (submit! d {:op "replace" :old old :new new} (timeout opts))))

;;; follow

(defn- out! [& words] (println (str/join " " words)) (flush))

(defn- follow-state
  "Where `follow` starts: after the cursor, or after `--after`."
  [d after]
  (let [c (cursor d)]
    {:epoch (:epoch c) :printed (or after (:seq c) 0) :noticed #{} :count 0
     :beat nil :beat-at (System/currentTimeMillis)}))

(defn- new-events
  "[state events]: the feed's events after what was printed.  A feed of
  another epoch than the one followed first gives the rest of the old
  one, then starts again at 1; both are said."
  [d {:keys [epoch printed] :as st}]
  (let [status (status d)]
    (if (nil? status)
      [(cond-> st
         (not ((:noticed st) :none)) (-> (update :noticed conj :none)
                                         (assoc :notices [(str "! no feed yet at " (:feed d))])))
       []]
      (let [restarted? (and epoch (not= epoch (:epoch status)))
            old  (when restarted?
                   (filter #(and (= epoch (:epoch %)) (> (:seq %) printed))
                           (feed/read-events (fs/path (:feed d) feed/prev-events-file))))
            printed (if (or restarted? (nil? epoch)) (if epoch 0 printed) printed)
            events (filter #(> (:seq %) printed)
                           (feed/read-events (fs/path (:feed d) feed/events-file)))
            before  (when (seq old)
                      [(str "! the rest of the feed before this one, which has ended: "
                            (count old) " events")])
            notices (cond-> []
                      restarted? (conj (str "! the feed restarted at " (:epoch status)
                                            ": seq starts again at 1"))
                      (and (false? (:attached status)) (not ((:noticed st) :detached)))
                      (conj "! detached: the operator ended the session, or the sidecar stopped"))
            now  (System/currentTimeMillis)
            beat? (not= (:heartbeat status) (:beat st))
            stale? (and (:attached status) (not beat?) (> (- now (:beat-at st)) stale-ms)
                        (not ((:noticed st) :stale)))]
        [(-> st
             (assoc :epoch (:epoch status) :printed printed
                    :old (vec old) :before before
                    :notices (cond-> notices
                               stale? (conj (str "! the sidecar's heartbeat has not changed for "
                                                 (quot (- now (:beat-at st)) 1000) " s"))))
             (cond-> beat? (-> (assoc :beat (:heartbeat status) :beat-at now)
                               (update :noticed disj :stale))
                     stale? (update :noticed conj :stale)
                     (false? (:attached status)) (update :noticed conj :detached)
                     (:attached status) (update :noticed disj :detached :none)))
         (vec events)]))))

(defn- print-batch!
  "Print `events`; with `wake?` only those that need action, and `! ack N`
  when the last one is not among them.  Returns how many lines it printed."
  [events {:keys [json wake?]}]
  (let [shown (if wake? (filter needs-action? events) events)]
    (doseq [e shown]
      (out! (if json (json/generate-string e) (line e))))
    (when (and wake? (seq events) (not (needs-action? (last events))))
      (out! "! ack" (:seq (last events))))
    (count shown)))

(defn- cmd-follow [d args]
  (let [[opts _] (parse args {:flags #{:once :json :raw} :optional #{:wake}})
        wake?   (boolean (:wake opts))
        quiet   (if (number? (:wake opts)) (:wake opts) 750)
        limit   (some-> (:count opts) parse-long)
        popts   {:json (:json opts) :wake? wake?}]
    (loop [st (follow-state d (some-> (:after opts) parse-long))
           batch [] first-at nil last-at nil]
      (let [[st events] (new-events d st)
            now   (System/currentTimeMillis)]
        (doseq [n (:before st)] (out! n))
        (when (seq (:old st)) (print-batch! (:old st) popts))
        (doseq [n (:notices st)] (out! n))
        (let [st (dissoc st :notices :before :old)
              st (cond-> st (seq events) (assoc :printed (:seq (last events))))
              batch (into batch events)
              first-at (if (and (seq events) (nil? first-at)) now first-at)
              last-at  (if (seq events) now last-at)
              due? (and (seq batch)
                        (or (not wake?) (:once opts)
                            (>= (- now last-at) quiet)
                            (>= (- now first-at) 3000)))
              n   (if due? (print-batch! batch popts) 0)
              st  (update st :count + n)]
          (cond
            (:once opts) nil
            (and limit (>= (:count st) limit)) nil
            :else (do (Thread/sleep poll-ms)
                      (if due?
                        (recur st [] nil nil)
                        (recur st batch first-at last-at)))))))))

(def ^:private commands
  {"status" cmd-status "notebook" cmd-notebook "follow" cmd-follow "ack" cmd-ack
   "output" cmd-output "append" cmd-append "replace" cmd-replace})

(defn run
  "Run the command in `args`; return its exit code."
  [args]
  (let [[opts [cmd & more]] (loop [args args, opts {}]
                              ;; --feed and --outbox may come before the command
                              (if (#{"--feed" "--outbox"} (first args))
                                (recur (drop 2 args) (assoc opts (keyword (subs (first args) 2)) (second args)))
                                [opts args]))]
    (try
      (if-let [f (commands cmd)]
        (do (f (dirs opts) more) 0)
        (do (binding [*out* *err*]
              (println "usage: sidecar status | notebook | follow | ack SEQ | output RUN | append | replace"))
            2))
      (catch clojure.lang.ExceptionInfo e
        (or (:exit (ex-data e)) (throw e))))))

(defn -main [& args]
  (let [code (run args)]
    (flush)
    (when-not (zero? code) (System/exit code))))

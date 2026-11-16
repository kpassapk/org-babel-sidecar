(ns sidecar.console
  "The console: charm.clj's Elm loop over the model.

  `update` turns a message into the next state and maybe a command;
  `view` (sidecar.view) draws the state.  Events from Emacs and from the
  agent manager arrive on a queue, which one command waits on, a quarter
  of a second at a time: that wait is also the clock.

  Commands are the only place anything is read or done: the queue, the
  index's files, a notebook's text, and what the keys ask of Emacs or of
  the agent manager."
  (:require [babashka.process :as p]
            [charm.message :as msg]
            [charm.program :as charm]
            [clojure.string :as str]
            [sidecar.agent :as agent]
            [sidecar.emacs :as emacs]
            [sidecar.index :as index]
            [sidecar.model :as model]
            [sidecar.org :as org]
            [sidecar.sandbox :as sandbox]
            [sidecar.view :as view])
  (:import (java.time LocalDateTime)
           (java.util ArrayList)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def tick-ms 250)
(def index-ms 5000)
(def outline-ms 2000)

;;; Commands

(defn- wait-cmd [{:keys [^LinkedBlockingQueue queue]}]
  (charm/cmd (fn []
               (let [head (.poll queue tick-ms TimeUnit/MILLISECONDS)
                     more (ArrayList.)]
                 (.drainTo queue more)
                 {:type :events :events (cond->> (vec more) head (cons head))}))))

(defn- index-cmd [{:keys [dir]}]
  (charm/cmd (fn []
               {:type :index
                :scanned (try (index/scan dir) (catch Exception _ []))})))

(defn- outline-cmd [{:keys [link]} file]
  (charm/cmd (fn []
               (let [held (when (emacs/up? link)
                            (try (emacs/call link 'sidecar-text file) (catch Exception _ nil)))
                     text (or (:text held) (try (slurp file) (catch Exception _ nil)))]
                 {:type :outline :file file
                  :outline (when text (org/parse text))
                  :unsaved (boolean (:modified held))}))))

(defn- doing
  "A command that runs `f` and reports what it returns, a string or nil,
  or the error it throws, in the footer."
  [f]
  (charm/cmd (fn []
               {:type :did
                :message (try (f) (catch Exception e (str (ex-message e))))})))

(defn- visit-cmd [{:keys [link]} file line]
  (when file
    (doing #(do (if line
                  (emacs/call link 'sidecar-visit file line)
                  (emacs/call link 'sidecar-visit file))
                nil))))

(defn- attach-cmd [{:keys [mgr box?]} file]
  (doing #(let [a (agent/attach! mgr file {:box? box?})]
            (str "Attached " (:name a) ". "
                 (if (:shell a)
                   (str "Talk to it: " (:shell a) "   (s on the agent opens it)")
                   (str "No sandbox: feed " (:feed a)))))))

(defn- detach-cmd [{:keys [mgr]} name]
  (doing #(do (agent/detach! mgr name) (str "Detached " name "."))))

(defn- shell-cmd [_ {:keys [name box shell]}]
  (doing #(cond
            (nil? box) "This agent has no sandbox."
            (System/getenv "TMUX")
            (do (p/shell {:out :string :err :string} "tmux" "new-window" "-n" (str "agent:" name) shell)
                (str "Opened a tmux window for " name "."))
            :else (str "Run: " shell))))

;;; State

(defn init-state [dir]
  {:model model/empty-model
   :dir (str dir)
   :scanned [] :entries []
   :notebook nil
   :now (LocalDateTime/now)
   :width 80 :height 24
   :index-at 0 :outline-at 0 :outline-stale? false :outline-pending? false
   :ui {:pane :index
        :cursor (zipmap view/panes (repeat 0))
        :top (zipmap view/panes (repeat 0))
        :filter "" :filtering? false :follow? true
        :detail nil :message nil :confirm nil}})

(defn- entries [{:keys [scanned model ui]}]
  (->> (index/with-open-buffers scanned (keys (:buffers model)))
       (filterv #(index/matches? (:filter ui) %))))

(defn- clamp
  "Every cursor on an item, and every pane's top over its cursor."
  [state]
  (reduce (fn [state pane]
            (let [n      (count (view/items state pane))
                  rows   (view/rows-in state pane)
                  cursor (max 0 (min (get-in state [:ui :cursor pane] 0) (dec n)))
                  top    (get-in state [:ui :top pane] 0)
                  top    (cond (< cursor top)           cursor
                               (>= cursor (+ top rows)) (inc (- cursor rows))
                               :else                    top)]
              (-> state
                  (assoc-in [:ui :cursor pane] cursor)
                  (assoc-in [:ui :top pane] (max 0 (min top (max 0 (- n rows))))))))
          state view/panes))

(defn- refresh-entries
  "Recompute the index, keeping the cursor on the file it was on."
  [state]
  (let [file  (:file (nth (:entries state) (get-in state [:ui :cursor :index]) nil))
        new   (entries state)
        at    (first (keep-indexed (fn [i e] (when (= file (:file e)) i)) new))]
    (clamp (cond-> (assoc state :entries new)
             at (assoc-in [:ui :cursor :index] at)))))

(defn- selected [state pane]
  (nth (view/items state pane) (get-in state [:ui :cursor pane]) nil))

(defn- show-notebook
  "Make `file` the notebook pane's; its outline is asked for."
  [state file]
  (if (= file (get-in state [:notebook :file]))
    state
    (-> state
        (assoc :notebook {:file file :outline nil} :outline-stale? true)
        (assoc-in [:ui :cursor :notebook] 0)
        (assoc-in [:ui :top :notebook] 0))))

(defn- follow
  "With follow on, the notebook pane shows where the operator is."
  [state]
  (let [{:keys [file]} (get-in state [:model :focus])]
    (if (and file (get-in state [:ui :follow?]))
      (let [state (show-notebook state file)
            at    (view/focus-index state)]
        (cond-> state at (assoc-in [:ui :cursor :notebook] at)))
      state)))

(defn- move [state pane f]
  (clamp (update-in state [:ui :cursor pane] f)))

(defn- message [state text] (assoc-in state [:ui :message] text))

(def ^:private touches-text #{"save" "revert" "open" "kill" "text" "edit"})

(defn- add-events [state events]
  (let [file   (get-in state [:notebook :file])
        model  (reduce model/add-event (:model state) events)
        stale? (some #(and (= file (:file %)) (touches-text (:event %))) events)
        focus? (some #(= "focus" (:event %)) events)
        index? (some #(#{"buffers" "open" "kill" "emacs"} (:event %)) events)]
    (cond-> (assoc state :model model)
      stale? (assoc :outline-stale? true)
      focus? follow
      index? refresh-entries)))

;;; Keys

(defn- key? [m & ks] (some #(msg/key-match? m %) ks))

(defn- target
  "[file line] of the thing under the cursor, for `e`."
  [state]
  (let [pane (get-in state [:ui :pane])
        x    (selected state pane)]
    (case pane
      :index    [(:file x) nil]
      :notebook [(get-in state [:notebook :file]) (:line x)]
      :runs     [(:file x) (:line x)]
      :problems [(:file x) (:line x)]
      :agents   [(:file x) nil])))

(defn- open
  "⏎ on the thing under the cursor."
  [env state]
  (let [pane (get-in state [:ui :pane])
        x    (selected state pane)]
    (if (nil? x)
      [state nil]
      (case pane
        :index    [(-> (show-notebook state (:file x))
                       (assoc-in [:ui :follow?] false)
                       (assoc-in [:ui :pane] :notebook))
                   nil]
        :notebook [state (visit-cmd env (get-in state [:notebook :file]) (:line x))]
        :runs     [(assoc-in state [:ui :detail] {:kind :run :id (:id x) :scroll 0}) nil]
        :problems [(assoc-in state [:ui :detail] {:kind :problem :item x :scroll 0}) nil]
        :agents   [(assoc-in state [:ui :detail] {:kind :agent :id (:name x) :scroll 0}) nil]))))

(defn- attach [env state]
  (let [pane (get-in state [:ui :pane])
        file (case pane
               :index    (:file (selected state :index))
               :notebook (get-in state [:notebook :file])
               nil)]
    (cond
      (nil? file) [(message state "Choose a notebook in the index first.") nil]
      (model/agent-on (:model state) file)
      [(message state (str "This notebook has the agent " (:name (model/agent-on (:model state) file)) ".")) nil]
      (not= "up" (get-in state [:model :emacs :status]))
      [(message state "Emacs is out of reach.") nil]
      :else [(message state "Attaching…") (attach-cmd env file)])))

(defn- filter-key [state m]
  (let [q (get-in state [:ui :filter])]
    (cond
      (key? m :enter)     (assoc-in state [:ui :filtering?] false)
      (key? m :escape)    (-> state (assoc-in [:ui :filter] "") (assoc-in [:ui :filtering?] false) refresh-entries)
      (key? m :backspace) (-> state (assoc-in [:ui :filter] (subs q 0 (max 0 (dec (count q))))) refresh-entries)
      (and (msg/key-press? m) (string? (:key m)) (not (:ctrl m)) (not (:alt m)))
      (-> state (update-in [:ui :filter] str (:key m)) refresh-entries)
      (key? m :space " ") (-> state (update-in [:ui :filter] str " ") refresh-entries)
      :else state)))

(defn- cycle-pane [state step]
  (let [i (.indexOf ^java.util.List view/panes (get-in state [:ui :pane]))]
    (assoc-in state [:ui :pane] (nth view/panes (mod (+ i step) (count view/panes))))))

(defn- detail-key [env state m]
  (let [rows   (view/detail-rows state)
        scroll (fn [f] [(update-in state [:ui :detail :scroll] #(max 0 (f %))) nil])
        {:keys [kind id item]} (get-in state [:ui :detail])]
    (cond
      (key? m :escape :left "h" :enter)  [(assoc-in state [:ui :detail] nil) nil]
      (key? m "j" :down)                 (scroll inc)
      (key? m "k" :up)                   (scroll dec)
      (key? m " " :space :page-down)     (scroll #(+ % rows))
      (key? m :page-up)                  (scroll #(- % rows))
      (key? m "g")                       (scroll (constantly 0))
      (key? m "G")                       (scroll (constantly (max 0 (- (count (:lines (view/detail state))) rows))))
      (key? m "e")
      (let [x (case kind
                :run     (get-in state [:model :runs id])
                :problem item
                :agent   (get-in state [:model :agents id])
                nil)]
        [state (visit-cmd env (:file x) (:line x))])
      :else [state nil])))

(defn- main-key [env state m]
  (let [pane (get-in state [:ui :pane])
        rows (view/rows-in state pane)]
    (cond
      (key? m :tab)                    [(cycle-pane state 1) nil]
      (key? m "shift+tab" :backtab)    [(cycle-pane state -1) nil]
      (some #(key? m (str %)) (range 1 6))
      [(assoc-in state [:ui :pane] (nth view/panes (dec (parse-long (:key m))))) nil]

      (key? m "j" :down)               [(move state pane inc) nil]
      (key? m "k" :up)                 [(move state pane dec) nil]
      (key? m " " :space :page-down)   [(move state pane #(+ % rows)) nil]
      (key? m :page-up)                [(move state pane #(- % rows)) nil]
      (key? m "g" :home)               [(move state pane (constantly 0)) nil]
      (key? m "G" :end)                [(move state pane (constantly Long/MAX_VALUE)) nil]

      (key? m :enter "l" :right)       (open env state)
      (key? m "h" :left)               [(assoc-in state [:ui :pane] :index) nil]
      (key? m "e")                     (let [[file line] (target state)]
                                         [state (visit-cmd env file line)])
      (key? m "/")                     [(-> state (assoc-in [:ui :pane] :index) (assoc-in [:ui :filtering?] true)) nil]
      (key? m "f")                     [(follow (update-in state [:ui :follow?] not)) nil]
      (key? m "r")                     [(assoc state :outline-stale? true :index-at 0) nil]
      (key? m "a")                     (attach env state)
      (key? m "?")                     [(assoc-in state [:ui :detail] {:kind :help :scroll 0}) nil]

      (and (= :agents pane) (key? m "d"))
      [(if-let [a (selected state :agents)]
         (assoc-in state [:ui :confirm] {:question (str "Detach the agent " (:name a) " and remove its sandbox?")
                                         :name (:name a)})
         state)
       nil]

      (and (= :agents pane) (key? m "s"))
      [state (some->> (selected state :agents) (shell-cmd env))]

      :else [state nil])))

(defn- timers
  "The commands that are due: the next wait, and the index and the
  notebook's outline when they are old or stale."
  [env state]
  (let [now   (System/currentTimeMillis)
        file  (get-in state [:notebook :file])
        index? (> (- now (:index-at state)) index-ms)
        outline? (and file (not (:outline-pending? state))
                      (or (:outline-stale? state)
                          (and (get-in state [:model :buffers file :modified])
                               (> (- now (:outline-at state)) outline-ms))))]
    [(cond-> state
       index?   (assoc :index-at now)
       outline? (assoc :outline-at now :outline-stale? false :outline-pending? true))
     (apply charm/batch
            (wait-cmd env)
            (cond-> []
              index?   (conj (index-cmd env))
              outline? (conj (outline-cmd env file))))]))

(defn make-update
  "The update function over `env`: {:link :mgr :queue :dir :box?}."
  [env]
  (fn [state m]
    (cond
      (msg/window-size? m)
      (do
        ;; charm redraws only the lines that changed, against what it
        ;; believes is on screen, and after a resize of the alternate
        ;; screen that belief is wrong.  Clear the terminal and draw one
        ;; empty frame: the next one, a tick later, draws every line.
        (doto System/out (.print "\u001b[2J\u001b[H") (.flush))
        [(clamp (assoc state :width (:width m) :height (:height m) :resized? true)) nil])

      (= :events (:type m))
      (timers env (-> (add-events state (:events m))
                      (assoc :now (LocalDateTime/now) :resized? false)
                      clamp))

      (= :index (:type m))
      [(refresh-entries (assoc state :scanned (:scanned m))) nil]

      (= :outline (:type m))
      [(cond-> (assoc state :outline-pending? false)
         (= (:file m) (get-in state [:notebook :file]))
         (-> (assoc :notebook (select-keys m [:file :outline :unsaved])) follow clamp))
       nil]

      (= :did (:type m))
      [(cond-> state (:message m) (message (:message m))) nil]

      (not (msg/key-press? m))
      [state nil]

      (key? m "ctrl+c")
      [state charm/quit-cmd]

      (get-in state [:ui :confirm])
      (let [{:keys [name]} (get-in state [:ui :confirm])
            state (assoc-in state [:ui :confirm] nil)]
        (if (key? m "y")
          [(message state "Detaching…") (detach-cmd env name)]
          [state nil]))

      (get-in state [:ui :filtering?])
      [(filter-key state m) nil]

      (key? m "q")
      [state charm/quit-cmd]

      :else
      (let [state (assoc-in state [:ui :message] nil)]
        (if (get-in state [:ui :detail])
          (detail-key env state m)
          (main-key env state m))))))

(defn run
  "Run the console over `dir` until the operator quits; then detach the
  agents and let go of Emacs."
  [{:keys [dir server box?] :or {box? true}}]
  (let [queue (LinkedBlockingQueue.)
        mgr   (atom nil)
        emit  (fn [e]
                (.put queue e)
                (when-let [m @mgr]
                  (try (agent/handle-event! m e)
                       (catch Exception ex
                         (.put queue {:event "problem" :kind "sidecar" :time (emacs/now)
                                      :message (str "feed: " (ex-message ex))})))))
        link  (emacs/start! {:server server :on-event emit})
        _     (reset! mgr (agent/start-polling! (agent/manager link #(.put queue %))))
        env   {:link link :mgr @mgr :queue queue :dir dir :box? box?}
        state (init-state dir)]
    (try
      (charm/run {:init       (fn [] [state (charm/batch (wait-cmd env))])
                  :update     (make-update env)
                  :view       #(if (:resized? %) "" (view/screen %))
                  :alt-screen true})
      (finally
        (agent/stop! @mgr)
        (emacs/stop! link)))))

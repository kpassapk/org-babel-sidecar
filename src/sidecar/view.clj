(ns sidecar.view
  "State to text.  `(screen state)` is the whole console as one string,
  :width by :height; nothing here reads a file, a clock or Emacs, so a
  test can compare a screen with the text it expects.

  Five panes: index, notebook, runs, problems, agents.  On a terminal of
  100 columns or more they are all on screen; on a narrower one only the
  pane that has the keyboard is."
  (:require [charm.ansi.width :as aw]
            [charm.style.color :as color]
            [charm.style.core :as style]
            [clojure.string :as str]
            [sidecar.model :as model]
            [sidecar.org :as org])
  (:import (java.time Duration LocalDateTime)))

(def panes [:index :notebook :runs :problems :agents])

(def ^:private pane-title
  {:index "Index" :notebook "Notebook" :runs "Runs" :problems "Problems" :agents "Agents"})

;;; Text

(def ^:private styles
  {:dim   (style/style :faint true)
   :ok    (style/style :fg color/green)
   :warn  (style/style :fg color/yellow)
   :err   (style/style :fg color/red :bold true)
   :head  (style/style :bold true)
   :info  (style/style :fg color/cyan)
   :sel   (style/style :reverse true)
   :focus (style/style :reverse true :bold true)})

(defn- paint [k s] (if-let [st (styles k)] (style/render st s) s))

(defn strip-ansi [s] (str/replace (str s) #"\x1b\[[0-9;?]*[A-Za-z]" ""))

(defn- vis
  "Columns `s` takes on screen."
  [s]
  (let [s (strip-ansi s)]
    (if (every? #(< (int %) 0x300) s) (count s) (aw/string-width s))))

(defn- spaces [n] (apply str (repeat (max 0 n) " ")))

(defn- fit
  "Plain `s` cut or padded to exactly `n` columns."
  [s n]
  (let [s (str s) w (vis s)]
    (cond (<= n 0) ""
          (<= w n) (str s (spaces (- n w)))
          (= w (count s)) (str (subs s 0 (dec n)) "…")
          :else (loop [cut (dec n)]
                  (let [t (str (subs s 0 (max 0 (min cut (count s)))) "…")]
                    (if (or (<= (vis t) n) (zero? cut))
                      (str t (spaces (- n (vis t))))
                      (recur (dec cut))))))))

(defn- clean
  "`s` as plain lines: no escape sequences, no carriage-return overdraw, no tabs."
  [s]
  (->> (str/split-lines (strip-ansi s))
       (map #(-> % (str/split #"\r") last str (str/replace "\t" "    ")
                 (str/replace #"[\x00-\x1f\x7f]" "")))))

;; ━ and ┃, not ─ and │: jline draws the light ones with the terminal's
;; line-drawing charset, which tmux shows as letters.
(defn- rule [width] (apply str (repeat (max 0 width) "━")))

(defn- seg [x] (if (string? x) [nil x] x))

(defn- line
  "One row of `width` columns: `left` and `right`, seqs of [tone text] or
  strings, `right` flush right.  `left` is cut to make room.  A selected
  row is drawn reversed, without its tones."
  ([width left] (line width left nil false))
  ([width left right selected?]
   (let [total  #(reduce + (map (comp vis second) %))
         ;; the left keeps 14 columns: the right gives up its last parts
         right  (loop [r (mapv seg right)]
                  (if (and (seq r) (> (total r) (max 0 (- width 14))))
                    (recur (pop r))
                    r))
         rw     (total right)
         room   (- width rw (if (pos? rw) 1 0))
         left   (loop [segs (map seg left), room room, out []]
                  (if-let [[tone s] (first segs)]
                    (let [w (vis s)]
                      (cond (<= room 0) out
                            (<= w room) (recur (rest segs) (- room w) (conj out [tone s]))
                            :else       (conj out [tone (fit s room)])))
                    (conj out [nil (spaces room)])))
         all    (concat left (when (pos? rw) [[nil " "]]) right)]
     (if selected?
       (paint :sel (fit (apply str (map second all)) width))
       (let [used (reduce + (map (comp vis second) all))]
         (str (apply str (map (fn [[tone s]] (paint tone s)) all))
              (spaces (- width used))))))))

;;; Values

(defn- home [path]
  (str/replace (str path) (System/getProperty "user.home") "~"))

(defn- tail
  "Plain `s` in at most `n` columns, keeping its end."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str "…" (subs s (- (count s) (dec n)))))))

(defn- file-name
  "The name to show for the file at `path`: of a denote name, its title part."
  [path]
  (some-> path
          (str/replace #".*/" "")
          (str/replace #"^\d{8}T\d{6}--([^_.]+)(__[^.]*)?\.org$" "$1.org")))

(defn clock
  "HH:mm:ss of an event's time, a string."
  [t]
  (if (and t (>= (count t) 19)) (subs t 11 19) "        "))

(defn- local-time
  "The LocalDateTime in an event's time; nil if it has none."
  [t]
  (when-let [[_ s] (and t (re-find #"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?)" t))]
    (try (LocalDateTime/parse s) (catch Exception _ nil))))

(defn fmt-ms [ms]
  (when ms
    (let [s (quot ms 1000)]
      (cond (< ms 10000) (format "%.1fs" (/ ms 1000.0))
            (< s 60)     (str s "s")
            (< s 3600)   (format "%dm%02ds" (quot s 60) (rem s 60))
            :else        (format "%dh%02dm" (quot s 3600) (rem (quot s 60) 60))))))

(defn- elapsed [run ^LocalDateTime now]
  (when-let [start (and now (local-time (:started run)))]
    (fmt-ms (max 0 (.toMillis (Duration/between start now))))))

(defn- run-status
  "[tone glyph text] for a run."
  [run now]
  (case (:status run)
    "running"   [:warn "▶" (str "running " (or (elapsed run now) ""))]
    "done"      [:ok "✓" (or (fmt-ms (:ms run)) "done")]
    "error"     [:err "✗" (if (:exit run) (str "exit " (:exit run)) "error")]
    "cancelled" [:dim "◼" "cancelled"]
    [:dim "·" (str (:status run))]))

(defn- first-line [s]
  (or (first (remove str/blank? (map str/trim (str/split-lines (str s))))) ""))

;;; What each pane lists

(defn items
  "The things `pane` lists, in the order it lists them."
  [{:keys [model entries notebook]} pane]
  (case pane
    :index    (vec entries)
    :notebook (vec (get-in notebook [:outline :nodes]))
    :runs     (vec (model/runs-newest model))
    :problems (vec (rseq (:problems model)))
    :agents   (vec (sort-by :name (vals (:agents model))))))

;;; Layout

(defn layout
  "Where the panes go on a screen of `width` by `height`: for each pane
  {:w :h}, its title line included.  :mode is :wide, with every pane on
  screen, or :narrow, with one."
  [width height n-agents]
  (let [body (max 3 (- height 4))]
    (if (and (>= width 100) (>= body 16))
      (let [lw (-> (quot (* width 36) 100) (max 34) (min 52))
            rw (- width lw 3)
            ah (-> (inc (max 1 n-agents)) (max 3) (min 7))
            ph (max 4 (quot body 5))
            rh (max 5 (quot (* body 35) 100))]
        {:mode :wide :body body
         :index    {:w lw :h (- body ah 1)}
         :agents   {:w lw :h ah}
         :notebook {:w rw :h (- body rh ph 2)}
         :runs     {:w rw :h rh}
         :problems {:w rw :h ph}})
      (into {:mode :narrow :body body}
            (map (fn [p] [p {:w width :h body}]) panes)))))

(defn state-layout [{:keys [width height model]}]
  (layout width height (count (:agents model))))

(defn rows-in
  "How many of its items `pane` shows at once."
  [state pane]
  (max 1 (dec (get-in (state-layout state) [pane :h]))))

;;; Rows

(defn- index-row [{:keys [model notebook]} stats e]
  (let [buf    (get-in model [:buffers (:file e)])
        here?  (= (:file e) (get-in model [:focus :file]))
        st     (get stats (:file e))
        agent  (model/agent-on model (:file e))
        shown? (= (:file e) (:file notebook))]
    {:left  [[(if here? :ok :dim) (cond here? "◉" buf "●" :else " ")]
             [:warn (if (:modified buf) "*" " ")]
             " "
             [(when shown? :head) (:title e)]
             [:dim (str "  " (str/join " " (map #(str ":" %) (:tags e))))]]
     :right (concat
             (when (pos? (:running st 0)) [[:warn (str "▶" (:running st) " ")]])
             (when (pos? (:errors st 0)) [[:err (str "✗" (:errors st) " ")]])
             (when agent [[(if (= "up" (:status agent)) :ok :err) (str "@" (:name agent) " ")]])
             (when (pos? (:blocks e 0)) [[:dim (str (:blocks e) "▸")]]))}))

(defn- todo-tone [todo]
  (if (#{"DONE" "CANCELLED" "CANCELED"} todo) :ok :warn))

(defn- notebook-row [{:keys [model notebook now]} here node]
  (let [mark   [(when here :ok) (if here "▌" " ")]
        indent (fn [n] (spaces (* 2 (max 0 n))))
        depth  (count (:outline node))]
    (case (:type node)
      :heading
      {:left  [mark (indent (dec (:level node)))
               [:dim (str (apply str (repeat (:level node) "*")) " ")]
               (if (:todo node) [(todo-tone (:todo node)) (str (:todo node) " ")] "")
               [(when (= 1 (:level node)) :head) (:title node)]]
       :right (when (seq (:tags node))
                [[:dim (str ":" (str/join ":" (:tags node)) ":")]])}

      (let [run    (model/block-run model (:file notebook) node)
            target (org/target (:outline notebook) node)
            label  (if (= :call (:type node))
                     (str "call " (:call node))
                     (or (:name node) (first-line (:body node))))
            [tone glyph text] (if run (run-status run now) [:dim "·" ""])]
        {:left  [mark (indent depth)
                 [:info "▸ "]
                 [:dim (str (fit (if (= :call (:type node)) "" (or (:lang node) "?")) 10) " ")]
                 [(when (:name node) :head) label]]
         :right (concat
                 (when target [[:dim (str (first target) "  ")]])
                 [[tone (str glyph (when (seq text) (str " " text)))]])}))))

(defn- run-row [{:keys [now]} run]
  (let [[tone glyph text] (run-status run now)]
    {:left  [[:dim (str (clock (:started run)) " ")]
             [tone (str glyph " ")]
             (fit (str (file-name (:file run)) ":" (:line run)) 22) " "
             (if (:parent run) [:dim "↳ "] "")
             [(when (:name run) :head)
              (or (:name run) (last (:outline run)) (first-line (:body run)))]
             [:dim (str "  " (:lang run))]]
     :right [[tone text]]}))

(def ^:private problem-glyph
  {"run" "✗" "error" "✗" "warning" "!" "gate" "⊘" "edit" "✎" "agent" "@" "sidecar" "!"})

(defn- problem-row [_ p]
  {:left [[:dim (str (clock (:time p)) " ")]
          [(if (= "warning" (:kind p)) :warn :err) (str (get problem-glyph (:kind p) "!") " ")]
          [:dim (fit (:kind p) 8)]
          (if (:file p) (str (file-name (:file p)) (when (:line p) (str ":" (:line p))) "  ") "")
          (first-line (:message p))]})

(defn- agent-row [_ a]
  {:left  [[(if (= "up" (:status a)) :ok :err) (if (= "up" (:status a)) "● " "○ ")]
           [:head (:name a)]
           [:dim (str "  " (file-name (:file a)))]]
   :right [(if (= "up" (:status a))
             [(if (pos? (:behind a 0)) :warn :dim) (str "behind " (:behind a 0) "  ")]
             [:err (str (:status a) "  ")])
           [:dim (str "edits " (:applied a 0)
                      (when (pos? (:refused a 0)) (str " ✗" (:refused a))))]]})

(defn focus-index
  "The index of the notebook node the operator's point is in, or nil."
  [{:keys [model notebook]}]
  (let [{:keys [file line]} (:focus model)]
    (when (and line (= file (:file notebook)))
      (->> (get-in notebook [:outline :nodes])
           (keep-indexed (fn [i n] (when (<= (:line n) line) i)))
           last))))

(defn- pane-rows [state pane]
  (let [xs (items state pane)]
    (case pane
      :index    (let [stats (model/file-stats (:model state))]
                  (map #(index-row state stats %) xs))
      :notebook (let [here (focus-index state)]
                  (map-indexed #(notebook-row state (= %1 here) %2) xs))
      :runs     (map #(run-row state %) xs)
      :problems (map #(problem-row state %) xs)
      :agents   (map #(agent-row state %) xs))))

(defn- pane-info
  "What a pane's title line says after its name."
  [{:keys [model entries notebook ui]} pane]
  (case pane
    :index    (str (count entries) (if (= 1 (count entries)) " file" " files")
                   (when (seq (:filter ui)) (str " · /" (:filter ui))))
    :notebook (if-let [f (:file notebook)]
                (str (file-name f)
                     (when-let [t (get-in notebook [:outline :title])] (str " · " t))
                     (when (:unsaved notebook) " · unsaved")
                     (when (:follow? ui) " · following"))
                "none: ⏎ on a file in the index")
    :runs     (let [n (:running (model/summary model))]
                (str (count (:order model)) (when (pos? n) (str " · " n " running"))))
    :problems (str (count (:problems model)))
    :agents   (str (count (:agents model)))))

(def ^:private empty-text
  {:index "No org files here." :notebook "" :runs "No block has run yet."
   :problems "Nothing has gone wrong." :agents "None. `a` on a notebook attaches one."})

(defn- pane-lines
  "The `h` lines of `pane`, each `w` columns: its title, then its rows
  from the pane's top, the row under the cursor reversed when the pane
  has the keyboard."
  [{:keys [ui] :as state} pane w h]
  (let [focused? (= pane (:pane ui))
        n      (inc (.indexOf ^java.util.List panes pane))
        title  (line w [[(if focused? :focus :head) (str " " n " " (pane-title pane) " ")]
                        [:dim (str " " (pane-info state pane))]])
        rows   (vec (pane-rows state pane))
        top    (-> (get-in ui [:top pane] 0) (min (max 0 (- (count rows) (dec h)))) (max 0))
        cursor (get-in ui [:cursor pane] 0)
        shown  (->> rows (drop top) (take (dec h))
                    (map-indexed (fn [i {:keys [left right]}]
                                   (line w left right (and focused? (= (+ top i) cursor))))))
        blank  (line w [])]
    (vec (take h (concat [title]
                         (if (empty? rows)
                           [(line w [[:dim (str " " (empty-text pane))]])]
                           shown)
                         (repeat blank))))))

;;; Screen

(defn- header [{:keys [model dir now width]}]
  (let [{:keys [status info error]} (:emacs model)
        {:keys [running problems agents]} (model/summary model)]
    (line width
          (concat [[:head " emacs-sidecar"] [:dim " · "] "emacs "]
                  (if (= "up" status)
                    [[:ok "●"] [:dim (str " " (:emacs info) " · org " (:org info)
                                          (when (:devops info) " · devops.el"))]]
                    [[:err "○ "] [:err (tail (first-line (or error "not connected")) (quot width 2))]])
                  [[:dim " · "] (tail (home dir) (max 12 (quot width 3)))])
          (concat (when (pos? running) [[:warn (str "▶" running "  ")]])
                  (when (pos? problems) [[:err (str "✗" problems "  ")]])
                  (when (pos? agents) [[:ok (str "@" agents "  ")]])
                  [[:dim (str (some-> ^LocalDateTime now str (subs 11 19)) " ")]])
          false)))

(def ^:private pane-keys
  {:index    "⏎ open  e emacs  a attach agent  / filter"
   :notebook "⏎ go there in emacs  a attach agent  f follow"
   :runs     "⏎ output  e emacs"
   :problems "⏎ detail  e emacs"
   :agents   "⏎ detail  s shell  d detach"})

(defn- footer [{:keys [ui width]}]
  (cond
    (:confirm ui)    (line width [[:warn (str " " (get-in ui [:confirm :question]) " (y/n)")]])
    (:filtering? ui) (line width [" / " (:filter ui) [:sel " "] [:dim "   ⏎ keep  esc clear"]])
    (:message ui)    (line width [[:warn (str " " (:message ui))]])
    :else            (line width [[:dim (str " " (pane-keys (:pane ui)))]]
                           [[:dim "tab pane  ? help  q quit "]] false)))

(defn- tabs [{:keys [ui width] :as state}]
  (line width
        (mapcat (fn [i p]
                  [[(if (= p (:pane ui)) :focus :dim) (str " " (inc i) " " (pane-title p) " ")] " "])
                (range) panes)
        [[:dim (str (tail (pane-info state (:pane ui)) (max 0 (- width 58))) " ")]]
        false))

(defn- main-lines [{:keys [ui width] :as state}]
  (let [lay (state-layout state)
        p   (fn [pane] (pane-lines state pane (get-in lay [pane :w]) (get-in lay [pane :h])))]
    (if (= :wide (:mode lay))
      (let [lw    (get-in lay [:index :w])
            rw    (get-in lay [:notebook :w])
            left  (concat (p :index) [(paint :dim (rule lw))] (p :agents))
            right (concat (p :notebook) [(paint :dim (rule rw))] (p :runs)
                          [(paint :dim (rule rw))] (p :problems))]
        (map (fn [l r] (str l (paint :dim " ┃ ") r)) left right))
      ;; one pane: its title line is the tab bar
      (cons (tabs state) (rest (p (:pane ui)))))))

;;; Details

(defn- section [title width]
  (str (paint :dim "━━ ") (paint :head title) " "
       (paint :dim (rule (- width 4 (vis title))))))

(defn- field [k v] (str " " (paint :dim (fit k 9)) v))

(defn- path-field
  "A field holding a path: when it is too long, its end is what is kept."
  [k path width]
  (field k (tail (home path) (max 10 (- width 11)))))

(defn- run-detail [{:keys [model now width]} id]
  (if-let [run (get-in model [:runs id])]
    (let [[tone _ text] (run-status run now)]
      {:title (str (file-name (:file run)) ":" (:line run)
                   (when (:name run) (str " · #" (:name run))))
       :lines (concat
               [(field "status" (paint tone (str (:status run) (when (seq text) (str "  " text)))))
                (path-field "where" (str (:file run) ":" (:line run)) width)
                (field "heading" (str/join " / " (:outline run)))
                (field "started" (clock (:started run)))
                (field "run" (str id (when (:async run) "  async") (when (:parent run) (str "  for " (:parent run)))))]
               (when (:error run) [(field "error" (paint :err (:error run)))])
               ["" (section (str "source · " (:lang run)) width)]
               (map #(str " " %) (clean (:body run)))
               (when (seq (str/trim (str (:stderr run))))
                 (concat ["" (section "stderr" width)]
                         (map #(str " " (paint :err %)) (clean (:stderr run)))))
               ["" (section "output" width)]
               (cond (seq (str (:output run))) (map #(str " " %) (clean (:output run)))
                     (model/running? run)      [(paint :dim " still running")]
                     :else                     [(paint :dim " none")]))})
    {:title "run" :lines [(paint :dim " This run is no longer kept.")]}))

(defn- problem-detail [{:keys [width]} p]
  {:title (str (:kind p) " · " (clock (:time p)))
   :lines (concat
           [(field "kind" (str (:kind p) (when (:source p) (str " · " (:source p)))))
            (if (:file p)
              (path-field "where" (str (:file p) (when (:line p) (str ":" (:line p)))) width)
              (field "where" "—"))
            "" (section "message" width)]
           (map #(str " " %) (clean (:message p))))})

(defn- agent-detail [{:keys [model width]} name]
  (if-let [a (get-in model [:agents name])]
    {:title (str "agent " name)
     :lines (concat
             [(field "status" (paint (if (= "up" (:status a)) :ok :err) (:status a)))
              (path-field "notebook" (:file a) width)
              (field "sandbox" (or (:box a) "none: the directories below are for whatever mounts them"))
              (field "events" (str (:seq a 0) " written, " (:behind a 0) " the agent has not acked"))
              (field "edits" (str (:applied a 0) " applied, " (:refused a 0) " refused"))]
             (when (:error a) [(field "error" (paint :err (:error a)))])
             ["" (section "talk to it" width)
              (str " " (or (:shell a) "no sandbox"))
              "" (section "directories" width)
              (path-field "feed" (:feed a) width)
              (path-field "outbox" (:outbox a) width)])}
    {:title (str "agent " name) :lines [(paint :dim " Detached.")]}))

(def ^:private help-lines
  [" The console shows what your Emacs is doing with org notebooks."
   ""
   " tab, shift-tab, 1-5   move between panes"
   " j k ↑ ↓               move        space, PgDn, PgUp   page"
   " g G                   first, last"
   " ⏎                     index: show the notebook    notebook: go there in Emacs"
   "                       runs, problems, agents: detail"
   " e                     show it in Emacs"
   " /                     filter the index"
   " f                     the notebook pane follows where you are in Emacs"
   " a                     attach a sandboxed agent to the notebook's buffer"
   " s                     agents: open the agent's terminal (in tmux), or say how"
   " d                     agents: detach"
   " r                     read the index and the notebook again"
   " esc, h, ←             back        q, C-c   quit"
   ""
   " Index marks:  ◉ you are here   ● open in Emacs   * unsaved"
   "               ▶ running   ✗ blocks whose last run failed   @ agent"
   ""
   " An attached agent reads the buffer's text and each run's output, and"
   " asks for edits; Emacs applies them, one undo each, with a bar beside"
   " what it wrote.  A block it wrote or changed asks before it runs."
   " M-x sidecar-accept takes the bar away without running."])

(defn detail
  "{:title :lines} of the detail the ui has open."
  [{:keys [ui] :as state}]
  (let [{:keys [kind id item]} (:detail ui)]
    (case kind
      :run     (run-detail state id)
      :problem (problem-detail state item)
      :agent   (agent-detail state id)
      :help    {:title "help" :lines help-lines})))

(defn detail-rows [{:keys [height]}] (max 1 (- height 4)))

(defn- detail-screen [{:keys [ui width] :as state}]
  (let [{:keys [title lines]} (detail state)
        lines (vec lines)
        rows  (detail-rows state)
        top   (max 0 (min (get-in ui [:detail :scroll] 0) (- (count lines) rows)))]
    (concat
     [(line width [[:head (str " " title)]]) (paint :dim (rule width))]
     (->> (concat (drop top lines) (repeat ""))
          (take rows)
          (map #(if (> (vis %) width) (fit (strip-ansi %) width) (str % (spaces (- width (vis %)))))))
     [(paint :dim (rule width))
      (if (:message ui)
        (line width [[:warn (str " " (:message ui))]])
        (line width [[:dim (str " " (inc top) "/" (max 1 (count lines)))]]
              [[:dim "↑↓ scroll  e emacs  esc back  q quit "]] false))])))

(defn screen
  "The whole console."
  [{:keys [ui width] :as state}]
  (str/join "\n"
            (if (:detail ui)
              (detail-screen state)
              (concat [(header state) (paint :dim (rule width))]
                      (main-lines state)
                      [(paint :dim (rule width)) (footer state)]))))

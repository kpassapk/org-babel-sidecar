(ns sidecar.org
  "Org text to an outline: its headings and the blocks under them.

  A line reader, not a parser of Org: enough for the console to list a
  notebook, and for the feed to say which headings a change touched.  It
  works on text, so the same code reads a file on disk and the unsaved
  text of a buffer."
  (:require [clojure.string :as str]))

(def ^:private default-todo #{"TODO" "DONE" "NEXT" "WAIT" "WAITING" "HOLD"
                              "STARTED" "DOING" "CANCELLED" "CANCELED"})

(defn- keyword-line
  "[KEY value] of a `#+KEY: value` line, KEY upper-cased; nil for others."
  [line]
  (when-let [[_ k v] (re-matches #"(?i)\s*#\+([a-z_]+):\s*(.*?)\s*" line)]
    [(str/upper-case k) v]))

(defn- todo-keywords [lines]
  (into default-todo
        (comp (keep keyword-line)
              (filter (fn [[k _]] (#{"TODO" "SEQ_TODO" "TYP_TODO"} k)))
              (mapcat (fn [[_ v]] (str/split v #"\s+")))
              (remove #{"|"})
              (map #(str/replace % #"\(.*\)$" "")))
        lines))

(defn- heading [todo? line]
  (when-let [[_ stars rest] (re-matches #"(\*+)\s+(.*)" line)]
    (let [[_ rest tags] (or (re-matches #"(.*?)\s+(:[\w@#%:]+:)\s*" rest) [nil rest nil])
          [word & more] (str/split rest #"\s+" 2)
          todo  (when (todo? word) word)
          title (if todo (or (first more) "") rest)
          title (str/replace title #"^\[#[A-Z0-9]\]\s*" "")]
      {:type :heading
       :level (count stars)
       :todo todo
       :title (str/trim title)
       :tags (if tags (vec (remove str/blank? (str/split tags #":"))) [])})))

(defn- begin-src [line]
  (when-let [[_ lang header] (re-matches #"(?i)\s*#\+begin_src(?:\s+(\S+))?\s*(.*)" line)]
    {:lang lang :header (str/trim header)}))

(defn parse
  "The outline of `text`:

    {:title .. :filetags [..] :targets {name value}
     :nodes [{:type :heading :level :todo :title :tags :line :outline}
             {:type :block :lang :name :header :body :line :end-line
              :outline :tags}
             {:type :call :name :call :line :outline :tags}]}

  Lines count from 1.  A block's :line is its #+begin_src line; its
  :outline the headings over it, outermost first, and :tags those
  headings' tags and the file's."
  [text]
  (let [lines (str/split (str text) #"\r?\n" -1)
        todo? (todo-keywords lines)]
    (loop [i 0, path [], nodes [], meta {:targets {} :filetags []}, pending-name nil]
      (if (>= i (count lines))
        (assoc meta :nodes nodes)
        (let [line (nth lines i)
              n    (inc i)
              ctx  (fn [] {:outline (mapv :title path)
                           :tags (vec (distinct (concat (:filetags meta) (mapcat :tags path))))})]
          (if-let [h (heading todo? line)]
            (let [path (conj (vec (take-while #(< (:level %) (:level h)) path)) h)]
              (recur (inc i) path
                     (conj nodes (assoc h :line n :outline (mapv :title path)))
                     meta nil))
            (if-let [b (begin-src line)]
              (let [end  (or (first (keep-indexed
                                     (fn [j l] (when (re-matches #"(?i)\s*#\+end_src\s*" l) (+ i 1 j)))
                                     (subvec lines (inc i))))
                             (count lines))
                    body (str/join "\n" (subvec lines (inc i) (min end (count lines))))]
                (recur (inc end) path
                       (conj nodes (merge b (ctx)
                                          {:type :block :name pending-name :line n
                                           :end-line (inc end) :body body}))
                       meta nil))
              (let [[k v] (keyword-line line)]
                (case k
                  "NAME"     (recur (inc i) path nodes meta v)
                  "HEADER"   (recur (inc i) path nodes meta pending-name)
                  "HEADERS"  (recur (inc i) path nodes meta pending-name)
                  "CALL"     (recur (inc i) path
                                    (conj nodes (merge (ctx) {:type :call :name pending-name
                                                              :call v :line n}))
                                    meta nil)
                  "TITLE"    (recur (inc i) path nodes (assoc meta :title v) nil)
                  "FILETAGS" (recur (inc i) path nodes
                                    (assoc meta :filetags (vec (remove str/blank? (str/split v #"[:\s]+"))))
                                    nil)
                  "TARGET"   (let [[tname tvalue] (str/split v #"\s+" 2)]
                               (recur (inc i) path nodes
                                      (assoc-in meta [:targets tname] (or tvalue ""))
                                      nil))
                  (recur (inc i) path nodes meta
                         (when (str/blank? line) nil)))))))))))

(defn target
  "The devops.el target a node runs on: the innermost of its tags that
  names a #+TARGET, as [name value]; nil without one."
  [{:keys [targets]} node]
  (some (fn [tag] (when-let [v (get targets tag)] [tag v]))
        (reverse (:tags node))))

(defn sections
  "`text` cut at its headings: a map from outline path, a vector of
  titles ([] before the first heading), to that section's own text,
  without its subheadings'."
  [text]
  (let [lines (str/split (str text) #"\r?\n" -1)
        todo? (todo-keywords lines)]
    (loop [lines lines, path [], levels [], in-block? false, acc {}]
      (if-let [line (first lines)]
        (let [h (when-not in-block? (heading todo? line))]
          (if h
            (let [keep  (count (take-while #(< % (:level h)) levels))
                  path  (conj (vec (take keep path)) (:title h))
                  levels (conj (vec (take keep levels)) (:level h))]
              (recur (rest lines) path levels false (update acc path (fnil conj []) line)))
            (recur (rest lines) path levels
                   (cond (begin-src line) true
                         (re-matches #"(?i)\s*#\+end_src\s*" line) false
                         :else in-block?)
                   (update acc path (fnil conj []) line))))
        (update-vals acc #(str/join "\n" %))))))

(defn changed-sections
  "The outline paths whose own text differs between `old` and `new`,
  those added or removed included, in `new`'s order then `old`'s."
  [old new]
  (let [a (sections old)
        b (sections new)]
    (vec (distinct (concat (filter #(not= (a %) (b %)) (keys b))
                           (remove #(contains? b %) (keys a)))))))

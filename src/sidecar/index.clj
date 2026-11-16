(ns sidecar.index
  "The file index: the org files under a directory, with what their
  names and first lines say about them.

  A denote name, `20261113T114426--host-facts__ops_db.org`, gives the
  date, a title and tags; `#+TITLE:` and `#+FILETAGS:` win over it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [sidecar.org :as org]))

(def max-files 500)
(def max-bytes (* 2 1024 1024))
(def max-depth 4)

(defn denote
  "What a denote file name says: {:date :title :tags}, or nil."
  [file-name]
  (when-let [[_ date slug tags] (re-matches #"(\d{8}T\d{6})--([^_.]*?)(?:__([^.]*))?\.org" file-name)]
    {:date  (str (subs date 0 4) "-" (subs date 4 6) "-" (subs date 6 8))
     :title (str/replace slug "-" " ")
     :tags  (if tags (vec (remove str/blank? (str/split tags #"_"))) [])}))

(defn entry
  "The index entry of `file`, whose text is `text`, under `root`."
  [root file text]
  (let [name    (str (fs/file-name file))
        d       (denote name)
        outline (org/parse text)
        nodes   (:nodes outline)]
    {:file   (str file)
     :rel    (str (fs/relativize root file))
     :name   name
     :title  (or (not-empty (:title outline)) (:title d) (str/replace name #"\.org$" ""))
     :tags   (vec (distinct (concat (:tags d) (:filetags outline))))
     :date   (:date d)
     :blocks (count (filter #(#{:block :call} (:type %)) nodes))
     :headings (count (filter #(= :heading (:type %)) nodes))
     :todo   (count (filter #(and (:todo %) (not (#{"DONE" "CANCELLED" "CANCELED"} (:todo %)))) nodes))}))

(defn- org-files [root]
  (let [root (fs/path root)]
    (->> (fs/glob root "**.org" {:max-depth max-depth})
         (remove (fn [p] (some #(str/starts-with? (str %) ".") (fs/relativize root p))))
         (remove (fn [p] (some #{"node_modules"} (map str (fs/relativize root p)))))
         (filter fs/regular-file?)
         (take max-files))))

(def ^:private cache (atom {}))

(defn scan
  "The entries of the org files under `root`, newest first.  A file is
  read again only when its time or size changed."
  [root]
  (let [root  (fs/canonicalize root)
        files (org-files root)
        seen  (into {}
                    (for [f files
                          :let [k   (str f)
                                sig [(str (fs/last-modified-time f)) (fs/size f)]
                                hit (get @cache k)]]
                      [k (if (= sig (:sig hit))
                           hit
                           {:sig sig
                            :mtime (fs/file-time->millis (fs/last-modified-time f))
                            :entry (entry root f (if (> (fs/size f) max-bytes) "" (slurp k)))})]))]
    (reset! cache seen)
    (->> (vals seen)
         (sort-by :mtime #(compare %2 %1))
         (mapv #(assoc (:entry %) :mtime (:mtime %))))))

(defn with-open-buffers
  "`entries` plus an entry for each of `files`, open in Emacs, that is not
  under the root: the operator's notebooks elsewhere."
  [entries files]
  (let [known (set (map :file entries))]
    (into (vec entries)
          (for [f (sort files)
                :when (and (not (known f)) (fs/exists? f))]
            (assoc (entry (fs/parent f) f (try (slurp f) (catch Exception _ "")))
                   :rel (str/replace f (System/getProperty "user.home") "~")
                   :elsewhere true)))))

(defn matches?
  "Whether `entry` matches the filter `q`: every word in its title, name or tags."
  [q entry]
  (let [hay (str/lower-case (str/join " " (concat [(:title entry) (:rel entry)] (:tags entry))))]
    (every? #(str/includes? hay %) (remove str/blank? (str/split (str/lower-case (str q)) #"\s+")))))

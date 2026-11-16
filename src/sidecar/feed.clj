(ns sidecar.feed
  "The two directories between the sidecar and a sandboxed agent, and
  the files in them.  Both sides use this namespace; it needs only what
  babashka ships.

      FEED/                    the sidecar writes, the sandbox reads (ro)
        status.json            {feed, notebook, attached, epoch, heartbeat, seq}
        events.jsonl           one event per line, in seq order
        events.prev.jsonl      the feed before this one
        outputs/<run>.json     one run: status, output, stderr, input
        notebook/<file>        the buffer's text, unsaved edits included
        replies/<edit>.json    what became of an edit

      OUTBOX/                  the sandbox writes, the sidecar reads
        edits/<edit>.json      an edit the agent asks for
        cursor.json            {epoch, seq}: the last event the agent acked

  Nothing in OUTBOX is ever run, loaded or opened in Emacs: the sidecar
  reads small JSON files from it, and only regular ones."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.nio.file Files LinkOption StandardCopyOption StandardOpenOption)
           (java.time OffsetDateTime)))

(def status-file "status.json")
(def events-file "events.jsonl")
(def prev-events-file "events.prev.jsonl")
(def cursor-file "cursor.json")
(def max-edit-bytes (* 1024 1024))
(def keep-replies 50)

(defn now [] (str (OffsetDateTime/now)))

;;; Files

(defn read-json
  "The JSON in `path`, keys as keywords; nil if it is missing or not JSON
  (a file caught half-written is read again on the next poll)."
  [path]
  (try
    (when (fs/exists? path)
      (json/parse-string (slurp (str path)) true))
    (catch Exception _ nil)))

(defn put-atomic!
  "Write `text` to `path` by renaming a finished file onto it: a reader on
  the same filesystem sees the old file or the new, never part of one.
  For what the sandbox writes and the host reads."
  [path text]
  (fs/create-dirs (fs/parent path))
  (let [tmp (fs/path (fs/parent path) (str "." (fs/file-name path) "." (random-uuid) ".tmp"))]
    (spit (str tmp) text)
    (Files/move tmp (fs/path path)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
    path))

(defn put!
  "Write `text` to `path` in place, in one write.  For what the host
  writes and the sandbox reads: a file replaced by rename reads as missing
  for some hundred milliseconds through a container's mount (podman on
  macOS: 149 of 180 reads missed a file renamed over four times a second,
  none one rewritten in place).  A reader may instead catch the file
  empty or cut short for an instant, so readers of JSON ask again when it
  does not parse."
  [path text]
  (fs/create-dirs (fs/parent path))
  (Files/write (fs/path path) (.getBytes (str text) "UTF-8")
               (into-array StandardOpenOption [StandardOpenOption/CREATE
                                               StandardOpenOption/WRITE
                                               StandardOpenOption/TRUNCATE_EXISTING]))
  path)

(defn put-json! [path x] (put! path (json/generate-string x)))

(defn read-events
  "The events in `path`, a .jsonl file, in order; lines that do not parse
  (the last one, while it is being written) are left out."
  [path]
  (if (fs/exists? path)
    (into [] (keep #(try (json/parse-string % true) (catch Exception _ nil)))
          (remove str/blank? (str/split-lines (slurp (str path)))))
    []))

;;; The sidecar's side

(defn open!
  "Start a feed in `dir` for the agent `name` on `notebook`, a file name.
  The feed that was there becomes events.prev.jsonl; its outputs,
  replies and notebook go.  Returns the feed, an atom."
  [dir {:keys [name notebook]}]
  (let [dir   (fs/path dir)
        epoch (now)]
    (fs/create-dirs dir)
    (when (fs/exists? (fs/path dir events-file))
      (fs/move (fs/path dir events-file) (fs/path dir prev-events-file) {:replace-existing true}))
    (doseq [sub ["outputs" "replies" "notebook"]]
      (fs/delete-tree (fs/path dir sub))
      (fs/create-dirs (fs/path dir sub)))
    (spit (str (fs/path dir events-file)) "")
    (let [feed (atom {:dir (str dir) :name name :notebook notebook
                      :epoch epoch :seq 0 :attached true})]
      (put-json! (fs/path dir status-file)
                 {:feed name :notebook notebook :attached true :epoch epoch
                  :heartbeat epoch :seq 0})
      feed)))

(defn heartbeat!
  "Rewrite status.json: the feed is alive, and this is its last seq."
  [feed]
  (let [{:keys [dir name notebook epoch seq attached]} @feed]
    (put-json! (fs/path dir status-file)
               {:feed name :notebook notebook :attached attached :epoch epoch
                :heartbeat (now) :seq seq})))

(defn append!
  "Add `event`, a map, to the feed, with the next seq; return it as written."
  [feed event]
  ;; one writer at a time: a reader skips a seq that lands after a later one
  (locking feed
    (let [{:keys [dir epoch seq name]} (swap! feed update :seq inc)
          event (assoc event :seq seq :feed name :epoch epoch)]
      (Files/write (fs/path dir events-file)
                   (.getBytes (str (json/generate-string event) "\n") "UTF-8")
                   (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND]))
      event)))

(defn put-output! [feed id m]
  (put-json! (fs/path (:dir @feed) "outputs" (str id ".json")) m))

(defn put-notebook! [feed text]
  (put! (fs/path (:dir @feed) "notebook" (:notebook @feed)) text))

(defn put-reply!
  "Answer the edit `id`, and drop all but the last `keep-replies` answers."
  [feed id m]
  (let [dir (fs/path (:dir @feed) "replies")]
    (put-json! (fs/path dir (str id ".json")) m)
    (doseq [old (drop keep-replies (sort-by fs/last-modified-time #(compare %2 %1)
                                            (fs/glob dir "*.json")))]
      (fs/delete-if-exists old))))

(defn close!
  "Mark the feed detached."
  [feed]
  (swap! feed assoc :attached false)
  (heartbeat! feed))

(def ^:private edit-id #"[A-Za-z0-9-]{1,64}")

(defn take-edits!
  "The edits waiting in `outbox`, oldest first, each {:id .. :edit map} or
  {:id .. :error why}; their files are removed.  Only regular files with
  a plain name and at most `max-edit-bytes` are read: a symlink, a
  directory or anything larger is an error, not followed."
  [outbox]
  (let [dir (fs/path outbox "edits")]
    (when (fs/directory? dir {:nofollow-links true})
      (vec
       (for [path (sort-by #(str (fs/last-modified-time %) (fs/file-name %))
                           (fs/list-dir dir))
             :let [file (str (fs/file-name path))
                   id   (str/replace file #"\.json$" "")]
             :when (and (str/ends-with? file ".json") (not (str/starts-with? file ".")))]
         (let [regular? (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
               result
               (cond
                 (not (re-matches edit-id id)) {:id (subs id 0 (min 64 (count id))) :error "the edit's name is not a plain id"}
                 (not regular?)                {:id id :error "the edit is not a regular file"}
                 (> (fs/size path) max-edit-bytes) {:id id :error "the edit is larger than 1 MB"}
                 :else (if-let [edit (read-json path)]
                         (if (map? edit) {:id id :edit edit} {:id id :error "the edit is not a JSON object"})
                         {:id id :error "the edit is not JSON"}))]
           (try (Files/delete path) (catch Exception _ nil))
           result))))))

(defn read-cursor
  "The agent's cursor in `outbox`, {:epoch :seq}, or nil."
  [outbox]
  (let [path (fs/path outbox cursor-file)]
    (when (and (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
               (< (fs/size path) 4096))
      (let [c (read-json path)]
        (when (and (map? c) (integer? (:seq c)))
          (select-keys c [:epoch :seq]))))))

(defn behind
  "How many of the feed's events the agent has not acked."
  [feed cursor]
  (let [{:keys [epoch seq]} @feed]
    (if (= epoch (:epoch cursor))
      (max 0 (- seq (:seq cursor)))
      seq)))

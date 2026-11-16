(ns sidecar.feed-test
  "The feed's files, and the agent's commands over them, without Emacs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sidecar.box :as box]
            [sidecar.feed :as feed]))

(defn- with-dirs [f]
  (let [root (str (fs/create-temp-dir {:prefix "sidecar-feed"}))]
    (try (f {:feed (str (fs/path root "feed")) :outbox (str (fs/path root "outbox"))})
         (finally (fs/delete-tree root)))))

(defn- box! [dirs & args]
  (let [out (java.io.StringWriter.)
        exit (binding [*out* out *err* out *in* (java.io.StringReader. "")]
               (box/run (concat ["--feed" (:feed dirs) "--outbox" (:outbox dirs)] args)))]
    {:exit exit :lines (str/split-lines (str/trim (str out)))}))

(def run-end {:event "run" :phase "end" :status "done" :file "wb.org" :line 43
              :id "3fa9c2d81b07" :name "host-os" :outline ["Host facts" "OS"]})

(deftest lines
  (is (= "5 run done wb.org:43 3fa9c2d81b07 #host-os [Host facts / OS]"
         (box/line (assoc run-end :seq 5))))
  (is (= "6 change operator+run [Host facts / Disk] [Notes]"
         (box/line {:seq 6 :event "change" :causes ["operator" "run"]
                    :headings [["Host facts" "Disk"] ["Notes"]]})))
  (is (= "7 gate declined wb.org:60" (box/line {:seq 7 :event "gate" :decision "declined" :file "wb.org" :line 60})))
  (is (= "8 save wb.org" (box/line {:seq 8 :event "save" :file "wb.org"}))))

(deftest what-wakes-the-agent
  (is (box/needs-action? run-end))
  (is (not (box/needs-action? (assoc run-end :phase "start"))))
  (is (box/needs-action? {:event "change" :causes ["run" "operator"]}))
  (is (not (box/needs-action? {:event "change" :causes ["agent"]})))
  (is (not (box/needs-action? {:event "change" :causes ["run"]})))
  (is (box/needs-action? {:event "gate" :decision "declined"}))
  (is (not (box/needs-action? {:event "gate" :decision "approved"})))
  (is (not (box/needs-action? {:event "edit" :ok true})))
  (is (box/needs-action? {:event "save"})))

(deftest follow-and-ack
  (with-dirs
    (fn [dirs]
      (testing "no feed yet"
        (is (= ["! no feed yet at " (:feed dirs)]
               (take 2 (str/split (first (:lines (box! dirs "follow" "--once"))) #"(?<=at )")))))
      (let [f (feed/open! (:feed dirs) {:name "wb" :notebook "wb.org"})]
        (feed/append! f (assoc run-end :phase "start" :status "running"))
        (feed/append! f run-end)
        (feed/append! f {:event "change" :causes ["run"] :headings [["Host facts" "OS"]]})
        (testing "raw gives every event; wake only what needs action, and a seq to ack"
          (is (= 3 (count (:lines (box! dirs "follow" "--once")))))
          (is (= ["2 run done wb.org:43 3fa9c2d81b07 #host-os [Host facts / OS]" "! ack 3"]
                 (:lines (box! dirs "follow" "--wake" "--once")))))
        (testing "ack moves the cursor, never back, and not past the feed"
          (is (= ["acked 2"] (:lines (box! dirs "ack" "2"))))
          (is (= ["acked 2"] (:lines (box! dirs "ack" "1"))))
          (is (= 1 (:exit (box! dirs "ack" "9"))))
          (is (= ["3 change run [Host facts / OS]"] (:lines (box! dirs "follow" "--once"))))
          (is (= 1 (feed/behind f (feed/read-cursor (:outbox dirs))))))
        (testing "a run's output"
          (feed/put-output! f "3fa9c2d81b07" {:status "done" :output "Darwin 25\n" :ms 12})
          (is (= ["status: done  12 ms" "--- output" "Darwin 25"] (:lines (box! dirs "output" "3fa9c2d81b07"))))
          (is (= 1 (:exit (box! dirs "output" "../status")))))
        (testing "a new feed: the rest of the old one first, then from 1"
          (let [f2 (feed/open! (:feed dirs) {:name "wb" :notebook "wb.org"})]
            (feed/append! f2 {:event "save" :file "wb.org"})
            (let [lines (:lines (box! dirs "follow" "--once"))]
              (is (str/starts-with? (nth lines 0) "! the rest of the feed before this one"))
              (is (= "3 change run [Host facts / OS]" (nth lines 1)))
              (is (str/starts-with? (nth lines 2) "! the feed restarted"))
              (is (= "1 save wb.org" (nth lines 3))))
            (is (= ["acked 1"] (:lines (box! dirs "ack" "1"))))
            (is (= [""] (:lines (box! dirs "follow" "--once"))))
            (feed/close! f2)
            (is (= ["! detached: the operator ended the session, or the sidecar stopped"]
                   (:lines (box! dirs "follow" "--once"))))
            (is (= 1 (:exit (box! dirs "append" "text"))))))))))

(deftest outbox-is-read-with-care
  (with-dirs
    (fn [{:keys [outbox]}]
      (let [edits (fs/path outbox "edits")]
        (fs/create-dirs edits)
        (spit (str (fs/path edits "a.json")) "{\"op\":\"append\",\"text\":\"x\"}")
        (spit (str (fs/path edits "b.json")) "[1]")
        (spit (str (fs/path edits "c.json")) (apply str (repeat (inc feed/max-edit-bytes) "x")))
        (spit (str (fs/path edits "bad name!.json")) "{}")
        (spit (str (fs/path edits ".hidden.json")) "{}")
        (fs/create-sym-link (fs/path edits "link.json") "/etc/passwd")
        (fs/create-dirs (fs/path edits "dir.json"))
        (let [got (into {} (map (juxt :id #(or (:edit %) (:error %)))) (feed/take-edits! outbox))]
          (is (= {:op "append" :text "x"} (got "a")))
          (is (= "the edit is not a JSON object" (got "b")))
          (is (= "the edit is larger than 1 MB" (got "c")))
          (is (= "the edit's name is not a plain id" (got "bad name!")))
          (is (= "the edit is not a regular file" (got "link")))
          (is (= "the edit is not a regular file" (got "dir")))
          (is (not (contains? got ".hidden"))))
        (is (fs/exists? "/etc/passwd"))
        (is (empty? (remove #(str/starts-with? (str (fs/file-name %)) ".")
                            (filter fs/regular-file? (fs/list-dir edits))))
            "what was read is gone")))))

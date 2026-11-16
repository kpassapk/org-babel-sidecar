(ns sidecar.model-test
  (:require [clojure.test :refer [deftest is]]
            [sidecar.model :as model]))

(defn- play [& events] (reduce model/add-event model/empty-model events))

(def start {:event "run" :phase "start" :id "a1" :file "/w/x.org" :line 3 :name "df"
            :lang "sh" :body "df -h" :time "2026-11-14T10:00:00.000-0600"})

(deftest runs
  (let [m (play start)]
    (is (= ["a1"] (:order m)))
    (is (model/running? (get-in m [:runs "a1"])))
    (is (= {:running 1 :problems 0 :agents 0} (model/summary m))))
  (let [m (play start (assoc start :phase "end" :status "done" :ms 1200 :output "ok"
                             :time "2026-11-14T10:00:01.200-0600"))]
    (is (= {:status "done" :ms 1200 :output "ok" :body "df -h"}
           (select-keys (get-in m [:runs "a1"]) [:status :ms :output :body])))
    (is (empty? (:problems m))))
  (let [m (play start (assoc start :phase "end" :status "error" :exit 3 :stderr "a\ncannot reach db\n"))]
    (is (= [{:kind "run" :run "a1" :message "cannot reach db" :line 3}]
           (map #(select-keys % [:kind :run :message :line]) (:problems m)))
        "a failed run is a problem, by the last line of its stderr")
    (is (= {"/w/x.org" {:runs 1 :errors 1}} (model/file-stats m))))
  (is (= "done" (get-in (play (assoc start :phase "end" :status "done")) [:runs "a1" :status]))
      "an end without its start, from a run under way when the console attached"))

(deftest latest-run-of-a-block
  (let [m (play start
                (assoc start :phase "end" :status "error" :exit 1)
                (assoc start :id "a2")
                (assoc start :id "a2" :phase "end" :status "done")
                (assoc start :id "b1" :name nil :line 9 :body "uptime")
                (assoc start :id "b1" :name nil :line 9 :phase "end" :status "done"))]
    (is (= "a2" (:id (model/block-run m "/w/x.org" {:name "df" :line 40}))) "by name, wherever it moved")
    (is (= "b1" (:id (model/block-run m "/w/x.org" {:body " uptime \n" :line 20}))) "by code")
    (is (nil? (model/block-run m "/w/x.org" {:body "other" :line 20})))
    (is (nil? (model/block-run m "/w/y.org" {:name "df"})))
    (is (= {"/w/x.org" {:runs 2}} (model/file-stats m))
        "the block that failed and then passed counts no error")))

(deftest buffers-and-emacs
  (let [m (play {:event "emacs" :status "up" :info {:emacs "32"}}
                {:event "buffers" :buffers [{:file "/w/x.org" :buffer "x.org" :modified false}]}
                {:event "modify" :file "/w/x.org" :buffer "x.org"})]
    (is (true? (get-in m [:buffers "/w/x.org" :modified])))
    (is (false? (get-in (model/add-event m {:event "save" :file "/w/x.org" :modified false})
                        [:buffers "/w/x.org" :modified])))
    (is (empty? (:buffers (model/add-event m {:event "kill" :file "/w/x.org"}))))
    (is (empty? (:buffers (model/add-event m {:event "emacs" :status "down" :error "gone"}))))))

(deftest agents-and-edits
  (let [m (play {:event "agent" :name "x" :file "/w/x.org" :status "up" :behind 0}
                {:event "agent" :name "x" :file "/w/x.org" :status "up" :behind 3})]
    (is (= 3 (get-in m [:agents "x" :behind])))
    (is (= "x" (:name (model/agent-on m "/w/x.org"))))
    (is (empty? (:problems (model/add-event m {:event "edit" :name "x" :ok false :error "not found"})))
        "an edit that did not fit is the agent's business")
    (is (= ["edit"] (map :kind (:problems (model/add-event m {:event "edit" :name "x" :ok false :unsafe true
                                                              :op "append" :error "Emacs Lisp in ..."})))))
    (is (= ["agent"] (map :kind (:problems (model/add-event m {:event "agent" :name "x" :status "error"
                                                               :error "The sandbox is not running"})))))
    (is (empty? (:agents (model/add-event m {:event "agent" :name "x" :status "gone"}))))))

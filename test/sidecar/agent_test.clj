(ns sidecar.agent-test
  "An attachment end to end, without a container: a batch Emacs, the
  manager, and the agent's commands run against the same directories."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sidecar.agent :as agent]
            [sidecar.box :as box]
            [sidecar.emacs :as emacs]
            [sidecar.feed :as feed]))

(def ^:private setup
  "(progn (require 'org) (require 'ob-shell)
          (setq org-confirm-babel-evaluate nil make-backup-files nil
                auto-save-default nil create-lockfiles nil
                sidecar-confirm-function (lambda (&rest _) t))
          (org-babel-do-load-languages 'org-babel-load-languages '((shell . t))))")

(defn- box!
  "Run an agent command; return {:exit :out}."
  [dirs stdin & args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit (binding [*out* out *err* err *in* (java.io.StringReader. (or stdin ""))]
               (box/run (concat ["--feed" (:feed dirs) "--outbox" (:outbox dirs)] args)))]
    {:exit exit :out (str/trim (str out err))}))

(defn- wait [f]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 30) (recur))))))

(deftest attachment
  (let [dir    (str (fs/canonicalize (fs/create-temp-dir {:prefix "sidecar-agent"})))
        file   (str (fs/path dir "20261113T114426--host-facts__ops.org"))
        _      (spit file "#+TITLE: Host facts\n* Facts\nintro\n")
        events (atom [])
        mgr    (atom nil)
        link   (emacs/start! {:target :batch :setup setup
                              :on-event (fn [e]
                                          (swap! events conj e)
                                          (when @mgr (agent/handle-event! @mgr e)))})]
    (try
      (reset! mgr (agent/start-polling!
                   (agent/manager link (fn [e] (swap! events conj e)) {:root (str (fs/path dir "agents"))})))
      (let [att  (agent/attach! @mgr file {:box? false})
            dirs {:feed (:feed att) :outbox (:outbox att)}]
        (is (= "host-facts" (:name att)))
        (testing "the agent finds the notebook's text"
          (let [path (:out (box! dirs nil "notebook"))]
            (is (str/ends-with? path "20261113T114426--host-facts__ops.org"))
            (is (str/includes? (slurp path) "intro"))))
        (testing "an append is applied in Emacs, and the mirror follows"
          (let [r (box! dirs "** OS\n#+name: os\n#+begin_src sh :results output\necho os-is-test\n#+end_src\n"
                        "append" "--under" "Facts")]
            (is (= 0 (:exit r)) (:out r))
            (is (re-find #"applied at line \d+" (:out r))))
          (is (wait #(str/includes? (slurp (:out (box! dirs nil "notebook"))) "echo os-is-test"))))
        (testing "a refused edit says why, with exit 1"
          (let [r (box! dirs "x" "replace" "--old" "no such text")]
            (is (= 1 (:exit r)))
            (is (str/includes? (:out r) "refused: the old text is not in the notebook"))))
        (testing "the operator runs the block; the agent is woken with the run, and reads it"
          (emacs/eval! link (format "(with-current-buffer (find-buffer-visiting %s) (goto-char (point-min)) (search-forward \"echo os-is\") (org-babel-execute-src-block))"
                                    (pr-str file)))
          (let [seen (wait #(let [r (box! dirs nil "follow" "--wake" "--once")]
                              (when (str/includes? (:out r) "run done") (:out r))))
                [_ seq id] (re-find #"(?m)^(\d+) run done \S+:\d+ ([0-9a-f]+) #os \[Facts / OS\]" (str seen))]
            (is id seen)
            (is (not (str/includes? (str seen) "change agent")) "its own edit is not news")
            (let [out (box! dirs nil "output" id)]
              (is (str/includes? (:out out) "os-is-test"))
              (is (str/includes? (:out out) "status: done")))
            (testing "ack moves the cursor, and the host sees it"
              (is (= (str "acked " seq) (:out (box! dirs nil "ack" seq))))
              (is (wait #(some-> (:behind (last (filter (fn [e] (= "agent" (:event e))) @events))) (<= 1)))
                  "nothing is behind but, maybe, the change the result itself made"))))
        (testing "an edit by the operator shows as a change under its heading"
          (box! dirs nil "ack" (str (:seq @(:feed (first (agent/agents @mgr))))))
          (emacs/eval! link (format "(with-current-buffer (find-buffer-visiting %s) (goto-char (point-min)) (search-forward \"intro\") (insert \" by hand\"))"
                                    (pr-str file)))
          (is (wait #(re-find #"change \S*operator\S* .*\[Facts\]" (:out (box! dirs nil "follow" "--wake" "--once"))))
              (str (:out (box! dirs nil "follow" "--once")) "\n" (slurp (str (fs/path (:feed dirs) "events.jsonl"))))))
        (testing "nothing unsafe is taken from the outbox"
          (fs/create-sym-link (fs/path (:outbox dirs) "edits" "evil.json") "/etc/passwd")
          (spit (str (fs/path (:outbox dirs) "edits" "bad.json")) "not json")
          (is (wait #(and (feed/read-json (fs/path (:feed dirs) "replies" "evil.json"))
                          (feed/read-json (fs/path (:feed dirs) "replies" "bad.json")))))
          (is (= "the edit is not a regular file"
                 (:error (feed/read-json (fs/path (:feed dirs) "replies" "evil.json"))))))
        (testing "detach is said in the feed, and edits stop"
          (agent/detach! @mgr "host-facts")
          (is (false? (:attached (feed/read-json (fs/path (:feed dirs) "status.json")))))
          (is (str/includes? (:out (box! dirs "late" "append")) "not attached"))
          (is (str/includes? (:out (box! dirs nil "follow" "--once")) "! detached"))))
      (finally
        (some-> @mgr agent/stop!)
        (emacs/stop! link)
        (fs/delete-tree dir)))))

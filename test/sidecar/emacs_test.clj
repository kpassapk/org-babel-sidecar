(ns sidecar.emacs-test
  "The Emacs side, against an `emacs --batch -Q` of its own: real
  org-babel runs, and what the sidecar hears of them."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [sidecar.emacs :as emacs]))

(def ^:private setup
  "(progn (require 'org) (require 'ob-shell)
          (setq org-confirm-babel-evaluate nil
                make-backup-files nil
                auto-save-default nil
                create-lockfiles nil)
          (org-babel-do-load-languages 'org-babel-load-languages
                                       '((shell . t) (emacs-lisp . t))))")

(def ^:dynamic *link* nil)
(def ^:dynamic *events* nil)
(def ^:dynamic *dir* nil)

(defn- with-emacs [f]
  (let [events (atom [])
        dir    (str (fs/canonicalize (fs/create-temp-dir {:prefix "sidecar-test"})))
        link   (emacs/start! {:target :batch :setup setup
                              :on-event #(swap! events conj %)})]
    (try
      (binding [*link* link *events* events *dir* dir]
        (f))
      (finally
        (emacs/stop! link)
        (fs/delete-tree dir)))))

(use-fixtures :once with-emacs)

(defn- wait-for
  "The first event after index `from` that `pred` accepts, within 5 s."
  ([pred] (wait-for 0 pred))
  ([from pred]
   (let [deadline (+ (System/currentTimeMillis) 5000)]
     (loop []
       (or (some #(when (pred %) %) (drop from @*events*))
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 20)
             (recur)))))))

(defn- notebook!
  "Write `text` to NAME in the test dir and visit it; return the path."
  [name text]
  (let [file (str (fs/path *dir* name))]
    (spit file text)
    (emacs/call *link* 'find-file-noselect file)
    file))

(defn- el [file & forms]
  (emacs/eval! *link* (format "(with-current-buffer (find-buffer-visiting %s) (save-excursion %s))"
                              (pr-str file) (str/join " " forms))))

(defn- run-block!
  "Run the block under the first line holding `marker`."
  [file marker]
  (el file (format "(goto-char (point-min)) (search-forward %s) (org-babel-execute-src-block)"
                   (pr-str marker))))

(defn- run? [phase id] #(and (= "run" (:event %)) (= phase (:phase %)) (or (nil? id) (= id (:id %)))))

(deftest connects
  (is (emacs/up? *link*))
  (is (= "0.1.0" (:version (emacs/info *link*))))
  (is (wait-for #(= "emacs" (:event %))))
  (is (wait-for #(= "buffers" (:event %)))))

(deftest sync-run
  (let [file (notebook! "sync.org" "* Facts\n#+name: hello\n#+begin_src sh :results output\necho hi there\n#+end_src\n")
        n    (count @*events*)]
    (run-block! file "echo hi")
    (let [start (wait-for n (run? "start" nil))
          end   (wait-for n (run? "end" (:id start)))]
      (is (= {:file file :line 3 :name "hello" :lang "sh" :outline ["Facts"] :status "running"}
             (select-keys start [:file :line :name :lang :outline :status])))
      (is (str/includes? (:body start) "echo hi there"))
      (is (= "done" (:status end)))
      (is (str/includes? (:output end) "hi there"))
      (is (number? (:ms end))))))

(deftest failing-run
  (let [file (notebook! "fail.org" "#+begin_src sh :results output\necho oops >&2; exit 3\n#+end_src\n")
        n    (count @*events*)]
    (run-block! file "exit 3")
    (let [end (wait-for n (run? "end" nil))]
      (is (= "error" (:status end)))
      (is (= 3 (:exit end)))
      (is (str/includes? (str (:stderr end)) "oops")))))

(deftest signalled-run
  (let [file (notebook! "signal.org" "#+begin_src emacs-lisp\n(error \"boom %d\" 7)\n#+end_src\n")
        n    (count @*events*)]
    (is (thrown? clojure.lang.ExceptionInfo (run-block! file "boom")))
    (let [end (wait-for n (run? "end" nil))]
      (is (= "error" (:status end)))
      (is (str/includes? (:error end) "boom 7")))))

(deftest nested-run
  (let [file (notebook! "nested.org" (str "#+name: dep\n#+begin_src emacs-lisp\n(+ 20 1)\n#+end_src\n\n"
                                          "#+begin_src emacs-lisp :var x=dep\n(* 2 x)\n#+end_src\n"))
        n    (count @*events*)]
    (run-block! file "(* 2 x)")
    (let [outer (wait-for n #(and ((run? "end" nil) %) (nil? (:parent %))))
          inner (wait-for n #(and ((run? "end" nil) %) (:parent %)))]
      (is (= "42" (str/trim (str/replace (:output outer) #"^: " ""))))
      (is (= "dep" (:name inner)))
      (is (= (:id outer) (:parent inner))))))

(deftest buffer-events
  (let [file (notebook! "edit.org" "* One\ntext\n")
        n    (count @*events*)]
    (el file "(goto-char (point-max)) (insert \"more\\n\")")
    (is (= ["One"] (:outline (wait-for n #(and (= "modify" (:event %)) (= file (:file %)))))))
    (el file "(save-buffer)")
    (is (false? (boolean (:modified (wait-for n #(and (= "save" (:event %)) (= file (:file %))))))))
    (is (some #(= file (:file %)) (emacs/call *link* 'sidecar-buffers)))
    (is (str/includes? (:text (emacs/call *link* 'sidecar-text file)) "more"))))

(defn- edit! [file edit]
  (try (emacs/call *link* 'sidecar-apply-edit file edit)
       (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))

(deftest agent-edits
  (let [file (notebook! "agent.org" "* Host facts\nintro\n\n* Later\nkeep\n")
        n    (count @*events*)]
    (emacs/call *link* 'sidecar-attach file "t1")
    (testing "append under a heading lands before the next one, and is the agent's"
      (is (= 4 (:line (edit! file {:op "append" :under ["Host facts"]
                                   :text "** Disk\n#+begin_src sh :results output\necho from-agent\n#+end_src"}))))
      (let [text (wait-for n #(and (= "text" (:event %)) (= ["agent"] (:causes %))))]
        (is (str/includes? (:text text) "intro\n\n** Disk\n#+begin_src sh :results output\necho from-agent\n#+end_src\n\n* Later"))))
    (testing "replace needs exactly one match"
      (is (str/includes? (:error (edit! file {:op "replace" :old "absent" :new "x"})) "not in the notebook"))
      (is (str/includes? (:error (edit! file {:op "replace" :old "e" :new "x"})) "times")))
    (testing "what Org or Emacs would evaluate is refused, and nothing changes"
      (let [before (:text (emacs/call *link* 'sidecar-text file))]
        (is (str/includes? (:error (edit! file {:op "append" :text "#+begin_src sh :var x=(shell-command \"id\")\nls\n#+end_src"}))
                           "Emacs Lisp"))
        (is (str/includes? (:error (edit! file {:op "replace" :old ":results output" :new ":dir (getenv \"HOME\")"}))
                           "Emacs Lisp"))
        (is (str/includes? (:error (edit! file {:op "append" :text "# Local Variables:\n# eval: (message \"x\")\n# End:"}))
                           "Local Variables"))
        (is (= before (:text (emacs/call *link* 'sidecar-text file))))))
    (testing "the gate asks before the agent's block runs; no leaves it unrun"
      (emacs/eval! *link* "(setq sidecar-confirm-function (lambda (&rest _) nil))")
      (let [n (count @*events*)]
        (is (thrown? clojure.lang.ExceptionInfo (run-block! file "echo from-agent")))
        (is (= "declined" (:decision (wait-for n #(= "gate" (:event %))))))
        (is (nil? (some #(when ((run? "start" nil) %) %) (drop n @*events*))))))
    (testing "yes runs it, and it is not asked about again"
      (emacs/eval! *link* "(setq sidecar-test-asked 0
                                 sidecar-confirm-function (lambda (&rest _) (setq sidecar-test-asked (1+ sidecar-test-asked)) t))")
      (let [n (count @*events*)]
        (run-block! file "echo from-agent")
        (is (= "done" (:status (wait-for n (run? "end" nil)))))
        (run-block! file "echo from-agent")
        (is (= 1 (emacs/eval! *link* "sidecar-test-asked")))))
    (testing "a change to an accepted block, even a deletion, is asked about again"
      (is (:line (edit! file {:op "replace" :old "echo from-agent" :new "echo from"})))
      (run-block! file "echo from")
      (is (= 2 (emacs/eval! *link* "sidecar-test-asked"))))
    (testing "the operator's own blocks are never asked about"
      (el file "(goto-char (point-max)) (insert \"\\n#+begin_src sh\\necho mine\\n#+end_src\\n\")")
      (run-block! file "echo mine")
      (is (= 2 (emacs/eval! *link* "sidecar-test-asked"))))
    (testing "one undo takes an edit back"
      (edit! file {:op "append" :text "to be undone"})
      (is (str/includes? (:text (emacs/call *link* 'sidecar-text file)) "to be undone"))
      (el file "(let ((l buffer-undo-list)) (while (and l (null (car l))) (pop l)) (primitive-undo 1 l))")
      (is (not (str/includes? (:text (emacs/call *link* 'sidecar-text file)) "to be undone"))))
    (testing "the agent's marks survive a save and a revert, and a detach"
      (edit! file {:op "append" :text "#+begin_src sh\necho after-revert\n#+end_src"})
      (el file "(save-buffer) (revert-buffer t t)")
      (emacs/call *link* 'sidecar-detach file)
      (is (str/includes? (:error (edit! file {:op "append" :text "late"})) "not attached"))
      (run-block! file "echo after-revert")
      (is (= 3 (emacs/eval! *link* "sidecar-test-asked"))))))

(deftest async-run
  (let [file (notebook! "async.org" (str "* Slow\n#+name: slow\n#+begin_src sh :session sidecar-async :async yes :results output\n"
                                         "sleep 0.3; echo async-finished\n#+end_src\n"))
        n    (count @*events*)]
    (run-block! file "sleep 0.3")
    (let [start (wait-for n (run? "start" nil))
          async (wait-for n (run? "async" (:id start)))
          end   (wait-for n (run? "end" (:id start)))]
      (is (= "running" (:status async)) "the block returned, and its run has not ended")
      (is (= "done" (:status end)))
      (is (true? (:async end)))
      (is (str/includes? (str (:output end)) "async-finished"))
      (is (>= (:ms end) 300)))
    (emacs/eval! *link* "(let ((kill-buffer-query-functions nil)) (when (get-buffer \"sidecar-async\") (kill-buffer \"sidecar-async\")))")))

(deftest results-are-unwrapped
  (let [plain #(emacs/call *link* 'sidecar--plain-result %)]
    (is (= "a\nb\n\nc\n" (plain ": a\n: b\n:\n: c\n")))
    (is (= "x\n: y" (plain "#+begin_example\nx\n: y\n#+end_example\n")))
    (is (= "| a | b |\n" (plain "| a | b |\n")))))

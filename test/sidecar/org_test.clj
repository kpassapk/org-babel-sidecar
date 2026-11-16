(ns sidecar.org-test
  (:require [clojure.test :refer [deftest is]]
            [sidecar.index :as index]
            [sidecar.org :as org]))

(def text
  (str "#+TITLE: Demo\n#+FILETAGS: :ops:\n#+TARGET: web /ssh:web-1:\n#+TODO: OPEN | CLOSED\n"
       "* TODO Host facts :web:\nintro\n"
       "** OPEN [#A] Disk\n#+name: df\n#+header: :var x=1\n#+begin_src sh :results output\ndf -h\n* not a heading\n#+end_src\n\n"
       "#+RESULTS: df\n: x\n"
       "* Other\n#+call: df()\n#+begin_src emacs-lisp\n(+ 1 2)\n"))

(deftest outline
  (let [{:keys [title filetags targets nodes] :as o} (org/parse text)
        [h1 h2 block h3 call open-block] nodes]
    (is (= "Demo" title))
    (is (= ["ops"] filetags))
    (is (= {"web" "/ssh:web-1:"} targets))
    (is (= {:type :heading :level 1 :todo "TODO" :title "Host facts" :tags ["web"] :line 5}
           (select-keys h1 [:type :level :todo :title :tags :line])))
    (is (= {:todo "OPEN" :title "Disk" :outline ["Host facts" "Disk"]}
           (select-keys h2 [:todo :title :outline]))
        "a #+TODO keyword, and the priority cookie is not the title")
    (is (= {:type :block :lang "sh" :name "df" :header ":results output" :line 10 :end-line 13
            :outline ["Host facts" "Disk"] :tags ["ops" "web"]
            :body "df -h\n* not a heading"}
           (select-keys block [:type :lang :name :header :line :end-line :outline :tags :body]))
        "a star line inside a block is code")
    (is (= ["web" "/ssh:web-1:"] (org/target o block)))
    (is (= "Other" (:title h3)))
    (is (= {:type :call :call "df()" :line 18} (select-keys call [:type :call :line])))
    (is (nil? (org/target o call)))
    (is (= "(+ 1 2)\n" (:body open-block)) "a block without its end runs to the end")))

(deftest changes
  (is (= [["A" "B"] ["D"] ["C"]]
         (org/changed-sections "pre\n* A\nx\n** B\ny\n* C\n" "pre\n* A\nx\n** B\nz\n* D\n")))
  (is (= [[]] (org/changed-sections "pre\n* A\n" "other\n* A\n")) "text before the first heading")
  (is (= [] (org/changed-sections text text))))

(deftest denote-names
  (is (= {:date "2026-11-13" :title "host facts" :tags ["ops" "db"]}
         (index/denote "20261113T114426--host-facts__ops_db.org")))
  (is (= {:date "2026-11-13" :title "interface" :tags []}
         (index/denote "20261113T114426--interface.org")))
  (is (nil? (index/denote "notes.org")))
  (let [e (index/entry "/w" "/w/sub/20261113T114426--host-facts__ops.org" text)]
    (is (= {:rel "sub/20261113T114426--host-facts__ops.org" :title "Demo" :tags ["ops"]
            :date "2026-11-13" :blocks 3 :headings 3 :todo 2}
           (select-keys e [:rel :title :tags :date :blocks :headings :todo])))
    (is (index/matches? "demo ops" e))
    (is (not (index/matches? "demo nope" e)))))

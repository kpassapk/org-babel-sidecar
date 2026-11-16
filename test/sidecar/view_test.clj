(ns sidecar.view-test
  "Whole screens, as text."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sidecar.console :as console]
            [sidecar.index :as index]
            [sidecar.model :as model]
            [sidecar.org :as org]
            [sidecar.view :as view])
  (:import (java.time LocalDateTime)))

(def notebook-text
  (str "#+TITLE: Host facts\n#+TARGET: web /ssh:web-1:\n"
       "* TODO Facts :web:\n** OS\n#+name: os\n#+begin_src sh\nuname -sr\n#+end_src\n"
       "* Checks\n#+begin_src sh\nexit 3\n#+end_src\n"))

(def file "/w/20261114T101500--host-facts__ops.org")

(def events
  [{:event "emacs" :status "up" :info {:emacs "32.0.50" :org "9.8.7"}}
   {:event "buffers" :buffers [{:file file :buffer "host-facts" :modified true}]}
   {:event "run" :phase "start" :id "aaa111" :file file :line 6 :name "os" :lang "sh"
    :outline ["Facts" "OS"] :body "uname -sr" :time "2026-11-14T11:20:00.000-0600"}
   {:event "run" :phase "end" :id "aaa111" :file file :line 6 :status "done" :ms 3400
    :output "Darwin 25.6.0" :time "2026-11-14T11:20:03.400-0600"}
   {:event "run" :phase "start" :id "bbb222" :file file :line 10 :lang "sh"
    :outline ["Checks"] :body "exit 3" :time "2026-11-14T11:21:00.000-0600"}
   {:event "run" :phase "end" :id "bbb222" :file file :line 10 :status "error" :exit 3
    :stderr "cannot reach db\n" :time "2026-11-14T11:21:00.100-0600"}
   {:event "run" :phase "start" :id "ccc333" :file file :line 6 :name "os" :lang "sh"
    :outline ["Facts" "OS"] :body "uname -sr" :time "2026-11-14T11:22:58.000-0600"}
   {:event "focus" :file file :line 7}
   {:event "agent" :name "host-facts" :file file :status "up" :behind 2 :applied 3 :refused 0
    :box "sidecar-host-facts" :shell "podman exec -it sidecar-host-facts claude" :seq 9
    :feed "/c/feed" :outbox "/c/outbox"}])

(defn- state [width height]
  (-> (console/init-state "/w")
      (assoc :model (reduce model/add-event model/empty-model events)
             :entries [(index/entry "/w" file notebook-text)
                       (index/entry "/w" "/w/scratch.org" "* Scratch\n")]
             :notebook {:file file :outline (org/parse notebook-text) :unsaved true}
             :now (LocalDateTime/parse "2026-11-14T11:23:05")
             :width width :height height)))

(defn- screen [state]
  (->> (str/split-lines (view/strip-ansi (view/screen state)))
       (map str/trimr)))

(deftest wide-screen
  (let [lines (screen (assoc-in (state 110 24) [:ui :pane] :runs))]
    (is (= [" emacs-sidecar · emacs ● 32.0.50 · org 9.8.7 · /w                                        ▶1  ✗1  @1  11:23:05"
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            " 1 Index  2 files                       ┃  2 Notebook  host-facts.org · Host facts · unsaved · following"
            "◉* Host facts  :o… ▶1 ✗1 @host-facts 2▸ ┃  * TODO Facts                                                  :web:"
            "   scratch                              ┃    ** OS"
            "                                        ┃ ▌    ▸ sh         os                             web  ▶ running 7.0s"
            "                                        ┃  * Checks"
            "                                        ┃    ▸ sh         exit 3                                      ✗ exit 3"
            "                                        ┃"
            "                                        ┃ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            "                                        ┃  3 Runs  3 · 1 running"
            "                                        ┃ 11:22:58 ▶ host-facts.org:6       os  sh                running 7.0s"
            "                                        ┃ 11:21:00 ✗ host-facts.org:10      Checks  sh                  exit 3"
            "                                        ┃ 11:20:00 ✓ host-facts.org:6       os  sh                        3.4s"
            "                                        ┃"
            "                                        ┃"
            "                                        ┃"
            "                                        ┃ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ┃  4 Problems  1"
            " 5 Agents  1                            ┃ 11:21:00 ✗ run     host-facts.org:10  cannot reach db"
            "● host-facts  host-f… behind 2  edits 3 ┃"
            "                                        ┃"
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            " ⏎ output  e emacs                                                                   tab pane  ? help  q quit"]
           lines))))

(deftest every-line-fills-the-width
  (doseq [[w h] [[110 30] [160 50] [80 24] [60 12] [100 20]]
          pane view/panes
          :let [s (assoc-in (state w h) [:ui :pane] pane)
                lines (str/split-lines (view/strip-ansi (view/screen s)))]]
    (is (= h (count lines)) (str w "x" h " " pane))
    (is (every? #(= w (count %)) lines)
        (str w "x" h " " pane ": " (pr-str (remove #(= w (count %)) lines))))))

(deftest narrow-screen-shows-one-pane
  (is (= [" emacs-sidecar · emacs ● 32.0.50 · org 9.8.7 · /w          ▶1  ✗1  @1  11:23:05"
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          " 1 Index   2 Notebook   3 Runs   4 Problems   5 Agents   …· unsaved · following"
          " * TODO Facts                                                              :web:"
          "   ** OS"
          "▌    ▸ sh         os                                         web  ▶ running 7.0s"
          " * Checks"
          "   ▸ sh         exit 3                                                  ✗ exit 3"
          ""
          ""
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          " ⏎ go there in emacs  a attach agent  f follow         tab pane  ? help  q quit"]
         (screen (assoc-in (state 80 12) [:ui :pane] :notebook)))))

(deftest run-detail
  (let [lines (screen (assoc-in (state 80 24) [:ui :detail] {:kind :run :id "bbb222" :scroll 0}))]
    (is (= " host-facts.org:10" (first lines)))
    (is (some #(= " status   error  exit 3" %) lines))
    (is (some #(= " heading  Checks" %) lines))
    (is (some #(= " cannot reach db" %) lines))
    (is (= " exit 3" (second (drop-while #(not (str/starts-with? % "━━ source · sh")) lines))))))

(deftest keys-move-and-open
  (let [update (console/make-update {})
        key    (fn [s k] (first (update s {:type :key-press :key k :ctrl false :alt false :shift false})))
        s      (state 110 30)]
    (is (= :runs (get-in (key s "3") [:ui :pane])))
    (is (= 1 (get-in (-> s (key "3") (key "j")) [:ui :cursor :runs])))
    (is (= 2 (get-in (-> s (key "3") (key "G")) [:ui :cursor :runs])) "G stops on the last")
    (is (= {:kind :run :id "bbb222" :scroll 0}
           (get-in (-> s (key "3") (key "j") (key :enter)) [:ui :detail])))
    (is (nil? (get-in (-> s (key "3") (key :enter) (key :escape)) [:ui :detail])))
    (is (= :notebook (get-in (key s :tab) [:ui :pane])))
    (let [filtered (-> s (assoc :scanned (:entries s)) (key "/") (key "s") (key "c") (key "r"))]
      (is (= ["scratch"] (map :title (:entries filtered))))
      (is (= 2 (count (:entries (key filtered :escape))))))
    (is (str/includes? (get-in (-> s (key "5") (key "d")) [:ui :confirm :question]) "host-facts"))
    (is (nil? (get-in (-> s (key "5") (key "d") (key "n")) [:ui :confirm])))))

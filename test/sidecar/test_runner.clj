(ns sidecar.test-runner
  (:require [clojure.test :as t]))

(def suites
  '[sidecar.org-test
    sidecar.model-test
    sidecar.view-test
    sidecar.feed-test
    sidecar.emacs-test
    sidecar.agent-test
    sidecar.main-test])

(defn main
  "Run the suites named in `args`, by their last part (`emacs`, `view`...),
  or all of them."
  [args]
  (let [wanted (if (seq args)
                 (filter (fn [s] (some #(= (str "sidecar." % "-test") (str s)) args)) suites)
                 suites)]
    (apply require wanted)
    (let [{:keys [fail error]} (apply t/run-tests wanted)]
      (when (pos? (+ fail error))
        (System/exit 1)))))

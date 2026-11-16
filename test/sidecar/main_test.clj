(ns sidecar.main-test
  (:require [clojure.test :refer [deftest is]]
            [sidecar.main :as main]))

(deftest attach-opts
  (is (= {:box? true :file "a.org"} (main/attach-opts ["a.org"])))
  (is (= {:box? false :file "a.org" :name "n" :root "/r" :server "s"}
         (main/attach-opts ["a.org" "--name" "n" "--no-box" "--root" "/r" "--server" "s"])))
  (is (= {:box? false :file "a.org"} (main/attach-opts ["--no-box" "a.org"])))
  (is (= "--name" (:bad (main/attach-opts ["a.org" "--name"]))))
  (is (= "--what" (:bad (main/attach-opts ["a.org" "--what"]))))
  (is (= "b.org" (:bad (main/attach-opts ["a.org" "b.org"])))))

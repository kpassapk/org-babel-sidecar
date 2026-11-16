(ns sidecar.main
  "The commands of `sidecar` on the host."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [sidecar.agent :as agent]
            [sidecar.console :as console]
            [sidecar.emacs :as emacs]
            [sidecar.sandbox :as sandbox]))

(defn- workspace [dir]
  (let [dir (fs/expand-home (or dir (System/getProperty "user.dir")))]
    (when-not (fs/directory? dir)
      (binding [*out* *err*] (println "error: no directory" (str dir)))
      (System/exit 1))
    (str (fs/canonicalize dir))))

(defn console
  "The console over the org files under --dir (default: here)."
  ;; babashka.cli reads --no-box as {:box false}
  [{:keys [dir server box]}]
  (console/run {:dir (workspace dir) :server server :box? (not (false? box))}))

(defn event-line
  "One event as `sidecar events` prints it."
  [{:keys [event phase status file line id name decision message error time]}]
  (->> [(some-> time (subs 11 19)) event (or phase decision) status
        (when file (str (fs/file-name file) (when line (str ":" line))))
        id (when name (str "#" name)) (or message error)]
       (remove nil?)
       (str/join " ")))

(defn events
  "A line per event from Emacs, until stopped or --count lines."
  [{:keys [server json count]}]
  (let [left (atom (or count Long/MAX_VALUE))
        done (promise)
        link (emacs/start! {:server server
                            :on-event (fn [e]
                                        (when (pos? @left)
                                          (println (if json
                                                     (json/generate-string (dissoc e :text :body :output))
                                                     (event-line e)))
                                          (flush)
                                          (when (zero? (swap! left dec)) (deliver done true))))})]
    (try @done (finally (emacs/stop! link)))))

(defn attach
  "Attach an agent to FILE's buffer and keep its feed going until stopped."
  [& args]
  (let [[file & more] args
        opts (apply hash-map more)
        name (get opts "--name")
        box? (not (some #{"--no-box"} args))]
    (when-not (and file (fs/exists? file))
      (binding [*out* *err*] (println "usage: sidecar attach FILE [--name N] [--no-box] [--server S]"))
      (System/exit 2))
    (let [mgr  (atom nil)
          link (emacs/start! {:server (get opts "--server")
                              :on-event (fn [e]
                                          (when (#{"emacs" "problem"} (:event e)) (println (event-line e)))
                                          (some-> @mgr (agent/handle-event! e)))})
          _    (reset! mgr (agent/start-polling!
                            (agent/manager link (fn [e] (when (#{"edit" "agent"} (:event e))
                                                          (println (event-line (assoc e :status (or (:status e) (if (:ok e) "applied" "refused"))))))))))
          stop (fn [] (agent/stop! @mgr) (emacs/stop! link))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
      (let [a (agent/attach! @mgr file {:name name :box? box?})]
        (println "feed  " (:feed a))
        (println "outbox" (:outbox a))
        (println (if (:shell a) (str "talk   " (:shell a)) "no sandbox: mount the two directories, or run `bb box` with SIDECAR_FEED and SIDECAR_OUTBOX"))
        @(promise)))))

(defn image
  "Build the sandbox image."
  [_]
  (sandbox/build!))

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

(defn attach-opts
  "The arguments of `sidecar attach`, as a map: :file, :name, :server,
  :root, :box?; :bad is an argument it does not know."
  [args]
  (loop [[a & more] args
         opts {:box? true}]
    (cond
      (nil? a) opts
      (= "--no-box" a) (recur more (assoc opts :box? false))
      (#{"--name" "--server" "--root"} a)
      (if (seq more)
        (recur (rest more) (assoc opts (keyword (subs a 2)) (first more)))
        (assoc opts :bad a))
      (or (str/starts-with? a "-") (:file opts)) (assoc opts :bad a)
      :else (recur more (assoc opts :file a)))))

(defn attach
  "Attach an agent to FILE's buffer and keep its feed going until stopped.
  --root is the directory its feed and outbox go under, as NAME/feed and
  NAME/outbox, for a container of the caller's own that mounts them."
  [& args]
  (let [{:keys [file name server root box? bad]} (attach-opts args)]
    (when-not (and file (not bad) (fs/exists? file))
      (binding [*out* *err*]
        (when bad (println "error: not understood:" bad))
        (when (and file (not (fs/exists? file))) (println "error: no file" file))
        (println "usage: sidecar attach FILE [--name N] [--no-box] [--root D] [--server S]"))
      (System/exit 2))
    (let [mgr  (atom nil)
          link (emacs/start! {:server server
                              :on-event (fn [e]
                                          (when (#{"emacs" "problem"} (:event e)) (println (event-line e)))
                                          (some-> @mgr (agent/handle-event! e)))})
          _    (reset! mgr (agent/start-polling!
                            (agent/manager link
                                           (fn [e] (when (#{"edit" "agent"} (:event e))
                                                     (println (event-line (assoc e :status (or (:status e) (if (:ok e) "applied" "refused")))))))
                                           (when root {:root (str (fs/absolutize (fs/expand-home root)))}))))
          stop (fn [] (agent/stop! @mgr) (emacs/stop! link))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
      (let [a (agent/attach! @mgr file {:name name :box? box?})]
        (println "feed  " (:feed a))
        (println "outbox" (:outbox a))
        (println (if (:shell a) (str "talk   " (:shell a)) "no sandbox: mount the two directories, or run `bb box` with SIDECAR_FEED and SIDECAR_OUTBOX"))
        (flush)
        @(promise)))))

(defn image
  "Build the sandbox image."
  [_]
  (sandbox/build!))

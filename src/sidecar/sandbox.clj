(ns sidecar.sandbox
  "The agent's container: started with the feed read-only and the outbox
  read-write, and nothing else of the host's.  No ssh keys, cloud CLIs or
  git credentials are in the image; only the Claude credential in the
  sidecar's own environment is passed in."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn engine
  "The container engine: SIDECAR_ENGINE, else podman, else docker."
  []
  (or (System/getenv "SIDECAR_ENGINE")
      (some #(when (fs/which %) %) ["podman" "docker"])
      (throw (ex-info "No container engine: install podman or docker, or set SIDECAR_ENGINE"
                      {:type :sidecar/no-engine}))))

(defn image [] (or (System/getenv "SIDECAR_IMAGE") "org-babel-sidecar-sandbox"))

(defn container [name] (str "sidecar-" name))

(def ^:private credentials ["CLAUDE_CODE_OAUTH_TOKEN" "ANTHROPIC_API_KEY"])

(defn- sh
  "Run `args`; return {:exit :out :err}, never throwing."
  [& args]
  (try
    (let [{:keys [exit out err]} @(p/process (vec args) {:out :string :err :string})]
      {:exit exit :out (str/trim (str out)) :err (str/trim (str err))})
    (catch Exception e
      {:exit -1 :out "" :err (ex-message e)})))

(defn run-args
  "The engine's arguments to start the container of agent `name`."
  [eng name {:keys [feed outbox]}]
  (concat
   ["run" "-d" "--rm" "--name" (container name) "--hostname" name
    "-v" (str feed ":/feed:ro")
    "-v" (str outbox ":/outbox:rw")]
   ;; rootless podman: the image's user (1000) is the one that owns the mounts
   (when (str/includes? (str (fs/file-name eng)) "podman")
     ["--userns=keep-id:uid=1000,gid=1000"])
   (mapcat (fn [k] (when (seq (System/getenv k)) ["-e" k])) credentials)
   [(image)]))

(defn start!
  "Start the container of agent `name`, replacing one left from before.
  Returns its name.  Throws, with the engine's own words, if it cannot."
  [name dirs]
  (let [eng (engine)
        box (container name)]
    (sh eng "rm" "-f" box)
    (let [{:keys [exit err]} (apply sh eng (run-args eng name dirs))]
      (when-not (zero? exit)
        (throw (ex-info (if (re-find #"(?i)no such image|not known|unable to (find|pull)|unqualified-search|pull access denied|manifest unknown" err)
                          (str "The sandbox image " (image) " is not built: run `sidecar image`, "
                               "or attach with --no-box.")
                          (str "The sandbox did not start: " (last (str/split-lines err))))
                        {:type :sidecar/sandbox-failed :engine eng :container box :err err})))
      box)))

(defn running? [name]
  (= "true" (:out (sh (engine) "inspect" "-f" "{{.State.Running}}" (container name)))))

(defn stop! [name]
  (sh (engine) "rm" "-f" "-t" "2" (container name))
  nil)

(defn shell-command
  "What the operator types to talk to the agent in `name`'s container."
  [name]
  (str (fs/file-name (engine)) " exec -it " (container name) " claude"))

(defn project-root
  "The checkout this code runs from, for the image build."
  []
  (let [url (io/resource "sidecar/sidecar.el")]
    (when (and url (= "file" (.getProtocol url)))
      (str (fs/parent (fs/parent (fs/parent (io/file url))))))))

(defn build!
  "Build the sandbox image from the checkout, with the engine's output shown."
  []
  (let [root (or (project-root)
                 (throw (ex-info "The image is built from a checkout of org-babel-sidecar" {})))]
    (p/shell {:dir root} (engine) "build" "-f" "sandbox/Dockerfile" "-t" (image) ".")))

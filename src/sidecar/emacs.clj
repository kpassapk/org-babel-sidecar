(ns sidecar.emacs
  "The link to the operator's Emacs, over podless-emacs.

  `start!` returns a link that attaches to the Emacs server, loads
  sidecar.el through the connection, and calls `on-event` with each event
  Emacs publishes.  When Emacs goes away it tries again every two
  seconds, and says so with an event of its own:

      {:event \"emacs\" :status \"up\" :info {...}}
      {:event \"emacs\" :status \"down\" :error \"...\"}

  Everything else is a function call in Emacs, by name, with data for
  arguments: `(call link 'sidecar-visit file line)`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [podless.emacs :as pe])
  (:import (java.io File)
           (java.time OffsetDateTime)))

(defn now [] (str (OffsetDateTime/now)))

(defn- cache-dir ^File []
  (io/file (or (System/getenv "SIDECAR_CACHE")
               (some-> (System/getenv "XDG_CACHE_HOME") (io/file "emacs-sidecar"))
               (io/file (System/getProperty "user.home") ".cache" "emacs-sidecar"))))

(defn cache-path
  "A file or directory under the sidecar's cache directory."
  ^File [& parts]
  (apply io/file (cache-dir) parts))

(defn- elisp-file
  "sidecar.el as a file Emacs can load."
  ^File []
  (let [url (or (io/resource "sidecar/sidecar.el")
                (throw (ex-info "sidecar.el is not on the classpath" {})))]
    (if (= "file" (.getProtocol url))
      (io/file url)
      (let [f (cache-path "elisp" "sidecar.el")]
        (io/make-parents f)
        (spit f (slurp url))
        f))))

(defn- connect!
  "One connection: attach, load sidecar.el, subscribe, install."
  [{:keys [server target on-event on-down setup]}]
  (let [conn (pe/connect (cond-> {:target (or target :attach)
                                  :config {:ns 'sidecar.user}}
                           server (assoc :server server)))]
    (try
      (when setup (pe/eval conn setup))
      (pe/eval-file conn (elisp-file))
      (pe/subscribe! conn "sidecar" on-event
                     {:on-done on-down
                      :on-error (fn [t]
                                  (on-event {:event "problem" :kind "sidecar"
                                             :time (now)
                                             :message (str "event handler: " (ex-message t))}))})
      (let [info (pe/funcall conn 'sidecar-install (:connection (pe/info conn)))]
        {:conn conn :info info})
      (catch Throwable t
        (pe/close! conn)
        (throw t)))))

(defn start!
  "Start a link and return it.  `opts`:

    :server    the Emacs server to attach to (default \"server\")
    :target    :attach (default), or :batch for an Emacs of its own, in tests
    :setup     elisp, a string, evaluated before sidecar.el is loaded
    :on-event  called with each event, on the connection's callback thread
    :retry-ms  how long to wait before trying a lost Emacs again (2000)"
  [{:keys [on-event retry-ms] :as opts}]
  (let [state (atom {:conn nil :info nil :stopped? false :error nil})
        link  {:state state :opts opts}
        down! (fn [error]
                (let [[old _] (swap-vals! state assoc :conn nil :info nil)]
                  (when (or (:conn old) (not= error (:error old)))
                    (swap! state assoc :error error)
                    (on-event {:event "emacs" :status "down" :time (now) :error error}))))
        try!  (fn []
                (try
                  (let [{:keys [conn info]} (connect! (assoc opts :on-down #(down! "Emacs went away")))]
                    (swap! state assoc :conn conn :info info :error nil)
                    (on-event {:event "emacs" :status "up" :time (now) :info info})
                    (on-event {:event "buffers" :time (now)
                               :buffers (vec (pe/funcall conn 'sidecar-buffers))}))
                  (catch Throwable t
                    ;; emacsclient's own words run to several lines
                    (down! (first (str/split-lines (str (ex-message t))))))))]
    (try!)
    (doto (Thread.
           ^Runnable
           (fn []
             (while (not (:stopped? @state))
               (Thread/sleep (long (or retry-ms 2000)))
               (when (and (not (:stopped? @state))
                          (or (nil? (:conn @state)) (pe/closed? (:conn @state))))
                 (try!)))))
      (.setName "sidecar-link")
      (.setDaemon true)
      (.start))
    link))

(defn up? [link] (some? (:conn @(:state link))))

(defn info [link] (:info @(:state link)))

(defn call
  "Call the Emacs Lisp function `f` with `args`, which travel as data.
  Throws ex-info with :type :sidecar/no-emacs when Emacs is out of reach."
  [link f & args]
  (if-let [conn (:conn @(:state link))]
    (apply pe/funcall conn f args)
    (throw (ex-info "Emacs is out of reach" {:type :sidecar/no-emacs}))))

(defn eval!
  "Evaluate elisp, a string, in Emacs.  For tests and setup."
  [link code]
  (if-let [conn (:conn @(:state link))]
    (pe/eval conn code)
    (throw (ex-info "Emacs is out of reach" {:type :sidecar/no-emacs}))))

(defn stop!
  "Close the link.  Emacs carries on, without the sidecar's hooks."
  [link]
  (let [[old _] (swap-vals! (:state link) assoc :stopped? true :conn nil)]
    (when-let [conn (:conn old)]
      (pe/close! conn)))
  nil)

(ns sidecar.model
  "Events to state.  Pure: `add-event` takes the console's model and one
  event, from Emacs or from the agent manager, and returns the model.

      {:emacs    {:status \"up\"|\"down\" :info {..} :error ..}
       :buffers  {file {:buffer :modified :attached}}     open in Emacs
       :runs     {id run}  :order [id ..]                  oldest first
       :problems [{:kind :time :file :line :message ..}]   oldest first
       :agents   {name {:file :status :behind :applied :refused ..}}
       :focus    {:file :line :outline}}                   where the operator is"
  (:require [clojure.string :as str]))

(def max-runs 2000)
(def max-problems 500)

(def empty-model
  {:emacs {:status "down"} :buffers {} :runs {} :order [] :problems []
   :agents {} :focus nil})

(defn- problem [model p]
  (let [ps (conj (:problems model) p)]
    (assoc model :problems (if (> (count ps) max-problems)
                             (subvec ps (- (count ps) max-problems))
                             ps))))

(defn- trim-runs [{:keys [order] :as model}]
  (if (> (count order) max-runs)
    (let [drop-n (- (count order) max-runs)]
      (-> model
          (update :runs #(apply dissoc % (take drop-n order)))
          (assoc :order (subvec order drop-n))))
    model))

(defn- add-run [model {:keys [id phase] :as e}]
  (let [known? (contains? (:runs model) id)
        fields (select-keys e [:id :file :buffer :line :name :lang :outline :parent])
        model  (cond-> model
                 (not known?) (-> (assoc-in [:runs id] (assoc fields :started (:time e) :status "running"))
                                  (update :order conj id)
                                  trim-runs))]
    (case phase
      "start" (update-in model [:runs id] merge fields {:body (:body e) :started (:time e) :status "running"})
      "async" (update-in model [:runs id] assoc :async true)
      "end"   (let [model (update-in model [:runs id] merge
                                     (select-keys e [:status :ms :output :exit :stderr :error])
                                     {:ended (:time e)})]
                (cond-> model
                  (= "error" (:status e))
                  (problem {:kind "run" :time (:time e) :file (:file e) :line (:line e)
                            :run id
                            :message (or (:error e)
                                         (some-> (:stderr e) str/trim not-empty str/split-lines last)
                                         (str "exit " (:exit e)))})))
      model)))

(defn- buffer [model {:keys [file] :as e}]
  (update-in model [:buffers file] merge
             {:buffer (:buffer e) :modified (boolean (:modified e))}))

(defn add-event
  "The model after event `e`."
  [model e]
  (case (:event e)
    "emacs"   (assoc model :emacs (select-keys e [:status :info :error :time])
                     ;; what was running when Emacs went is not running now
                     :buffers (if (= "down" (:status e)) {} (:buffers model)))
    "buffers" (assoc model :buffers
                     (into {} (map (fn [b] [(:file b) (-> (select-keys b [:buffer :attached])
                                                          (assoc :modified (boolean (:modified b))))]))
                           (:buffers e)))
    ("open" "save" "revert") (buffer model e)
    "modify"  (buffer model (assoc e :modified true))
    "kill"    (update model :buffers dissoc (:file e))
    "focus"   (assoc model :focus (select-keys e [:file :line :outline]))
    "run"     (add-run model e)
    "problem" (problem model (select-keys e [:kind :time :file :line :message :source :level]))
    "gate"    (cond-> model
                (= "refused" (:decision e))
                (problem {:kind "gate" :time (:time e) :file (:file e) :line (:line e)
                          :message (str "not run: " (:reason e))}))
    ;; an edit that did not fit is the agent's to retry; one refused as
    ;; unsafe is the operator's to know
    "edit"    (cond-> model
                (:unsafe e)
                (problem {:kind "edit" :time (:time e) :file (:file e)
                          :message (str "agent " (:name e) ": " (:op e) " refused: " (:error e))}))
    "agent"   (if (= "gone" (:status e))
                (update model :agents dissoc (:name e))
                (cond-> (update-in model [:agents (:name e)] merge (dissoc e :event))
                  (= "error" (:status e))
                  (problem {:kind "agent" :time (:time e) :file (:file e)
                            :message (str "agent " (:name e) ": " (:error e))})))
    model))

;;; Questions

(defn runs-newest [model]
  (map (:runs model) (rseq (:order model))))

(defn running? [run] (= "running" (:status run)))

(defn file-stats
  "Per file: {:runs n :running n :errors n}, errors counting the blocks
  whose latest run failed."
  [model]
  (let [latest (reduce (fn [m run] (assoc m [(:file run) (or (:name run) (:line run))] run))
                       {} (map (:runs model) (:order model)))]
    (reduce (fn [m run]
              (-> m
                  (update-in [(:file run) :runs] (fnil inc 0))
                  (cond-> (running? run) (update-in [(:file run) :running] (fnil inc 0))
                          (= "error" (:status run)) (update-in [(:file run) :errors] (fnil inc 0)))))
            {} (vals latest))))

(defn- same-code? [a b]
  (let [norm #(->> (str/split-lines (str %)) (map str/trim) (remove str/blank?))]
    (and a b (= (norm a) (norm b)))))

(defn block-run
  "The latest run of `block`, a node of the outline of `file`: by its
  #+name, else by its code, else by its line."
  [model file {:keys [name body line]}]
  (let [runs (filter #(= file (:file %)) (runs-newest model))]
    (or (when name (first (filter #(= name (:name %)) runs)))
        (first (filter #(and (not (:name %)) (same-code? body (:body %))) runs))
        (when-not name (first (filter #(and (not (:name %)) (= line (:line %))) runs))))))

(defn agent-on [model file]
  (first (filter #(= file (:file %)) (vals (:agents model)))))

(defn summary [model]
  {:running  (count (filter running? (vals (:runs model))))
   :problems (count (:problems model))
   :agents   (count (:agents model))})

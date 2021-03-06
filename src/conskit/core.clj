(ns conskit.core
  (:require
    [conskit.protocols :refer [ActionRegistry Action]]
    [conskit.macros :refer [within-phase]]
    [clojure.tools.logging :as log]
    [puppetlabs.trapperkeeper.core :refer [defservice]]
    [puppetlabs.trapperkeeper.services :refer [service-context]]
    [slingshot.slingshot :refer [throw+]]))

;; Concrete Implementation of Action Protocol
(defrecord ActionInstance [metadata f]
  Action
  (invoke [_ request]
    (f request)))

(def empty-container
  "Default Container"
  {:controllers []
   :bindings {}
   :interceptors {:annotations #{}
                  :aliases      {}
                  :requirements {}
                  :handlers    {}
                  :settings    {}}})

;; Action Registry Service
(def ^:private registry-container
  "Temporary container for the Registry"
  (atom empty-container))

(defn- compare-priority
  "Compares the priority of two annotations"
  [config]
  (fn [m1 m2]
    (let [[k1 _] (first m1)
          [k2 _] (first m2)]
      (>= (or (k1 config) 0) (or (k2 config) 0)))))

(defn register-interceptors!*
  "Registers a list of interceptors into the container"
  ([interceptors config container-atom] (register-interceptors!* interceptors config container-atom false))
  ([interceptors config container-atom all?]
    (doseq [inter interceptors]
      (let [{qannot :annotation, annot :alias, interceptor :fn :as all} (if (vector? inter) (first inter) inter)]
        (when (vector? inter)
          (swap! container-atom update-in [:interceptors] #(assoc-in % [:settings qannot] (second inter))))
        (swap! container-atom update-in [:interceptors]
               #(-> %
                    (update :annotations (fn [v] (into (sorted-set-by (compare-priority config)) (conj v {qannot all?}))))
                    (assoc-in [:aliases qannot] annot)
                    (assoc-in [:requirements qannot] (select-keys all [:requires :name]))
                    (assoc-in [:handlers qannot] interceptor)))))))

(defn register-controllers!*
  "Registers a list of controllers into the container"
  ([controllers container-atom] (register-controllers!* controllers container-atom nil nil))
  ([controllers container-atom interceptors config]
   (when interceptors
     (register-interceptors!* interceptors config container-atom true))
   (doseq [controller controllers]
     (if (vector? controller)
       (let [[ctrl opts] controller
             [k v] (first (seq opts))]
         (swap! container-atom update :controllers #(conj % (merge ctrl {k (map (fn [n] (keyword (:ns ctrl) (name n))) v)}))))
       (swap! container-atom update :controllers #(conj % controller))))))

(defn register-bindings!*
  "Registers Bindings into the container"
  [bindings container-atom]
  (doseq [[id value] bindings]
    (when (get-in @container-atom [:bindings id])
      (log/warn (str "The binding " id " is being overriden")))
    (swap! container-atom update :bindings #(assoc % id value))))


(defn get-action*
  "Retrieves an action"
  [container id]
  (get container id))

(defn select-meta-keys*
  "Is effectively the result of mapping selectkeys on the metadata of a one or all action(s)"
  ([container key-seq]
    (map #(select-keys % key-seq)
         (map :metadata (vals container))))
  ([container id key-seq]
    (select-keys (:metadata (get-action* container id)) key-seq)))

(defn- check-requirements-satisfied!
  "Verifies that bindings exist for all the controller or interceptor parameters"
  [{:keys [name requires]} bindings type]
  (doseq [req requires]
    (assert (bindings req) (str type " \"" name "\" requirement \"" req "\" is not satisfiable: Did you register a binding for it?"))))

(defn- intercept-all
  "Intercepts all actions except those with particular annotations"
  [actions exception config handler has-req? bindings]
  (apply merge
         (for [[k v] actions
               :let [{:keys [metadata f]} v]]
           (if (exception metadata)
             {k v}
             {k (assoc v :f (if has-req? (handler f config (if (not= :_ (:get-meta bindings))
                                                             bindings
                                                             (assoc bindings :get-meta (constantly metadata)))) (handler f config)))}))))

(defn- check-for-ambiguous-alias!
  "Checks if a specified annotation alias clashes with any other alias"
  [metadata alias all-aliases]
  (let [interceptors (keys (filter (fn [[_ v]] (= alias v)) all-aliases))
        multiple? (> (count interceptors) 1)
        action-name (:name metadata)
        namespc (:ns metadata)]
    (assert (not (and (alias metadata)
                      multiple?)) (str "Ambiguous Interceptor annotation alias \"" alias "\" found on action: \""
                                       action-name "\" defined in " namespc ".\n\n Please use the appropriate qualified version of the annotation:\n "
                                       (apply str (map #(str "\n> " % ) interceptors)) "\n\n"))))

(defn- intercept-specific
  "Intercepts particular actions that have relevant annotations"
  [actions annot alias default-config handler has-req? bindings all-aliases]
  (apply merge
         (for [[k v] actions
               :let [{:keys [metadata f]} v]]
           (do (check-for-ambiguous-alias! metadata alias all-aliases)
               (if-let [annot-config (or (annot metadata) (alias metadata))]
                 (let [config (cond
                                (= annot-config default-config) default-config
                                (true? annot-config) (or default-config true)
                                (and (map? annot-config)
                                     (map? default-config)) (merge default-config annot-config)
                                :else annot-config)]
                   {k (assoc v :f (if has-req? (handler f config
                                                        (if (not= :_ (:get-meta bindings))
                                                          bindings
                                                          (assoc bindings :get-meta (constantly metadata))))
                                               (handler f config)))})
                 {k v})))))

(defn- add-interceptors
  "Adds interceptors to all the actions"
  [actions {:keys [annotations aliases handlers settings requirements]} bindings]
  (loop [annots annotations
         acts actions]
    (if (empty? annots)
      acts
      (let [[annot all?] (ffirst annots)
            alias (annot aliases)
            handler (annot handlers)
            {:keys [except config] :as default-config} (annot settings)
            req (annot requirements)
            has-req? (:requires req)
            binds (merge {:get-meta :_} bindings)]
        (check-requirements-satisfied! req binds "Interceptor")
        (recur (rest annots)
               (if all?
                 (intercept-all acts except config handler has-req? binds)
                 (intercept-specific acts annot alias default-config handler has-req? binds aliases)))))))

(defn- inherit-annotations
  "Merges the annotations of the controller with the each action's annotations"
  [actions meta]
  (apply merge (map (fn [[k v]] {k (update v :metadata #(merge meta %))}) actions)))

(defn build-registry
  "Builds the state for the registry (i.e map of actions)"
  [{:keys [controllers bindings interceptors]} exclusions overrides-allowed?]
  (let [actions (atom {})

        all-requirements (map #(select-keys % [:name :requires]) controllers)]
    (doseq [requirements all-requirements]
        (check-requirements-satisfied! requirements bindings "Controller"))
    (doseq [controller controllers]
      (let [{metadata :metadata, func :fn, local-exclude :exclude, local-include :include} controller
            ctrl-actions (func bindings)
            filtered-actions (cond
                               local-exclude (apply dissoc ctrl-actions (conj local-exclude exclusions))
                               local-include (select-keys (apply dissoc ctrl-actions exclusions) local-include)
                               :else ctrl-actions)
            inherited-actions (inherit-annotations filtered-actions metadata)
            intercepted-actions (add-interceptors inherited-actions interceptors bindings)
            action-instances (apply merge (map (fn [[k v]] {k (map->ActionInstance v)}) intercepted-actions))]
        (doseq [[id action] action-instances]
          (when (get @actions id)
            (if overrides-allowed?
              (log/warn (str "Overriding the Action " id))
              (throw+ (RuntimeException. (str "Action " id " already exists. To override this action you must set "
                                              ":allow-overrides to true in the registry configuration")))))
          (swap! actions assoc id action))))
    @actions))


(defservice
  ;; See ActionRegistry Protocol Documentation
  registry ActionRegistry
  [[:ConfigService get-in-config]]
  (init [this context]
        (log/info "Initializing Action Registry")
        (-> context
            (assoc :phase :init)
            (assoc :container {})))
  (start [this context]
         (log/info "Starting Action Registry")
         (-> context
             (assoc :phase :start)
             (assoc :container (build-registry @registry-container
                                               (get-in-config [:registry :exclusions])
                                               (get-in-config [:registry :allow-overrides])))))
  (stop [this context]
        (log/info "Stopping Action Registry")
        (reset! registry-container empty-container)
        (-> context
            (assoc :state nil)
            (assoc :container nil)))
  (register-controllers! [this controllers]
                         (within-phase
                           :init (service-context this)
                           (register-controllers!* controllers registry-container)))
  (register-controllers! [this controllers interceptors]
                         (within-phase
                           :init (service-context this)
                           (register-controllers!* controllers registry-container interceptors (get-in-config [:registry :interceptors :priorties]))))
  (register-bindings! [this bindings]
                      (within-phase
                        :init (service-context this)
                        (register-bindings!* bindings registry-container)))
  (register-interceptors! [this interceptors]
                      (within-phase
                        :init (service-context this)
                        (register-interceptors!* interceptors (get-in-config [:registry :interceptors :priorities]) registry-container)))
  (get-action [this id] (get-action* (:container (service-context this)) id))
  (select-meta-keys [this key-seq]
                    (select-meta-keys* (:container (service-context this)) key-seq))
  (select-meta-keys [this id key-seq]
                    (select-meta-keys* (:container (service-context this)) id key-seq)))

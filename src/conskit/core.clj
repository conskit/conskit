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
  (invoke [this request data]
    (f request data)))


;; Action Registry Service
(def ^:private registry-container
  "Temporary container"
  (atom {:controllers []
         :bindings {}
         :interceptors {:annotations #{}
                        :aliases      {}
                        :requirements {}
                        :handlers    {}
                        :settings    {}}}))

(defn- compare-priority
  "Compares the priority of two annotations"
  [config]
  (fn [m1 m2]
    (let [[k1 _] (first m1)
          [k2 _] (first m2)]
      (>= (or (config k1) 0) (or (config k2) 0)))))

(defn register-interceptors!*
  "Registers a list of controllers"
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
  "Registers a list of controllers"
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
  "Registers Bindings"
  [bindings container-atom]
  (doseq [[id value] bindings]
    (when (get-in @container-atom [:bindings id])
      (log/warn (str "The binding " id " is being overriden")))
    (swap! container-atom update :bindings #(assoc % id value))))


(defn get-action*
  "Retrieves an action"
  [container id]
  (get container id))

(defn- check-requirements-satisfied!
  "Verifies that bindings exist for all the controller parameters"
  [{:keys [name requires]} bindings type]
  (doseq [req requires]
    (assert (bindings req) (str type " \"" name "\" requirement \"" req "\" is not satisfiable: Did you register a binding for it?"))))

(defn- intercept-all
  [actions exception config handler has-req? bindings]
  (apply merge
         (for [[k v] actions
               :let [{:keys [metadata f]} v]]
           (if (exception metadata)
             {k v}
             {k (assoc v :f (if has-req? (handler f config bindings) (handler f config)))}))))

(defn- check-for-ambiguous-alias!
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
  [actions annot alias handler has-req? bindings all-aliases]
  (apply merge
         (for [[k v] actions
               :let [{:keys [metadata f]} v]]
           (do (check-for-ambiguous-alias! metadata alias all-aliases)
               (if-let [config (or (annot metadata) (alias metadata))]
                 {k (assoc v :f (if has-req? (handler f config bindings) (handler f config)))}
                 {k v})))))

(defn- add-interceptors
  ""
  [actions {:keys [annotations aliases handlers settings requirements]} bindings]
  (loop [annots annotations
         acts actions]
    (if (empty? annots)
      acts
      (let [[annot all?] (ffirst annots)
            alias (annot aliases)
            handler (annot handlers)
            {:keys [except config]} (annot settings)
            req (annot requirements)
            has-req? (:requires req)]
        (check-requirements-satisfied! req bindings "Interceptor")
        (recur (rest annots)
               (if all?
                 (intercept-all acts except config handler has-req? bindings)
                 (intercept-specific acts annot alias handler has-req? bindings aliases)))))))

(defn- inherit-annotations
  ""
  [actions meta]
  (apply merge (map (fn [[k v]] {k (update v :metadata #(merge meta %))}) actions)))

(defn build-registry
  "Builds the state for the registry"
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
  ;; Change this to store the action group/functions initially along with all domain services in the INIT phase
  ;; Then in the START Phase is where the action maps will be realized by closing them over all the domain services
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
                        (register-interceptors!* interceptors (get-in-config [:registry :interceptors :priorties]) registry-container)))
  (get-action [this id] (get-action* (:container (service-context this)) id))
  (selectactions [this key-seq]
                 ; TODO: Implement Me
                 ))

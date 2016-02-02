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
                        :all         #{}
                        :alias       {}
                        :handlers    {}
                        :settings    {}}}))

(defn- compare-priority
  "Compares the priority of two annotations"
  [config]
  #(>= (or (config %1) 0) (or (config %2) 0)))

(defn register-interceptors!*
  "Registers a list of controllers"
  ([interceptors config container-atom] (register-interceptors!* interceptors config container-atom false))
  ([interceptors config container-atom all?]
    (doseq [inter interceptors]
      (let [{qannot :annotation, annot :alias, interceptor :fn} (if (vector? inter) (first inter) inter)]
        (when (vector? inter)
          (swap! container-atom update-in [:interceptors] #(assoc-in % [:settings qannot] (second inter))))
        (swap! container-atom update-in [:interceptors]
               #(-> %
                    (update (if all? :all :annotations) (fn [v] (into (sorted-set-by (compare-priority config)) (conj v qannot))))
                    (assoc-in [:alias qannot] annot)
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
  [{:keys [name requires]} bindings]
  (doseq [req requires]
    (assert (bindings req) (str "Controller \"" name "\" requirement \"" req "\" is not satisfiable: Did you register a binding for it?"))))

(defn- add-global-interceptors
  [annotations handlers settings bindings])

(defn- add-specific-interceptors
  "docstring"
  []
  )

(defn- inherit-annotations
  ""
  [actions meta]
  (map (fn [[k v]] {k (update v :metadata #(merge meta %))}) actions))

(defn build-registry
  "Builds the state for the registry"
  [{:keys [controllers bindings interceptors]} exclusions]
  (let [actions (atom {})

        all-requirements (map #(select-keys % [:name :requires]) controllers)
        _ (doseq [requirements all-requirements]
            (check-requirements-satisfied! requirements bindings))

        {global-annotations   :all, annotations :annotations,
         qualified-annotation :alias, handlers :handlers,
         exceptions           :settings} interceptors
        ]
    (doseq [controller controllers]
      (let [{metadata :metadata, func :fn, local-exclude :exclude, local-include :include} controller
            ctrl-actions (func bindings)
            filtered-actions (cond
                               local-exclude (apply dissoc ctrl-actions (conj local-exclude exclusions))
                               local-include (select-keys (apply dissoc ctrl-actions exclusions) local-include)
                               :else ctrl-actions)
            inherited-actions (inherit-annotations filtered-actions metadata)]
        ))
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
             (assoc :container (build-registry @registry-container (get-in-config [:registry :exclusions])))))
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

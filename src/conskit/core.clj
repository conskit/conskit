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
                        :handlers {}}}))

(defn register-controllers!*
  "Registers a list of controllers"
  [controllers container-atom]
  (swap! container-atom update :controllers #(into % controllers)))

(defn register-bindings!*
  "Registers Bindings"
  [bindings container-atom]
  (doseq [[id value] bindings]
    (when (get-in @container-atom [:bindings id])
      (log/warn (str "The binding " id " is being overriden")))
    (swap! container-atom update :bindings #(assoc % id value))))

(defn- compare-priority
  "Compares the priority of two annotations"
  [config]
  #(>= (config %1) (config %2)))

(defn register-interceptors!*
  "Registers a list of controllers"
  [interceptors config container-atom]
  (doseq [[annot interceptor] interceptors]
    (assert (var? interceptor) (str "Intercepter must be specified as a var"))
    (swap! container-atom update-in [:interceptors]
           #(let [all? (-> interceptor meta :all)]
             (-> %
                 (update :annotations (fn [v] (into (sorted-set-by (compare-priority config)) (conj v annot))))
                 (assoc-in [:handlers annot] interceptor))))))

(defn get-action*
  "Retrieves an action"
  [container id]
  (get container id))

(defn- check-requirements-satisfied!
  "docstring"
  [{:keys [name requires]} bindings]
  (doseq [req requires]
    (assert (bindings req) (str "Controller \"" name "\" requirement \"" req "\" is not satisfiable: Did you register a binding for it?"))))

(defn build-registry
  "Builds the state for the registry"
  [{:keys [controllers bindings interceptors]}]
  (let [actions (atom {})
        all-requirements (map #(select-keys % [:name :requires]) controllers)
        _ (doseq [requirements all-requirements]
            (check-requirements-satisfied! requirements bindings))
        controller-fns (map :fn controllers)
        action-maps (map #(% bindings) controller-fns)]
    action-maps))


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
             (assoc :container @registry-container)))
  (stop [this context]
        (log/info "Stopping Action Registry")
        (-> context
            (assoc :state nil)
            (assoc :container nil)))
  (register-controllers! [this controllers]
                         (within-phase
                           :init (service-context this)
                           (register-controllers!* controllers registry-container)))
  (register-bindings! [this bindings]
                      (within-phase
                        :init (service-context this)
                        (register-bindings!* bindings registry-container)))
  (register-interceptors! [this interceptors]
                      (within-phase
                        :init (service-context this)
                        (register-interceptors!* interceptors (get-in-config [:registry :interceptors :priorties]) registry-container)))
  (get-action [this id] (get-action* (:container (service-context this)) id)))

(ns conskit.core)

(defprotocol Action
  "Actions can be invoked"
  (invoke [this request data] "Invoke this action"))

(defprotocol ActionRegistry
  "Functions to manage an instance of ActionRegistry"
  (register-actions! [this actiongroups] "Registers multiple actions supplied in hashmap")
  (register-services! [this services-map] "Registers multiple actions supplied in hashmap")
  (get-action [this id] "Retrieve an action from the registry"))

;; Concrete Implementation of Action Protocol
(defrecord ActionInstance [metadata f]
  Action
  (invoke [this request data]
    (f request data metadata)))

(ns conskit.protocols)

(defprotocol Action
  "Actions can be invoked"
  (invoke [this request data] "Invoke this action"))

(defprotocol ActionRegistry
  "Functions to manage an instance of ActionRegistry"
  (register-controllers! [this controllers]
    "Registers a list of controllers in the form:

     [c1 c2] or [[c1 options] [c2 options]]

     where options can be {:exclude [:actionnames]} or {:include [:actionnames]}
     used to filter which actions in the controller will be registered")
  (register-bindings! [this bindings]
    "Registers a map of bindings that will be provided to controllers at startup time.
     These bindings can then be used in actions")
  (register-interceptors! [this interceptors]
    "Registers a list of interceptors that will be used to wrap actions depending the presence of a particular
     annotation. the list is in the form:

     {:annotation-key interceptor-fn} or {:annotation-key [priority interceptor-fn]}

     where the interceptor-fn is a function that accepts both the function and metadata of an action and
     returns a new function that accepts a request, data and metadata")
  (get-action [this id] "Retrieve an action from the registry"))

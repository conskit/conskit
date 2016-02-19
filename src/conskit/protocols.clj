(ns conskit.protocols)

(defprotocol Action
  "Actions can be invoked"
  (invoke [this request] "Invoke this action"))

(defprotocol ActionRegistry
  "Functions to manage an instance of ActionRegistry"
  (register-controllers! [this controllers] [this controllers interceptors]
    "Registers a list of controllers in the form:

     [c1 c2] or [[c1 options] [c2 options]]

     where options can be {:exclude [:actionnames]} or {:include [:actionnames]}
     used to filter which actions in the controller will be registered.

     Any interceptors passed in here will be used to wrap all actions. They can be specified as:

     [in1 in2] or [[in1 options] [in2 options]]

     where options at the moment can be {:except :ignore-annotation :config :settings} which specifies an annotation
     that can be used to exclude a particular action or controller from being wrapped as well as the default config
     to be passed to the interceptor")
  (register-bindings! [this bindings]
    "Registers a map of bindings that will be provided to controllers at startup time.
     These bindings can then be used in actions")
  (register-interceptors! [this interceptors]
    "Registers a list of interceptors that will be used to wrap actions depending the presence of a particular
     annotation. the list is in the form:

     {:annotation-key interceptor-fn} or {:annotation-key [priority interceptor-fn]}

     where the interceptor-fn is a function that accepts both the function and metadata of an action and
     returns a new function that accepts a request, data and metadata")
  (get-action [this id]
    "Retrieve an action from the registry")
  (select-meta-keys [this key-seq] [this id key-seq]
    "Is effectively the result of mapping selectkeys on the metadata of a one or all action(s)"))

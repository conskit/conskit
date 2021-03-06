(ns conskit.macros
  (:require [slingshot.slingshot :refer [throw+]]))

(defmacro within-phase
  "A macro that can be used to ensure that certain activites
  only happen during the init phase of a service (see hrubix.services.routing for an example)"
  [phase context & body]
  `(if (= (get ~context :phase) ~phase)
     (do ~@body)
     (throw+ (Exception. (str "This can only be done during the " (name ~phase) " phase")))))


(defmacro action
  "A macro that produces a map containing a single entry that has metadata and a function.

  Actions are created with an id which is a namespaced keyword combination of the namespace
  it was defined in and the action name. it is added as a part of the metadata.

  Additional meta data can be added by annotation the action using clojure's meta facilities
  (i.e) `^{:hello :world}`"
  [action-name args & body]
  (let [defined-ns (str *ns*)
        action-ns (or (namespace action-name) defined-ns)
        temp-name (symbol (name action-name))
        action-meta (meta action-name)]
    (assert (= (count args) 1) "Function arity for actions must be 1")
    `(let [action-var# (def ~temp-name)                     ;; Might not Need this anymore
           metadata# (merge (meta action-var#) ~action-meta)
           _# (ns-unmap *ns* '~temp-name)
           key# (keyword ~action-ns (name '~action-name))]
       {key# {:metadata (-> metadata#
                            (dissoc :column)
                            (dissoc :ns)
                            (assoc :id key#)
                            (assoc :ns ~defined-ns))
              :f      (fn ~args ~@body)}})))

(defmacro defcontroller
  "A macro that creates a var containing a map with data pertaining to a controller as well
  as a function that expects to be provided with a map of bindings to produce a map of actions"
  [cname args & actions]
  (let [destructured (destructure [{:keys args}])
        metadata (meta cname)
        defined-in-ns (str *ns*)]
    `(def ~cname
       {:name     (name '~cname)
        :requires (map keyword '~args)
        :metadata ~metadata
        :ns       ~defined-in-ns
        :fn       (fn
                    ~destructured
                    (merge ~@actions))})))

(defmacro definterceptor
  "A macro that create a var containing a map with data pertaining to an interceptor including
  the function to be used to wrap actions"
  [iname & forms]
  (let [meta (meta iname)
        _ (assert meta (str "Missing annotation for interceptor: " iname))
        annot (first (first meta))
        has-ns? (namespace annot)
        has-doc? (string? (first forms))
        doc-string (if has-doc? (first forms) "")
        [args & body] (if has-doc? (rest forms) forms)
        num-args (count args)
        annot-alias (if has-ns? (keyword (name annot)) annot)
        qualified-annot (if has-ns? annot (keyword (str *ns*) (name annot)))
        result {:alias annot-alias :annotation qualified-annot :name (name iname)}]
    (assert (= (count meta) 1) "Only one annotation allowed per interceptor")
    (condp = num-args
      ;2 `(def ~iname ~doc-string
      ;     (assoc ~result
      ;       :fn (fn [~@args] ~@body)))
      3 (let [arg (get args 2)
              isBinding? (and (set? arg)
                             (not-empty arg))
              b-args (if isBinding?
                       (destructure (assoc args 2 {:keys (into [] arg)}))
                       args)]
          (if isBinding?
            `(def ~iname ~doc-string
               (-> ~result
                   (assoc :requires (map keyword '~arg))
                   (assoc :fn (fn [~@b-args] ~@body))))
            `(def ~iname ~doc-string
               (assoc ~result :fn (fn [~@(take 2 args)]
                                    (fn [~@(take-last 1 args)] ~@body))))))
      4 (let [bindings (get args 2)
              _ (assert (and (set? bindings)
                             (not-empty bindings)) "Interceptor bindings must be specified as a non-empty set of symbols")
              first-args (into [] (take 3 args))
              b-args (destructure (assoc first-args 2 {:keys (into [] bindings)}))
              rest-args (take-last 1 args)]
          `(def ~iname ~doc-string
             (-> ~result
                 (assoc :requires (map keyword '~bindings))
                 (assoc :fn (fn [~@b-args]
                         (fn [~@rest-args] ~@body)))))))))

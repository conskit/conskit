(ns conskit.macros
  (:require [slingshot.slingshot :refer [throw+]]))

(defmacro within-phase
  "A macro that can be used to ensure that certain activites
  only happen during the init phase of a service (see hrubix.services.routing for an example)"
  [phase context & body]
  `(if (= (get ~context :phase) ~phase)
     (do ~@body)
     (throw+ (Exception. "This can only be done during init phase"))))


(defmacro action [action-name args & body]
  (let [action-ns (or (namespace action-name) (str *ns*))
        temp-name (symbol (name action-name))
        action-meta (meta action-name)]
    (assert (= (count args) 2) "Function arity for actions must be 2")
    `(let [action-var# (def ~temp-name)                     ;; Might not Need this anymore
           metadata# (merge (meta action-var#) ~action-meta)
           _# (ns-unmap *ns* '~temp-name)
           key# (keyword ~action-ns (name '~action-name))]
       {key# {:metadata (-> metadata#
                            (dissoc :column)
                            (dissoc :ns)
                            (assoc :id key#))
              :f      (fn ~args ~@body)}})))

(defmacro defcontroller [cname args & actions]
  (let [destructured (destructure [{:keys args}])
        metadata (meta cname)
        defined-in-ns (str *ns*)]
    `(def ~cname "kdjfkjlad"
       {:name     (name '~cname)
        :requires (map keyword '~args)
        :metadata ~metadata
        :ns       ~defined-in-ns
        :fn       (fn
                    ~destructured
                    (merge ~@actions))})))

(defmacro definterceptor [iname & forms]
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
        result {:alias annot-alias :annotation qualified-annot}]
    (assert (= (count meta) 1) "Only one annotation allowed per interceptor")
    (condp = num-args
      2 `(def ~iname ~doc-string
           (assoc ~result
             :fn (fn [~@args] ~@body)))
      3 (let [bindings (get args 2)
              _ (assert (and (set? bindings)
                             (not-empty bindings)) "Interceptor bindings must be specified as a non-empty set of symbols")
              b-args (destructure (assoc args 2 {:keys (into [] bindings)}))]
          `(def ~iname ~doc-string
             (assoc ~result
               :fn (fn [~@b-args] ~@body))))
      4 `(def ~iname ~doc-string
           (assoc ~result
             :fn (fn [~@(take 2 args)]
                   (fn [~@(take-last 2 args)] ~@body))))
      5 (let [bindings (get args 2)
              _ (assert (and (set? bindings)
                             (not-empty bindings)) "Interceptor bindings must be specified as a non-empty set of symbols")
              first-args (into [] (take 3 args))
              b-args (destructure (assoc first-args 2 {:keys (into [] bindings)}))
              rest-args (take-last 2 args)]
          `(def ~iname ~doc-string
             (assoc ~result
               :fn (fn [~@b-args]
                     (fn [~@rest-args] ~@body))))))))

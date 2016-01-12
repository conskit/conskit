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
  (let [defined-in-ns (str *ns*)]
    (assert (= (count args) 2) "Function arity for actions must be 2")
    `(let [action-var# (def ~action-name)
           metadata# (meta action-var#)
           _# (ns-unmap *ns* '~action-name)
           key# (keyword ~defined-in-ns (name '~action-name))]
       {key# {:metadata (-> metadata# (dissoc :column) (dissoc :ns))
              :fn      (fn ~args ~@body)}})))

(defmacro defcontroller [cname args & actions]
  (let [destructured (destructure [{:keys args}])
        metadata (meta cname)]
    `(def ~cname "kdjfkjlad"
       {:name     (name '~cname)
        :requires (map keyword '~args)
        :metadata ~metadata
        :fn       (fn
                    ~destructured
                    (merge ~@actions))})))

(defmacro definterceptor [iname & forms]
  (let [meta (meta iname)
        all-except? (:all-except meta)
        annot (if all-except? meta (first (first meta)))
        has-doc? (string? (first forms))
        doc-string (if has-doc? (first forms) "")
        [args & body] (if has-doc? (rest forms) forms)]
    (assert (= (count meta) 1) "Only one annotation allowed per interceptor")
    (condp = (count args)
      2 `{:annotation ~annot
          :fn         (defn ~iname ~doc-string ~args ~@body)}
      4 `{:annotation ~annot
          :fn         (defn ~iname ~doc-string [~@(take 2 args)]
                        (fn [~@(take-last 2 args)] ~@body))})))

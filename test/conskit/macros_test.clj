(ns conskit.macros-test
  (:require [conskit.macros :refer :all])
  (:use [midje.sweet]))

(def test-action (action
                   ^{:doc "This cool action"
                     :intercept-request true}
                   do-all-the-things
                   [a _]
                   {:foo a}))
(defcontroller
  ^{:intercept-response true}
  test-controller
  [a b]
  test-action
  (action
    ^{:doc "This cool action"}
    do-one-thing
    [_ _]
    {:foo a}))

(definterceptor
  ^:intercept-request
  test-interceptor-request
  "Intercept Request"
  [f config request data]
  (f request (assoc data :intercept-req config)))

(definterceptor
  ^:all
  test-interceptor-all
  "Intercept all actions"
  [f config request data]
  (f (assoc request :all-actions :have-this) data))

(definterceptor
  ^:does-nothing
  test-interceptor-all-except
  "Intercept all actions"
  [f config request data]
  (f (assoc request :all-actions :have-this) data))

(definterceptor
  ^:intercept-response
  test-interceptor-response
  "Foo Bar"
  [f config #{add}]
  (let [heavy-startup-operation-result (identity config)]
    (fn
      [request data]
      (assoc (f request data) :intercept-resp heavy-startup-operation-result))))


(fact
  "Action can be created from macro"
  (select-keys
    (get-in test-action [::do-all-the-things :metadata])
    [:doc :intercept-request :id]) => {:doc  "This cool action"
                                       :intercept-request true
                                       :id ::do-all-the-things})
(fact
  "Calling the action function should execute the body with the parameters"
  (let [f (get-in test-action [::do-all-the-things :f])]
    (f "bar" 2)) => {:foo "bar"})

(fact
  "defcontroller macro procduces correct name and requirements"
  (select-keys test-controller [:name :requires :metadata]) => {:name "test-controller" :requires [:a :b] :metadata {:intercept-response true}})

(fact
  "Calling the controller fn with bindings should return a map with its actions as keys"
  (let [f (get test-controller :fn)]
    (keys (f {:a 1 :b 2}))) => [::do-all-the-things ::do-one-thing])

(fact
  "An action defined within defcontroller should have access to the controllers bindings."
  (let [ctrlf (get test-controller :fn)
        f (get-in (ctrlf {:a :baz :b 2}) [::do-one-thing :f])]
    (f 1 2)) => {:foo :baz})

(fact
  "definterceptor macro produces the correct annotation"
  (select-keys test-interceptor-request [:annotation :alias]) => {:annotation           ::intercept-request
                                                                  :alias :intercept-request})

(fact
  "Interceptor function returns an intercepted function"
  (let [f (:fn test-interceptor-request)
        intercepted (f #(merge %1 %2) {:empty :config})]
    (intercepted {} {})) => {:intercept-req {:empty :config}})

(ns conskit.macros-test
  (:require [conskit.macros :refer :all])
  (:use [midje.sweet]))

(def test-action (action
                   ^{:doc "This cool action"
                     :custom :stuff}
                   do-all-the-things
                   [a _]
                   {:foo a}))
 (defcontroller
   ^{:wrap :all}
  test-controller
  [a b]
  test-action
  (action
    ^{:doc    "This cool action"
      :custom :stuff}
    do-one-thing
    [_ _]
    {:foo a}))

(definterceptor
  ^:intercept-request
  test-interceptor-request
  "Hmmmm"
  [f config request data]
  (f request (assoc data :intercept-req config)))


(definterceptor
  ^:all
  test-interceptor
  [f config request data]
  (f request data))

(definterceptor
  ^:intercept-response
  test-interceptor-response
  "Foo Bar"
  [f config]
  (let [heavy-startup-operation-result (identity config)]
    (fn
      [request data]
      (f request (assoc data :intercept-req heavy-startup-operation-result)))))


(fact
  "Action can be created from macro"
  (select-keys
    (get-in test-action [::do-all-the-things :metadata])
    [:doc :custom]) => {:doc  "This cool action"
                        :custom :stuff})
(fact
  "Calling the action function should execute the body with the parameters"
  (let [f (get-in test-action [::do-all-the-things :fn])]
    (f "bar" 2)) => {:foo "bar"})

(fact
  "defcontroller macro procduces correct name and requirements"
  (select-keys test-controller [:name :requires :metadata]) => {:name "test-controller" :requires [:a :b] :metadata {:wrap :all}})

(fact
  "Calling the controller fn with bindings should return a map with its actions as keys"
  (let [f (get test-controller :fn)]
    (keys (f {:a 1 :b 2}))) => [::do-all-the-things ::do-one-thing])

(fact
  "An action defined within defcontroller should have access to the controllers bindings."
  (let [ctrlf (get test-controller :fn)
        f (get-in (ctrlf {:a :baz :b 2}) [::do-one-thing :fn])]
    (f 1 2)) => {:foo :baz})

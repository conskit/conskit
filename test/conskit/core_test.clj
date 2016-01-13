(ns conskit.core-test
  (:require [conskit.core :refer :all]
            [conskit.macros-test :refer [test-controller]])
  (:use midje.sweet))

(def test-container (atom {}))

(with-state-changes
  [(before :facts (do (reset! test-container {:controllers  []
                                              :bindings     {}
                                              :interceptors {}})
                      (register-controllers!* [test-controller] test-container)
                      (register-bindings!* {:a 1 :b 2} test-container)
                      ;(register-interceptors!* [] {:intercept-request 0 :intercept-response 2} test-container)
                      ))]
  (fact (map #(select-keys % [:name :requires]) (:controllers @test-container)) => [{:name "test-controller" :requires [:a :b]}])
  (fact (:bindings @test-container) => {:a 1 :b 2})
  (fact (first (get-in @test-container [:interceptors :annotations])) => :intercept-response)
  (fact (let [f (get-in @test-container [:interceptors :handlers :intercept-request])
              intercepted (f (fn [request data] data) [:hello])]
          (intercepted {} {})) => {:intercept-req [:hello]})
  (fact (let [f (get-in @test-container [:interceptors :handlers :intercept-response])
              intercepted (f (fn [request data] data) [:goodbye])]
          (intercepted {} {})) => {:intercept-resp [:goodbye]})
  (fact (build-registry @test-container) => []))

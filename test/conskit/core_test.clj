(ns conskit.core-test
  (:require [conskit.core :refer :all]
            [conskit.macros :refer :all])
  (:use midje.sweet))


(def test-container (atom {}))

(def priorities-config {:foo/modify-request 0 ::modify-response 2})

(def test-bindings {:add +})

(def global-exclusions [::action-without-annotation])

(defcontroller
  normal-controller
  [service-a service-b]
  (action
    not/normal-action
    [req data]
    {:result (+ service-a service-b)}))

(definterceptor
  ^:foo/modify-request
   modify-requests
  "Intercepts requests and adds {:mod :req}"
  [f config #{add} request data]
  (if config
    (f (assoc request :mod :req) data)
    (f request data)))

(definterceptor
  ^:modify-response
  modify-responses
  "Intercepts responses and adds {:mod :resp}"
  [f config request data]
  (if config
    (assoc (f request data) :mod :resp)
    (f request data)))

(definterceptor
  ^:modify-req-resp
  modify-both
  "Modifyies the request and response"
  [f config #{add} request data]
  (if config
    (assoc (f (assoc request :req :mod) data) :resp :mod)
    (f request data)))

(defcontroller
  ^{:modify-request true}
  controller-with-annotation
  []
  (action
    ^{:modify-response true}
    action-with-annotation
    [req data]
    {:hello "world" :req req})
  (action
    action-without-annotation
    [req data]
    {:hello "world" :req req}))

(defcontroller
  controller-with-some-unwanted-actions
  []
  (action
    i-want-this-action
    [req data]
    {:hey :i_got_picked})
  (action
    i-dont-want-this-action
    [req data]
    {:boo! :i_was_not_chosen}))

(with-state-changes
  [(before :facts (do (reset! test-container {:controllers  []
                                              :bindings     {}
                                              :interceptors {:annotations #{}
                                                             :all         #{}
                                                             :alias       {}
                                                             :handlers    {}
                                                             :settings    {}}})
                      (register-controllers!* [normal-controller
                                               controller-with-annotation
                                               [controller-with-some-unwanted-actions
                                                {:exclude [:i-dont-want-this-action]}]
                                               [controller-with-some-unwanted-actions
                                                {:include [:i-want-this-action]}]]
                                              test-container
                                              [[modify-both {:except :dont-modify-me}]]
                                              priorities-config)
                      (register-bindings!* {:service-a 1 :service-b 2}
                                           test-container)
                      (register-interceptors!* [modify-requests
                                                modify-responses]
                                               priorities-config
                                               test-container)
                      ))]
  ;; Tests
  (fact (map #(select-keys % [:name :requires :metadata :exclude :include])
             (:controllers @test-container)) => [{:name "normal-controller" :requires [:service-a :service-b] :metadata nil}
                                                 {:name "controller-with-annotation" :requires [] :metadata {:modify-request true}}
                                                 {:name "controller-with-some-unwanted-actions" :requires [] :metadata nil :exclude [::i-dont-want-this-action]}
                                                 {:name "controller-with-some-unwanted-actions" :requires [] :metadata nil :include [::i-want-this-action]}
                                                 ])
  (fact (:bindings @test-container) => {:service-a 1 :service-b 2})
  (fact (first (get-in @test-container [:interceptors :annotations])) => ::modify-response)
  (fact (let [f (get-in @test-container [:interceptors :handlers :foo/modify-request])
              intercepted (f (fn [request data] request) true test-bindings)]
          (intercepted {} {})) => {:mod :req})
  (fact (let [f (get-in @test-container [:interceptors :handlers ::modify-response])
              intercepted (f (fn [request data] data) true)]
          (intercepted {} {})) => {:mod :resp})
  (fact (let [f (get-in @test-container [:interceptors :handlers ::modify-req-resp])
              intercepted (f (fn [request data] (merge request data)) true test-bindings)]
          (intercepted {} {})) => {:resp :mod :req :mod})
  (fact (get-in @test-container [:interceptors :annotations]) => (just [:foo/modify-request ::modify-response]))
  (fact (get-in @test-container [:interceptors :all]) => (just [::modify-req-resp]))
  (fact (get-in @test-container [:interceptors :settings ::modify-req-resp]) => {:except :dont-modify-me})
  (fact (build-registry @test-container global-exclusions) => []))

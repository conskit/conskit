(ns conskit.core-test
  (:require [conskit.core :refer :all]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-cli-data]]
            [conskit.protocols :as p]
            [conskit.macros :refer :all])
  (:use midje.sweet))


(def test-container (atom {}))

(def test-registry (atom {}))

(def priorities-config {:foo/modify-request 0 ::modify-response 2})

(def test-bindings {:add + :binding-a 1 :binding-b 2})

(def global-exclusions [::action-without-annotation])

(def allow-overrides? true)

(defcontroller
  normal-controller
  [add binding-a binding-b]
  (action
    not/normal-action
    [req]
    {:result (add binding-a binding-b)}))

(definterceptor
  ^:foo/modify-request
   modify-requests
  "Intercepts requests and adds {:mod :req}"
  [f config #{add} request]
  (if config
    (f (assoc request :mod :req))
    (f request)))

(definterceptor
  ^:modify-response
  modify-responses
  "Intercepts responses and adds {:mod :resp}"
  [f config request]
  (if config
    (assoc (f request) :mod :resp)
    (f request)))

(definterceptor
  ^:modify-req-resp
  modify-both
  "Modifyies the request and response"
  [f config #{add} request]
  (if config
    (assoc (f (assoc request :req :mod)) :resp :mod)
    (f request)))

(defcontroller
  ^{:modify-request true}
  controller-with-annotation
  []
  (action
    ^{::modify-response true}
    action-with-annotation
    [req]
    {:hello "world" :req req})
  (action
    action-without-annotation
    [req]
    {:hello "world" :req req}))

(defcontroller
  controller-with-some-unwanted-actions
  []
  (action
    i-want-this-action
    [req]
    {:hey :i_got_picked})
  (action
    i-dont-want-this-action
    [req]
    {:boo! :i_was_not_chosen}))

(defcontroller
  controller-with-some-wanted-actions
  []
  (action
    i-really-want-this-action
    [req]
    {:woo! :i_am_the_best})
  (action
    i-really-dont-want-this-action
    [req]
    {:sad :i_am_depressed}))

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
                                               [controller-with-some-wanted-actions
                                                {:include [:i-really-want-this-action]}]]
                                              test-container
                                              [[modify-both {:except :dont-modify-me}]]
                                              priorities-config)
                      (register-bindings!* test-bindings
                                           test-container)
                      (register-interceptors!* [modify-requests
                                                modify-responses]
                                               priorities-config
                                               test-container)
                      (reset! test-registry (build-registry @test-container global-exclusions allow-overrides?))))]
  ;; Tests
  (fact "All Controllers have been registered with their requirements, metadata and include/exclude option"
        (map #(select-keys % [:name :requires :metadata :exclude :include])
             (:controllers @test-container)) => [{:name "normal-controller" :requires [:add :binding-a :binding-b] :metadata nil}
                                                 {:name "controller-with-annotation" :requires [] :metadata {:modify-request true}}
                                                 {:name "controller-with-some-unwanted-actions" :requires [] :metadata nil :exclude [::i-dont-want-this-action]}
                                                 {:name "controller-with-some-wanted-actions" :requires [] :metadata nil :include [::i-really-want-this-action]}
                                                 ])
  (fact "All bindings were registered"
        (keys (:bindings @test-container)) => [:add :binding-a :binding-b])
  (fact "Interceptor Annotations where registered"
        (get-in @test-container [:interceptors :annotations]) => (just [{::modify-response false} {::modify-req-resp true} {:foo/modify-request false}]))
  (fact "Intercetor function for :foo/modify-request works as expected"
        (let [f (get-in @test-container [:interceptors :handlers :foo/modify-request])
              intercepted (f (fn [request] request) true test-bindings)]
          (intercepted {})) => {:mod :req})
  (fact "Intercetor function for ::modify-response works as expected"
        (let [f (get-in @test-container [:interceptors :handlers ::modify-response])
              intercepted (f (fn [request] request) true)]
          (intercepted {})) => {:mod :resp})
  (fact "Intercetor function for ::modify-req-response works as expected"
        (let [f (get-in @test-container [:interceptors :handlers ::modify-req-resp])
              intercepted (f (fn [request] request) true test-bindings)]
          (intercepted {})) => {:resp :mod :req :mod})
  (fact "Global intereptors can specify settings when registered"
    (get-in @test-container [:interceptors :settings ::modify-req-resp]) => {:except :dont-modify-me})
  (fact "All actions from all controllers where registered except those that were explicitly filtered out"
    (keys @test-registry) => [:not/normal-action  ::action-with-annotation ::action-without-annotation ::i-want-this-action ::i-really-want-this-action])
  (fact "Invoking the action ::action-with-annotation should intercept the request and response"
        (p/invoke (get-action* @test-registry ::action-with-annotation)
                                  {}) => {:hello "world", :mod :resp, :req {:mod :req}})
  (fact "Invoking the action ::action-without-annotation should ONLY intercept the request"
        (p/invoke (get-action* @test-registry ::action-without-annotation)
                                  {}) => {:hello "world", :req {:mod :req}})
  (fact "Invoking the action :not/normal-action should NOT intercept the request or response and should have result adding the 2 services"
        (p/invoke (get-action* @test-registry :not/normal-action)
                                  {}) => {:result 3})
  (fact "Invoking the action ::i-want-this-action should NOT intercept the request or response and should work as expected"
        (p/invoke (get-action* @test-registry ::i-want-this-action)
                                  {}) => {:hey :i_got_picked})
  (fact "Invoking the action ::i-really-want-this-action should NOT intercept the request or response and should work as expected"
        (p/invoke (get-action* @test-registry ::i-really-want-this-action)
                                  {}) => {:woo! :i_am_the_best})
  (fact "Metadata for all actions can be retrieved with select-meta-keys"
        (select-meta-keys* @test-registry [:id]) => [{:id :not/normal-action}
                                                     {:id ::action-with-annotation}
                                                     {:id ::action-without-annotation}
                                                     {:id ::i-want-this-action}
                                                     {:id ::i-really-want-this-action}])
  (fact "Metadata for a single action can be retrieved with select-meta-keys"
        (select-meta-keys* @test-registry ::action-with-annotation [::modify-response :modify-reponse]) => {::modify-response true}))

(defservice
  test-service
  [[:ActionRegistry register-controllers! register-bindings! register-interceptors!]]
  (init [this context]
        (register-controllers! [normal-controller
                                controller-with-annotation
                                [controller-with-some-unwanted-actions
                                 {:exclude [:i-dont-want-this-action]}]
                                [controller-with-some-wanted-actions
                                 {:include [:i-really-want-this-action]}]]
                               [[modify-both {:except :dont-modify-me}]])
        (register-bindings! test-bindings)
        (register-interceptors! [modify-requests
                                 modify-responses])
        context))

(with-app-with-cli-data
  app
  [registry test-service]
  {:config "./dev-resources/test-config.conf"}
  (let [reg (app/get-service app :ActionRegistry)]
    (fact (p/invoke (p/get-action reg ::action-with-annotation)
                    {}) => {:hello "world", :mod :resp, :req {:mod :req}})
    (fact "Invoking the action ::action-with-annotation should intercept the request and response"
          (p/invoke (p/get-action reg ::action-with-annotation)
                    {}) => {:hello "world", :mod :resp, :req {:mod :req}})
    (fact "Invoking the action ::action-without-annotation should ONLY intercept the request"
          (p/invoke (p/get-action reg ::action-without-annotation)
                    {}) => {:hello "world", :req {:mod :req}})
    (fact "Invoking the action :not/normal-action should NOT intercept the request or response and should have result adding the 2 services"
          (p/invoke (p/get-action reg :not/normal-action)
                    {}) => {:result 3})
    (fact "Invoking the action ::i-want-this-action should NOT intercept the request or response and should work as expected"
          (p/invoke (p/get-action reg ::i-want-this-action)
                    {}) => {:hey :i_got_picked})
    (fact "Invoking the action ::i-really-want-this-action should NOT intercept the request or response and should work as expected"
          (p/invoke (p/get-action reg ::i-really-want-this-action)
                    {}) => {:woo! :i_am_the_best})))

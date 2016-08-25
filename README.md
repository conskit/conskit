
# Conskit [![Build Status](https://travis-ci.org/conskit/conskit.svg)](https://travis-ci.org/conskit/conskit) [![Dependencies Status](https://jarkeeper.com/conskit/conskit/status.svg)](https://jarkeeper.com/conskit/conskit) [![Clojars Project](https://img.shields.io/clojars/v/conskit.svg)](https://clojars.org/conskit)

Conskit (Construction Kit) is a toolkit for building Clojure web applications in a very modular and consistent manner
while still providing freedom and flexibility that has become commonplace in the Clojure community.


## Quick Links
- [Lein Template](https://github.com/conskit/ck_app)
- [Full Documentation](https://github.com/conskit/conskit/wiki)
- [Book CRUD Tutorial](https://github.com/conskit/conskit/wiki/Let's-Build-a-Book-Database-(CRUD))

## Conskit at a Glance
Conskit was built using [Trapperkeeper from puppetlabs](https://github.com/puppetlabs/trapperkeeper), a microservices framework, as a base. In fact, at
it's core Conskit is simply a single Trapperkeeper service that provides the following methods: `register-bindings!`,
`register-controllers!` and `register-intercepters!`. Before diving any further into their purposes, I highly recommend
taking a glance at Trapperkeeper's comprehensive [documentation](https://github.com/puppetlabs/trapperkeeper/wiki) and
in particular, how to [define services](https://github.com/puppetlabs/trapperkeeper/wiki/Defining-Services).

### Actions
The primary unit of work in Conskit is an `Action`. Actions are invokable objects that accepts a one parameter. Here is the simplest example of an `Action` defined using the `action` macro:

```clojure
(ns com.foobar)

(def test-action
  (action
    do-all-the-things
    [data]
    "Hello World"))
```

### Bindings
In Conskit, bindings are basically a map of domain resources and services that needs to be shared across actions globally.
For example:

```clojure
{:conn {:subprotocol "mysql"
        :subname "//127.0.0.1:3306/clojure_test"
        :user "clojure_test"
       :password "clojure_test"}
 :send-email! email-fn
 :encryptor #(encr %)
 :decryptor #(decr %)
 :send-to-s3! #(aws-put ...)}
```

[//]: # (What's the difference between this and `defn`?  `action` doesn't create a var and returns an map)
### Controllers
Controllers are logical groupings of Actions. They're also responsible for providing their Actions with access to
**bindings**. Here's an example of a controller defined using the `defcontroller` macro:

```clojure
(defcontroller test-controller
  [conn send-email! send-to-s3!] ;; Domain resources/services required
  test-action            ;; Action defined earlier
  (action                ;; Inline Action
    do-one-thing
    [data]
    "Goodbye Mars"))
```

### Interceptors
You can think of interceptors as something analagous to [ring middleware](https://github.com/ring-clojure/ring/wiki/Concepts#middleware) (or [pedestal's interceptors](https://github.com/pedestal/pedestal/blob/master/guides/documentation/service-interceptors.md))
except that it is setup to be more declarative. Similar to ring middleware they allow you to effectively wrap actions
to perform any additional functionality. Interceptors are defined using the macro `definterceptor`:

```clojure
(definterceptor
  ^:logger
  log-things
  "I log everything"              ;; optional docstring
  [f config request]
  (log/info "Entering action")
  (let [result (f request)]
    (log/info "Leaving action")
    result))
```

This interceptor could then be declared on an action like this:

```clojure
(def logged-action
  (action
    ^{:logger {:level :info}}
    do-foo-things
    [request]
    "Hello World"))
```

In our `log-things` interceptor, `f` is the function for the action being wrapped, `config` is the information provided when the interceptor was declared (in this case it would be `{:level :info}`) while `request` is simply the single argument to the action.

### Registration

After all your actions, bindings, controllers and interceptors have been defined, how do they all tie together?
This is where our Trapperkeeper service comes into play. You would effectively create your own Trapperkeeper servive that
depends on the service provided by Conskit (:ActionRegistry) and then, using the methods provided by the service, you would register
your controllers, bindings and interceptors:

```clojure
(defservice my-service
  [[:ActionRegistry register-bindings! register-controllers! register-interceptors!]]
  (init [this context]
    (register-bindings! {:conn {:subprotocol "mysql"
                                :subname "//127.0.0.1:3306/clojure_test"
                                :user "clojure_test"
                               :password "clojure_test"}
                         :send-email! email-fn
                         :encryptor #(encr %)
                         :decryptor #(decr %)
                         :send-to-s3! #(aws-put ...)})
    (register-controllers! [test-controller])
    (register-interceptors! [log-things])
    context))
```

Add your service to your `bootstrap.cfg` along with Conskit's registry service:

```
conskit.core/registry
com.foobar/my-service
```

Then once Trapperkeeper boots up then Conskit will take care of the rest.


### I thought you said Web applications...

You're correct. So far I haven't shown any mention of routing, templates, webserver, database abstractions etc. The idea here is
that Conskit (Hint: Construction Kit) expects you to bring your own parts/batteries. These parts that you bring to the
table are also going to be Trapperkeeper services. For example, lets say we wanted to use [http-kit](http://http-kit.org/)
as our web server:

```clojure
;; Wont really work
(defservice
  web-server-service WebServer
  [[:ConfigService get-in-config]]
  (start [this context]
    (let [handler (wrap (get-handler))
          options (get-in-config [:server])
          stopper (run-server handler (assoc options :queue-size 40000))]
      (log/info (str "Starting Web server on port " (:port options)))
      (assoc-in context [:server :stopper] stopper)))
  (stop [this context]
    (log/info "Stopping Web server.")
    (when-let [stopper (get-in context [:server :stopper])]
      (stopper :timeout 5000))
    (assoc-in context [:server :stopper] nil))
  (add-handler [this handler]
    (..)))

;;  Then back in our service

(defservice my-service
  [[:ActionRegistry register-bindings! register-controllers! register-interceptors!]
   [:WebServer add-handler]]         ;; Depend on WebServer service
  (init [this context]
    (register-bindings! {:conn {:subprotocol "mysql"
                                :subname "//127.0.0.1:3306/clojure_test"
                                :user "clojure_test"
                               :password "clojure_test"}
                         :send-email! email-fn
                         :encryptor #(encr %)
                         :decryptor #(decr %)
                         :send-to-s3! #(aws-put ...)})
    (register-controllers! [test-controller])
    (register-interceptors! [log-things])

    (add-handler some-ring-handler) ;; Call WebServer method
    context))
```

Add the webserver to our `bootstrap.cfg` and done!

```
conskit.core/registry
com.foobar/my-service
com.foobar/web-server-service
```


But! Of course it would be nice to have some reasonable defaults for these and other kinds of services:

- [ck.routing](https://github.com/conskit/ck.routing)
- [ck.server](https://github.com/conskit/ck.server)
- [ck.migrations](https://github.com/conskit/ck.migrations)
- [ck.mailer](https://github.com/conskit/ck.mailer)
- [*Plus more...*](https://github.com/conskit)


### What about the actions?

The Conskit registry service actually provides an additional method method called `get-action` that could be used to
retrieve any action that was registered in a controller. `get-action` expects to be provided with a keyword with the
action ID and it will return an `ActionInstance` on which `conskit.protocols/invoke` can be called.

The action IDs are created by concatenating the name provided with the namespace it was declared/defined in. So in our
very first example of a simple action. Its ID would actually be `:com.foobar/do-all-the-things`:

```clojure
(get-action :com.foobar/do-all-the-things)
```

So essentially your routing service could leverage `get-action` and dispatch actions based on the ID
provided

Learn more about the Core Concepts [here](https://github.com/conskit/conskit/wiki/Core-Concepts).

## What makes conskit different?

The main selling point of this toolkit is that everything is replaceable including the core service provided. You have
the option to use third-party services which may register their own actions, controllers, bindings and interceptors, you can
be selective and use only a subset of the functionality offered by the third party or you can choose to roll your own.
Conskit does not attempt to provide you with a "framework" but to provide you with the facility to construct your own
"framework" (or application) from several reusable and replaceable parts.

### Why you might decide against Conskit?

-  Perhaps you're not a fan of all the macros or Trapperkeeper
-  The declarative setup for interceptors makes the true nature of actions less obvious/implicit
-  more...?

## Credits
Conskit and its various modules would not exist without the foundation provided by the following libraries and supporting articles.
- [trapperkeeper](https://github.com/puppetlabs/trapperkeeper)
- [omelette](https://github.com/DomKM/omelette)
- [ServerSide Rendering of Reagent Components](http://blog.gonzih.me/blog/2015/02/16/serverside-rendering-of-reagent-components/)


## Contributors
Conskit was created by [Jason Murphy](https://github.com/jsonmurphy)


## License

Copyright © 2016 Jason Murphy

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

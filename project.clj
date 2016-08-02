(defproject conskit "0.3.0-SNAPSHOT"
  :description "Toolkit for building applications"
  :url "https://github.com/conskit/conskit"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [slingshot "0.12.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [puppetlabs/trapperkeeper "1.4.1"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[puppetlabs/trapperkeeper "1.4.1" :classifier "test"]
                                  [puppetlabs/kitchensink "1.3.1" :classifier "test" :scope "test"]
                                  [midje "1.8.3"]]
                   :plugins [[lein-midje "3.2"]]}})

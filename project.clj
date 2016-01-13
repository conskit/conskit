(defproject conskit "0.1.0-SNAPSHOT"
  :description "Toolkit for building applications"
  :url "https://github.com/conskit/conskit"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [slingshot "0.12.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [puppetlabs/trapperkeeper "1.2.0"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[puppetlabs/trapperkeeper "1.2.0" :classifier "test"]
                                  [midje "1.8.3"]]
                   :plugins [[lein-midje "3.1.3"]]}})

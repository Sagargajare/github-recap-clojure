(defproject github-recap "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ring/ring-core "1.10.0"]
                 [ring/ring-jetty-adapter "1.10.0"]
                 [clj-http "3.13.0"]
                 [compojure "1.6.2"]
                 [cheshire "5.13.0"]
                 [clj-time "0.15.2"]
                 [environ "1.2.0"]
                 [selmer "1.12.44"]]
  :plugins [[environ/environ.lein "0.3.1"] [dev.weavejester/lein-cljfmt "0.13.0"]]
  :hooks [environ.leiningen.hooks]
  :main ^:skip-aot github-recap.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})

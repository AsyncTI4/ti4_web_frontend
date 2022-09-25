(ns ^:figwheel-no-load ti4-web-frontend.dev
  (:require
    [ti4-web-frontend.core :as core]
    [devtools.core :as devtools]))

(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))

(devtools/install!)

(enable-console-print!)

(core/init!)

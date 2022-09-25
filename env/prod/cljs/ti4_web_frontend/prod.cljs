(ns ti4-web-frontend.prod
  (:require [ti4-web-frontend.core :as core]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(core/init!)

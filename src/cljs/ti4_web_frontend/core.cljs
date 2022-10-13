(ns ti4-web-frontend.core
  (:require
   [reagent.core :as reagent :refer [atom]]
   [reagent.dom :as rdom]
   [reagent.session :as session]
   [reitit.frontend :as reitit]
   [clerk.core :as clerk]
   [accountant.core :as accountant]
   [ajax.core :refer [GET]]))

;; -------------------------
;; Routes

(def router
  (reitit/router
   [["/" :index]
    ["/game"
     ["/:game-id" :game]]
    ["/about" :about]]))

(defn path-for [route & [params]]
  (if params
    (:path (reitit/match-by-name router route params))
    (:path (reitit/match-by-name router route))))

(def state (atom {}))

(defn refresh-games [resp]
  (swap! state assoc :games resp)
  )


(defn first-number [x]
  (js/parseInt (second (re-find #"[A-Za-z]*([0-9]+)" (:MapName x)))))


;; -------------------------
;; Page components

(defn home-page []
  (GET "/maps.json" {:handler refresh-games
                     :response-format :json
                     :keywords? true})
  (fn []

    [:div.main
     [:h1 "Welcome to Ti4 Async Web"]
     [:div.listmain (map (fn [x] [:div.listitem [:a {:key  (:MapName x)
                                   :href (path-for :game {:game-id (:MapName x)})
                                   } (:MapName x)]]) (sort-by first-number (:games @state)))]
     ]))




(defn game-page []
  (GET "/maps.json" {:handler refresh-games
                     :response-format :json
                     :keywords? true})
  (fn []

    (let [routing-data (session/get :route)
          item-name (get-in routing-data [:route-params :game-id])
          item (first (filter #(= item-name (:MapName %)) (:games @state)))]
      (set! (.-title js/document) (:MapName item))
      [:span.main
       [:h1 (str "Game " item-name "")]
       [:img {:src (:MapURL item)}]
       [:p [:a {:href (path-for :index)} "Back to the list of games"]]])))


(defn about-page []
  (fn [] [:span.main
          [:h1 "About ti4_web_frontend.  For the TI4 Async Community"]]))


;; -------------------------
;; Translate routes -> page components

(defn page-for [route]
  (case route
    :index #'home-page
    :about #'about-page
    :game #'game-page))


;; -------------------------
;; Page mounting component

(defn current-page []
  (fn []
    (let [page (:current-page (session/get :route))]
      [:div
       [page]])))

;; -------------------------
;; Initialize app

(defn mount-root []
  (rdom/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (clerk/initialize!)
  (accountant/configure-navigation!
   {:nav-handler
    (fn [path]
      (let [match (reitit/match-by-path router path)
            current-page (:name (:data  match))
            route-params (:path-params match)]
        (reagent/after-render clerk/after-render!)
        (session/put! :route {:current-page (page-for current-page)
                              :route-params route-params})
        (clerk/navigate-page! path)
        ))
    :path-exists?
    (fn [path]
      (boolean (reitit/match-by-path router path)))})
  (accountant/dispatch-current!)
  (mount-root))

(defn ^:dev/after-load reload! []
  (mount-root))

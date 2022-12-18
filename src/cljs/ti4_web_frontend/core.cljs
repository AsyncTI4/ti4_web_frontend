(ns ti4-web-frontend.core
  (:require
   [reagent.core :as reagent :refer [atom]]
   [reagent.dom :as rdom]
   [reagent.session :as session]
   [reitit.frontend :as reitit]
   [clerk.core :as clerk]
   [accountant.core :as accountant]
   [ajax.core :refer [GET]]
   [cljs.core.async :as a :refer [go, go-loop, <!]]
   [haslett.client :as ws]
   [haslett.format :as fmt]))

;; -------------------------
;; Routes
(enable-console-print!)

(def router
  (reitit/router
   [["/" :index]
    ["/gamenew"
     ["/:game-id" :gamenew]]
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
    (set! (.-title js/document) "Play by Discord Web Hosting") 

    
    [:div.main
     [:h1 "Welcome to Ti4 Async Web"]
     [:div [:h3 (str "Total Active Games: " (count (:games @state)))]]
     [:div.listmain (map (fn [x] [:div.listitem [:a {:key  (:MapName x)
                                                     :href (path-for :game {:game-id (:MapName x)})
                                                     }(:MapName x)]]) (sort-by first-number (:games @state)))]

     ]))


(defn game-page-new []
  (GET "/maps.json" {:handler refresh-games
                     :response-format :json
                     :keywords? true})
  (reagent/create-class
    {
     :display-name "game-new"

     :component-did-mount
     (fn []
       ;       (new Zoomist. "#zoomist")
       )
     :reagent-render
     (fn []
       (let [routing-data (session/get :route)
             item-name (get-in routing-data [:route-params :game-id])
             item (first (filter #(= item-name (:MapName %)) (:games @state)))]
         [:div.main
          [:div#zoomist {:data-zoomist-src (:MapURL item)}]
          ]
         )
       )})
  )

(defn game-page [] 
  

  (GET "/maps.json" {:handler refresh-games
                     :response-format :json
                     :keywords? true}) 
  (let [data (atom nil)
        imgurl (atom "http://ti4.westaddisonheavyindustries.com/map/pbd254/2022-12-18T04%3A23%3A45.244735447.png")]


    (reagent/create-class {:display-name "my-comp"

                           :component-did-mount
                           (fn []
                             (let [ti4ws (ws/connect (str "wss://4z4c1wj2e2.execute-api.us-east-1.amazonaws.com/dev?map=" (get-in (session/get :route) [:route-params :game-id])))]
                               (go (let [stream (<! ti4ws)]
                                     (go-loop [] (let [newmap (<! (:source stream))]
                                                   (when newmap 
                                                     (println newmap)
                                                     (reset! imgurl newmap))
                                                   (recur))))))
                             (GET (str "https://bbg9uiqewd.execute-api.us-east-1.amazonaws.com/Prod/map/" (get-in (session/get :route) [:route-params :game-id]))
                               {:handler         (fn [x] (reset! data x))
                                :response-format :json
                                :keywords?       true})
                             (let [routing-data (session/get :route)
                                   item-name (get-in routing-data [:route-params :game-id])
                                   item (first (filter #(= item-name (:MapName %)) (:games @state)))]
                               (reset! imgurl (:MapURL item))
                               (set! (.-title js/document) (if @data (str (:MapName item) " - Round " (:round @data)) (:MapName item)))))

                           ;:component-did-update (fn [this]
                           ;  (let [new-notes (rest (reagent/argv this))]
                           ;    (apply draw-system-with-chord new-notes)))
                           
                           :reagent-render
                           (fn []
                             (let [routing-data (session/get :route)
                                   item-name (get-in routing-data [:route-params :game-id])]
                               [:span.main
                                [:h1 (str "Game " item-name "")]
                                [:img {:src @imgurl}]
                                [:p [:a {:href (path-for :index)} "Back to the list of games"]]]))})))


(defn about-page []
  (fn [] [:span.main
          [:h1 "About ti4_web_frontend.  For the TI4 Async Community"]]))


;; -------------------------
;; Translate routes -> page components

(defn page-for [route]
  (case route
    :index #'home-page
    :gamenew #'game-page-new
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

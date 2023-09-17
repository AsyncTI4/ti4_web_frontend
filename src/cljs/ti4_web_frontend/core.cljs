(ns ti4-web-frontend.core
  (:require [accountant.core :as accountant]
            [ajax.core :refer [GET POST]]
            [clerk.core :as clerk]
            [cljs.core.async :as a :refer [<! >! alts! go-loop timeout go put!]]
            [haslett.client :as ws]
            [haslett.format :as fmt]
            [reagent.core :as reagent :refer [atom]]
            [reagent.dom :as rdom]
            [reagent.session :as session]
            [reitit.frontend :as reitit]
            [cemerick.url :refer (url url-encode)]
            [alandipert.storage-atom :refer [local-storage]]))

(defonce data (atom nil))
(defonce connected? (atom false))
(defonce imgurl (atom {}))
(defonce imgupdates (atom []))
(defonce games (local-storage (atom nil) :games-watching))
(defonce user (local-storage (atom {}) :user-info))

(def cycle-fn (fn [stream]
                (go-loop [stream stream]
                  (let [[val ch] (alts! [(timeout 300000) (:source stream)])]
                    (if (= ch (:source stream))
                      (case (get val "command")
                        "pong" nil
                        "map" (do (swap! imgurl assoc (get val "map") (get val "mapurl"))
                                  (swap! imgupdates conj (get val "map")))
                        (println val))
                      (>! (:sink stream) {:command "ping"}))
                    (if (and (= ch (:source stream)) (nil? val))
                      (reset! connected? false)
                      (recur stream))
                    (when-not (= ch (:source stream))
                      (recur stream))))))

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
    ["/froggame"
     ["/:player-id/:game-id" :froggame]]
    ["/login" :login]
    ["/cc" :command-center]
    ["/about" :about]]))

(defn path-for [route & [params]]
  (if params
    (:path (reitit/match-by-name router route params))
    (:path (reitit/match-by-name router route))))

(def state (atom {}))
(def ws-stream (atom nil))

(defn refresh-games [resp]
  (swap! state assoc :games resp))

(defn first-number [x]
  (let [n (js/parseInt (second (re-find #"[A-Za-z]*([0-9]+)" (:MapName x))))]
    (if (NaN? n)
      0
      n)))

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
     [:div.listmain (map (fn [x] [:div.listitem {:key (:MapName x)} [:a  {:href (path-for :game {:game-id (:MapName x)})}
                                                                     (:MapName x)]]) (sort-by first-number (:games @state)))]]))

(defn game-page-new []
  (GET "/maps.json" {:handler refresh-games
                     :response-format :json
                     :keywords? true})
  (reagent/create-class
   {:display-name "game-new"

    :component-did-mount
    (fn [])
       ;       (new Zoomist. "#zoomist")

    :reagent-render
    (fn []
      (let [routing-data (session/get :route)
            item-name (get-in routing-data [:route-params :game-id])
            item (first (filter #(= item-name (:MapName %)) (:games @state)))]
        [:div.main
         [:div#zoomist {:data-zoomist-src (:MapURL item)}]]))}))

(defn connect-ws [game-name on-connect]
  (go (let [stream (<! (ws/connect (str "wss://4z4c1wj2e2.execute-api.us-east-1.amazonaws.com/dev?map=" game-name) {:format fmt/json}))]
        (reset! ws-stream stream)
        (on-connect stream))))

;; User Setup
(when (not (:userid @user))
  (swap! user assoc :userid (str (random-uuid))))

(when (not (:userstate @user))
  (swap! user assoc :userstate :unauth))

(defn login-return []
  (let [query-data (:query (url (-> js/window .-location .-href)))
        code (get-in query-data ["code"])
        ;oauth-state (get-in query-data [:state])
        ]
    (fn []
      (prn query-data)
      (swap! user assoc :userstate :awaittoken)
      (swap! user assoc :code code)
      ;; Probably check state?  I unno
      ;; Do Post here to backend to get an auth token.
      (POST "https://bbg9uiqewd.execute-api.us-east-1.amazonaws.com/Prod/login"
        {:params {:code code
                  :user_id (:userid @user)}
         :handler (fn [e] (reset! user {:userid (:user_id e)
                                        :name (:discord_name e)
                                        :discord_id (:discord_id e)
                                        :bearer_token (:bearer_token e)
                                        :userstate :loggedin}))
         :response-format :json
         :format :json
         :keywords? true})
      (accountant/navigate! "/")
      (accountant/dispatch-current!))))

(defn login-widget []
  (let [beurl (assoc (url "https://discord.com/oauth2/authorize")
                     :query {:response_type "code"
                             :client_id "1084164538053689464"
                             :scope "identify guilds guilds.members.read"
                             :redirect_uri "https://ti4.westaddisonheavyindustries.com/login"
                             :prompt "none"})
        show-frogs? (atom false)
        frog-list (atom [])]
    (fn []
      [:div.loginbox
       (case (:userstate @user)
         nil [:spam.error "A User Error Has Occured."]
         :unauth [:button
                  {:on-click (fn [_] (.assign (.-location js/document) beurl))}
                  "Login to Discord"]
         :awaittoken [:span "Waiting for token from backend...."]
         :loggedin [:div.userbox {
                                  ;:on-mouse-leave (fn [_] (reset! frog-list []))
                                  :on-mouse-enter (fn [_]
                                                    (GET (str "https://bbg9uiqewd.execute-api.us-east-1.amazonaws.com/Prod/frogs/" (get @user :discord_id))
                                                      {:handler (fn [data]
                                                                  (prn data)
                                                                  (reset! frog-list data))
                                                       :response-format :json
                                                       :keywords? true}))}
                    [:span (:name @user)]])
       [:div.froglist
        (map (fn [frog] [:div.frog-game-tile [:a {:href (path-for :froggame {:player-id (:discord_id @user) :game-id (:map_name frog)})} (:map_name frog)]]) @frog-list)]
       [:div.logout [:button {:on-click (fn [_] (swap! user assoc :userstate :unauth))} "Logout"]]])))

(defn frog-game-page []
  (let [discord-id (get-in (session/get :route) [:route-params :player-id])
        game-name (get-in (session/get :route) [:route-params :game-id])]
    (GET (str "https://bbg9uiqewd.execute-api.us-east-1.amazonaws.com/Prod/frog/" discord-id "/" game-name)
      {:handler (fn [x] (swap! imgurl assoc game-name x))
       :response-format :text})
    (fn []
      [:div.froggame
       (if (= discord-id (str (get @user :discord_id)))
         [:img {:src (get @imgurl game-name)}]
         [:span (str "This is not your frog.   If you think this is your frog please sign in. User:" discord-id " Frog: " (str (get @user :discord_id)))])])))

(defn game-page []
  (GET "/maps.json" {:handler refresh-games
                     :response-format :json
                     :keywords? true})
  (let [game-header-tile (fn [params]
                           (let [e (:map params)]
                             [:div
                              {:class (reagent/class-names "gametile" (when (some #(= e %) @imgupdates) "newgame"))}
                              [:a.gametilelink {:href (path-for :game {:game-id e})} e]
                              [:div.closebutton {:on-click
                                                 (fn [_] (js/console.log "Click!") (reset! games (remove #(= % e) @games)))} "x"]]))
        ws-fun (fn []
                 (connect-ws (get-in (session/get :route) [:route-params :game-id])
                             (fn [stream] (put! (:sink stream) {:command "map" :map (get-in (session/get :route) [:route-params :game-id])})
                               (doall (map (fn [mmmm] (put! (:sink stream) {:command "mapsub" :map mmmm})) @games))
                               (reset! connected? true)
                               (cycle-fn stream))))]

    (reagent/create-class
     {:display-name "single-game-render"

      :component-did-mount
      (fn []
        (ws-fun)
        (let [routing-data (session/get :route)
              item-name (get-in routing-data [:route-params :game-id])
              item (first (filter #(= item-name (:MapName %)) (:games @state)))]
          (swap! imgurl assoc item-name (:MapURL item))
          (GET (str "https://bbg9uiqewd.execute-api.us-east-1.amazonaws.com/Prod/map/" (get-in (session/get :route) [:route-params :game-id]))
            {:handler (fn [x] (reset! data x)
                        (set! (.-title js/document) (if @data (str item-name " - Round " (:round @data)) (:MapName item))))
             :response-format :json
             :keywords? true})))

      :component-will-unmount
      (fn []
        (when @ws-stream (ws/close @ws-stream)))

      :reagent-render
      (fn []
        (let [routing-data (session/get :route)
              item-name (get-in routing-data [:route-params :game-id])]

          [:div.main
           [:div.gamebar
            (map (fn [e]
                   [game-header-tile ^{:key e} {:map e}]) @games)]
           [:h1 (str "Game " item-name "")]
           [:input {:type "button" :value "Follow Game" :on-click #(swap! games conj item-name)}]
           [:input {:type "button" :value "Load Map" :on-click #(put! (:sink @ws-stream) {:command "map" :map item-name})}]
           [:input {:type "button" :value (if @connected? "Connected" "Disconnected") :disabled @connected? :on-click ws-fun}]
           [:img {:src (get @imgurl item-name)}]
           [:p [:a {:href (path-for :index)} "Back to the list of games"]]]))})))


(defonce curgame (atom ""))
(defonce ws-cc-stream (atom nil))
(defonce cc-data (atom {}))

(defn command-center-outlier-game []
  (let [game-data (atom {})]
    (fn [params]
      (let [game (:map params)
            update-data (fn []
                          (GET (str "https://bbg9uiqewd.execute-api.us-east-1.amazonaws.com/Prod/map/" game)
                            {:handler (fn [x]
                                        (reset! game-data x)
                                        (set! (.-title js/document) (if x (str game " - Round " (:round x)) "TI4-web")))
                             :response-format :json
                             :keywords? true}))
            calc-time (fn []
                        (let [now (.now js/Date)
                              bef (get @game-data "lastModifiedDate")
                              elapsed (/ (- now bef) 1000)]
                          (cond
                            (> elapsed 86400) (str (/ elapsed 86400) " days")
                            (> elapsed 3600) (str (/ elapsed 3600) " hours")
                            :else "Less than an hour")))

            on-click (fn []
                       (update-data)
                       (reset! curgame game)
                       (when (some? @ws-cc-stream) (put! (:sink @ws-cc-stream) {:command "map" :map game})))]
        [:div.cc-game {:on-click on-click} [:div.cc-label game]
         [:div.cc-time (calc-time)]]))))


(defn command-center-outlier []
  (let [show-full (atom false)]
    (reagent/create-class
     {:display-name "outlier"
      :reagent-render
      (fn []
        (let [toggle-on (fn [] (reset! show-full true))
              toggle-off (fn [] (reset! show-full false))]
          [:div.outlier {:on-mouse-enter toggle-on :on-mouse-leave toggle-off}
           [:div.gamelist
            (when @show-full
              (map (fn [e] [command-center-outlier-game ^{:key e} {:map e}]) @games))]
           [:span {:style {"font-color" "#FFFFFF"}} "Command Center"]]))})))

(defn command-center []
  (let [ws-fun (fn []
                 (connect-ws @curgame
                             (fn [stream]
                               (doall (map (fn [mmmm] (put! (:sink stream) {:command "mapsub" :map mmmm})) @games))
                               (reset! connected? true)
                               (reset! ws-cc-stream stream)
                               (cycle-fn stream))))]
    (reagent/create-class
     {:display-name "command-center"
      :reagent-render
      (fn []
        (ws-fun)
        (let []
          [:div.main
           "Command Center --- Coming Soon"
           [command-center-outlier] [:img {:src (get @imgurl @curgame)}]]))})))

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
    :game #'game-page
    :froggame #'frog-game-page
    :command-center #'command-center
    :login #'login-return))

;; -------------------------
;; Page mounting component

(defn current-page []
  (fn []
    (let [page (:current-page (session/get :route))]
      [:div
       [login-widget]
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
        (clerk/navigate-page! path)))

    :path-exists?
    (fn [path]
      (boolean (reitit/match-by-path router path)))})
  (accountant/dispatch-current!)
  (mount-root))

(defn ^:dev/after-load reload! []
  (mount-root))

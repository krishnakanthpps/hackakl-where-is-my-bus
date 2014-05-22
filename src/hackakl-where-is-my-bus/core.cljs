(ns hackakl_where_is_my_bus.core
  (:require [reagent.core :as reagent :refer [atom]]
            [datascript :as d]
            [goog.net.Jsonp :as jsonp]
           ))

(def api_key "cc2c65c9-5213-404e-8dda-45d17c2dd817")
(def at_server "https://api.at.govt.nz/v1/")

;;; not my code --stu
(enable-console-print! )

(defn bind
  ([conn q]
   (bind conn q (atom nil)))
  ([conn q state]
   (let [k (rand)]
     (reset! state (d/q q @conn))
     (d/listen! conn k (fn [tx-report]
                         (let [novelty (d/q q (:tx-data tx-report))]
                           (when (not-empty novelty) ;; Only update if query results actually changed
                             (reset! state (d/q q (:db-after tx-report)))))))
     (set! (.-__key state) k)
     state)))

(defn unbind
  [conn state]
  (d/unlisten! conn (.-__key state)))

;;; Creates a DataScript "connection" (really an atom with the current DB value)
(def conn (d/create-conn))

;;; Maintain DB history.
(def history (atom []))

(d/listen! conn :history (fn [tx-report] (swap! history conj tx-report)))

(defn undo
  []
  (when (not-empty @history)
    (let [prev (peek @history)
          before (:db-before prev)
          after (:db-after prev)
          ;; Invert transition, adds->retracts, retracts->adds
          tx-data (map (fn [{:keys [e a v t added]}] (d/Datom. e a v t (not added))) (:tx-data prev))]
      (reset! conn before)
      (swap! history pop)
      (doseq [[k l] @(:listeners (meta conn))]
        (when (not= k :history) ;; Don't notify history of undos
          (l (d/TxReport. after before tx-data)))))))

;;; back to my code --stu

;;; Some non-DB state
(def state (atom {:routes nil :route nil}))

;;; updates the choosen route
(defn route-change [route]
  (swap! state assoc-in [:route] route)
  )

;;; defines a reagent component that generates a select box of routes
(defn routes-view
  []
  [:div
   [:h2 "Routes " (count(:routes @state))]
   [:select {:on-change #(route-change (.. % -target -value)) }
    (map(fn [r] [:option {:value r} [:span r]]) (:routes @state))]])

;;; Query to find trip_ids corresponding to a route
(defn q-trip_ids [route_id]
  (d/q '[:find ?t
    ;; the :in cause is how you supply a parameter into the query
    :in $ ?route_id
    :where
     [?e :trip_id ?t]
     [?e :route_id ?r]
     [(= ?r ?route_id)]
     ] @conn route_id)
)

;;; Query to get positions corresponding to a route
(defn q-positons [route_id]
    (d/q '[:find ?p ?v
      :in $ ?route_id
      :where
       [?e :position ?p]
       [?e :route_id ?r]
       [?e :vehicle_id ?v]
       [(= ?r ?route_id)]
       ] @conn route_id)
    )

;;; Query to get route info from the id
(defn q-route-info [route_id]
    (d/q '[:find ?short-name ?long-name
      :in $ ?route_id
      :where
       [?e :route_id ?r]
       [?e :route_short_name ?short-name]
       [?e :route_long_name ?long-name]
       [(= ?r ?route_id)]
       ] @conn route_id)
    )

;;; sets the map marker
(defn set-map-marker
  [[lat-long vehicle_id]]
  (let [lat (:latitude lat-long)
        lon (:longitude lat-long)]
    (js/set_marker lat lon vehicle_id)
    ))

;;; once a route is chosen shows the vechicles on the route and their lat-longs
(defn lat-long-view
  []
  (let [route (:route @state)
        ;; I am not sure how to bind a query with a parameter
        trip_ids (q-trip_ids route)
        lat-longs (q-positons route)
        [lat-long _] (first (seq lat-longs))
        [[short-name long-name]] (seq (q-route-info route))]
      (if route
                 [:div
                 [:h3 "Choosen Route " route]
                 [:h3 "Short Name: " short-name]
                 [:h3 "Long Name: " long-name]
                 [:h4 "Trip IDs " (pr-str trip_ids)]
                 [:h4 "lat longs: " (pr-str lat-longs)
                  (js/delete_markers)
                  (dorun (map set-map-marker lat-longs))
                  ]
                  [:ul
                   (map (fn [[p v_id]] [:li
                                        [:span "vehicle id: " v_id " position " (pr-str p)]]) lat-longs)]]
                 [:div])
     ))

;;; Uber component, contains/controls routes-view and lat-long-view.
(defn uber
  []
  [:div
   [:div [routes-view]]
   [:div [lat-long-view]]
   ])

;;; Initial render
(reagent/render-component [uber] (. js/document (getElementById "app")))

;;; below gets info from the real-time feed and puts it in the database
;;; only called once atm

;;; uses Jsonp to get data from the real-time feed
(defn retrieve-realtime-data
  [callback error-callback]
  (.send (goog.net.Jsonp. (str at_server "public/realtime/vehiclelocations") "callback")
    (doto (js-obj) (aset "api_key" api_key) )
    callback error-callback)
  [callback error-callback trip_ids]
  ;; Jsonp is how you work around cross-domain scripting errors
  (.send (goog.net.Jsonp. (str at_server "public/realtime/vehiclelocations") "callback")
    (doto (js-obj)
      (aset "api_key" api_key)
      (aset "trip_ids" trip_ids) )
    callback error-callback)
  )

;;; adds the information for each vehichle into the client-side db
(defn add-realtime [vehicle]
  (let [trip (:trip vehicle)
        trip_id (:trip_id trip)
        route_id (:route_id trip)
        position (:position vehicle)
        vehicle_id (:id (:vehicle vehicle))]
    (d/transact! conn [{:db/id -1 :route_id route_id
                        :trip_id trip_id :position position
                        :vehicle_id vehicle_id}])
    ))

;;; unpacks the response from the at realtime api
(defn set-realtime-info [json-obj]
  (let [data (js->clj json-obj :keywordize-keys true)
        items (:entity (:response data))
        vehicles (map :vehicle items)
        routes (set (map #(:route_id (:trip %)) vehicles))]
    (swap! state assoc-in [:routes] routes)
    ;; dorun is needed because map is lazy
    (dorun (map add-realtime vehicles))
    ))

(retrieve-realtime-data set-realtime-info #(js/alert (str "error getting realtime data" %)))

;;; uses Jsonp to get data from the route feed
(defn retrieve-route-data
  [callback error-callback]
  (.send (goog.net.Jsonp. (str at_server "gtfs/routes") "callback")
    (doto (js-obj) (aset "api_key" api_key) )
    callback error-callback)
  )

;;; adds the information for each vehichle into the client-side db
(defn add-route-info [route]
  (let [route_id (:route_id route)
        route_short_name (:route_short_name route)
        route_long_name (:route_long_name route)]
    (d/transact! conn [{:db/id -1 :route_id route_id
                        :route_short_name route_short_name
                        :route_long_name route_long_name}])
    ))

;;; unpacks the response from the at route api
(defn set-route-info [json-obj]
  (let [data (js->clj json-obj :keywordize-keys true)
        routes (:response data)]
    ;; dorun is needed because map is lazy
    (dorun (map add-route-info routes))
    ))

(retrieve-route-data set-route-info #(js/alert (str "error getting route data" %)))

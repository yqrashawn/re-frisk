(ns re-frisk.trace
  (:require [re-frisk.diff.diff :as diff]
            [re-frisk.utils :as utils]
            [re-frame.trace]
            [re-frame.interop :as interop]
            [re-frame.db :as db]))

(defn normalize-traces [traces]
  (reduce (fn [items {:keys [op-type tags duration id] :as trace}]
            (let [op-type (if (= (namespace op-type) "sub") :sub op-type)
                  item    {:indx id :trace? true}]
              (case op-type
                ;:re-frame.router/fsm-trigger
                #_(conj items (merge item
                                     (select-keys trace [:id :op-type :operation :start :end])))
                :event
                (conj items (merge (dissoc item :trace?)
                                   (assoc (select-keys trace [:id :op-type :operation :duration
                                                              :start :end])
                                     :event (:event tags)
                                     :truncated-name (utils/truncate-name (str (first (:event tags))))
                                     :app-db-diff (diff/diff (:app-db-before tags) (:app-db-after tags)))))
                :event/handler
                (let [prev (peek items)]
                  (if (= (:op-type prev :event))
                    (conj (pop items) (assoc prev :handler-duration duration))
                    (conj items (merge item
                                       (select-keys trace [:id :op-type :operation :duration])))))
                :event/do-fx
                (let [prev (peek items)]
                  (if (= (:op-type prev :event))
                    (conj (pop items) (assoc prev :fx-duration duration))
                    (conj items (merge item
                                       (select-keys trace [:id :op-type :duration])))))
                (:sub :render)
                (let [prev  (peek items)
                      trace (select-keys trace [:id :op-type :operation :duration :start :end])
                      trace (assoc trace :duration-ms (utils/str-ms (:duration trace))
                                         :reaction (:reaction tags)
                                         :cached? (:cached? tags)
                                         :input-signals (:input-signals tags))]
                  (if (:subs? prev)
                    (conj (pop items) (update prev :subs conj trace))
                    (conj items (merge item
                                       {:op-type         :subs :subs? true :subs [trace]
                                        :app-db-reaction (interop/reagent-id db/app-db)
                                        :start           (:start trace)}))))
                items)))
          []
          (sort-by :id traces)))

(defn normalize-durations [first-event]
  (fn [{:keys [subs? subs op-type handler-duration fx-duration]
        :as   trace}]
    (let [{:keys [duration handler-duration fx-duration start created-duration-cached
                  run-duration created-duration disposed-duration render-duration]
           :as   trace}
          (cond
            subs?
            (merge trace
                   (reduce (fn [acc {:keys [duration op-type end cached?]}]
                             (cond-> (update acc :duration + duration)
                                     :always
                                     (assoc :end end)
                                     (= op-type :sub/run)
                                     (-> (update :run-count inc)
                                         (update :run-duration + duration))
                                     (and (= op-type :sub/create) (not cached?))
                                     (-> (update :created-count inc)
                                         (update :created-duration + duration))
                                     (and (= op-type :sub/create) cached?)
                                     (-> (update :created-count-cached inc)
                                         (update :created-duration-cached + duration))
                                     (= op-type :sub/dispose)
                                     (-> (update :disposed-count inc)
                                         (update :disposed-duration + duration))
                                     (= op-type :render)
                                     (-> (update :render-count inc)
                                         (update :render-duration + duration))))
                           {:duration                0
                            :run-count               0
                            :run-duration            0
                            :render-count            0
                            :render-duration         0
                            :created-count           0
                            :created-duration        0
                            :disposed-count          0
                            :disposed-duration       0
                            :created-count-cached    0
                            :created-duration-cached 0}
                           subs))
            (= op-type :event)
            (let [handler-fx-duration (+ handler-duration fx-duration)]
              (assoc trace :handler-fx-duration handler-fx-duration
                           :handler-fx-duration-ms (utils/str-ms handler-fx-duration)))
            :else
            trace)]
      (cond-> trace
              duration
              (assoc :duration-ms (utils/str-ms duration))
              handler-duration
              (assoc :handler-duration-ms (utils/str-ms handler-duration))
              fx-duration
              (assoc :fx-duration-ms (utils/str-ms fx-duration))
              run-duration
              (assoc :run-duration-ms (utils/str-ms run-duration))
              created-duration
              (assoc :created-duration-ms (utils/str-ms created-duration))
              created-duration-cached
              (assoc :created-duration-cached-ms (utils/str-ms created-duration-cached))
              disposed-duration
              (assoc :disposed-duration-ms (utils/str-ms disposed-duration))
              render-duration
              (assoc :render-duration-ms (utils/str-ms render-duration))
              :always
              (assoc :position (- start (:start first-event)))))))
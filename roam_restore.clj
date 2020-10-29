(ns roam-restore
 (:require [datascript.transit :as dt]
           [datascript.core :as d]
           [cheshire.core :as json]
           [clojure.string :as string]))

(def harfile "roamresearch.com.har")
(def missing-date "10-28-2020")
(def outfile "recovered.txt")

(defn block-from-uid
  [conn uid]
  (d/q `[:find ?e ?n :where [?e :block/uid ~uid] [?e :block/string ?n]] @conn))

(defn parse-har
  []
  (let [json (json/parse-string (slurp harfile) true)
        ws-messages (->> json :log :entries (filter #(some? (:_webSocketMessages %))) second :_webSocketMessages)
        ws-data (map :data ws-messages)
        combined-data (-> (str "[" (string/join "" ws-data) "]")
                          (string/replace #"\}\{" "},{")
                          ;; yolo
                          (string/replace #"\}(\d+)?\{" "},$1,{")
                          (json/parse-string true))]
    combined-data))

(defn find-deleted-blocks
  [parsed-har]
  (let [nested-obj-is-delete? (fn [obj] (and (seqable? obj) (some (fn [[k v]] (= "delete-page" (some-> v :tx-meta :tx-name))) obj)))
        delete-page-event (->> parsed-har
                               (filter #(some-> % :d :b :d nested-obj-is-delete?))
                               first :d :b :d
                               (keep (fn [[k v]] (when (and (= "delete-page" (some-> v :tx-meta :tx-name))
                                                            (string/includes? (some-> v :tx) missing-date))
                                                   v)))
                               first)
        tx-parsed (dt/read-transit-str (:tx delete-page-event))]
    {:blocks
     (->> tx-parsed
          (filter #(= :db.fn/retractEntity (first %)))
          (map (comp second second)))
     :time (:time delete-page-event)}))

(defn apply-transactions-until
  [db parsed-har until-time]
  (let [transactions-to-apply (->> parsed-har
                                   (map #(some-> % :d :b :d))
                                   (filter seqable?)
                                   (apply concat)
                                   (filter #(string/starts-with? (-> % first name) "-MK"))
                                   (map second)
                                   (filter #(< (:time %) until-time))
                                   (sort-by :time)
                                   (map :tx)
                                   (map dt/read-transit-str))
        conn (d/conn-from-db db)]
    (doseq [tx transactions-to-apply]
      (d/transact! conn tx))
    (d/db conn)))

(defn parse-db
  [parsed-har]
  (let [db-str (->> parsed-har
                    (filter #(some-> % :d :b :d :split-db))
                    first :d :b :d :split-db
                    vals
                    (string/join ""))]
    (dt/read-transit-str db-str)))

(defn recover
  [db deleted-blocks]
  (let [conn (d/conn-from-db db)]
    (->> deleted-blocks
         (mapcat (partial block-from-uid conn))
         (map second)
         (string/join "\n"))))

(defn run
  []
  (let [parsed-har (parse-har)
        db (parse-db parsed-har)
        {:keys [blocks time]} (find-deleted-blocks parsed-har)
        db (apply-transactions-until db parsed-har time)
        recovered (recover db blocks)]
    (spit outfile recovered)))

(run)

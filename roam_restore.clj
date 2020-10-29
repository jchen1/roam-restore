(ns roam-restore
 (:require [datascript.transit :as dt]
           [datascript.core :as d]
           [cheshire.core :as json]
           [clojure.string :as string]))

(def missing-blocks (read-string (slurp "missing-blocks.edn")))
(defn block-from-uid
  [conn uid]
  (d/q `[:find ?e ?n :where [?e :block/uid ~uid] [?e :block/string ?n]] @conn))

(defn recover
  []
  (let [json (json/parse-string (slurp "roamresearch.com.har") true)
        ws-messages (->> json :log :entries (filter #(some? (:_webSocketMessages %))) second :_webSocketMessages)
        ws-data (map :data ws-messages)
        combined-data (-> (str "[" (string/join "" ws-data) "]")
                          (string/replace #"\}\{" "},{")
                          ;; yolo
                          (string/replace #"\}(\d+)?\{" "},$1,{")
                          (json/parse-string true))
        db-str (->> combined-data
                    (filter #(some-> % :d :b :d :split-db))
                    first :d :b :d :split-db
                    vals
                    (string/join ""))
        db (dt/read-transit-str db-str)
        conn (d/conn-from-db db)]
    (->> missing-blocks
         (take 10)
         (mapcat (partial block-from-uid conn))
         (map second)
         (string/join "\n"))))

(comment
  (recover))

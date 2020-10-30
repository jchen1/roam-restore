(ns roam-restore
  (:require [datascript.transit :as dt]
            [datascript.core :as d]
            [cheshire.core :as json]
            [clojure.string :as string]))

(def harfile "roamresearch.com.har")
(def missing-date "10-28-2020")
(def outfile "recovered.txt")

(defn parse-har
  []
  (let [json (json/parse-string (slurp harfile) true)
        ws-messages (->> json :log :entries (filter #(some? (:_webSocketMessages %))) second :_webSocketMessages)
        ws-data (map :data ws-messages)
        try-parse #(try (json/parse-string % true)
                        (catch Throwable _ nil))
        ;; Roam sends a series of JSON objects over WS messages.
        ;; If an object is bigger than 16kb it's split across
        ;; multiple messages - so we need to stitch them together.
        ws-json (reduce (fn [{:keys [done partial]} next]
                          (let [potential-json-str (str partial next)]
                            (if-let [json (try-parse potential-json-str)]
                              {:done (conj done json)
                               :partial ""}
                              {:done done
                               :partial potential-json-str})))
                        {:done [] :partial ""}
                        ws-data)]
    (assert (= (:partial ws-json) ""))
    (:done ws-json)))

(defn find-deletion-time
  [parsed-har]
  (let [nested-obj-is-delete? (fn [obj] (and (seqable? obj) (some (fn [[k v]] (= "delete-page" (some-> v :tx-meta :tx-name))) obj)))
        delete-page-event (->> parsed-har
                               (filter #(some-> % :d :b :d nested-obj-is-delete?))
                               first :d :b :d
                               (keep (fn [[k v]] (when (and (= "delete-page" (some-> v :tx-meta :tx-name))
                                                            (string/includes? (some-> v :tx) missing-date))
                                                   v)))
                               first)]
    (:time delete-page-event)))

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

(defn materialize
  [{:keys [block/string] :as page} level]
  (let [indent (* level 4)
        children (->> (:block/children page)
                      (sort-by :block/order)
                      (map #(materialize % (inc level)))
                      (map #(format "%s- %s" (apply str (repeat indent " ")) %))
                      (string/join "\n"))]
    (cond
      (and string (not-empty children)) (str string "\n" children)
      (not-empty children) children
      string string
      :else "")))

(defn recover
  [db]
  (let [conn (d/conn-from-db db)
        note-eid (ffirst (d/q `[:find ?e :where [?e :block/uid ~missing-date]] @conn))
        page (d/pull db '[:block/string {:block/children [:block/order :block/string {:block/children ...}]}] note-eid)]
    (materialize page 0)))

(defn run
  []
  (let [parsed-har (parse-har)
        db (parse-db parsed-har)
        time (find-deletion-time parsed-har)
        db (apply-transactions-until db parsed-har time)
        recovered (recover db)]
    (println (format "Recovered %s - saved to %s!" missing-date outfile))
    (spit outfile recovered)))

(run)

(ns tap-mssql.sync-strategies.full
  (:refer-clojure :exclude [sync])
  (:require [tap-mssql.config :as config]
            [tap-mssql.singer.fields :as singer-fields]
            [tap-mssql.singer.bookmarks :as singer-bookmarks]
            [tap-mssql.singer.messages :as singer-messages]
            [tap-mssql.singer.transform :as singer-transform]
            [tap-mssql.sync-strategies.common :as common]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.java.jdbc :as jdbc]))

(defn get-max-pk-values [config catalog stream-name state]
  (let [dbname (get-in catalog ["streams" stream-name "metadata" "database-name"])
        schema-name (get-in catalog ["streams" stream-name "metadata" "schema-name"])
        bookmark-keys (map common/sanitize-names (singer-bookmarks/get-bookmark-keys catalog stream-name))
        table-name (-> (get-in catalog ["streams" stream-name "table_name"])
                       (common/sanitize-names))
        sql-query [(format "SELECT %s FROM %s.%s" (string/join " ," (map (fn [bookmark-key] (format "MAX(%1$s) AS %1$s" bookmark-key)) bookmark-keys)) schema-name table-name)]]
    (if (not (empty? bookmark-keys))
      (do
        (log/infof "Executing query: %s" (pr-str sql-query))
        (->> (jdbc/query (assoc (config/->conn-map config) :dbname dbname) sql-query {:keywordize? false :identifiers identity})
             first
             (assoc-in state ["bookmarks" stream-name "max_pk_values"])))
      state)))

(defn valid-full-table-state? [state table-name]
  ;; The state MUST contain max_pk_values if there is a bookmark
  (if (contains? (get-in state ["bookmarks" table-name]) "last_pk_fetched")
    (contains? (get-in state ["bookmarks" table-name]) "max_pk_values")
    true))

(defn build-sync-query [stream-name schema-name table-name record-keys state]
  {:pre [(not (empty? record-keys))
         (valid-full-table-state? state stream-name)]}
  ;; TODO: Fully qualify and quote all database structures, maybe just schema
  (let [last-pk-fetched   (get-in state ["bookmarks" stream-name "last_pk_fetched"])
        bookmark-keys     (map (fn [col] (->> (common/sanitize-names col)
                                             (format "%s >= ?")))
                               (keys last-pk-fetched))
        max-pk-values     (get-in state ["bookmarks" stream-name "max_pk_values"])
        sanitized-mpkv    (map common/sanitize-names (keys max-pk-values))
        limiting-keys     (map #(format "%s <= ?" %)
                               sanitized-mpkv)
        add-where-clause? (or (not (empty? bookmark-keys))
                              (not (empty? limiting-keys)))
        where-clause      (when add-where-clause?
                            (str " WHERE " (string/join " AND "
                                                        (concat bookmark-keys
                                                                limiting-keys))))
        order-by           (when (not (empty? limiting-keys))
                             (str " ORDER BY " (string/join ", "
                                                            (map #(format "%s" %)
                                                                 sanitized-mpkv))))
        sql-params        [(str (format "SELECT %s FROM %s.%s"
                                        (string/join ", " (map common/sanitize-names record-keys))
                                        schema-name
                                        (common/sanitize-names table-name))
                                where-clause
                                order-by)]]
    (if add-where-clause?
      (concat sql-params
              (vals last-pk-fetched)
              ;; TODO: String PKs? They may need quoted, they may also
              ;; need N'' for nvarchar/nchar/ntext, etc., conditionally
              ;; :facepalm:
              ;; Likely an edge case, but might be pretty rough.
              (vals max-pk-values))
      sql-params)))

(defn sync-and-write-messages!
  "Syncs all records, states, returns the latest state. Ensures that the
  bookmark we have for this stream matches our understanding of the fields
  defined in the catalog that are bookmark-able."
  [config catalog stream-name state]
  (let [dbname (get-in catalog ["streams" stream-name "metadata" "database-name"])
        record-keys (singer-fields/get-selected-fields catalog stream-name)
        bookmark-keys (singer-bookmarks/get-bookmark-keys catalog stream-name)
        table-name (get-in catalog ["streams" stream-name "table_name"])
        schema-name (get-in catalog ["streams" stream-name "metadata" "schema-name"])
        sql-params (build-sync-query stream-name schema-name table-name record-keys state)]
    (log/infof "Executing query: %s" (pr-str sql-params))
    (-> (reduce (fn [acc result]
               (let [record (->> (select-keys result record-keys)
                                 (singer-transform/transform catalog stream-name))]
                 (singer-messages/write-record! stream-name state record)
                 (->> (singer-bookmarks/update-last-pk-fetched stream-name bookmark-keys acc record)
                      (singer-messages/write-state-buffered! stream-name))))
             state
             (jdbc/reducible-query (assoc (config/->conn-map config)
                                          :dbname dbname)
                                   sql-params
                                   {:raw? true}))
        (update-in ["bookmarks" stream-name] dissoc "last_pk_fetched" "max_pk_values"))))

(defn sync!
  [config catalog stream-name state]
  {:post [(map? %)]}
  (->> state
       (get-max-pk-values config catalog stream-name)
       (singer-messages/write-state! stream-name)
       (sync-and-write-messages! config catalog stream-name)
       (singer-messages/write-activate-version! stream-name)))
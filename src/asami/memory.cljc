(ns ^{:doc "A storage implementation over in-memory indexing."
      :author "Paula Gearon"}
    asami.memory
    (:require [asami.storage :as storage :refer [ConnectionType DatabaseType UpdateData]]
              [asami.internal :refer [now instant? to-timestamp]]
              [asami.index :as mem]
              [asami.multi-graph :as multi]
              [asami.graph :as gr :refer [GraphType]]
              [asami.query :as query]
              [zuko.schema :refer [Triple]]
              [asami.entities.reader :as reader]
              [schema.core :as s :include-macros true]))

(defn ^:private find-index
  "Performs a binary search through a sorted vector, returning the index of a provided value
   that is in the vector, or the next lower index if the value is not present.
   a: The vector to be searched
   v: The value being searched for
   cmp: A 2 argument comparator function (Optional).
        Defaults to clojure.core/compare
        Must return -1 when the first arg < the second arg.
                    +1 when the first arg > the second arg.
                    0 when the args are equal."
  ([a v]
   (find-index a v compare))
  ([a v cmp]
     (loop [low 0 high (count a)]
       (if (= (inc low) high)
         low
         (let [mid (int (/ (+ low high) 2))
               mv (nth a mid)
               c (cmp mv v)]
           (cond
             (zero? c) mid
             (> 0 c) (recur mid high)
             (< 0 c) (recur low mid)))))))


(declare as-of* as-of-t* as-of-time* since* since-t* graph* entity*
         get-url* next-tx* db* delete-database* transact-update* transact-data*)

;; graph is the wrapped graph
;; history is a seq of Databases, excluding this one
;; timestamp is the time the database was created
(defrecord MemoryDatabase [graph history timestamp t]
  storage/Database

  (as-of [this t] (as-of* this t))
  (as-of-t [this] (as-of-t* this))
  (as-of-time [this] (as-of-time* this))
  (since [this t] (since* this t))
  (since-t [this] (since-t* this))
  (graph [this] (graph* this))
  (entity [this id nested?] (entity* this id nested?)))

;; name is the name of the database
;; state is an atom containing:
;; :db is the latest DB
;; :history is a list of tuples of Database, including db
(defrecord MemoryConnection [name state]
  storage/Connection
  (open? [this] true)
  (get-name [this] name)
  (get-url [this] (get-url* this))
  (next-tx [this] (next-tx* this))
  (db [this] (db* this))
  (delete-database [this] (delete-database* this))
  (release [this]) ;; no-op for memory databases
  (transact-update [this update-fn] (transact-update* this update-fn))
  (transact-data [this updates! asserts retracts] (transact-data* this updates! asserts retracts))
  (transact-data [this updates! generator-fn] (transact-data* this updates! generator-fn)))


(def empty-graph mem/empty-graph)
(def empty-multi-graph multi/empty-multi-graph)

(s/defn new-connection :- ConnectionType
  "Creates a memory Connection object"
  [name :- s/Str
   gr :- GraphType]
  (let [db (->MemoryDatabase gr [] (now) 0)]
    (->MemoryConnection name (atom {:db db :history [db]}))))

(s/defn get-url* :- s/Str
  [{:keys [name state]} :- ConnectionType]
  (let [first-graph (-> state deref :history first :graph)
        gtype (condp = first-graph
                empty-graph "mem"
                empty-multi-graph "multi"
                (throw (ex-info (str "Unknown graph type:" (type first-graph)) {:graph first-graph})))]
    (str "asami:" gtype "://" name)))

(s/defn next-tx* :- s/Num
  [connection :- ConnectionType]
  (count (:history @(:state connection))))

(s/defn db* :- DatabaseType
  "Retrieves the most recent value of the database for reading."
  [connection :- ConnectionType]
  (:db @(:state connection)))

(s/defn delete-database* :- s/Bool
  "Reverts the state of a connection to an empty database, resetting the initialization time."
  [{:keys [state] :as connection} :- ConnectionType]
  (let [db (->MemoryDatabase (-> state deref :history first :graph) [] (now) 0)]
    (reset! state {:db db :history [db]})
    true))

(s/defn as-database :- DatabaseType
  "Creates a Database around an existing Graph.
   graph: The graph to build a database around. "
  [graph :- GraphType]
  (->MemoryDatabase graph [] (now) 0))

(s/defn as-of* :- DatabaseType
  "Retrieves the database as of a given moment, inclusive.
   The t value may be an instant, or a transaction ID.
   The database returned will be whatever database was the latest at the specified time or transaction."
  [{:keys [graph history timestamp] :as db} :- DatabaseType
   t :- (s/cond-pre s/Int (s/pred instant?))]
  (cond
    (instant? t) (let [ts (to-timestamp t)]
                   (if (>= (compare ts timestamp) 0)
                     db
                     (nth history
                          (find-index history ts #(compare (:timestamp %1) %2)))))
    (int? t) (if (>= t (count history))
               db
               (nth history (min (max t 0) (dec (count history)))))))

(s/defn as-of-t* :- s/Int
  "Returns the as-of point for a database, or nil if none"
  [{history :history :as db} :- DatabaseType]
  (and history (count history)))

(s/defn as-of-time* :- s/Int
  "Returns the timestamp for a database"
  [{:keys [timestamp]} :- DatabaseType]
  timestamp)

(s/defn since* :- (s/maybe DatabaseType)
  "Returns the value of the database since some point t, exclusive.
   t can be a transaction number, or instant."
  [{:keys [graph history timestamp] :as db} :- DatabaseType
   t :- (s/cond-pre s/Int (s/pred instant?))]
  (cond
    (instant? t) (let [ts (to-timestamp t)]
                   (cond
                     (> (compare ts timestamp) 0) nil
                     (< (compare ts (:timestamp (first history))) 0) (first history)
                     :default (let [tx (inc (find-index history ts #(compare (:timestamp %1) %2)))]
                                (if (= tx (count history))
                                  db
                                  (nth history tx)))))
    (int? t) (cond (>= t (count history)) nil
                   (= (count history) (inc t)) db
                   :default (nth history (min (max (inc t) 0) (dec (count history)))))))

(s/defn since-t* :- s/Int
  "Returns the since point of a database, or nil if none"
  [{history :history :as db} :- DatabaseType]
  (if-not (seq history)
    0
    (count history)))

(s/defn graph* :- GraphType
  "Returns the Graph object associated with a Database"
  [database :- DatabaseType]
  (:graph database))

(def DBsBeforeAfter [(s/one DatabaseType "The database before an operation")
                     (s/one DatabaseType "The database after an operation")])

(s/defn transact-update* :- DBsBeforeAfter
  "Updates a graph with a function, updating the connection to the new graph.
  The function accepts a graph and a transaction ID.
  Returns a triple containing the old database, the new one, and any adjunect data the statement generator may have created."
  [conn :- ConnectionType
   update-fn :- (s/pred fn?)]
  (let [[{db-before :db} {db-after :db tempids :adjunct}]
        (swap-vals! (:state conn)
                    (fn [state]
                      (let [{:keys [graph db history t] :as db-before} (:db state)
                            next-tx (count (:history state))
                            next-graph (update-fn graph next-tx)
                            db-after (->MemoryDatabase next-graph (conj history db-before) (now) (inc t))]
                        {:db db-after
                         :history (conj (:history db-after) db-after)})))]
    [db-before db-after]))

(s/defn transact-data* :- DBsBeforeAfter
  "Removes a series of tuples from the latest graph, and asserts new tuples into the graph.
   Updates the connection to the new graph."
  ([conn :- ConnectionType
    updates! :- UpdateData
    asserts :- [Triple]   ;; triples to insert
    retracts :- [Triple]] ;; triples to remove
   (transact-update* conn (fn [graph tx-id] (gr/graph-transact graph tx-id asserts retracts updates!))))
  ([conn :- ConnectionType
    updates! :- UpdateData
    generator-fn]
   (transact-update* conn
                     (fn [graph tx-id]
                       (let [[asserts retracts] (generator-fn graph)]
                         (gr/graph-transact graph tx-id asserts retracts updates!))))))


(s/defn entity* :- (s/maybe {s/Any s/Any})
  "Returns an entity based on an identifier, either the :db/id or a :db/ident if this is available. This eagerly retrieves the entity.
   Objects may be nested, but references to top level objects will be nil in order to avoid loops."
  ;; TODO Reference the up-coming entity index
  [{graph :graph :as db} id nested?]
  (reader/ident->entity graph id nested?))


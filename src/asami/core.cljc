(ns ^{:doc "A storage implementation over in-memory indexing. Includes full query engine."
      :author "Paula Gearon"}
    asami.core
    (:require [asami.storage :as storage :refer [ConnectionType DatabaseType]]
              [asami.memory :as memory]
              #?(:clj [asami.durable.store :as durable])  ;; TODO: make this available to CLJS when ready
              [asami.query :as query]
              [asami.graph :as gr]
              [asami.entities :as entities]
              [asami.entities.general :refer [GraphType]]
              [asami.internal :as internal]
              #?(:clj  [clojure.edn :as edn]
                 :cljs [cljs.reader :as edn])
              #?(:clj  [schema.core :as s]
                 :cljs [schema.core :as s :include-macros true]))
    #?(:clj (:import (java.util.concurrent CompletableFuture)
                     (java.util.function Supplier))))

(defonce connections (atom {}))

(defn shutdown
  "Releases all connection resources for a clean shutdown.
  This should only be called during shutdown, and not if further database access is desired."
  []
  (doseq [connection (vals @connections)]
    (storage/release connection)
    (reset! connections {})))

#?(:clj
   (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown)))

(s/defn ^:private parse-uri :- {:type s/Str
                                :name s/Str}
  "Splits up a database URI string into structured parts"
  [uri]
  (if (map? uri)
    uri
    (if-let [[_ db-type db-name] (re-find #"asami:([^:]+)://(.+)" uri)]
      {:type db-type
       :name db-name}
      (throw (ex-info (str "Invalid URI: " uri) {:uri uri})))))

(defn- connection-for
  "Creates a connection for a URI"
  [uri]
  (let [{:keys [type name]} (parse-uri uri)]
    (case type
      "mem" (memory/new-connection name memory/empty-graph)
      "multi" (memory/new-connection name memory/empty-multi-graph)
      "local" #?(:clj (durable/create-database name) :cljs (throw (ex-info "Local storage not available" {:reason :cljs})))
      (throw (ex-info (str "Unknown graph URI schema" type) {:uri uri :type type :name name})))))

(defn- db-exists?
  [uri]
  (let [{:keys [type name]} (parse-uri uri)]
    (case type
      "mem" false
      "multi" false
      "local" #?(:clj (durable/db-exists? name) :cljs (throw (ex-info "Local storage not available" {:reason :cljs})))
      (throw (ex-info (str "Unknown graph URI schema" type) {:uri uri :type type :name name})))))

(s/defn create-database :- s/Bool
  "Creates database specified by uri. Returns true if the
   database was created, false if it already exists."
  [uri :- s/Str]
  (boolean
   (when-not (@connections uri)
     (swap! connections assoc uri (connection-for uri)))))

(s/defn connect :- ConnectionType
  "Connects to the specified database, returning a Connection.
  In-memory databases get created if they do not exist already.
  Memory graphs:
  asami:mem://dbname    A standard graph
  asami:multi://dbname  A multigraph"
  [uri :- s/Str]
  (if-let [conn (@connections uri)]
    conn
    (do
      (create-database uri)
      (@connections uri))))

(s/defn delete-database :- s/Bool
  "Deletes the database specified by the URI.
   Returns true if the delete occurred."
  [uri :- s/Str]
  ;; retrieve the database from connections
  (if-let [conn (@connections uri)]
    (do
      (swap! connections dissoc uri)
      (storage/delete-database conn))
    ;; database not in the connections
    ;; connect to it to free its resources
    (boolean
     (when (db-exists? uri)
       (if-let [conn (connection-for uri)]
         (storage/delete-database conn))))))

(s/defschema StringOrConnection (s/if string? s/Str ConnectionType))

(s/defn release
 [conn :- StringOrConnection]
 (if (string? conn)
   (when-let [c (@connections conn)]
     (swap! connections dissoc conn)
     (storage/release c))
   (do
     ;; if the connection is known then remove it
     (when-let [url (storage/get-url conn)]
       (swap! connections dissoc url)
       ;; if a different version of this connection was in storage, then remove it too
       (when-let [c (@connections url)]
         (when-not (identical? c conn)
           (storage/release c))))
     ;; release the connection
     (storage/release conn))))

(s/defn get-database-names
  "Returns a seq of database names that this instance is aware of."
  []
  (keys @connections))

(def Graphable (s/cond-pre GraphType {:graph GraphType}))

(defn ^:private as-graph
  "Converts the d argument to a Graph. Leaves it alone if it can't be converted."
  [d]
  (if (satisfies? gr/Graph d)
    d
    (let [g (:graph d)]
      (cond
        (satisfies? gr/Graph g) g
        (satisfies? storage/Database d) (storage/graph d)
        (satisfies? storage/Connection d) (storage/graph (storage/db d))
        :default d))))

(s/defn as-connection :- ConnectionType
  "Creates a Database/Connection around an existing Graph.
   graph: The graph or graph wrapper to build a database around.
   uri: The uri of the database."
  ([graph :- Graphable] (as-connection graph (str (gensym "asami:mem://internal"))))
  ([graph :- Graphable
    uri :- s/Str]
   (let [{:keys [name]} (parse-uri uri)
         c (memory/new-connection name (as-graph graph))]
     (swap! connections assoc uri c)
     c)))

(defn check-attachment
  "Checks if a connection is attached to the connections map.
   If not, then reconnect if still open. Returns the connection if previously connected,
   false if it needed to be reconnected.
   Throws an exception if the graph is closed."
  [connection]
  (when-not (storage/open? connection)
    (throw (ex-info "Database closed" {:open false})))
  (let [url (storage/get-url connection)
        c (@connections url)]
    (if (nil? c)
      (do
        (swap! connections assoc url connection)
        false)
      (when-not (identical? c connection)
        (throw (ex-info "Updating a detached connection" {:url url}))))))

(def db storage/db)
(def as-of storage/as-of)
(def as-of-t storage/as-of-t)
(def as-of-time storage/as-of-time)
(def since storage/since)
(def since-t storage/since-t)
(def graph storage/graph)
(def now internal/now)
(def instant internal/instant)
(def instant? internal/instant?)
(def long-time internal/long-time)

(def TransactData (s/if map?
                    {(s/optional-key :tx-data) [s/Any]
                     (s/optional-key :tx-triples) [[(s/one s/Any "entity")
                                                    (s/one s/Any "attribute")
                                                    (s/one s/Any "value")]]
                     (s/optional-key :executor) s/Any
                     (s/optional-key :update-fn) (s/pred fn?)
                     (s/optional-key :input-limit) s/Num}
                    [s/Any]))

(s/defn transact-async
  ;; returns a deref'able object that derefs to:
  ;; {:db-before DatabaseType
  ;;  :db-after DatabaseType
  ;;  :tx-data [datom/DatomType]
  ;;  :tempids {s/Any s/Any}}
  "Updates a database.
   connection: The connection to the database to be updated.
   tx-info: This is either a seq of items to be transacted, or a map.
            If this is a map, then a :tx-data value will contain the same type of seq that tx-info may have.
            Each item to be transacted is one of:
            - vector of the form: [:db/add entity attribute value] - creates a datom
            - vector of the form: [:db/retract entity attribute value] - removes a datom
            - map: an entity to be inserted/updated.
            Alternatively, a map may have a :tx-triples key. If so, then this is a seq of 3 element vectors.
            Each vector in a :tx-triples seq will contain the raw values for [entity attribute value]
            :executor An optional value in the tx-info containing an executor to be used to run the CompletableFuture
            :input-limit contains an optional maximum number of statements to insert (approx)
  Entities and assertions may have attributes that are keywords with a trailing ' character.
  When these appear an existing attribute without that character will be replaced. This only occurs for the top level
  entity, and is not applied to attributes appearing in nested structures.
  Entities can be assigned a :db/id value. If this is a negative number, then it is considered a temporary value and
  will be mapped to a system-allocated ID. Other entities can reference such an entity using that ID.
  Entities can be provided a :db/ident value of any type. This will be considered unique, and can be used to identify
  entities for updates in subsequent transactions.

  Returns a future/delay object that will hold a map containing the following:
  :db-before    database value before the transaction
  :db-after     database value after the transaction
  :tx-data      sequence of datoms produced by the transaction
  :tempids      mapping of the temporary IDs in entities to the allocated nodes"
  [{:keys [name state] :as connection} :- ConnectionType
   tx-info :- TransactData]

  ;; Detached databases need to be reattached when transacted into
  (check-attachment connection)

  ;; destructure tx-info, if it can be destructured
  (let [{:keys [tx-data tx-triples executor update-fn input-limit]} (if (map? tx-info) tx-info {})
        op (if update-fn
             (fn []
               (let [[db-before db-after] (storage/transact-update connection update-fn)]
                 {:db-before db-before
                  :db-after db-after}))
             (fn []
               (let [current-db (storage/db connection)
                     ;; single maps should not be passed in, but if they are then wrap them
                     seq-wrapper (fn [x] (if (map? x) [x] x))
                     ;; volatiles to capture data for the user
                     ;; This is to avoid passing parameters to functions that users may want to call directly
                     ;; and especially to avoid the difficulty of asking users to of return multiple structures
                     vtempids (volatile! {}) ;; volatile to capture the tempid map from built-triples
                     generated-data (volatile! [[] []]) ;; volatile to capture the asserted and retracted data in a transaction
                     [db-before db-after] (if tx-triples
                                            ;; simple assertion of triples
                                            (storage/transact-data connection generated-data (seq-wrapper tx-triples) nil)
                                            ;; a seq of statements and/or entities
                                            ;; convert these to assertions/retractions and send to transaction
                                            ;; also, capture tempids that are generated during conversion
                                            (storage/transact-data connection
                                                                   generated-data
                                                                   (fn [graph]
                                                                     ;; building triples returns a tuple of assertions, retractions, tempids
                                                                     (let [[_ _ tempids :as result]
                                                                           (entities/build-triples graph
                                                                                                   (seq-wrapper (or tx-data tx-info))
                                                                                                   input-limit)]
                                                                       (vreset! vtempids tempids)
                                                                       result))))
                     ;; pull out the info captured during the transaction
                     [triples retracts] (deref generated-data)]
                 {:db-before db-before
                  :db-after db-after
                  :tx-data (concat retracts triples)
                  :tempids @vtempids})))]
    #?(:clj (CompletableFuture/supplyAsync (reify Supplier (get [_] (op)))
                                           (or executor clojure.lang.Agent/soloExecutor))
       :cljs (let [d (delay (op))]
               (force d)
               d))))

;; set a generous default transaction timeout of 100 seconds 
#?(:clj (def ^:const default-tx-timeout 100000))

#?(:clj
   (defn get-timeout
     "Retrieves the timeout value to use in ms"
     []
     (or (System/getProperty "asami.txTimeoutMsec")
         (System/getProperty "datomic.txTimeoutMsec")
         default-tx-timeout)))

#?(:clj
   (s/defn transact
     "This returns a completed future with the data from a transaction.
      See the documentation for transact-async for full details on arguments.
      If the transaction times out, the call to transact will throw an ExceptionInfo exception.
      The default is 100 seconds

      The result derefs to a map of:
       :db-before database value before the transaction
       :db-after database value after the transaction
       :tx-data a sequence of the transacted datom operations
       :tempids a map of temporary id values and the db identifiers that were allocated for them}"
     ;; returns a deref'able object that derefs to:
     ;; {:db-before DatabaseType
     ;;  :db-after DatabaseType
     ;;  :tx-data [datom/DatomType]
     ;;  :tempids {s/Any s/Any}}
     [connection :- ConnectionType
      tx-info :- TransactData]
     (let [transact-future (transact-async connection tx-info)
           timeout (get-timeout)]
       (when (= ::timeout (deref transact-future timeout ::timeout))
         (throw (ex-info "Transaction timeout" {:timeout timeout})))
       transact-future))

   :cljs
   (s/defn transact
     "This is a thin wrapper around the transact-async function.
      TODO: convert this to a promise-based approach for the async implementation
      See the documentation for transact-async for full details on arguments.
      returns a deref'able object that derefs to a map of:
       :db-before database value before the transaction
       :db-after database value after the transaction
       :tx-data a sequence of the transacted datom operations
       :tempids a map of temporary id values and the db identifiers that were allocated for them}"
     ;; {:db-before DatabaseType
     ;;  :db-after DatabaseType
     ;;  :tx-data [datom/DatomType]
     ;;  :tempids {s/Any s/Any}}
     [connection :- ConnectionType
      tx-info :- TransactData]
     (transact-async connection tx-info)))

(defn- graphs-of
  "Converts Database objects to the graph that they wrap. Other arguments are returned unmodified."
  [inputs]
  (map (fn [d]
         (if (satisfies? storage/Database d)
           (storage/graph d)
           (as-graph d)))
       inputs))

(defn q
  "Execute a query against the provided inputs.
   The query can be a map, a seq, or a string.
   See the documentation at https://github.com/quoll/asami/wiki/6.-Querying
   for a full description of queries.
   The end of the parameters may include a series of key/value pairs for query options.
   The only recognized option for now is:
     `:planner :user`
   This ensures that a query is executed in user-specified order, and not the order calculated by the optimizing planner."
  [query & inputs]
  (query/query-entry query memory/empty-graph (graphs-of inputs) false))

(defn show-plan
  "Return a query plan and do not execute the query.
   All parameters are identical to the `q` function."
  [query & inputs]
  (query/query-entry query memory/empty-graph (graphs-of inputs) true))

(defn export-data
  "Returns a simplified data structures of all statements in a database"
  [database]
  (let [g (if (satisfies? storage/Database database)
            (storage/graph database)
            (as-graph database))]
    (gr/resolve-pattern g '[?e ?a ?v ?t]))) ;; Note that transactions are not returned yet

(defn entity
  "Wrapper around the storage/entity function so that connections can be asked for entities.
  d: a connection or database
  id: an identifier for an entity"
  ([d id] (entity d id false))
  ([d id nested?]
   (let [database (if (satisfies? storage/Database d) d (db d))]
     (storage/entity database id nested?))))

(defn export-str
  "A wrapper on export-data to serialize to a string"
  [database]
  (prn-str (export-data database)))

(defn import-data
  "Loads raw statements into a connection. This does no checking of existing contents of storage.
  Accepts either a seq of tuples, or an EDN string which contains a seq of tuples.
  Optionally accepts options for reading a string (will be ignored if the data is not a string).
  These options are the same as for clojure.edn/read and cljs.reader/read."
  ([connection data]
   (import-data connection {} data))
  ([connection opts data]
   (let [readers #?(:cljs gr/node-reader
                    :clj (merge clojure.core/*data-readers* gr/node-reader))
         ;; add any user-provided readers to the system readers
         user-readers (merge readers (:readers opts))
         statements (if (string? data)
                      (edn/read-string (merge opts {:readers user-readers}) data)
                      data)]
     ;; TODO: consider making imported nodes unique by adding an offset
     (transact connection {:tx-triples statements}))))

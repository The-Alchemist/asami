(ns ^{:doc "A multi-graph implementation.
Resolution counting ignores multiple edges connecting nodes, so as to
allow rules to successfully use this graph type."
      :author "Paula Gearon"}
    asami.multi-graph
  (:require [asami.graph :as gr :refer [Graph new-graph graph-add graph-delete graph-diff
                                        resolve-triple count-triple]]
            [asami.common-index :as common :refer [? NestedIndex]]
            [asami.analytics :as analytics]
            [zuko.node :refer [NodeAPI]]
            [zuko.logging :as log :include-macros true]
            [schema.core :as s :include-macros true]))

(def ^:dynamic *insert-op* inc)

(def IndexStructure
  {s/Any
   {s/Any
    {s/Any
     {(s/required-key :count) s/Num
      (s/required-key :t) s/Int
      (s/required-key :id) s/Int
      s/Keyword s/Any}}}})

(s/defn multi-add :- IndexStructure
  "Add elements to a 4-level index"
  [idx :- IndexStructure
   a :- s/Any
   b :- s/Any
   c :- s/Any
   tx :- s/Int
   id :- s/Int]
  (if-let [idxb (get idx a)]
    (if-let [idxc (get idxb b)]
      (if-let [entry (get idxc c)]
        (assoc idx a (assoc idxb b (assoc idxc c (update entry :count *insert-op*))))
        (assoc idx a (assoc idxb b (assoc idxc c {:count (*insert-op* 0) :t tx :id id}))))
      (assoc idx a (assoc idxb b {c {:count (*insert-op* 0) :t tx :id id}})))
    (assoc idx a {b {c {:count (*insert-op* 0) :t tx :id id}}})))

(s/defn multi-delete :- (s/maybe IndexStructure)
  "Remove elements from a 3-level index. Returns the new index, or nil if there is no change."
  [idx :- IndexStructure
   a :- s/Any
   b :- s/Any
   c :- s/Any]
  (if-let [idx2 (idx a)]
    (if-let [idx3 (idx2 b)]
      (if-let [{c4 :count :as elt} (idx3 c)]
        (if (= 1 c4)
          (let [new-idx3 (dissoc idx3 c)
                new-idx2 (if (seq new-idx3) (assoc idx2 b new-idx3) (dissoc idx2 b))
                new-idx (if (seq new-idx2) (assoc idx a new-idx2) (dissoc idx a))]
            new-idx)
          (assoc idx a (assoc idx2 b (assoc idx3 c (assoc elt :count (dec c4))))))))))

(defmulti get-from-multi-index
  "Lookup an index in the graph for the requested data.
   Returns a sequence of unlabelled bindings. Each binding is a vector of binding values."
  common/simplify)

;; Extracts the required index (idx), and looks up the requested fields.
;; If an embedded index is pulled out, then this is referred to as edx.
(defmethod get-from-multi-index [:v :v :v] [{idx :spo} s p o] (let [n (some-> idx (get s) (get p) (get o) :count)]
                                                                (if (and (number? n) (> n 0))
                                                                  (repeat n [])
                                                                  [])))
(defmethod get-from-multi-index [:v :v  ?] [{idx :spo} s p o] (for [[o {c :count}] (some-> idx (get s) (get p))
                                                                    _ (range c)]
                                                                [o]))
(defmethod get-from-multi-index [:v  ? :v] [{idx :osp} s p o] (for [[p {c :count}] (some-> idx (get o) (get s))
                                                                    _ (range c)]
                                                                [p]))
(defmethod get-from-multi-index [:v  ?  ?] [{idx :spo} s p o] (let [edx (idx s)]
                                                                (for [p (keys edx)
                                                                      [o {c :count}] (edx p)
                                                                      _ (range c)]
                                                                  [p o])))
(defmethod get-from-multi-index [ ? :v :v] [{idx :pos} s p o] (for [[s {c :count}] (some-> idx (get p) (get o))
                                                                    _ (range c)]
                                                                [s]))
(defmethod get-from-multi-index [ ? :v  ?] [{idx :pos} s p o] (let [edx (idx p)]
                                                                (for [o (keys edx)
                                                                      [s {c :count}] (edx o)
                                                                      _ (range c)]
                                                                  [s o])))
(defmethod get-from-multi-index [ ?  ? :v] [{idx :osp} s p o] (let [edx (idx o)]
                                                                (for [s (keys edx)
                                                                      [p {c :count}] (edx s)
                                                                      _ (range c)]
                                                                  [s p])))
(defmethod get-from-multi-index [ ?  ?  ?] [{idx :spo} s p o] (for [s (keys idx)
                                                                    p (keys (idx s))
                                                                    [o {c :count}] ((idx s) p)
                                                                    _ (range c)]
                                                                [s p o]))

(defmulti count-transitive-from-index
  "Lookup an index in the graph for the requested data and count the results based on a transitive index."
  common/trans-simplify)

;; TODO count these efficiently
(defmethod count-transitive-from-index :default
  [graph tag s p o]
  (if (= [? ? ?] (common/simplify graph s p o))
    ;; There is no sense in this except for planning, so estimate an upper bound
    (* (count (:spo graph)) (count (:osp graph)))
    (count (common/get-transitive-from-index graph tag s p o))))

(declare empty-multi-graph)

(defrecord MultiGraph [spo pos osp next-stmt-id]
  NestedIndex
  (lowest-level-fn [this] keys)
  (lowest-level-set-fn [this] (comp set keys))
  (lowest-level-sets-fn [this] (partial map (comp set keys)))
  (mid-level-map-fn [this] #(into {} (map (fn [[k v]] [k (set (keys v))]) %1)))

  Graph
  (new-graph [this] empty-multi-graph)
  (graph-add [this subj pred obj]
    (graph-add this subj pred obj gr/*default-tx-id*))
  (graph-add [this subj pred obj tx]
    (log/trace "insert " [subj pred obj tx])
    (let [id (or (:next-stmt-id this) 1)]
      (assoc this
             :spo (multi-add spo subj pred obj tx id)
             :pos (multi-add pos pred obj subj tx id)
             :osp (multi-add osp obj subj pred tx id)
             :next-stmt-id (inc id))))
  (graph-delete [this subj pred obj]
    (log/trace "delete " [subj pred obj])
    (if-let [idx (multi-delete spo subj pred obj)]
      (assoc this :spo idx :pos (multi-delete pos pred obj subj) :osp (multi-delete osp obj subj pred))
      (do
        (log/trace "statement did not exist")
        this)))
  (graph-transact [this tx-id assertions retractions]
    (common/graph-transact this tx-id assertions retractions (volatile! [[] [] {}])))
  (graph-transact [this tx-id assertions retractions generated-data]
    (common/graph-transact this tx-id assertions retractions generated-data))
  (graph-diff [this other]
    (when-not (= (type this) (type other))
      (throw (ex-info "Unable to compare diffs between graphs of different types" {:this this :other other})))
    (let [s-po (remove (fn [[s po]] (= po (get (:spo other) s)))
                       spo)]
      (map first s-po)))
  (resolve-triple [this subj pred obj]
    (if-let [[plain-pred trans-tag] (common/check-for-transitive pred)]
      (common/get-transitive-from-index this trans-tag subj plain-pred obj)
      (get-from-multi-index this subj pred obj)))
  (attribute-values [this node]
    (get-from-multi-index this node '?a '?v))
  (count-triple [this subj pred obj] ;; This intentionally ignores multi-edges, and is used for Naga
    (if-let [[plain-pred trans-tag] (common/check-for-transitive pred)]
      (count-transitive-from-index this trans-tag subj plain-pred obj)
      (common/count-from-index this subj pred obj)))
  
  NodeAPI
  (data-attribute [_ _] :a/first)
  (container-attribute [_ _] :a/contains)
  (new-node [_] (gr/new-node))
  (node-id [_ n] (gr/node-id n))
  (node-type? [_ _ n] (gr/node-type? n))
  (find-triple [this [e a v]] (resolve-triple this e a v)))

(defn multi-graph-add
  ([graph subj pred obj tx n]
   (log/trace "insert *" n)
   (binding [*insert-op* (partial + n)]
     (graph-add graph subj pred obj tx)))
  ([graph subj pred obj tx]
   (binding [*insert-op* inc]
     (graph-add graph subj pred obj tx))))

(def empty-multi-graph (->MultiGraph {} {} {} nil))

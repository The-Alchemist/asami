(ns asami.core-query-test
  "Tests the public query functionality"
  (:require [asami.core :refer [q]]
            [asami.graph :refer [new-node graph-transact]]
            [asami.query :refer [*env* *override-restrictions*]]
            [asami.index :refer [empty-graph]]
            [schema.core :as s]
            #?(:clj  [clojure.test :refer [is use-fixtures testing]]
               :cljs [clojure.test :refer-macros [is run-tests use-fixtures testing]])
            #?(:clj  [schema.test :as st :refer [deftest]]
               :cljs [schema.test :as st :refer-macros [deftest]]))
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(defn assert-data
  [graph data]
  (graph-transact graph 0 data nil))

(def nn new-node)

(let [pmc (nn)
      gh (nn)
      jl (nn)
      r1 (nn)
      r2 (nn)
      r3 (nn)
      r4 (nn)
      r5 (nn)

      data [[pmc :artist/name "Paul McCartney"]
            [gh :artist/name "George Harrison"]
            [jl :artist/name "John Lennon"]
            [r1 :release/artists pmc]
            [r1 :release/name "My Sweet Lord"]
            [r2 :release/artists gh]
            [r2 :release/name "Electronic Sound"]
            [r3 :release/artists gh]
            [r3 :release/name "Give Me Love (Give Me Peace on Earth)"]
            [r4 :release/artists gh]
            [r4 :release/name "All Things Must Pass"]
            [r5 :release/artists jl]
            [r5 :release/name "Imagine"]]

      store (-> empty-graph
                (assert-data data))]

  (deftest test-simple-query
    (let [results (q '[:find ?e ?a ?v
                       :where [?e ?a ?v]] store)
          artists (q '[:find ?artist
                       :where [?e :artist/name ?artist]] store)]
      (is (= (set data) (set results)))
      (is (= #{["Paul McCartney"] ["George Harrison"] ["John Lennon"]} (set artists)))))

  (deftest test-ns-simple-query
    (let [results (q '[:find ?e ?us/a ?v
                       :where [?e ?us/a ?v]] store)
          artists (q '[:find ?artist
                       :where [?tst/e :artist/name ?artist]] store)]
      (is (= (set data) (set results)))
      (is (= #{["Paul McCartney"] ["George Harrison"] ["John Lennon"]} (set artists)))))

  (deftest test-join-query
    (let [results (q '[:find ?name
                       :where [?r :release/name "My Sweet Lord"]
                       [?r :release/artists ?a]
                       [?a :artist/name ?name]] store)]
      (is (= [["Paul McCartney"]] results))))

  (deftest test-ns-join-query
    (let [results (q '[:find ?u/name
                       :where [?r :release/name "My Sweet Lord"]
                       [?r :release/artists ?a]
                       [?a :artist/name ?u/name]] store)]
      (is (= [["Paul McCartney"]] results))))

  (deftest test-join-multi-query
    (let [results (q '[:find ?rname
                       :where [?a :artist/name "George Harrison"]
                       [?r :release/artists ?a]
                       [?r :release/name ?rname]] store)]
      (is (= #{["Electronic Sound"]
               ["Give Me Love (Give Me Peace on Earth)"]
               ["All Things Must Pass"]} (set results)))))

  (deftest test-not-query
    (let [results (q '[:find ?rname
                       :where [?a :artist/name "George Harrison"]
                       [?r :release/artists ?a]
                       [?r :release/name ?rname]
                       (not [?r :release/name "Electronic Sound"])] store)]
      (is (= #{["Give Me Love (Give Me Peace on Earth)"]
               ["All Things Must Pass"]} (set results))))

    (let [results (q '[:find ?rname
                       :where [?a :artist/name "George Harrison"]
                       [?r :release/artists ?a]
                       [?r :release/name ?rname]
                       (not [?ra :release/artists ?aa]
                            [?aa :artist/name "John Lennon"])] store)]
      (is (empty? results)))

    (let [results (q '[:find ?rname
                       :where [?a :artist/name "George Harrison"]
                       [?r :release/artists ?a]
                       [?r :release/name ?rname]
                       (not [?r :release/artists ?aa] [?aa :artist/name "Julian Lennon"])]
                     store)]
      (is (= #{["Electronic Sound"]
               ["Give Me Love (Give Me Peace on Earth)"]
               ["All Things Must Pass"]} (set results)))))

  (deftest test-bindings-query
    (let [all-results (q '[:find ?release-name
                           :where [?artist :artist/name ?artist-name]
                           [?release :release/artists ?artist]
                           [?release :release/name ?release-name]]
                         store)
          results (q '[:find ?release-name
                       :in $ [?artist-name ...]
                       :where [?artist :artist/name ?artist-name]
                       [?release :release/artists ?artist]
                       [?release :release/name ?release-name]]
                     store ["Paul McCartney" "George Harrison"])]
      (is (= #{["My Sweet Lord"] 
               ["Electronic Sound"]
               ["Give Me Love (Give Me Peace on Earth)"] 
               ["All Things Must Pass"]
               ["Imagine"]}
             (set all-results)))
      (is (= #{["My Sweet Lord"] 
               ["Electronic Sound"]
               ["Give Me Love (Give Me Peace on Earth)"] 
               ["All Things Must Pass"]}
             (set results)))))


  (deftest test-query-error
    (is (thrown-with-msg? ExceptionInfo #"Missing ':find' clause"
                          (q '[:select ?e ?a ?v
                               :where [?e ?a ?v]] store)))
    (is (thrown-with-msg? ExceptionInfo #"Missing ':where' clause"
                          (q '[:find ?e ?a ?v
                               :given [?e ?a ?v]] store)))
    (is (thrown-with-msg? ExceptionInfo #"Unknown clauses: "
                          (q '[:select ?e ?a ?v
                               :having [(?v > 5)]
                               :where [?e ?a ?v]] store)))))

(comment "The next structure represents the following data"
  {:disposition 5
   :observable {:type "domain"
                :value "cisco.com"
                :deliberated true}}
  {:disposition 5
   :observable {:type "ip"
                :value "72.163.4.161"
                :deliberated false}}
  {:disposition 1
   :observable {:type "domain"
                :value "ipo.pl"
                :deliberated true
                :internal true}}
  {:disposition 1
   :observable {:type "domain"
                :value "cisco.com"
                :deliberated true}}
  {:disposition 5
   :observable {:type "ip"
                :value "72.163.4.177"
                :deliberated true}})


(let [obs1 (nn)
      obs2 (nn)
      obs3 (nn)
      obs4 (nn)
      obs5 (nn)
      v1 (nn)
      v2 (nn)
      v3 (nn)
      v4 (nn)
      v5 (nn)

      idata [[obs1 :type "domain"]
             [obs1 :value "cisco.com"]
             [obs1 :deliberated true]
             [obs2 :type "ip"]
             [obs2 :value "72.163.4.161"]
             [obs2 :deliberated false]
             [obs3 :type "domain"]
             [obs3 :value "ipo.pl"]
             [obs3 :deliberated true]
             [obs3 :internal true]
             [obs4 :type "domain"]
             [obs4 :value "cisco.com"]
             [obs4 :deliberated true]
             [obs5 :type "ip"]
             [obs5 :value "72.163.4.177"]
             [obs5 :deliberated true]
             [v1 :disposition 5]
             [v1 :observable obs1]
             [v2 :disposition 5]
             [v2 :observable obs2]
             [v3 :disposition 1]
             [v3 :observable obs3]
             [v4 :disposition 1]
             [v4 :observable obs4]
             [v5 :disposition 5]
             [v5 :observable obs5]]]

  (deftest test-negation-query
    (let [st (-> empty-graph (assert-data idata))
          observables (q '[:find ?type ?value
                           :where [?observable :type ?type]
                           [?observable :value ?value]
                           [?observable :deliberated true]]
                         st)
          int-observables (q '[:find ?type ?value
                               :where [?observable :type ?type]
                               [?observable :value ?value]
                               [?observable :deliberated true]
                               (not [?observable :internal true])]
                             st)
          disp-observables (q '[:find ?type ?value
                                :where [?observable :type ?type]
                                [?observable :value ?value]
                                [?observable :deliberated true]
                                (not [?observable :internal true])
                                (not [?verdict :disposition 1]
                                     [?verdict :observable ?verdict-observable]
                                     [?verdict-observable :type ?type]
                                     [?verdict-observable :value ?value])] 
                              st)]
      (is (= #{["domain" "cisco.com"]
               ["domain" "ipo.pl"]
               ["ip" "72.163.4.177"]}
             (set observables)))
      (is (= #{["domain" "cisco.com"]
               ["ip" "72.163.4.177"]}
             (set int-observables)))
      (is (= [["ip" "72.163.4.177"]]
             disp-observables))))

  (deftest test-negation-binding-query
    (let [st (-> empty-graph (assert-data idata))
          observables (q '[:find ?type ?value
                           :in $ ?observable-type
                           :where [?observable :type ?type]
                           [?observable :value ?value]
                           [?observable :deliberated true]
                           (not [?observable :internal true])
                           (not [?verdict :disposition ?observable-type]
                                [?verdict :observable ?verdict-observable]
                                [?verdict-observable :type ?type]
                                [?verdict-observable :value ?value])] 
                         st 1)]
      (is (= [["ip" "72.163.4.177"]]
             observables)))))

(let [o1 (nn)
      o2 (nn)
      o3 (nn)
      ver1 (nn)
      sight1 (nn)
      other1 (nn)

      ddata [[o1 :type "domain"]
             [o1 :value "cisco.com"]
             [o1 :verdict ver1]
             [o2 :type "ip"]
             [o2 :value "72.163.4.161"]
             [o2 :sighting sight1]
             [o3 :type "domain"]
             [o3 :value "ilo.pl"]
             [o3 :other other1]
             [ver1 :id "verdict-1"]
             [sight1 :id "sighting-1"]
             [other1 :id "other-1"]]]

  (deftest test-disjunctions
    (let [st (-> empty-graph (assert-data ddata))
          r1 (q '[:find ?related ?type ?value
                  :where [?observable :value ?value]
                  [?observable :type ?type]
                  (or [?observable :verdict ?related]
                      [?observable :sighting ?related])]
                st)
          r2 (q '[:find ?observable ?related
                  :where
                  (or [?observable :verdict ?related]
                      [?observable :sighting ?related])]
                st)
          r3 (q '[:find ?id ?type ?value
                  :where [?observable :value ?value]
                  [?observable :type ?type]
                  (or [?observable :verdict ?related]
                      [?observable :sighting ?related])
                  [?related :id ?id]]
                st)
          r4 (q '[:find ?observable
                  :where (or [?observable :value "ilo.pl"]
                             [?observable :value "nonexistent"])]
                st)
          r5 (q '{:find [?id ?value]
                  :where
                  [(or [?observable :value ?value]
                       [?sighting :id ?id])]}
                st)]
      (is (= #{[ver1 "domain" "cisco.com"]
               [sight1 "ip" "72.163.4.161"]}
             (set r1)))
      (is (= #{[o1 ver1]
               [o2 sight1]}
             (set r2)))
      (is (= #{["verdict-1" "domain" "cisco.com"]
               ["sighting-1" "ip" "72.163.4.161"]}
             (set r3)))
      (is (= [[o3]] r4))
      (is (= #{[nil "ilo.pl"]
               [nil "cisco.com"]
               [nil "72.163.4.161"]
               ["verdict-1" nil]
               ["sighting-1" nil]
               ["other-1" nil]}
             (set r5))))))

(let [b1 (nn)
      b2 (nn)
      b3 (nn)
      ver1 (nn)
      sight1 (nn)
      other1 (nn)

      data [[b1 :title "The Art of Computer Programming"]
            [b1 :author-last "Knuth"]
            [b1 :author-first "Donald"]
            [b1 :price 55.66]
            [b1 :profit 20.00]
            [b2 :title "Basic Category Theory for Computer Scientists"]
            [b2 :author-last "Pierce"]
            [b2 :author-first "Benjamin"]
            [b2 :price 38.17]
            [b2 :profit 12.00]
            [b3 :title "Purely Functional Data Structures"]
            [b3 :author-last "Okasaki"]
            [b3 :author-first "Chris"]
            [b3 :price 47.77]
            [b3 :profit 15.00]]]

  (deftest test-value-bindings
    (let [st (-> empty-graph (assert-data data))
          r1 (q '[:find ?title ?cost
                  :where [?book :title ?title]
                  [?book :price ?price]
                  [?book :profit ?profit]
                  [(- ?price ?profit) ?cost]]
                st)
          r2 (q '[:find ?title ?author
                  :where [?book :title ?title]
                  [?book :author-first ?first]
                  [?book :author-last ?last]
                  [(str ?last ", " ?first) ?author]]
                st)
          name-fn #(str %2 ", " %1)
          r3 (q [:find '?title '?author
                 :where '[?book :title ?title]
                 '[?book :author-first ?first]
                 '[?book :author-last ?last]
                 [(list name-fn '?first '?last) '?author]]
                st)
          r4 (q '[:find ?title ?author
                  :in $ ?name-fn
                  :where [?book :title ?title]
                  [?book :author-first ?first]
                  [?book :author-last ?last]
                  [(?name-fn ?first ?last) ?author]]
                st name-fn)]
      (is (= #{["The Art of Computer Programming" 35.66]
               ["Basic Category Theory for Computer Scientists" 26.17]
               ["Purely Functional Data Structures" 32.77]}
             (set r1)))
      (is (= #{["The Art of Computer Programming" "Knuth, Donald"]
               ["Basic Category Theory for Computer Scientists" "Pierce, Benjamin"]
               ["Purely Functional Data Structures" "Okasaki, Chris"]}
             (set r2)))
      (is (= #{["The Art of Computer Programming" "Knuth, Donald"]
               ["Basic Category Theory for Computer Scientists" "Pierce, Benjamin"]
               ["Purely Functional Data Structures" "Okasaki, Chris"]}
             (set r3)))
      (is (= #{["The Art of Computer Programming" "Knuth, Donald"]
               ["Basic Category Theory for Computer Scientists" "Pierce, Benjamin"]
               ["Purely Functional Data Structures" "Okasaki, Chris"]}
             (set r4)))))
  
  (deftest test-filter-queries
    (let [st (-> empty-graph (assert-data data))
          r1 (q '[:find ?title ?profit
                  :where [?book :title ?title]
                  [?book :price ?price]
                  [?book :profit ?profit]
                  [(>= ?price 40)]]
                st)
          r2 (binding [*env* {'right-price #(>= % 40)}]
               (q '[:find ?title
                    :where [?book :title ?title]
                    [?book :price ?price]
                    [(right-price ?price)]]
                  st))
          r3 (q '[:find ?title
                  :in $ ?right-price
                  :where [?book :title ?title]
                  [?book :price ?price]
                  [(?right-price ?price)]]
                st #(>= % 40))]
      (is (= #{["The Art of Computer Programming" 20.00]
               ["Purely Functional Data Structures" 15.00]}
             (set r1)))
      (is (= #{["The Art of Computer Programming"]
               ["Purely Functional Data Structures"]}
             (set r2)))
      (is (= #{["The Art of Computer Programming"]
               ["Purely Functional Data Structures"]}
             (set r3)))))

  #?(:clj
     (deftest test-sandbox-queries
       (let [st (-> empty-graph (assert-data data))
             r1 (binding [*override-restrictions* true]
                  (q '[:find ?title ?cost
                       :where [?book :title ?title]
                       [?book :price ?price]
                       [?book :profit ?profit]
                       [(read-string "-") ?minus]
                       [(?minus ?price ?profit) ?cost]]
                     st))
             r2 (q '[:find ?title ?cost
                     :in $ ?rs
                     :where [?book :title ?title]
                     [?book :price ?price]
                     [?book :profit ?profit]
                     [(?rs "-") ?minus]
                     [(?minus ?price ?profit) ?cost]]
                   st read-string)
             r3 (binding [*env* {'rs read-string}]
                  (q '[:find ?title ?cost
                       :where [?book :title ?title]
                       [?book :price ?price]
                       [?book :profit ?profit]
                       [(rs "-") ?minus]
                       [(?minus ?price ?profit) ?cost]]
                     st))]
         (is (thrown-with-msg?
              ExceptionInfo #"Unsupported operation: read-string"
              (q '[:find ?title ?cost
                   :where [?book :title ?title]
                   [?book :price ?price]
                   [?book :profit ?profit]
                   [(read-string "-") ?minus]
                   [(?minus ?price ?profit) ?cost]]
                 st)))
         (is (= #{["The Art of Computer Programming" 35.66]
                  ["Basic Category Theory for Computer Scientists" 26.17]
                  ["Purely Functional Data Structures" 32.77]}
                (set r1)))
         (is (= #{["The Art of Computer Programming" 35.66]
                  ["Basic Category Theory for Computer Scientists" 26.17]
                  ["Purely Functional Data Structures" 32.77]}
                (set r2)))
         (is (= #{["The Art of Computer Programming" 35.66]
                  ["Basic Category Theory for Computer Scientists" 26.17]
                  ["Purely Functional Data Structures" 32.77]}
                (set r3)))))

     ;; ClojureScript is already sandboxed, so this is just testing mechanism
     :cljs
     (deftest test-sandbox-queries
       (let [st (-> empty-graph (assert-data data))
             r1 (q '[:find ?title ?cost
                     :in $ ?atom
                     :where [?book :title ?title]
                     [?book :price ?price]
                     [?book :profit ?profit]
                     [(?atom ?price) ?p]
                     [(deref ?p) ?p2]
                     [(- ?p2 ?profit) ?cost]]
                   st atom)
             r2 (binding [*env* {'atom atom}]
                  (q '[:find ?title ?cost
                       :where [?book :title ?title]
                       [?book :price ?price]
                       [?book :profit ?profit]
                       [(atom ?price) ?p]
                       [(deref ?p) ?p2]
                       [(- ?p2 ?profit) ?cost]]
                     st))]
         (is (thrown-with-msg?
              ExceptionInfo #"Unsupported operation: atom"
              (q '[:find ?title ?cost
                   :where [?book :title ?title]
                   [?book :price ?price]
                   [?book :profit ?profit]
                   [(atom ?price) ?p]
                   [(deref ?p) ?p2]
                   [(- ?p2 ?profit) ?cost]]
                 st)))
         (is (= #{["The Art of Computer Programming" 35.66]
                  ["Basic Category Theory for Computer Scientists" 26.17]
                  ["Purely Functional Data Structures" 32.77]}
                (set r1)))
         (is (= #{["The Art of Computer Programming" 35.66]
                  ["Basic Category Theory for Computer Scientists" 26.17]
                  ["Purely Functional Data Structures" 32.77]}
                (set r2)))))))

(let [pa (nn)
      paa (nn)
      pab (nn)
      pac (nn)
      pb (nn)
      pba (nn)
      pbb (nn)
      pc (nn)
      pca (nn)
      pcb (nn)
      f (nn)
      m (nn)

      data [[pa :name "Alice"]
            [pa :gender f]
            [pa :address "1313 Mockingbird Lane"]
            [pa :child paa]
            [pa :child pab]
            [pa :child pac]
            [paa :name "Antoine"]
            [paa :age 16]
            [paa :gender m]
            [pab :name "Betty"]
            [pab :age 14]
            [pab :gender f]
            [pac :name "Chuck"]
            [pac :age 12]
            [pac :gender m]

            [pb :name "Barbara"]
            [pb :gender f]
            [pb :address "742 Evergreen Terrace"]
            [pb :child pba]
            [pb :child pbb]
            [pba :name "Ann"]
            [pba :age 13]
            [pba :gender f]
            [pbb :name "Bob"]
            [pbb :age 11]
            [pbb :gender m]

            [pc :name "Cary"]
            [pc :gender m]
            [pc :address "742 Evergreen Terrace"]
            [pc :child pba]
            [pc :child pbb]

            [f :label "female"]
            [m :label "male"]]

      dat2 [[pa :name "Alison"]
            [pa :gender f]
            [pa :address "1313 Mockingbird Lane"]
            [pa :child paa]
            [pa :child pab]
            [pa :child pac]
            [paa :name "Andrew"]
            [paa :age 16]
            [paa :gender m]
            [pab :name "Boris"]
            [pab :age 14]
            [pab :gender m]
            [pac :name "Cassie"]
            [pac :age 12]
            [pac :gender f]

            [pb :name "Beatrice"]
            [pb :gender f]
            [pb :address "742 Evergreen Terrace"]
            [pb :child pba]
            [pb :child pbb]
            [pba :name "Allie"]
            [pba :age 13]
            [pba :gender f]
            [pbb :name "Barry"]
            [pbb :age 11]
            [pbb :gender m]

            [pc :name "Charlie"]
            [pc :gender m]
            [pc :address "1313 Mockingbird Lane"]
            [pc :child pca]
            [pc :child pcb]
            [pca :name "Aaron"]
            [pca :age 16]
            [pca :gender m]
            [pcb :name "Barbara"]
            [pcb :age 6]
            [pcb :gender f]

            [f :label "female"]
            [m :label "male"]]]

  (deftest test-grouped-aggregates
    (let [st (-> empty-graph (assert-data data))

          r1 (q '[:find ?address (count ?child)
                  :where
                  [?parent :address ?address]
                  [?parent :child ?child]] st)

          st2 (-> empty-graph (assert-data dat2))

          r2 (q '[:find ?address (count ?child)
                  :where
                  [?parent :address ?address]
                  [?parent :child ?child]] st2)]

      (is (= '[?address ?count-child] (:cols (meta r1))))
      (is (= #{["1313 Mockingbird Lane" 3] ["742 Evergreen Terrace" 2]} (set r1)))

      (is (= '[?address ?count-child] (:cols (meta r2))))
      (is (= #{["1313 Mockingbird Lane" 5] ["742 Evergreen Terrace" 2]} (set r2)))))

  (deftest test-aggregates
    (let [st (-> empty-graph (assert-data data))
          sq (fn [query] (q query st))

          r1 (sq '[:find (count ?person)
                   :where [?person :gender ?g]])
          r2 (sq [:find '(count ?child)
                  :where [pb :child '?child]])
          r3 (sq '[:find (count ?child)
                   :where
                   [?parent :name "Barbara"]
                   [?parent :child ?child]])
          r4 (sq '[:find (count ?child)
                   :where
                   [?parent :child ?child]])
          r4b (sq '[:find (count *)
                    :where
                    [?parent :child ?child]])
          r4c (sq '[:find (count-distinct ?child)
                    :where
                    [?parent :child ?child]])
          r4d (sq '[:find (count-distinct *)
                    :where
                    [?parent :child ?child]])
          r5 (sq '[:find ?parent (count ?child)
                   :where
                   [?parent :child ?child]])
          r5' (sq '[:find ?pname (count ?child)
                    :where
                    [?parent :name ?pname]
                    [?parent :child ?child]])


          r6 (sq '[:find (count ?child)
                   :where
                   [?parent :gender ?f]
                   [?f :label "female"]
                   [?parent :child ?child]])
          r7 (sq '[:find (count ?child)
                   :with ?parent
                   :where
                   [?parent :gender ?f]
                   [?f :label "female"]
                   [?parent :child ?child]])

          r8 (sq '[:find ?address (count ?child)
                   :where
                   [?parent :address ?address]
                   [?parent :child ?child]])]
      (is (thrown-with-msg? ExceptionInfo #"Wildcard is not permitted for sum"
                            (sq '[:find (sum *) :where [?parent :child ?child]])))
      (is (= '[?count-person] (:cols (meta r1))))
      (is (= [[8]] r1))
      (is (= '[?count-child] (:cols (meta r2))))
      (is (= [[2]] r2))
      (is (= '[?count-child] (:cols (meta r3))))
      (is (= [[2]] r3))

      (is (= '[?count-child] (:cols (meta r4))))
      (is (= [[5]] r4))
      (is (= '[?count-all] (:cols (meta r4b))))
      (is (= [[7]] r4b))
      (is (= '[?count-distinct-child] (:cols (meta r4c))))
      (is (= [[7]] r4c))
      (is (= '[?count-distinct-all] (:cols (meta r4d))))
      (is (= [[7]] r4d))

      (is (= '[?parent ?count-child] (:cols (meta r5))))
      (is (= #{[pa 3] [pb 2] [pc 2]} (set r5)))
      (is (= '[?pname ?count-child] (:cols (meta r5'))))
      (is (= #{["Alice" 3] ["Barbara" 2] ["Cary" 2]} (set r5')))
      (is (= '[?count-child] (:cols (meta r6))))
      (is (= [[5]] r6))
      (is (= '[?count-child] (:cols (meta r7))))
      (is (= #{[3] [2]} (set r7)))

      (is (= '[?address ?count-child] (:cols (meta r8))))
      (is (= #{["1313 Mockingbird Lane" 3] ["742 Evergreen Terrace" 2]} (set r8)))))

  (deftest test-agg-projections
    (let [st (-> empty-graph (assert-data data))
          sq (fn [query] (q query st))

          r1 (sq '[:find (count ?child) .
                   :where
                   [?parent :child ?child]])
          r1b (sq '[:find (count *) .
                    :where
                    [?parent :child ?child]])
          r1c (sq '[:find (count-distinct ?child) .
                    :where
                    [?parent :child ?child]])
          r1d (sq '[:find (count-distinct *) .
                    :where
                    [?parent :child ?child]])

          r2 (sq '[:find [(count ?child) ...]
                   :where
                   [?parent :child ?child]])
          r2b (sq '[:find [(count *) ...]
                    :where
                    [?parent :child ?child]])
          r2c (sq '[:find [(count-distinct ?child) ...]
                    :where
                    [?parent :child ?child]])
          r2d (sq '[:find [(count-distinct *) ...]
                    :where
                    [?parent :child ?child]])

          r3 (sq '[:find [(count ?child) (count-distinct ?child)]
                   :where
                   [?parent :child ?child]])
          r3b (sq '[:find [(count *) (count-distinct *)]
                    :where
                    [?parent :child ?child]])]
      
      (is (= 5 r1))
      (is (= 7 r1b))
      (is (= 7 r1c))
      (is (= 7 r1d))

      (is (= [5] r2))
      (is (= [7] r2b))
      (is (= [7] r2c))
      (is (= [7] r2d))

      (is (= [5 7] r3))
      (is (= [7 7] r3b))))

  (deftest test-ag
    (let [st (-> empty-graph (assert-data data))
          sq (fn [query] (q query st))

          r6 (sq '[:find (count ?child)
                   :where
                   [?parent :gender ?f]
                   [?f :label "female"]
                   [?parent :child ?child]])]
      (is (= '[?count-child] (:cols (meta r6))))
      (is (= [[5]] r6)))))

#?(:cljs (run-tests))


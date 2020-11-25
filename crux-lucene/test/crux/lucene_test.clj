(ns crux.lucene-test
  (:require [clojure.test :as t]
            [crux.api :as c]
            [crux.db :as db]
            [crux.fixtures :as fix :refer [*api* submit+await-tx]]
            [crux.fixtures.lucene :as lf]
            [crux.lucene :as l]
            [crux.rocksdb :as rocks])
  (:import org.apache.lucene.document.Document))

(t/use-fixtures :each lf/with-lucene-module fix/with-node)

(t/deftest test-can-search-string
  (let [doc {:crux.db/id :ivan :name "Ivan"}]
    (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Ivan"}]])

    (t/testing "using Lucene directly"
      (with-open [search-results ^crux.api.ICursor (l/search (:crux.lucene/lucene-store @(:!system *api*)) "name" "Ivan")]
        (let [docs (iterator-seq search-results)]
          (t/is (= 1 (count docs)))
          (t/is (= "Ivan" (.get ^Document (ffirst docs) "_val"))))))

    (t/testing "using in-built function"
      (with-open [db (c/open-db *api*)]
        (t/is (= #{[:ivan]} (c/q db {:find '[?e]
                                     :where '[[(text-search :name "Ivan") [[?e]]]
                                              [?e :crux.db/id]]})))

        (t/testing "bad spec"
          (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #""
                                  (c/q db {:find '[?e]
                                           :where '[[(text-search "Wot" "Ivan") [[?e]]]
                                                    [?e :crux.db/id]]}))))

        (t/testing "fuzzy"
          (t/is (= #{[:ivan]} (c/q db {:find '[?e]
                                       :where '[[(text-search :name "Iv*") [[?e]]]
                                                [?e :crux.db/id]]}))))))

    (t/testing "Subsequent tx/doc"
      (with-open [before-db (c/open-db *api*)]
        (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan2 :name "Ivbn"}]])
        (let [q {:find '[?e] :where '[[(text-search :name "Iv?n") [[?e]]] [?e :crux.db/id]]}]
          (t/is (= #{[:ivan]} (c/q before-db q)))
          (with-open [db (c/open-db *api*)]
            (t/is (= #{[:ivan] [:ivan2]} (c/q db q)))))))

    (t/testing "Modifying doc"
      (with-open [before-db (c/open-db *api*)]
        (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Derek"}]])
        (let [q {:find '[?e] :where '[[(text-search :name "Derek") [[?e]]] [?e :crux.db/id]]}]
          (t/is (not (seq (c/q before-db q))))
          (with-open [db (c/open-db *api*)]
            (t/is (= #{[:ivan]} (c/q db q)))))))

    (t/testing "Eviction"
      (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan2 :name "Derek"}]])
      (submit+await-tx [[:crux.tx/evict :ivan]])
      (with-open [db (c/open-db *api*)]
        (t/is (empty? (c/q db {:find '[?e]
                               :where
                               '[[(text-search :name "Ivan") [[?e]]]
                                 [?e :crux.db/id]]}))))
      (with-open [search-results ^crux.api.ICursor (l/search (:crux.lucene/lucene-store @(:!system *api*)) "name" "Ivan")]
        (t/is (empty? (iterator-seq search-results))))
      (with-open [search-results ^crux.api.ICursor (l/search (:crux.lucene/lucene-store @(:!system *api*)) "name" "Derek")]
        (t/is (seq (iterator-seq search-results)))))

    (t/testing "Scores"
      (submit+await-tx [[:crux.tx/put {:crux.db/id "test0" :name "ivon"}]])
      (submit+await-tx [[:crux.tx/put {:crux.db/id "test1" :name "ivan"}]])
      (submit+await-tx [[:crux.tx/put {:crux.db/id "test2" :name "testivantest"}]])
      (submit+await-tx [[:crux.tx/put {:crux.db/id "test3" :name "testing"}]])
      (submit+await-tx [[:crux.tx/put {:crux.db/id "test4" :name "ivanpost"}]])
      (with-open [db (c/open-db *api*)]
        (t/is (= #{["test1" "ivan" 1.0] ["test4" "ivanpost" 1.0]}
                 (c/q db {:find '[?e ?v ?score]
                          :where '[[(text-search :name "ivan*") [[?e ?v ?score]]]
                                   [?e :crux.db/id]]})))))

    (t/testing "cardinality many"
      (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :foo #{"atar" "abar" "nomatch"}}]])

      (with-open [db (c/open-db *api*)]
        (t/is (= #{[:ivan "atar"]}
                 (c/q db {:find '[?e ?v]
                          :where '[[(text-search :foo "atar") [[?e ?v]]]
                                   [?e :crux.db/id]]}))))

      (with-open [db (c/open-db *api*)]
        (t/is (= #{[:ivan "abar"]
                   [:ivan "atar"]}
                 (c/q db {:find '[?e ?v]
                          :where '[[(text-search :foo "a?ar") [[?e ?v]]]
                                   [?e :crux.db/id]]})))))))

(t/deftest test-can-search-string-across-attributes
  (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Ivan"}]])

  (with-open [db (c/open-db *api*)]
    (t/testing "dont specify A"
      (t/is (= #{[:ivan "Ivan" :name]}
               (c/q db {:find '[?e ?v ?a]
                        :where '[[(wildcard-text-search "Ivan") [[?e ?v ?a]]]
                                 [?e :crux.db/id]]}))))

    (t/testing "no match against a non-existant field"
      (t/is (= #{}
               (c/q db {:find '[?e ?v]
                        :where '[[(text-search :non-field "Ivan") [[?e ?v]]]
                                 [?e :crux.db/id]]})))))

  (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Ivan" :surname "Ivan"}]])

  (t/testing "can find multiple a/vs"
    (with-open [db (c/open-db *api*)]
      (t/is (= #{[:ivan "Ivan" :name]
                 [:ivan "Ivan" :surname]}
               (c/q db {:find '[?e ?v ?a]
                        :where '[[(wildcard-text-search "Ivan") [[?e ?v ?a _]]]
                                 [?e :crux.db/id]]}))))))

;; Leaving to document when score is impacted by accumulated temporal data
#_(t/deftest test-scoring-shouldnt-be-impacted-by-non-matched-past-docs
  (submit+await-tx [[:crux.tx/put {:crux.db/id :real-ivan :name "Ivan Bob"}]])
  (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan-dave :name "Ivan Dave Ivan"}]])

  (let [q {:find '[?v ?score]
           :where '[[(text-search "Ivan" :name) [[?e ?v ?a ?score]]]
                    [?e :crux.db/id]]}

        prior-score (with-open [db (c/open-db *api*)]
                      (c/q db q))]

    (doseq [n (range 10)]
      (submit+await-tx [[:crux.tx/put {:crux.db/id (str "id-" n) :name "NO MATCH"}]])
      (submit+await-tx [[:crux.tx/delete (str "id-" n)]]))

    (with-open [db (c/open-db *api*)]
      (t/is (= prior-score (c/q db q))))))

;; Leaving to document when score is impacted by accumulated temporal data
#_(t/deftest test-scoring-shouldnt-be-impacted-by-matched-past-docs
  (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan" :name "Ivan Bob Bob"}]])

  (let [q {:find '[?e ?v ?s]
           :where '[[(text-search "Ivan" :name) [[?e ?v ?a ?s]]]
                    [?e :crux.db/id]]}
        prior-score (with-open [db (c/open-db *api*)]
                      (c/q db q))]

    (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan1" :name "Ivan"}]])
    (submit+await-tx [[:crux.tx/delete "ivan1"]])

    (with-open [db (c/open-db *api*)]
      (t/is (= prior-score (c/q db q))))))

(t/deftest test-structural-sharing
  (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan" :name "Ivan"}]])
  (let [q {:find '[?e ?v ?s]
           :where '[[(text-search :name "Ivan") [[?e ?v ?s]]]
                    [?e :crux.db/id]]}
        prior-score (with-open [db (c/open-db *api*)]
                      (c/q db q))]

    (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan" :name "Ivan"}]])
    (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan" :name "Ivan"}]])

    (t/is (= 2 (l/doc-count)))

    (with-open [db (c/open-db *api*)]
      (t/is (= prior-score (c/q db q))))))

(t/deftest test-keyword-ids
  (submit+await-tx [[:crux.tx/put {:crux.db/id :real-ivan-2 :name "Ivan Bob"}]])
  (with-open [db (c/open-db *api*)]
    (t/is (seq (c/q db {:find '[?e ?v]
                        :where '[[(text-search :name "Ivan") [[?e ?v]]]
                                 [?e :crux.db/id]]})))))

(t/deftest test-past-fuzzy-results-excluded
  (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan0" :name "Ivan"}]])
  (submit+await-tx [[:crux.tx/delete "ivan0"]])
  (submit+await-tx [[:crux.tx/put {:crux.db/id "ivan1" :name "Ivana"}]])

  (let [q {:find '[?e ?v ?s]
           :where '[[(text-search :name "Ivan*") [[?e ?v ?s]]]
                    [?e :crux.db/id]]}]
    (with-open [db (c/open-db *api*)]
      (t/is (= ["ivan1"] (map first (c/q db q)))))))

(t/deftest test-exclude-future-results
  (let [q {:find '[?e] :where '[[(text-search :name "Ivan") [[?e]]] [?e :crux.db/id]]}]
    (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Ivanka"}]])
    (with-open [before-db (c/open-db *api*)]
      (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Ivan"}]])
      (t/is (empty? (c/q before-db q))))))

(t/deftest test-ensure-lucene-store-keeps-last-tx
  (let [latest-tx (fn [] (l/latest-submitted-tx (:crux.lucene/lucene-store @(:!system *api*))))]
    (t/is (not (latest-tx)))
    (submit+await-tx [[:crux.tx/put {:crux.db/id :ivan :name "Ivank"}]])

    (t/is (latest-tx))))

(t/deftest test-ensure-lucene-store-keeps-up
  (fix/with-tmp-dir "rocks" [rocks-tmp-dir]
    (fix/with-tmp-dir "lucene" [lucene-tmp-dir]
      (with-open [node (c/start-node {:crux/index-store {:kv-store {:crux/module `rocks/->kv-store
                                                                    :db-dir rocks-tmp-dir}}})]
        (submit+await-tx node [[:crux.tx/put {:crux.db/id :ivan :name "Ivan"}]]))

      (try
        (with-open [node (c/start-node {:crux/index-store {:kv-store {:crux/module `rocks/->kv-store
                                                                      :db-dir rocks-tmp-dir}}
                                        :crux.lucene/lucene-store {:db-dir lucene-tmp-dir}})])
        (t/is false "Exception expected")
        (catch Exception t
          (t/is (= "Lucene store latest tx mismatch" (ex-message (ex-cause t)))))))))

(t/deftest test-namespaced-attributes
  (submit+await-tx [[:crux.tx/put {:crux.db/id :real-ivan-2 :myns/name "Ivan Bob"}]])
  (with-open [db (c/open-db *api*)]
    (t/is (seq (c/q db {:find '[?e ?v]
                        :where '[[(text-search :myns/name "Ivan") [[?e ?v]]]
                                 [?e :crux.db/id]]})))))

(comment
  (do
    (import '[ch.qos.logback.classic Level Logger]
            'org.slf4j.LoggerFactory)
    (.setLevel ^Logger (LoggerFactory/getLogger "crux.lucene") (Level/valueOf "INFO"))))

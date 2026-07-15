(ns finishing.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db + langchain-store) store satisfy the
  same contract is what makes 'swap the SSoT for Datomic / kotoba-server'
  a configuration change, not a rewrite -- see `demolition.store-
  contract-test` for the same pattern on the sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [finishing.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Heights Apartments -- Interior Plastering & Painting" (:name (store/site s "site-1"))))
      (is (= "JPN" (:jurisdiction (store/site s "site-1"))))
      (is (true? (:site-verified? (store/site s "site-1"))))
      (is (true? (:hazmat-survey-completed? (store/site s "site-1"))))
      (is (false? (:safety-concern-unresolved? (store/site s "site-1"))))
      (is (nil? (:scaffold-working-height-m (store/site s "site-1"))))
      (is (false? (:site-verified? (store/site s "site-3"))) "site-3 seeded not-yet-verified")
      (is (false? (:hazmat-survey-completed? (store/site s "site-4"))) "site-4 seeded with an incomplete hazmat survey")
      (is (= 3.5 (:scaffold-working-height-m (store/site s "site-5"))) "site-5 seeded above JPN's fall-protection trigger")
      (is (false? (:fall-protection-installed? (store/site s "site-5"))) "site-5 seeded with no fall protection installed")
      (is (true? (:safety-concern-unresolved? (store/site s "site-6"))) "site-6 seeded with an unresolved concern")
      (is (= "USA" (:jurisdiction (store/site s "site-7"))))
      (is (= "DEU" (:jurisdiction (store/site s "site-8"))))
      (is (= ["site-1" "site-2" "site-3" "site-4" "site-5" "site-6" "site-7" "site-8"]
             (mapv :id (store/all-sites s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/site-record-log-history s)))
      (is (= [] (store/schedule-proposal-history s)))
      (is (= [] (store/safety-concern-flag-history s)))
      (is (= [] (store/supply-order-proposal-history s)))
      (is (zero? (store/next-site-record-sequence s "JPN")))
      (is (zero? (store/next-schedule-sequence s "JPN")))
      (is (zero? (store/next-safety-concern-sequence s "JPN")))
      (is (zero? (store/next-supply-order-sequence s "JPN")))
      (is (= 2 (count (:safety-contacts (store/site s "site-1"))))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "log-site-record commits a patch and appends a site-record-log entry"
        (store/commit-record! s {:op :log-site-record :path ["site-1"]
                                 :value {:id "site-1" :hazmat-detected? true}})
        (is (true? (:hazmat-detected? (store/site s "site-1"))))
        (is (= "Sakura Heights Apartments -- Interior Plastering & Painting" (:name (store/site s "site-1"))) "unrelated field preserved")
        (is (= "JPN-SRL-000000" (get (first (store/site-record-log-history s)) "record_id")))
        (is (= 1 (store/next-site-record-sequence s "JPN"))))
      (testing "schedule-finishing-operation drafts a record and advances the sequence"
        (store/commit-record! s {:op :schedule-finishing-operation :path ["site-1"]
                                 :value {:site-id "site-1" :window {}}})
        (is (= "JPN-SCH-000000" (get (first (store/schedule-proposal-history s)) "record_id")))
        (is (= "schedule-proposal-draft" (get (first (store/schedule-proposal-history s)) "kind")))
        (is (= 1 (store/next-schedule-sequence s "JPN"))))
      (testing "flag-safety-concern sets safety-concern-unresolved? true, drafts a record + notice document"
        (store/commit-record! s {:op :flag-safety-concern :path ["site-1"]
                                 :value {:site-id "site-1" :concern-description "test crack"}})
        (is (true? (:safety-concern-unresolved? (store/site s "site-1"))))
        (is (= "JPN-SCF-000000" (get (first (store/safety-concern-flag-history s)) "record_id")))
        (is (some? (get (first (store/safety-concern-flag-history s)) "document")))
        (is (= 1 (store/next-safety-concern-sequence s "JPN"))))
      (testing "log-site-record can resolve the concern"
        (store/commit-record! s {:op :log-site-record :path ["site-1"]
                                 :value {:id "site-1" :safety-concern-unresolved? false}})
        (is (false? (:safety-concern-unresolved? (store/site s "site-1"))))
        (is (= 2 (store/next-site-record-sequence s "JPN"))))
      (testing "order-supplies drafts a record and advances the sequence"
        (store/commit-record! s {:op :order-supplies :path ["site-1"]
                                 :value {:site-id "site-1" :items ["paint"] :cost-usd 500}})
        (is (= "JPN-SUP-000000" (get (first (store/supply-order-proposal-history s)) "record_id")))
        (is (= "supply-order-proposal-draft" (get (first (store/supply-order-proposal-history s)) "kind")))
        (is (= 1 (store/next-supply-order-sequence s "JPN"))))
      (testing "sequences are jurisdiction-scoped, not global"
        (store/commit-record! s {:op :log-site-record :path ["site-7"]
                                 :value {:id "site-7" :hazmat-detected? true}})
        (is (= "USA-SRL-000000" (get (last (store/site-record-log-history s)) "record_id"))
            "USA gets its own independent sequence starting at 0, not continuing JPN's"))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/site s "nope")))
    (is (= [] (store/all-sites s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/site-record-log-history s)))
    (is (zero? (store/next-site-record-sequence s "JPN")))
    (store/with-sites s {"x" {:id "x" :name "n" :jurisdiction "JPN"
                              :site-verified? true :hazmat-survey-completed? true
                              :safety-concern-unresolved? false :scaffold-working-height-m nil
                              :fall-protection-installed? nil :status :intake}})
    (is (= "n" (:name (store/site s "x"))))
    (is (true? (:site-verified? (store/site s "x"))))))

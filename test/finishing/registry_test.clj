(ns finishing.registry-test
  (:require [clojure.test :refer [deftest is]]
            [finishing.registry :as r]))

;; ----------------------------- register-site-record -----------------------------

(deftest site-record-assigns-a-number
  (let [result (r/register-site-record "site-1" "JPN" 7)]
    (is (= (get result "site_record_number") "JPN-SRL-000007"))
    (is (= (get-in result ["record" "site_id"]) "site-1"))
    (is (= (get-in result ["record" "kind"]) "site-record-log-entry"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest site-record-validation-rules
  (is (thrown? Exception (r/register-site-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-site-record "site-1" "" 0)))
  (is (thrown? Exception (r/register-site-record "site-1" "JPN" -1))))

;; ----------------------------- register-schedule-proposal -----------------------------

(deftest schedule-proposal-assigns-a-number
  (let [result (r/register-schedule-proposal "site-1" "JPN" 3)]
    (is (= (get result "schedule_number") "JPN-SCH-000003"))
    (is (= (get-in result ["record" "kind"]) "schedule-proposal-draft"))))

(deftest schedule-proposal-validation-rules
  (is (thrown? Exception (r/register-schedule-proposal "" "JPN" 0)))
  (is (thrown? Exception (r/register-schedule-proposal "site-1" "" 0)))
  (is (thrown? Exception (r/register-schedule-proposal "site-1" "JPN" -1))))

;; ----------------------------- register-safety-concern-flag -----------------------------

(deftest safety-concern-flag-assigns-a-number
  (let [result (r/register-safety-concern-flag "site-1" "JPN" 0)]
    (is (= (get result "concern_number") "JPN-SCF-000000"))
    (is (= (get-in result ["record" "kind"]) "safety-concern-flag-draft"))))

(deftest safety-concern-flag-validation-rules
  (is (thrown? Exception (r/register-safety-concern-flag "" "JPN" 0)))
  (is (thrown? Exception (r/register-safety-concern-flag "site-1" "JPN" -1))))

;; ----------------------------- register-supply-order-proposal -----------------------------

(deftest supply-order-proposal-assigns-a-number
  (let [result (r/register-supply-order-proposal "site-1" "JPN" 0)]
    (is (= (get result "order_number") "JPN-SUP-000000"))
    (is (= (get-in result ["record" "kind"]) "supply-order-proposal-draft"))))

(deftest supply-order-proposal-validation-rules
  (is (thrown? Exception (r/register-supply-order-proposal "" "JPN" 0)))
  (is (thrown? Exception (r/register-supply-order-proposal "site-1" "" 0)))
  (is (thrown? Exception (r/register-supply-order-proposal "site-1" "JPN" -1))))

;; ----------------------------- render-safety-concern-notice -----------------------------

(def sample-site
  {:id "site-1" :name "Sakura Heights Apartments -- Interior Plastering & Painting" :jurisdiction "JPN"})

(deftest safety-concern-notice-cites-legal-basis-inline
  (let [doc (r/render-safety-concern-notice sample-site "JPN-SCF-000000" "旧塗膜に鉛塗料の疑いあり")]
    (is (re-find #"JPN-SCF-000000" doc))
    (is (re-find #"石綿障害予防規則" doc))
    (is (re-find #"laws\.e-gov\.go\.jp" doc))
    (is (re-find #"旧塗膜に鉛塗料" doc))
    (is (re-find #"site supervisor" doc) "honestly disclaims structural-completion-sign-off authority")))

(deftest safety-concern-notice-is-honest-about-uncovered-jurisdiction
  (let [doc (r/render-safety-concern-notice (assoc sample-site :jurisdiction "ATL") "ATL-SCF-000000" "test")]
    (is (re-find #"NOT COVERED" doc))))

;; ----------------------------- append -----------------------------

(deftest history-is-append-only
  (let [c1 (r/register-site-record "site-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-site-record "site-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-SRL-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-SRL-000001" (get-in hist2 [1 "record_id"])))))

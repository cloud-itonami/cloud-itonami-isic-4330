(ns finishing.facts-test
  (:require [clojure.test :refer [deftest is]]
            [finishing.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:hazmat-survey-provenance (facts/spec-basis "JPN"))))
  (is (= :quantitative (:threshold-model (facts/spec-basis "JPN"))))
  (is (= 2.0 (:fall-protection-trigger-height-m (facts/spec-basis "JPN")))))

(deftest usa-has-a-spec-basis-with-a-different-numeric-trigger
  (is (= :quantitative (:threshold-model (facts/spec-basis "USA"))))
  (is (= 1.8 (:fall-protection-trigger-height-m (facts/spec-basis "USA")))))

(deftest deu-is-honestly-qualitative-not-fabricated
  (is (= :qualitative (:threshold-model (facts/spec-basis "DEU"))))
  (is (nil? (:fall-protection-trigger-height-m (facts/spec-basis "DEU")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "USA"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["JPN" "USA"] (:covered-jurisdictions report)))))

;; ----------------------------- fall-protection-noncompliant? -----------------------------

(deftest jpn-fall-protection-is-a-real-numeric-recheck
  (is (true? (facts/fall-protection-noncompliant? "JPN" {:scaffold-working-height-m 3.5 :fall-protection-installed? false})))
  (is (false? (facts/fall-protection-noncompliant? "JPN" {:scaffold-working-height-m 3.5 :fall-protection-installed? true})))
  (is (false? (facts/fall-protection-noncompliant? "JPN" {:scaffold-working-height-m 1.0 :fall-protection-installed? false}))))

(deftest usa-fall-protection-uses-its-own-different-numeric-trigger
  (is (true? (facts/fall-protection-noncompliant? "USA" {:scaffold-working-height-m 2.0 :fall-protection-installed? false})))
  (is (false? (facts/fall-protection-noncompliant? "USA" {:scaffold-working-height-m 2.0 :fall-protection-installed? true})))
  (is (false? (facts/fall-protection-noncompliant? "USA" {:scaffold-working-height-m 1.0 :fall-protection-installed? false}))))

(deftest deu-never-gets-a-fabricated-true-false
  (is (= :qualitative (facts/fall-protection-noncompliant? "DEU" {:scaffold-working-height-m 10 :fall-protection-installed? false})))
  (is (= :qualitative (facts/fall-protection-noncompliant? "DEU" {:scaffold-working-height-m 0 :fall-protection-installed? true}))))

(deftest unknown-jurisdiction-returns-nil-not-a-guess
  (is (nil? (facts/fall-protection-noncompliant? "ATL" {:scaffold-working-height-m 10 :fall-protection-installed? false}))))

(deftest non-numeric-actual-never-fires-a-quantitative-hold
  (is (false? (facts/fall-protection-noncompliant? "JPN" {:scaffold-working-height-m nil :fall-protection-installed? false}))))

;; ----------------------------- catalog citation honesty -----------------------------

(deftest jpn-cites-real-hazmat-survey-and-fall-protection-law
  (let [sb (facts/spec-basis "JPN")]
    (is (re-find #"石綿障害予防規則" (:hazmat-survey-basis sb)))
    (is (re-find #"laws\.e-gov\.go\.jp" (:hazmat-survey-provenance sb)))
    (is (re-find #"労働安全衛生規則第518条|作業床" (:fall-protection-basis sb)))
    (is (re-find #"laws\.e-gov\.go\.jp" (:fall-protection-provenance sb)))))

(deftest usa-cites-real-epa-and-osha-law
  (let [sb (facts/spec-basis "USA")]
    (is (re-find #"745" (:hazmat-survey-basis sb)))
    (is (re-find #"ecfr\.gov" (:hazmat-survey-provenance sb)))
    (is (re-find #"1926\.501" (:fall-protection-basis sb)))
    (is (re-find #"osha\.gov" (:fall-protection-provenance sb)))))

(deftest deu-cites-real-eu-directive-and-german-transposition
  (let [sb (facts/spec-basis "DEU")]
    (is (re-find #"2009/148" (:hazmat-survey-basis sb)))
    (is (re-find #"eur-lex\.europa\.eu" (:hazmat-survey-provenance sb)))
    (is (re-find #"TRGS 519|GefStoffV|Gefahrstoffverordnung" (:hazmat-survey-basis sb)))
    (is (re-find #"TRBS 2121" (:fall-protection-basis sb)))
    (is (re-find #"baua\.de" (:fall-protection-provenance sb)))))

(deftest uncovered-jurisdiction-has-no-fabricated-catalog-entry
  (is (nil? (facts/spec-basis "ATL"))))

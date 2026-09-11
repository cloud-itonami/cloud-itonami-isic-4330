(ns finishing.sim
  "Demo driver -- `clojure -M:dev:run`. Walks building-finishing sites
  through a full coordination episode, exercising every governor check:
  log a site record (auto-commits) -> propose a finishing-operation
  schedule for a clean, verified site (AUTO-COMMITS at phase 3, UNLIKE
  the sibling demolition/road-rail actors -- see `finishing.governor`/
  `finishing.phase` ns docstrings) -> flag a safety concern (ALWAYS
  escalates, human approves, safety-concern notice actually 'sent' via
  the mock notifier) -> log the concern's resolution (auto-commits) ->
  order supplies below the cost threshold (AUTO-COMMITS) -> order
  supplies above the cost threshold (escalates, approved) -> then SIX
  HARD holds that never reach a human at all: an uncovered jurisdiction,
  a not-independently-verified site, an incomplete hazmat survey, a
  fall-protection violation, an unresolved safety concern, and an op
  outside the closed four-op allowlist -- then a cross-jurisdiction (USA,
  quantitative, ABOVE the trigger height but compliant because fall
  protection is installed) and a qualitative (DEU/EU, never fabricates a
  numeric trigger) schedule walkthrough. Finally prints the audit ledger
  + every coordination-artifact history."
  (:require [langgraph.graph :as g]
            [finishing.store :as store]
            [finishing.notify :as notify]
            [finishing.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :site-supervisor :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        notifier (notify/mock-notifier)
        actor (op/build db {:notifier notifier})]
    (println "== log-site-record site-1 (progress update, no ground-truth booleans touched) (AUTO-COMMITS) ==")
    (println (exec! actor "t1" {:op :log-site-record :subject "site-1"
                                :patch {:id "site-1" :hazmat-detected? false}} operator))

    (println "== schedule-finishing-operation site-1 (clean, interior work, high confidence -- AUTO-COMMITS at phase 3) ==")
    (println (exec! actor "t2" {:op :schedule-finishing-operation :subject "site-1"
                                :trade :plastering
                                :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-10"}
                                :notes "内装左官・塗装仕上げ"} operator))

    (println "== flag-safety-concern site-1 (ALWAYS escalates -- human approves; safety-concern notice actually sent via mock notifier) ==")
    (let [r (exec! actor "t3" {:op :flag-safety-concern :subject "site-1"
                               :concern-type :materials-hazard
                               :concern-description "旧塗膜に鉛塗料の疑いあり、追加調査が必要。"} operator)]
      (println r)
      (println (approve! actor "t3")))
    (println "-- sent log --")
    (println (notify/sent-log notifier))

    (println "== log-site-record site-1: concern resolved after inspection (AUTO-COMMITS) ==")
    (println (exec! actor "t4" {:op :log-site-record :subject "site-1"
                                :patch {:id "site-1" :safety-concern-unresolved? false}} operator))

    (println "== order-supplies site-1, cost below threshold (AUTO-COMMITS at phase 3) ==")
    (println (exec! actor "t5" {:op :order-supplies :subject "site-1"
                                :items ["interior-paint-20L" "plaster-mix-50kg"]
                                :cost-usd 800 :vendor "Local Building Supply Co."} operator))

    (println "== order-supplies site-1, cost ABOVE threshold (escalates -- human approves) ==")
    (let [r (exec! actor "t6" {:op :order-supplies :subject "site-1"
                               :items ["scissor-lift-rental"] :cost-usd 9000 :vendor "Access Equipment Rentals"} operator)]
      (println r)
      (println (approve! actor "t6")))

    (println "== schedule-finishing-operation site-2 (ATL, no spec-basis -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t7" {:op :schedule-finishing-operation :subject "site-2" :trade :painting :window {}} operator))

    (println "== schedule-finishing-operation site-3 (site-verified? false -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :schedule-finishing-operation :subject "site-3" :trade :tiling :window {}} operator))

    (println "== schedule-finishing-operation site-4 (hazmat-survey-completed? false -> HARD hold) ==")
    (println (exec! actor "t9" {:op :schedule-finishing-operation :subject "site-4" :trade :painting :window {}} operator))

    (println "== schedule-finishing-operation site-5 (JPN, scaffold-working-height-m=3.5 >= 2m trigger, fall-protection-installed?=false -> HARD hold) ==")
    (println (exec! actor "t10" {:op :schedule-finishing-operation :subject "site-5" :trade :painting :window {}} operator))

    (println "== schedule-finishing-operation site-6 (safety-concern-unresolved? true on file -> HARD hold) ==")
    (println (exec! actor "t11" {:op :schedule-finishing-operation :subject "site-6" :trade :glazing :window {}} operator))

    (println "== :direct-equipment-command site-1 (outside the closed 4-op allowlist -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t12" {:op :direct-equipment-command :subject "site-1"} operator))

    (println "== USA (quantitative, 1.8m/6ft trigger) -- schedule-finishing-operation site-7, height=2.4m, fall-protection installed -- compliant (AUTO-COMMITS) ==")
    (println (exec! actor "t13" {:op :schedule-finishing-operation :subject "site-7" :trade :painting
                                 :window {:proposed-start-date "2026-09-01" :proposed-end-date "2026-09-10"}} operator))

    (println "== DEU/EU (qualitative -- no fixed numeric trigger, never fabricated) -- schedule-finishing-operation site-8 (AUTO-COMMITS, confidence clean) ==")
    (println (exec! actor "t14" {:op :schedule-finishing-operation :subject "site-8" :trade :plastering
                                 :window {:proposed-start-date "2026-09-15" :proposed-end-date "2026-09-25"}} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== site-record-log ==")
    (doseq [r (store/site-record-log-history db)] (println r))

    (println "== schedule-proposal history ==")
    (doseq [r (store/schedule-proposal-history db)] (println r))

    (println "== safety-concern-flag history ==")
    (doseq [r (store/safety-concern-flag-history db)] (println (get r "document")))

    (println "== supply-order-proposal history ==")
    (doseq [r (store/supply-order-proposal-history db)] (println r))))

(ns finishing.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-4330`: this
  repo had `docs/index.html` (a product face) but NO operator console and
  no generator at all. This namespace drives the REAL actor stack
  (`finishing.operation` -> `finishing.governor` -> `finishing.phase` ->
  `finishing.store`) and renders what actually came back. Nothing on the
  page is hand-typed telemetry:

    - every site row is `finishing.store/all-sites` on the live store,
      seeded from `finishing.store/demo-data` (site-1..site-8);
    - every ledger row is `finishing.store/ledger`;
    - every HARD-hold rule/detail string is the Finishing Governor's own
      `:violations` output;
    - the phase-gate matrix is read out of `finishing.phase/phases`,
      not transcribed from its docstring;
    - the jurisdiction table is read out of `finishing.facts/catalog`
      plus `finishing.facts/coverage`;
    - the safety-concern notice is the document `finishing.registry/
      render-safety-concern-notice` actually produced, and the send log
      is what `finishing.notify`'s mock transport actually recorded;
    - the approver-attribution disclosure is DERIVED by walking the
      store after a real human approval (see `approver-retention`), so
      it self-corrects if the store ever starts retaining the approver.

  The scenario deliberately exercises all EIGHT of the governor's HARD
  checks. Six of them (`:no-legal-basis`, `:site-not-verified`,
  `:hazmat-survey-incomplete`, `:fall-protection-noncompliant`,
  `:unresolved-safety-concern`, `:unknown-op`) fire against the seeded
  sites with the ordinary mock advisor. The remaining two
  (`:effect-not-propose`, `:forbidden-action-class`) are
  defense-in-depth checks against a COMPROMISED advisor -- the mock
  advisor can never emit them by construction -- so they are exercised
  through the advisor injection seam `finishing.operation/build` already
  exposes (`:advisor`), with a deliberately rogue advisor. That is the
  governor doing its real job against a real proposal, not a mock hold.

  Deterministic: no timestamps, no clock reads, no randomness, and every
  map is iterated in an explicit sort order -- byte-identical across
  reruns (verify by rendering twice and comparing `shasum`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [finishing.advisor :as advisor]
            [finishing.facts :as facts]
            [finishing.governor :as governor]
            [finishing.notify :as notify]
            [finishing.operation :as op]
            [finishing.phase :as phase]
            [finishing.store :as store]
            [langgraph.graph :as g]))

(def ^:private operator
  "The human operator context injected into every run. `:phase` is
  overridden per step so the page can show the phase gate doing real
  work rather than asserting it in prose."
  {:actor-id "op-1" :actor-role :site-supervisor :phase 3})

;; ----------------------------- scenario driver -----------------------------

(defn- step!
  "Runs ONE request through `actor` on its own thread and records what
  actually came back. When `:approval` is supplied AND the actor really
  interrupted (i.e. the governor+phase gate genuinely escalated to a
  human), the run is resumed with that approval -- so an op that
  auto-committed is never mislabelled as human-approved."
  [acc actor tid ph request & [{:keys [approval note]}]]
  (let [r0 (g/run* actor {:request request :context (assoc operator :phase ph)}
                   {:thread-id tid})
        r  (if (and approval (= :interrupted (:status r0)))
             (g/run* actor {:approval approval} {:thread-id tid :resume? true})
             r0)
        st (:state r)]
    (swap! acc conj {:n           (inc (count @acc))
                     :thread      tid
                     :phase       ph
                     :op          (:op request)
                     :subject     (:subject request)
                     :note        note
                     :interrupted? (= :interrupted (:status r0))
                     :approval    approval
                     :disposition (:disposition st)
                     :audit       (vec (:audit st))})
    r))

(defn- rogue-advisor
  "A DELIBERATELY compromised advisor, used only to exercise the
  governor's two defense-in-depth checks (`:effect-not-propose`,
  `:forbidden-action-class`). `finishing.governor`'s ns docstring states
  outright that it re-checks these independently precisely so 'a
  compromised advisor gains nothing by trying' -- this is that claim
  being executed rather than asserted. Everything else about the run is
  the ordinary actor: the governor, the phase gate, the store and the
  ledger are untouched."
  []
  (reify advisor/Advisor
    (-advise [_ st req]
      (case (:rogue req)
        :actuate
        (let [a (store/site st (:subject req))
              sb (facts/spec-basis (:jurisdiction a))]
          {:summary    "足場を自動展開して塗装工程を開始する"
           :rationale  "（改竄された助言者。:effect を :propose 以外にして実作動を試みる）"
           :cites      [(:fall-protection-basis sb) (:fall-protection-provenance sb)]
           :effect     :actuate
           :value      {:site-id (:subject req) :jurisdiction (:jurisdiction a)
                        :spec-basis (:fall-protection-provenance sb)}
           :stake      :schedule-finishing-operation
           :confidence 0.95})

        :forbidden
        {:summary    "リフトを直接操作し、構造完了サインオフを確定する"
         :rationale  "（改竄された助言者。禁止アクションクラスのマーカーを立てて越権を試みる）"
         :cites      [(:subject req)]
         :effect     :propose
         :value      {:site-id (:subject req)
                      :items ["scissor-lift-rental"] :cost-usd 1200 :vendor "Access Equipment Rentals"
                      :trade-equipment-control? true
                      :finalizes-structural-completion-sign-off? true}
         :stake      :order-supplies
         :confidence 0.95}

        (advisor/infer st req)))))

(defn run-demo!
  "Drives a fresh seeded store through nineteen real operations and
  returns `{:db :steps :notifier}`.

  Covered, in order: a phase-1 write that the ROLLOUT PHASE blocks
  (`:phase-disabled` -- a hold, but NOT a governor HARD hold, and the
  page distinguishes them); a phase-2 `:log-site-record` that the phase
  gate escalates and a human approves; a clean phase-3 finishing-
  operation schedule that auto-commits; a `:flag-safety-concern` that
  ALWAYS escalates, is approved, and really fans a notice out over mail
  + phone to site-1's two-contact roster; the schedule attempt that the
  governor now HARD-holds BECAUSE that flag moved the site's own
  ground truth; the follow-up record that clears it; a supply order
  under the cost threshold that auto-commits; one over it that escalates
  and is approved; one over it that escalates and is REJECTED by the
  human (a hold that DID reach a human -- the contrast case); then eight
  distinct HARD holds that never reach a human at all; then the two
  cross-jurisdiction schedules (USA above the 1.8 m trigger but
  compliant because fall protection is installed; DEU/EU where the
  catalog honestly has no numeric trigger and none is fabricated)."
  []
  (let [db       (store/seed-db)
        notifier (notify/mock-notifier)
        actor    (op/build db {:notifier notifier})
        rogue    (op/build db {:notifier notifier :advisor (rogue-advisor)})
        acc      (atom [])
        s!       (partial step! acc actor)]

    ;; --- rollout phase gate (not a governor verdict) ---
    (s! "t01" 1 {:op :schedule-finishing-operation :subject "site-1" :trade :plastering :window {}}
        {:note "phase 1 は :schedule-finishing-operation を書き込み許可していない"})

    ;; --- phase-2 write: phase gate escalates a governor-clean op ---
    (s! "t02" 2 {:op :log-site-record :subject "site-1"
                 :patch {:id "site-1" :hazmat-detected? false}}
        {:approval {:status :approved :by "op-1"}
         :note "phase 2 は :auto が空なので、ガバナーが clean でも人間承認へ回る"})

    ;; --- phase-3 clean lifecycle ---
    (s! "t03" 3 {:op :schedule-finishing-operation :subject "site-1" :trade :plastering
                 :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-10"}
                 :notes "内装左官・塗装仕上げ"}
        {:note "内装仕上げ・足場高さ記録なし・事前調査済 -> phase 3 で自動コミット"})

    (s! "t04" 3 {:op :flag-safety-concern :subject "site-1"
                 :concern-type :materials-hazard
                 :concern-description "旧塗膜に鉛塗料の疑いあり、追加調査が必要。"}
        {:approval {:status :approved :by "op-2"}
         :note "安全性懸念のフラグは全フェーズで必ず人間へ。承認後に通知を実送信"})

    (s! "t05" 3 {:op :schedule-finishing-operation :subject "site-1" :trade :painting :window {}}
        {:note "t04 が現場の :safety-concern-unresolved? を true にしたので、同じ現場が即 HARD hold"})

    (s! "t06" 3 {:op :log-site-record :subject "site-1"
                 :patch {:id "site-1" :safety-concern-unresolved? false}}
        {:note "点検の結果、懸念は解消 -> 自動コミット"})

    (s! "t07" 3 {:op :order-supplies :subject "site-1"
                 :items ["interior-paint-20L" "plaster-mix-50kg"]
                 :cost-usd 800 :vendor "Local Building Supply Co."}
        {:note "コスト閾値 5000 USD 未満 -> 自動コミット"})

    (s! "t08" 3 {:op :order-supplies :subject "site-1"
                 :items ["scissor-lift-rental"] :cost-usd 9000 :vendor "Access Equipment Rentals"}
        {:approval {:status :approved :by "op-2"}
         :note "コスト閾値超過 -> 人間承認（承認された）"})

    (s! "t09" 3 {:op :order-supplies :subject "site-7"
                 :items ["boom-lift-rental" "exterior-paint-200L"]
                 :cost-usd 12000 :vendor "Access Equipment Rentals"}
        {:approval {:status :rejected :by "op-3"}
         :note "コスト閾値超過 -> 人間承認（却下された）。人間に届いた hold の対照例"})

    ;; --- eight HARD governor holds, none of which reaches a human ---
    (s! "t10" 3 {:op :schedule-finishing-operation :subject "site-2" :trade :painting :window {}}
        {:note "ATL は finishing.facts に未登録 -> 要件を創作させない"})

    (s! "t11" 3 {:op :schedule-finishing-operation :subject "site-3" :trade :tiling :window {}}
        {:note "現場記録が未検証、かつ事前調査も未完了（2ルール同時）"})

    (s! "t12" 3 {:op :schedule-finishing-operation :subject "site-4" :trade :painting :window {}}
        {:note "鉛塗料/石綿の事前調査が未完了"})

    (s! "t13" 3 {:op :schedule-finishing-operation :subject "site-5" :trade :painting :window {}}
        {:note "JPN 2.0m トリガーに対し実測 3.5m、墜落防止措置なし（数値を独立再計算）"})

    (s! "t14" 3 {:op :schedule-finishing-operation :subject "site-6" :trade :glazing :window {}}
        {:note "未解決の安全性懸念が現場記録に残っている"})

    (s! "t15" 3 {:op :direct-equipment-command :subject "site-1"}
        {:note "4オペレーション allowlist の外"})

    (step! acc rogue "t16" 3
           {:op :schedule-finishing-operation :subject "site-1" :rogue :actuate
            :trade :painting :window {}}
           {:note "改竄された助言者が :effect :actuate を出した（正規の助言者は出せない）"})

    (step! acc rogue "t17" 3
           {:op :order-supplies :subject "site-1" :rogue :forbidden
            :items ["scissor-lift-rental"] :cost-usd 1200 :vendor "Access Equipment Rentals"}
           {:note "改竄された助言者が機材直接操作/構造完了サインオフ確定のマーカーを立てた"})

    ;; --- cross-jurisdiction ---
    (s! "t18" 3 {:op :schedule-finishing-operation :subject "site-7" :trade :painting
                 :window {:proposed-start-date "2026-09-01" :proposed-end-date "2026-09-10"}}
        {:note "USA 1.8m トリガーを超える 2.4m だが墜落防止措置が設置済 -> 適合、自動コミット"})

    (s! "t19" 3 {:op :schedule-finishing-operation :subject "site-8" :trade :plastering
                 :window {:proposed-start-date "2026-09-15" :proposed-end-date "2026-09-25"}}
        {:note "DEU/EU は :qualitative。固定の数値トリガーを創作せず、通常の信頼度ゲートに委ねる"})

    {:db db :steps @acc :notifier notifier}))

;; ----------------------------- derived measurements -----------------------------

(defn hard-holds
  "The HARD governor holds on the ledger.

  A `:governor-hold` fact with a NON-EMPTY `:basis` is a HARD governor
  violation -- `finishing.governor/hold-fact` fills `:basis` from the
  verdict's `:violations`. A `:governor-hold` with an EMPTY `:basis`ROLLOUT-
  is a rollout-PHASE hold (`:phase-disabled`), and `:approval-rejected`
  is a human's decision -- neither is a HARD governor hold, and neither
  is counted here."
  [ledger]
  (vec (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) ledger)))

(defn- hard-rule-summary
  "rule -> {:rule :count :subjects :detail}, sorted by rule name."
  [ledger]
  (->> (hard-holds ledger)
       (mapcat (fn [f] (map (fn [v] (assoc v :subject (:subject f) :op (:op f)))
                            (:violations f))))
       (group-by :rule)
       (map (fn [[rule vs]]
              {:rule     rule
               :count    (count vs)
               :ops      (vec (sort (distinct (map #(str (:op %)) vs))))
               :subjects (vec (sort (distinct (map :subject vs))))
               :detail   (:detail (first vs))}))
       (sort-by #(name (:rule %)))
       vec))

(defn- approval-facts
  "Every human-in-the-loop fact the runs produced, in step order. These
  live in the graph's `:audit` channel; `:approval-granted` in
  particular is NOT written to the store ledger."
  [steps]
  (vec (for [{:keys [n thread audit]} steps
             f audit
             :when (#{:approval-requested :approval-granted :approval-rejected} (:t f))]
         (assoc f :step n :thread thread))))

(defn approver-retention
  "MEASURES, rather than asserts, where an approving human's id survives.

  `finishing.operation`'s `:request-approval` node writes the approver
  into the commit record at `[:value :approved-by]`. Whether that
  survives depends entirely on what each op's `finishing.store/commit-
  record!` branch does with `:value` -- so this walks the store that the
  scenario actually produced and reports what it finds. If the store is
  ever changed to retain the approver, this section changes with it."
  [db steps]
  (let [granted   (filter #(= :approval-granted (:t %)) (approval-facts steps))
        approvers (vec (sort (distinct (map :by granted))))
        sites-with (vec (sort (keep #(when (contains? % :approved-by) (:id %))
                                    (store/all-sites db))))
        hist-with  (->> [[:site-record-log (store/site-record-log-history db)]
                         [:schedule-proposal (store/schedule-proposal-history db)]
                         [:safety-concern-flag (store/safety-concern-flag-history db)]
                         [:supply-order-proposal (store/supply-order-proposal-history db)]]
                        (filter (fn [[_ h]] (some #(contains? % "approved_by") h)))
                        (mapv first))
        ledger-with (vec (sort (distinct (keep #(when (or (contains? % :by)
                                                          (contains? % :approved-by))
                                                  (str (:t %)))
                                               (store/ledger db)))))]
    {:approvals-granted (count granted)
     :approvers         approvers
     :sites-with-approved-by sites-with
     :histories-with-approver hist-with
     :ledger-facts-with-approver ledger-with}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- yn [v]
  (cond
    (true? v)  "<span class=\"ok\">yes</span>"
    (false? v) "<span class=\"critical\">no</span>"
    :else      "<span class=\"muted\">n/a</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

(defn- table [headers body-rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"))

;; ----------------------------- sections -----------------------------

(defn- last-fact-for [ledger site-id]
  (last (filter #(= (:subject %) site-id) ledger)))

(defn- status-cell [ledger site-id]
  (let [f (last-fact-for ledger site-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) (str "<span class=\"ok\">committed &middot; " (esc (name (:op f))) "</span>")
      (= :approval-rejected (:t f)) "<span class=\"warn\">approver rejected</span>"
      (and (= :governor-hold (:t f)) (seq (:basis f)))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map name (:basis f)))) "</span>")
      (= :governor-hold (:t f)) "<span class=\"warn\">phase hold</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- site-rows [db ledger]
  (for [{:keys [id name jurisdiction trades site-verified? hazmat-survey-completed?
                hazmat-detected? safety-concern-unresolved? scaffold-working-height-m
                fall-protection-installed? safety-contacts status]} (store/all-sites db)]
    (row (code id) (esc name) (code jurisdiction)
         (esc (str/join ", " (map clojure.core/name trades)))
         (yn site-verified?) (yn hazmat-survey-completed?) (yn hazmat-detected?)
         (if (number? scaffold-working-height-m)
           (str "<span class=\"num\">" scaffold-working-height-m "</span>")
           "<span class=\"muted\">n/a</span>")
         (yn fall-protection-installed?)
         (yn safety-concern-unresolved?)
         (str "<span class=\"num\">" (count safety-contacts) "</span>")
         (code status)
         (status-cell ledger id))))

(defn- jurisdiction-rows []
  (for [iso3 (sort (keys facts/catalog))
        :let [{:keys [name owner-authority threshold-model fall-protection-trigger-height-m
                      hazmat-survey-basis hazmat-survey-provenance
                      fall-protection-basis fall-protection-provenance]} (facts/catalog iso3)]]
    (row (code iso3) (esc name) (esc owner-authority)
         (str "<span class=\"" (if (= :quantitative threshold-model) "ok" "warn") "\">"
              (esc (clojure.core/name threshold-model)) "</span>")
         (if fall-protection-trigger-height-m
           (str "<span class=\"num\">" fall-protection-trigger-height-m " m</span>")
           "<span class=\"muted\">数値トリガーなし（創作しない）</span>")
         (str (esc hazmat-survey-basis) "<br><a href=\"" (esc hazmat-survey-provenance) "\">"
              (esc hazmat-survey-provenance) "</a>")
         (str (esc fall-protection-basis) "<br><a href=\"" (esc fall-protection-provenance) "\">"
              (esc fall-protection-provenance) "</a>"))))

(defn- phase-rows []
  (for [ph (sort (keys phase/phases))
        :let [{:keys [label writes auto]} (get phase/phases ph)]]
    (row (str "<span class=\"num\">" ph "</span>") (code label)
         (if (seq writes)
           (str/join " " (map #(code (str %)) (sort-by str writes)))
           "<span class=\"muted\">（書き込みなし）</span>")
         (if (seq auto)
           (str/join " " (map #(code (str %)) (sort-by str auto)))
           "<span class=\"muted\">（自動コミットなし）</span>"))))

(defn- step-rows [steps]
  (for [{:keys [n thread phase op subject note disposition interrupted? approval audit]} steps
        :let [hold (last (filter #(= :governor-hold (:t %)) audit))
              hard? (and hold (seq (:basis hold)))]]
    (row (str "<span class=\"num\">" n "</span>") (code thread)
         (str "<span class=\"num\">" phase "</span>")
         (code op) (code subject)
         (cond
           hard? (str "<span class=\"critical\">HARD hold</span>")
           (and hold (:phase-reason hold))
           (str "<span class=\"warn\">phase hold &middot; " (esc (name (:phase-reason hold))) "</span>")
           (= :hold disposition) "<span class=\"warn\">hold</span>"
           (= :commit disposition) (if interrupted?
                                     "<span class=\"ok\">approved &amp; committed</span>"
                                     "<span class=\"ok\">auto-committed</span>")
           :else "<span class=\"muted\">-</span>")
         (cond
           hard? (esc (str/join ", " (map name (:basis hold))))
           (some #(= :approval-rejected (:t %)) audit)
           (str "approver <code>" (esc (:by approval)) "</code> rejected")
           interrupted?
           (str "escalated &rarr; approved by <code>" (esc (:by approval)) "</code>")
           :else "<span class=\"muted\">-</span>")
         (esc note))))

(defn- hard-rule-rows [ledger]
  (for [{:keys [rule count ops subjects detail]} (hard-rule-summary ledger)]
    (row (code rule) (str "<span class=\"num\">" count "</span>")
         (str/join " " (map #(str "<code>" (esc %) "</code>") ops))
         (str/join " " (map #(str "<code>" (esc %) "</code>") subjects))
         (esc detail))))

(defn- ledger-rows [ledger]
  (for [{:keys [t op actor subject disposition basis confidence phase-reason]} ledger]
    (row (case t
           :committed "<span class=\"ok\">committed</span>"
           :governor-hold (if (seq basis)
                            "<span class=\"critical\">governor-hold</span>"
                            "<span class=\"warn\">governor-hold</span>")
           :approval-rejected "<span class=\"warn\">approval-rejected</span>"
           (esc (str t)))
         (code op) (code actor) (code subject)
         (esc (name (or disposition :n-a)))
         (cond
           (and (seq basis) (keyword? (first basis)))
           (str/join " " (map #(str "<code>" (esc (name %)) "</code>") basis))
           (seq basis) "<span class=\"muted\">（法的根拠を引用してコミット）</span>"
           phase-reason (str "<code>" (esc (name phase-reason)) "</code>")
           :else "<span class=\"muted\">-</span>")
         (if (number? confidence)
           (str "<span class=\"num\">" confidence "</span>")
           "<span class=\"muted\">-</span>"))))

(defn- approval-rows [steps]
  (for [{:keys [t step thread op subject reason by phase confidence]} (approval-facts steps)]
    (row (str "<span class=\"num\">" step "</span>") (code thread)
         (case t
           :approval-requested "<span class=\"warn\">approval-requested</span>"
           :approval-granted "<span class=\"ok\">approval-granted</span>"
           :approval-rejected "<span class=\"critical\">approval-rejected</span>"
           (esc (str t)))
         (code op) (code subject)
         (if reason (code reason) "<span class=\"muted\">-</span>")
         (if by (code by) "<span class=\"muted\">-</span>")
         (if phase (str "<span class=\"num\">" phase "</span>") "<span class=\"muted\">-</span>")
         (if (number? confidence)
           (str "<span class=\"num\">" confidence "</span>")
           "<span class=\"muted\">-</span>"))))

(defn- history-rows [db]
  (for [[label recs] [["site-record-log" (store/site-record-log-history db)]
                      ["schedule-proposal" (store/schedule-proposal-history db)]
                      ["safety-concern-flag" (store/safety-concern-flag-history db)]
                      ["supply-order-proposal" (store/supply-order-proposal-history db)]]
        r recs]
    (row (esc label) (code (get r "record_id")) (esc (get r "kind"))
         (code (get r "site_id")) (code (get r "jurisdiction"))
         (yn (get r "immutable")))))

(defn- notice-block [db]
  (let [docs (keep #(get % "document") (store/safety-concern-flag-history db))]
    (str/join "\n"
              (for [d docs]
                (str "    <pre>" (esc d) "</pre>\n")))))

(defn- sent-rows [notifier]
  (for [{:keys [status channel to subject]} (notify/sent-log notifier)]
    (row (if (= :sent status) "<span class=\"ok\">sent</span>"
             (str "<span class=\"critical\">" (esc (name status)) "</span>"))
         (code channel) (code to)
         (if subject (esc subject) "<span class=\"muted\">（電話：本文のみ）</span>"))))

(defn- approver-block [db steps]
  (let [{:keys [approvals-granted approvers sites-with-approved-by
                histories-with-approver ledger-facts-with-approver]} (approver-retention db steps)]
    (str
     "    <ul>\n"
     "      <li>この実行で人間が承認した回数: <span class=\"num\">" approvals-granted
     "</span>（承認者 "
     (if (seq approvers)
       (str/join "、" (map #(str "<code>" (esc %) "</code>") approvers))
       "なし") "）</li>\n"
     "      <li>承認者IDが残った現場レコード（<code>:approved-by</code>）: "
     (if (seq sites-with-approved-by)
       (str/join " " (map #(str "<code>" (esc %) "</code>") sites-with-approved-by))
       "<span class=\"critical\">なし</span>") "</li>\n"
     "      <li>承認者IDが残った台帳ファクト: "
     (if (seq ledger-facts-with-approver)
       (str/join " " (map #(str "<code>" (esc %) "</code>") ledger-facts-with-approver))
       "<span class=\"critical\">なし</span>") "</li>\n"
     "      <li>承認者IDが残った調整成果物の履歴: "
     (if (seq histories-with-approver)
       (str/join " " (map #(str "<code>" (esc (name %)) "</code>") histories-with-approver))
       "<span class=\"critical\">なし</span>") "</li>\n"
     "    </ul>\n"
     "    <p>"
     (if (and (pos? approvals-granted)
              (empty? ledger-facts-with-approver)
              (empty? histories-with-approver))
       (str "上の測定が示すとおり、<strong>承認者IDは監査台帳にも調整成果物の履歴にも保存されていない</strong>。"
            "<code>finishing.operation</code> の <code>:request-approval</code> ノードは承認者を "
            "<code>[:value :approved-by]</code> に確かに書き込むが、"
            "<code>finishing.store/commit-record!</code> は op ごとに <code>:value</code> の扱いが違う —— "
            (if (seq sites-with-approved-by)
              (str "<code>:log-site-record</code> だけは <code>:value</code> 全体を現場レコードへ merge するため "
                   (str/join "、" (map #(str "<code>" (esc %) "</code>") sites-with-approved-by))
                   " に <code>:approved-by</code> が残っているが、これを読み戻すコードは無い。"
                   "残る3つの op（<code>:schedule-finishing-operation</code> / "
                   "<code>:flag-safety-concern</code> / <code>:order-supplies</code>）は "
                   "<code>:value</code> を破棄するので承認者は完全に失われる。")
              "全ての op が <code>:value</code> の承認者キーを破棄している。")
            "承認者が誰だったかは、実行中の <code>:audit</code> チャネル（上表）にしか存在しない。"
            "これはこのデモコミットでは<strong>修正しない</strong> —— actor の SSoT 意味論を変える"
            "変更であり、デモの生成物に混ぜるべきものではない。上の一覧は実際のストアを"
            "歩いて求めた測定値なので、保存されるようになれば自動的に表示が変わる。")
       (str "この実行では承認者IDが少なくとも1つの永続面に残っている（上の一覧を参照）。"
            "この段落はストアを実際に歩いた測定から生成されており、決め打ちの主張ではない。"))
     "</p>\n")))

;; ----------------------------- document -----------------------------

(defn render
  "Pure: `{:db :steps :notifier}` -> the operator-console HTML string.
  Deterministic -- every map is iterated in an explicit sort order and
  nothing reads a clock."
  [{:keys [db steps notifier]}]
  (let [ledger    (vec (store/ledger db))
        hs        (hard-holds ledger)
        rule-kinds (hard-rule-summary ledger)
        cov       (facts/coverage)]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-4330 &middot; 建築物の仕上げ工事 &middot; Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>建築物の仕上げ工事（ISIC 4330 Building completion and finishing） — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · coordination-only（機材操作・構造完了サインオフは一切行わない）</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行のサマリ</h2>\n"
     "    <p class=\"muted\">下の数値はすべて、このページを生成する過程で実際に "
     "<code>finishing.operation</code> → <code>finishing.governor</code> → "
     "<code>finishing.store</code> を走らせて得たもの。手書きの値は1つも無い。</p>\n"
     (table ["項目" "値"]
            [(row "実行したオペレーション" (str "<span class=\"num\">" (count steps) "</span>"))
             (row "監査台帳のファクト数" (str "<span class=\"num\">" (count ledger) "</span>"))
             (row "HARD hold（人間に届かない差し止め）"
                  (str "<span class=\"critical num\">" (count hs) "</span>"))
             (row "発火した HARD ルールの種類"
                  (str "<span class=\"num\">" (count rule-kinds) "</span> / "
                       (count governor/closed-op-allowlist) " op allowlist &middot; "
                       (str/join " " (map #(code (:rule %)) rule-kinds))))
             (row "人間の承認要求"
                  (str "<span class=\"num\">"
                       (count (filter #(= :approval-requested (:t %)) (approval-facts steps)))
                       "</span>"))
             (row "現場（seed data）" (str "<span class=\"num\">" (count (store/all-sites db)) "</span>"))
             (row "信頼度フロア / 発注コスト閾値"
                  (str "<span class=\"num\">" governor/confidence-floor "</span> / "
                       "<span class=\"num\">" governor/supply-order-cost-threshold-usd "</span> USD"))
             (row "法域カバレッジ（正直な申告）"
                  (str "<span class=\"num\">" (:covered cov) "</span> / "
                       (:requested cov) " &middot; " (esc (str/join ", " (:covered-jurisdictions cov)))))])
     "  </section>\n"

     (section "現場ディレクトリ（SSoT）"
              (str "<code>finishing.store/all-sites</code> をそのまま表にしたもの。"
                   "太字の判定色は governor が独立に再チェックする ground-truth フィールド。"
                   "<code>最終オペレーション</code> 列は監査台帳の最後のファクトから導出。")
              (table ["現場" "名称" "法域" "職種" "検証済" "事前調査完了" "hazmat検出"
                      "作業高さ(m)" "墜落防止設置" "未解決の懸念" "安全連絡先" "状態" "最終オペレーション"]
                     (site-rows db ledger)))

     (section "シナリオ実行ログ（19オペレーション）"
              (str "各行は独立した langgraph スレッド1本＝1オペレーション。"
                   "<code>phase</code> 列はその実行に注入したロールアウトフェーズで、"
                   "同じリクエストでもフェーズが違えば結果が変わることを実演している。")
              (table ["#" "thread" "phase" "op" "現場" "結果" "根拠 / 承認者" "備考"]
                     (step-rows steps)))

     (section (str "HARD hold ルール（" (count rule-kinds) " 種類が実際に発火）")
              (str "Finishing Governor の HARD チェックは人間が上書きできない —— "
                   "これらの hold は <code>:request-approval</code> ノードに到達すらしない。"
                   "<code>:effect-not-propose</code> と <code>:forbidden-action-class</code> は"
                   "正規の助言者が構造上出せないので、<code>finishing.operation/build</code> の "
                   "<code>:advisor</code> 注入シームに改竄された助言者を差し込んで発火させている"
                   "（governor 側は一切の細工なし）。<code>detail</code> は governor 自身が生成した文字列。")
              (table ["ルール" "件数" "op" "対象現場" "governor が返した detail"]
                     (hard-rule-rows ledger)))

     (section "ロールアウトフェーズ・ゲート"
              (str "<code>finishing.phase/phases</code> をそのまま読み出したもの（docstring からの転記ではない）。"
                   "<code>:flag-safety-concern</code> がどのフェーズの自動コミット集合にも入っていないことに注意 —— "
                   "これはロードマップ上の未達項目ではなく恒久的な構造的事実で、"
                   "<code>finishing.governor/high-stakes</code> が独立に同じ不変条件を強制している。")
              (table ["phase" "ラベル" "書き込み可能な op" "自動コミット可能な op"]
                     (phase-rows)))

     (section "法域スペックベース（finishing.facts）"
              (str (esc (:note cov))
                   " 未登録の法域には spec-basis が存在せず、"
                   "スケジュール提案は <code>:no-legal-basis</code> で HARD hold になる（本ページの site-2 = ATL）。")
              (table ["ISO3" "名称" "所管当局" "閾値モデル" "墜落防止トリガー高さ"
                      "事前調査（hazmat survey）の根拠" "墜落防止の根拠"]
                     (jurisdiction-rows)))

     (section "監査台帳（この実行）"
              (str "<code>finishing.store/ledger</code> の全ファクト。追記のみ。"
                   "<span class=\"critical\">赤の governor-hold</span> は HARD 差し止め、"
                   "<span class=\"warn\">黄の governor-hold</span> はフェーズによる差し止め、"
                   "<span class=\"warn\">approval-rejected</span> は人間が却下したもの。")
              (table ["ファクト" "op" "アクター" "現場" "処理" "根拠 / ルール" "信頼度"]
                     (ledger-rows ledger)))

     (section "人間参加（human-in-the-loop）の記録"
              (str "<code>interrupt-before #{:request-approval}</code> により実際に一時停止し、"
                   "承認者が resume したもの。これらのファクトは実行中の <code>:audit</code> チャネルに存在し、"
                   "<strong>監査台帳（上表）には書かれない</strong> —— 次節の測定はここが起点。")
              (table ["#" "thread" "ファクト" "op" "現場" "理由" "承認者" "phase" "信頼度"]
                     (approval-rows steps)))

     (section "承認者の帰属（測定結果、主張ではない）"
              (str "承認者IDがどの永続面に残るかを、実際のストアを歩いて数えた結果。"
                   "決め打ちの記述ではないので、ストアが承認者を保持するようになればこの節は自動的に変わる。")
              (approver-block db steps))

     (section "調整成果物の履歴（append-only）"
              (str "4つの op それぞれが持つ独立の追記専用履歴。record_id は "
                   "<code>finishing.registry</code> が法域スコープの連番から組み立てたもので、"
                   "国際的なチェックディジット標準を創作していない。"
                   "<code>immutable</code> はレコード自身が持つフラグ。")
              (table ["履歴" "record_id" "種別" "現場" "法域" "immutable"]
                     (history-rows db)))

     (section "実際に送信された安全性懸念通知"
              (str "<code>:flag-safety-concern</code> が人間に承認された後、"
                   "<code>finishing.notify</code> のモック transport が実際に記録した送信。"
                   "現場の <code>:safety-contacts</code> 名簿（現場監督・安全管理者）宛に、"
                   "メールと電話の両チャネルへファンアウトされる。職人へ直接指示は出さない。")
              (str (table ["状態" "チャネル" "宛先" "件名"] (sent-rows notifier))
                   "    <h3>送信された通知文書</h3>\n"
                   "    <p class=\"muted\"><code>finishing.registry/render-safety-concern-notice</code> が"
                   "生成した本文そのまま。法域の事前調査根拠をインラインで引用している。</p>\n"
                   (notice-block db)))

     "  <section class=\"card\">\n"
     "    <h2>このアクターがやらないこと</h2>\n"
     "    <p>このアクターは <strong>調整（coordination）専用</strong>で、提案の <code>:effect</code> は"
     " 常に <code>:propose</code> だけ。「コミット」とは調整成果物が台帳に載ったという意味であり、"
     "職人や機材を動かしたという意味ではない。工事用機材の直接操作と構造完了サインオフの確定は"
     "現場監督・建築主事の専権事項で、governor のチェック1〜4がこれを恒久的・構造的な"
     "HARD hold として符号化している（人間の承認でも上書きできない）。</p>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>生成: <code>clojure -M:dev:render-html</code>（<code>finishing.render-html</code>）。"
     "実 actor 実行から生成される決定的な成果物で、同じ seed に対して再実行してもバイト単位で同一。"
     "タイムスタンプ・乱数・時刻読み取りを一切含まない。</p>\n"
     "  <p>スタイル: <a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">"
     "jp-go-digital-design-system</a>（デジタル庁デザインシステム）。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (hard-holds (store/ledger db))]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [html (render result)]
      (spit out html)
      (println "wrote" out
               "(" (count (store/ledger db)) "ledger facts,"
               (count hs) "HARD governor holds,"
               (count (hard-rule-summary (store/ledger db))) "distinct HARD rules,"
               (count (store/schedule-proposal-history db)) "schedule proposals,"
               (count (store/safety-concern-flag-history db)) "safety-concern flags,"
               (count (store/supply-order-proposal-history db)) "supply orders )"))))

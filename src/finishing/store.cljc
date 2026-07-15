(ns finishing.store
  "SSoT for the building-finishing-coordination actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam every
  prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/finishing/store_contract_test.clj), which is the whole point: the
  actor, the Finishing Governor and the audit ledger never know which SSoT
  they run on.

  `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the
  EDN-blob codec, `:db.unique/identity` schema and the seq-keyed
  event-log read/append pattern, instead of hand-rolling `enc`/`dec*`.

  This actor has FOUR coordination-proposal ops, each with its OWN
  append-only history collection and jurisdiction-scoped sequence
  counter: `:log-site-record` (site-record-log), `:schedule-finishing-
  operation` (schedule-proposal), `:flag-safety-concern` (safety-
  concern-flag) and `:order-supplies` (supply-order-proposal). None of
  these is a one-time double-actuation-guarded real-world event -- every
  op may recur any number of times for the same site (a site gets logged
  repeatedly over its project lifecycle, may be rescheduled across trades,
  may accumulate multiple safety-concern flags, may place multiple supply
  orders) BECAUSE this actor never actually dispatches a trade crew,
  authorizes trade-equipment use, or signs off on structural completion --
  it only ever proposes, logs and schedules (`:effect :propose`
  unconditionally, see `finishing.governor` ns docstring). The site's own
  `:site-verified?` / `:hazmat-survey-completed?` / `:safety-concern-
  unresolved?` / `:scaffold-working-height-m` / `:fall-protection-
  installed?` ground-truth fields are what the Finishing Governor
  independently re-checks before a `:schedule-finishing-operation`
  proposal may ever commit (see `finishing.governor`).

  The ledger stays append-only on every backend: 'which site was logged,
  which finishing-operation schedule was proposed, which safety concern
  was flagged and notified, which supply order was proposed, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log."
  (:require [finishing.registry :as registry]
            [langchain-store.core :as ls]
            [langchain.db :as d]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (ledger [s])
  (site-record-log-history [s] "the append-only site-record-log history (finishing.registry drafts)")
  (schedule-proposal-history [s] "the append-only finishing-operation schedule-proposal history")
  (safety-concern-flag-history [s] "the append-only safety-concern-flag history")
  (supply-order-proposal-history [s] "the append-only supply-order-proposal history")
  (next-site-record-sequence [s jurisdiction])
  (next-schedule-sequence [s jurisdiction])
  (next-safety-concern-sequence [s jurisdiction])
  (next-supply-order-sequence [s jurisdiction])
  (commit-record! [s record] "apply a committed op's PROPOSAL record to the SSoT -- see ns docstring, never a real-world actuation")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site set covering the happy path (site-1, JPN,
  interior finishing -- no scaffold height involved), the uncovered-
  jurisdiction / not-verified / hazmat-survey-incomplete / fall-
  protection-noncompliant / unresolved-safety-concern failure modes
  (site-2..site-6), a cross-jurisdiction (USA) happy path where the site
  is ABOVE the numeric trigger height but compliant because fall
  protection is already installed (site-7), and the honestly-qualitative
  (DEU/EU) jurisdiction that never fabricates a numeric trigger (site-8)
  -- so the actor + tests run offline."
  []
  {:sites
   {"site-1" {:id "site-1" :name "Sakura Heights Apartments -- Interior Plastering & Painting"
              :jurisdiction "JPN" :trades [:plastering :painting]
              :site-verified? true :hazmat-survey-completed? true
              :hazmat-detected? false :safety-concern-unresolved? false
              :scaffold-working-height-m nil :fall-protection-installed? nil
              :safety-contacts [{:name "Tanaka (site supervisor)" :email "tanaka@example.com" :phone "+819000000001"}
                                {:name "Suzuki (safety officer)" :email "suzuki@example.com" :phone "+819000000002"}]
              :status :ready-to-schedule}
    "site-2" {:id "site-2" :name "Atlantis Waterfront Tower -- Exterior Finishing Works"
              :jurisdiction "ATL" :trades [:painting :glazing]
              :site-verified? true :hazmat-survey-completed? true
              :hazmat-detected? false :safety-concern-unresolved? false
              :scaffold-working-height-m 3.0 :fall-protection-installed? true
              :safety-contacts []
              :status :intake}
    "site-3" {:id "site-3" :name "鈴木ビル改修 -- タイル張替工事"
              :jurisdiction "JPN" :trades [:tiling]
              :site-verified? false :hazmat-survey-completed? false
              :hazmat-detected? false :safety-concern-unresolved? false
              :scaffold-working-height-m nil :fall-protection-installed? nil
              :safety-contacts [{:name "Sato (site supervisor)" :email "sato@example.com" :phone "+819000000003"}]
              :status :unverified}
    "site-4" {:id "site-4" :name "田中団地 -- 外壁塗装・改修工事"
              :jurisdiction "JPN" :trades [:painting :finish-carpentry]
              :site-verified? true :hazmat-survey-completed? false
              :hazmat-detected? nil :safety-concern-unresolved? false
              :scaffold-working-height-m 1.0 :fall-protection-installed? false
              :safety-contacts [{:name "Ito (site supervisor)" :email "ito@example.com" :phone "+819000000004"}]
              :status :survey-pending}
    "site-5" {:id "site-5" :name "ことぶき小学校 -- 外壁塗装・足場工事"
              :jurisdiction "JPN" :trades [:painting]
              :site-verified? true :hazmat-survey-completed? true
              :hazmat-detected? false :safety-concern-unresolved? false
              :scaffold-working-height-m 3.5 :fall-protection-installed? false
              :safety-contacts [{:name "Watanabe (site supervisor)" :email "watanabe@example.com" :phone "+819000000005"}]
              :status :fall-protection-pending}
    "site-6" {:id "site-6" :name "山田マンション -- ガラス改修工事"
              :jurisdiction "JPN" :trades [:glazing]
              :site-verified? true :hazmat-survey-completed? true
              :hazmat-detected? true :safety-concern-unresolved? true
              :scaffold-working-height-m nil :fall-protection-installed? nil
              :safety-contacts [{:name "Yamamoto (site supervisor)" :email "yamamoto@example.com" :phone "+819000000006"}
                                {:name "Kobayashi (safety officer)" :email "kobayashi@example.com" :phone "+819000000007"}]
              :status :concern-open}
    "site-7" {:id "site-7" :name "Riverside Plaza -- Exterior Painting & Glazing"
              :jurisdiction "USA" :trades [:painting :glazing]
              :site-verified? true :hazmat-survey-completed? true
              :hazmat-detected? false :safety-concern-unresolved? false
              :scaffold-working-height-m 2.4 :fall-protection-installed? true
              :safety-contacts [{:name "Casey (site supervisor)" :email "casey@example.com" :phone "+15550000002"}]
              :status :ready-to-schedule}
    "site-8" {:id "site-8" :name "Alt-Fabrikgelände -- Innenausbau"
              :jurisdiction "DEU" :trades [:plastering :finish-carpentry]
              :site-verified? true :hazmat-survey-completed? true
              :hazmat-detected? false :safety-concern-unresolved? false
              :scaffold-working-height-m 4.0 :fall-protection-installed? true
              :safety-contacts [{:name "Müller (site supervisor)" :email "mueller@example.com" :phone "+4915000000001"}]
              :status :ready-to-schedule}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- log-site-record!
  [s site-id patch]
  (let [a (site s site-id)
        jurisdiction (or (:jurisdiction patch) (:jurisdiction a) "UNKNOWN")
        seq-n (next-site-record-sequence s jurisdiction)
        result (registry/register-site-record site-id jurisdiction seq-n)]
    {:result result :jurisdiction jurisdiction :site-patch patch}))

(defn- schedule-finishing-operation!
  [s site-id]
  (let [a (site s site-id)
        seq-n (next-schedule-sequence s (:jurisdiction a))
        result (registry/register-schedule-proposal site-id (:jurisdiction a) seq-n)]
    {:result result}))

(defn- flag-safety-concern!
  [s site-id concern-description]
  (let [a (site s site-id)
        seq-n (next-safety-concern-sequence s (:jurisdiction a))
        result (registry/register-safety-concern-flag site-id (:jurisdiction a) seq-n)
        concern-number (get result "concern_number")
        doc (registry/render-safety-concern-notice a concern-number concern-description)]
    {:result (assoc-in result ["record" "document"] doc)
     :site-patch {:safety-concern-unresolved? true}}))

(defn- order-supplies!
  [s site-id]
  (let [a (site s site-id)
        seq-n (next-supply-order-sequence s (:jurisdiction a))
        result (registry/register-supply-order-proposal site-id (:jurisdiction a) seq-n)]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (ledger [_] (:ledger @a))
  (site-record-log-history [_] (:site-record-log @a))
  (schedule-proposal-history [_] (:schedule-proposals @a))
  (safety-concern-flag-history [_] (:safety-concern-flags @a))
  (supply-order-proposal-history [_] (:supply-order-proposals @a))
  (next-site-record-sequence [_ jurisdiction] (get-in @a [:site-record-sequences jurisdiction] 0))
  (next-schedule-sequence [_ jurisdiction] (get-in @a [:schedule-sequences jurisdiction] 0))
  (next-safety-concern-sequence [_ jurisdiction] (get-in @a [:safety-concern-sequences jurisdiction] 0))
  (next-supply-order-sequence [_ jurisdiction] (get-in @a [:supply-order-sequences jurisdiction] 0))
  (commit-record! [s {:keys [op path value]}]
    (let [site-id (first path)]
      (case op
        :log-site-record
        (let [{:keys [result jurisdiction site-patch]} (log-site-record! s site-id value)]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:site-record-sequences jurisdiction] (fnil inc 0))
                         (update-in [:sites site-id] merge site-patch)
                         (update :site-record-log registry/append result))))
          result)

        :schedule-finishing-operation
        (let [{:keys [result]} (schedule-finishing-operation! s site-id)
              jurisdiction (:jurisdiction (site s site-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:schedule-sequences jurisdiction] (fnil inc 0))
                         (update :schedule-proposals registry/append result))))
          result)

        :flag-safety-concern
        (let [{:keys [result site-patch]} (flag-safety-concern! s site-id (:concern-description value))
              jurisdiction (:jurisdiction (site s site-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:safety-concern-sequences jurisdiction] (fnil inc 0))
                         (update-in [:sites site-id] merge site-patch)
                         (update :safety-concern-flags registry/append result))))
          result)

        :order-supplies
        (let [{:keys [result]} (order-supplies! s site-id)
              jurisdiction (:jurisdiction (site s site-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:supply-order-sequences jurisdiction] (fnil inc 0))
                         (update :supply-order-proposals registry/append result))))
          result)

        nil))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn seed-db
  "A MemStore seeded with the demo site set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger []
                           :site-record-sequences {} :site-record-log []
                           :schedule-sequences {} :schedule-proposals []
                           :safety-concern-sequences {} :safety-concern-flags []
                           :supply-order-sequences {} :supply-order-proposals []))))

;; ----------------------------- DatomicStore (langchain.db + langchain-store) -----------------------------

(def ^:private site-spec
  "langchain-store.core field-spec for the `site` entity -- drives
  `map->tx`/`pull->map`/`pull-pattern` from data instead of hand-written
  triples (ADR-2607141600)."
  {:id                             {:attr :site/id}
   :name                           {:attr :site/name}
   :jurisdiction                   {:attr :site/jurisdiction}
   :trades                         {:attr :site/trades-edn :blob? true :default []}
   :site-verified?                 {:attr :site/site-verified? :coerce boolean}
   :hazmat-survey-completed?       {:attr :site/hazmat-survey-completed? :coerce boolean}
   :hazmat-detected?               {:attr :site/hazmat-detected? :coerce boolean}
   :safety-concern-unresolved?     {:attr :site/safety-concern-unresolved? :coerce boolean}
   :scaffold-working-height-m      {:attr :site/scaffold-working-height-m}
   :fall-protection-installed?     {:attr :site/fall-protection-installed? :coerce boolean}
   :safety-contacts                {:attr :site/safety-contacts-edn :blob? true :default []}
   :status                         {:attr :site/status}})

(def ^:private site-pull (ls/pull-pattern site-spec))

(defn- site->tx [m] (ls/map->tx site-spec m))
(defn- pull->site [pulled] (ls/pull->map site-spec :id pulled))

(def ^:private schema
  (ls/identity-schema [:site/id
                       :ledger/seq
                       :site-record-log/seq
                       :schedule-proposal/seq
                       :safety-concern-flag/seq
                       :supply-order-proposal/seq
                       :site-record-sequence/jurisdiction
                       :schedule-sequence/jurisdiction
                       :safety-concern-sequence/jurisdiction
                       :supply-order-sequence/jurisdiction]))

(defrecord DatomicStore [conn]
  Store
  (site [_ id]
    (pull->site (d/pull (d/db conn) site-pull [:site/id id])))
  (all-sites [_]
    (->> (d/q '[:find [?id ...] :where [?e :site/id ?id]] (d/db conn))
         (map #(pull->site (d/pull (d/db conn) site-pull [:site/id %])))
         (sort-by :id)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (site-record-log-history [_] (ls/read-stream conn :site-record-log/seq :site-record-log/record))
  (schedule-proposal-history [_] (ls/read-stream conn :schedule-proposal/seq :schedule-proposal/record))
  (safety-concern-flag-history [_] (ls/read-stream conn :safety-concern-flag/seq :safety-concern-flag/record))
  (supply-order-proposal-history [_] (ls/read-stream conn :supply-order-proposal/seq :supply-order-proposal/record))
  (next-site-record-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :site-record-sequence/jurisdiction ?j] [?e :site-record-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-schedule-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :schedule-sequence/jurisdiction ?j] [?e :schedule-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-safety-concern-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :safety-concern-sequence/jurisdiction ?j] [?e :safety-concern-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-supply-order-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :supply-order-sequence/jurisdiction ?j] [?e :supply-order-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (commit-record! [s {:keys [op path value]}]
    (let [site-id (first path)]
      (case op
        :log-site-record
        (let [{:keys [result jurisdiction site-patch]} (log-site-record! s site-id value)
              next-n (inc (next-site-record-sequence s jurisdiction))]
          (d/transact! conn
                       [(site->tx (assoc site-patch :id site-id))
                        {:site-record-sequence/jurisdiction jurisdiction :site-record-sequence/next next-n}])
          (ls/append-blob! conn :site-record-log/seq :site-record-log/record
                           (count (site-record-log-history s)) (get result "record"))
          result)

        :schedule-finishing-operation
        (let [{:keys [result]} (schedule-finishing-operation! s site-id)
              jurisdiction (:jurisdiction (site s site-id))
              next-n (inc (next-schedule-sequence s jurisdiction))]
          (d/transact! conn [{:schedule-sequence/jurisdiction jurisdiction :schedule-sequence/next next-n}])
          (ls/append-blob! conn :schedule-proposal/seq :schedule-proposal/record
                           (count (schedule-proposal-history s)) (get result "record"))
          result)

        :flag-safety-concern
        (let [{:keys [result site-patch]} (flag-safety-concern! s site-id (:concern-description value))
              jurisdiction (:jurisdiction (site s site-id))
              next-n (inc (next-safety-concern-sequence s jurisdiction))]
          (d/transact! conn
                       [(site->tx (assoc site-patch :id site-id))
                        {:safety-concern-sequence/jurisdiction jurisdiction :safety-concern-sequence/next next-n}])
          (ls/append-blob! conn :safety-concern-flag/seq :safety-concern-flag/record
                           (count (safety-concern-flag-history s)) (get result "record"))
          result)

        :order-supplies
        (let [{:keys [result]} (order-supplies! s site-id)
              jurisdiction (:jurisdiction (site s site-id))
              next-n (inc (next-supply-order-sequence s jurisdiction))]
          (d/transact! conn [{:supply-order-sequence/jurisdiction jurisdiction :supply-order-sequence/next next-n}])
          (ls/append-blob! conn :supply-order-proposal/seq :supply-order-proposal/record
                           (count (supply-order-proposal-history s)) (get result "record"))
          result)

        nil))
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-sites [s sites]
    (when (seq sites) (d/transact! conn (mapv site->tx (vals sites)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:sites
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [sites]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-sites s sites))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo site set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

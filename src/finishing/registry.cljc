(ns finishing.registry
  "Pure-function site-record-log / schedule-proposal / safety-concern-flag
  / supply-order-proposal record construction -- an append-only
  building-finishing-project book-of-record draft, the finishing-domain
  analog of `demolition.registry`/`construction.registry`.

  Like every sibling actor's registry, there is no single international
  check-digit standard for any of these reference numbers -- every
  operator/jurisdiction assigns its own reference format. This namespace
  does NOT invent one; it builds a jurisdiction-scoped sequence number and
  validates the record's required fields, the same honest, non-
  fabricating discipline `finishing.facts` uses.

  `render-safety-concern-notice` produces the actual human-readable
  document text sent to a site's supervisor/safety-officer contact roster
  (`finishing.notify`) -- citing the jurisdiction's pre-work hazmat-survey
  legal basis inline so the notice is self-evidencing about which law
  grounds the concern.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any real regulatory filing system, no mail/phone send. It builds the
  RECORD/DOCUMENT this actor's `:effect :propose`-only proposals produce;
  it never builds, and this actor never proposes, a record that commands
  trade equipment or finalizes a structural-completion sign-off (see
  `finishing.governor` ns docstring)."
  (:require [clojure.string :as str]
            [finishing.facts :as facts]))

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- assert-record-fields! [op-label site-id jurisdiction sequence]
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info (str op-label ": site_id required") {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info (str op-label ": jurisdiction required") {})))
  (when (< sequence 0)
    (throw (ex-info (str op-label ": sequence must be >= 0") {}))))

(defn register-site-record
  "Validate + construct the SITE-RECORD-LOG registration DRAFT -- one
  entry in the append-only log of site trade-progress / material-usage /
  hazmat-assessment data this actor's `:log-site-record` op produces.
  Pure function -- does not verify or register anything itself; it builds
  the RECORD an operator would keep. `finishing.governor` independently
  re-verifies the site's own recorded `:site-verified?` / `:hazmat-
  survey-completed?` ground-truth fields before any OTHER op may commit
  against this site."
  [site-id jurisdiction sequence]
  (assert-record-fields! "site-record" site-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SRL-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "site-record-log-entry"
               "site_id" site-id "jurisdiction" jurisdiction "immutable" true}
     "site_record_number" record-id}))

(defn register-schedule-proposal
  "Validate + construct the FINISHING-OPERATION SCHEDULE-PROPOSAL DRAFT --
  a proposed plastering/painting/glazing/tiling/finish-carpentry schedule
  window, NEVER a trade-equipment-dispatch command or a structural-
  completion sign-off (that authority is the site supervisor / building
  official's exclusively -- see README and `finishing.governor` ns
  docstring). Pure function -- `finishing.governor` independently
  re-verifies the site is verified, its pre-work hazmat survey is
  complete, its jurisdiction's fall-protection requirement is met (or the
  jurisdiction is honestly `:qualitative`), and no safety concern is
  unresolved on file, before this is ever allowed to commit."
  [site-id jurisdiction sequence]
  (assert-record-fields! "schedule-proposal" site-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SCH-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "schedule-proposal-draft"
               "site_id" site-id "jurisdiction" jurisdiction "immutable" true}
     "schedule_number" record-id}))

(defn register-safety-concern-flag
  "Validate + construct the SAFETY-CONCERN-FLAG DRAFT -- surfacing a
  scaffold-safety, materials-hazard (VOC paint fumes, asbestos in old
  finishes) or structural concern for human review. Pure function --
  `:flag-safety-concern` ALWAYS escalates to a human at every phase (see
  `finishing.phase`/`finishing.governor` ns docstrings), so this record
  is only ever committed after a human has reviewed it."
  [site-id jurisdiction sequence]
  (assert-record-fields! "safety-concern-flag" site-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SCF-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "safety-concern-flag-draft"
               "site_id" site-id "jurisdiction" jurisdiction "immutable" true}
     "concern_number" record-id}))

(defn register-supply-order-proposal
  "Validate + construct the SUPPLY-ORDER PROPOSAL DRAFT -- a materials/
  equipment procurement proposal (paint, plaster, tile, glazing, finish-
  carpentry supplies and rental equipment). Pure function -- does not
  place any real order; it builds the RECORD an operator would keep.
  Proposals above a cost threshold, or below the confidence floor, always
  escalate to a human (see `finishing.governor`)."
  [site-id jurisdiction sequence]
  (assert-record-fields! "supply-order-proposal" site-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SUP-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "supply-order-proposal-draft"
               "site_id" site-id "jurisdiction" jurisdiction "immutable" true}
     "order_number" record-id}))

;; ----------------------------- notice document -----------------------------

(defn render-safety-concern-notice
  "Human-readable SAFETY-CONCERN NOTICE document text, citing the
  jurisdiction's pre-work hazmat-survey legal basis inline -- the document
  sent (mail + phone, `finishing.notify`) to the site's supervisor/safety-
  officer contact roster once a human has approved logging the concern.
  `site` is the site record at flag time; `concern-number` is from
  `register-safety-concern-flag`."
  [{:keys [id name jurisdiction]} concern-number concern-description]
  (let [{:keys [hazmat-survey-basis hazmat-survey-provenance owner-authority]} (facts/spec-basis jurisdiction)]
    (str "# Building-Finishing Site Safety-Concern Notice\n\n"
         "Concern number: " concern-number "\n"
         "Site: " name " (" id ")\n"
         "Jurisdiction: " jurisdiction "\n"
         "Relevant authority: " (or owner-authority "n/a") "\n"
         "Related hazmat-survey basis: " (or hazmat-survey-basis "NOT COVERED -- no jurisdiction spec-basis on file") "\n"
         "Source: " (or hazmat-survey-provenance "n/a") "\n\n"
         "## Concern description\n" (or concern-description "(not recorded)") "\n\n"
         "## Status\nThis is a COORDINATION NOTICE only -- it proposes nothing about "
         "trade-equipment dispatch or a structural-completion sign-off. "
         "Resolution and any structural-completion decision remain the site "
         "supervisor / building official's exclusive authority.\n")))

(defn append [history result]
  (conj (vec history) (get result "record")))

(ns finishing.phase
  "Phase 0->3 staged rollout -- the building-completion/finishing-
  coordination analog of `demolition.phase`/`roadrail.phase`.

    Phase 0  read-only              -- no writes, still governor-gated.
    Phase 1  assisted-logging       -- `:log-site-record` allowed, every
                                       write needs human approval.
    Phase 2  assisted-coordination  -- adds `:flag-safety-concern` and
                                       `:order-supplies` writes, still
                                       approval.
    Phase 3  supervised-coordination -- adds `:schedule-finishing-
                                       operation`; governor-clean,
                                       high-confidence `:log-site-record`,
                                       `:schedule-finishing-operation` AND
                                       `:order-supplies` BELOW the cost
                                       threshold may auto-commit.

  ## Actuation (there is none -- read this before changing this file)

  This actor performs NO real-world actuation. Every proposal it can ever
  produce carries `:effect :propose` (see `finishing.governor` ns
  docstring checks 1-4) -- 'committing' a proposal here means only that a
  coordination artifact (a site-record-log entry, a finishing-operation
  schedule PROPOSAL, a safety-concern flag, a supply-order PROPOSAL) is
  now logged in the SSoT + audit ledger. It never dispatches trade
  equipment, never finalizes a structural-completion sign-off -- that
  authority is the site supervisor / building official's exclusively.

  `:flag-safety-concern` is DELIBERATELY ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Surfacing a scaffold-safety/materials-
  hazard/structural concern is exactly the judgment this actor must never
  let auto-commit; `finishing.governor`'s `high-stakes` set enforces the
  same invariant independently -- two layers, not one, agree on this (see
  `finishing.governor` ns docstring).

  UNLIKE `demolition.phase`/`roadrail.phase`, `:schedule-finishing-
  operation` IS a member of phase 3's `:auto` set here -- this is the
  ONE deliberate structural difference from those sibling actors, and it
  mirrors `finishing.governor`'s own `high-stakes` set (which likewise
  does NOT include `:schedule-finishing-operation`). Rationale: scheduling
  a plastering/painting/glazing/tiling/finish-carpentry work window does
  not coordinate potential HEAVY-equipment dispatch near structural-
  collapse / public-traffic / buried-utility risk the way a demolition-
  phase or earthwork/paving/track-laying schedule does -- it is a
  materially lower-stakes coordination artifact, closer in risk profile
  to `:order-supplies` than to a heavy-civil operation schedule. The
  eight HARD governor checks (`finishing.governor` ns docstring) --
  including site verification, pre-work hazmat-survey completeness,
  independent fall-protection recheck and unresolved-concern checks --
  still apply UNCONDITIONALLY regardless of phase or `:auto` membership;
  only the routing to a human vs. auto-commit changes.

  `:log-site-record` (trade-progress/material-usage/hazmat-assessment
  DATA LOGGING, no direct capital or safety risk) and `:order-supplies`
  BELOW the cost threshold (see `finishing.governor/supply-order-cost-
  threshold-usd`) round out phase 3's `:auto` set -- but the governor's
  own cost-threshold/confidence-floor check can still force `:order-
  supplies` to escalate even when it's `:auto`-eligible, the same 'phase
  says maybe, governor decides' layering `demolition.phase` established."
  )

(def read-ops  #{})
(def write-ops #{:log-site-record :schedule-finishing-operation
                 :flag-safety-concern :order-supplies})

;; NOTE the invariant: `:flag-safety-concern` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there -- see ns docstring 'Actuation'
;; section above before changing this.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"                 :writes #{}                                            :auto #{}}
   1 {:label "assisted-logging"          :writes #{:log-site-record}                             :auto #{}}
   2 {:label "assisted-coordination"     :writes #{:log-site-record :flag-safety-concern
                                                    :order-supplies}                              :auto #{}}
   3 {:label "supervised-coordination"   :writes write-ops
      :auto #{:log-site-record :schedule-finishing-operation :order-supplies}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-safety-concern` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't). `:log-site-record`, `:schedule-finishing-
    operation` and `:order-supplies` MAY auto-commit at phase 3 -- see ns
    docstring."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Finishing Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))

(ns finishing.operation
  "OperationActor -- one building-finishing-project coordination operation
  = one supervised actor run, expressed as a langgraph-clj StateGraph. The
  advisor (Finishing Advisor) is sealed into a single node (:advise); its
  proposal is ALWAYS routed through the Finishing Governor (:govern) and
  the rollout phase gate (:decide) before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam) - `store` arg
    - the Advisor  (mock | real LLM)                                       - :advisor opt
    - the Notifier (mock | real, caller-injected transport)                - :notifier opt
    - the Phase    (0->3 rollout)                                          - :phase in ctx

  One graph run = one building-finishing-coordination operation (intake ->
  advise -> govern -> decide -> commit | hold | approval). No unbounded
  inner loop -- each operation is auditable and checkpointed.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to a human operator (the site supervisor). The approver
  resumes with `{:approval {:status :approved}}` (or :rejected).
  `:flag-safety-concern` ALWAYS reaches this node -- see `finishing.
  phase`/`finishing.governor` ns docstrings. `:log-site-record`,
  `:schedule-finishing-operation` and `:order-supplies` (below the cost
  threshold) MAY auto-commit at phase 3 when the governor is clean -- see
  `finishing.phase` ns docstring.

  Every committed record carries `:effect :propose` (see `finishing.
  governor` ns docstring) -- the `:commit` node writes a COORDINATION
  ARTIFACT to the SSoT + audit ledger, NEVER a real-world actuation. ONLY
  when the committed op is `:flag-safety-concern` does `:commit`
  ADDITIONALLY send the safety-concern notice (mail + phone, `finishing.
  notify`) to the site's `:safety-contacts` roster -- always AFTER a
  human has approved it, since `:flag-safety-concern` is never
  auto-eligible at any phase."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [finishing.advisor :as advisor]
            [finishing.governor :as governor]
            [finishing.notify :as notify]
            [finishing.phase :as phase]
            [finishing.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:op      (:op request)
   :effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `finishing.store/Store`).
  opts:
    :advisor      -- a `finishing.advisor/Advisor` (default: mock-advisor)
    :notifier     -- a `finishing.notify/Notifier` (default: mock-notifier)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor notifier checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    notifier     (notify/mock-notifier)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; Finishing Advisor inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; Finishing Governor -- independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before; a human resumes
      ;; with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc-in (commit-record request context proposal)
                               [:value :approved-by] (:by approval))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [{:t :approval-rejected :op (:op request) :subject (:subject request)
                      :disposition :hold :basis [:approver-rejected]}]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger. Every
      ;; committed record carries `:effect :propose` -- see ns docstring.
      ;; For `:flag-safety-concern` specifically, ALSO actually sends the
      ;; safety-concern notice (mail + phone) via the injected Notifier --
      ;; always AFTER a human has approved it (never auto-eligible).
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)
                notify-result (when (= :flag-safety-concern (:op record))
                                (let [site (store/site store (:subject request))]
                                  (notify/dispatch-safety-concern-notice!
                                   notifier (:safety-contacts site) (:value proposal))))]
            (store/append-ledger! store f)
            (cond-> {:audit [f]}
              notify-result (assoc :notify-result notify-result)))))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

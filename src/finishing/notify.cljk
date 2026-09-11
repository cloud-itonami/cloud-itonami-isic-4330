(ns finishing.notify
  "Mail + phone transport for the SAFETY-CONCERN NOTICE this actor sends
  once a human has approved committing a `:flag-safety-concern` proposal
  -- the concrete 呼びかけ／共有 (outreach/sharing) deliverable, the
  building-finishing-domain analog of `demolition.notify`/`roadrail.
  notify`.

  `Notifier` is the injection seam: `mock-notifier` (deterministic, no
  network -- the default everywhere, same as every sibling actor's mock
  advisor) for dev/tests/demo, `fn-notifier` for a real transport.
  `dispatch-safety-concern-notice!` fans the SAME notice out to every
  contact in a site's `:safety-contacts` roster (the site supervisor /
  safety officer, NOT the trade crew directly -- this actor coordinates,
  it does not dispatch a crew) over BOTH channels, isolating one
  contact's send failure from every other contact's.

  UNLIKE `construction.notify`'s auto-committed dispatch, this is NEVER
  fired by an auto-commit -- `:flag-safety-concern` always escalates to a
  human first (see `finishing.phase`/`finishing.governor` ns docstrings),
  so by the time `finishing.operation`'s `:commit` node calls this, a
  human has already reviewed the concern. This namespace builds/sends the
  message; it does NOT decide whether to send one.

  ## Portability -- fully `.cljc`, NO platform interop in this namespace

  Per this repo's mandate (cljs-first / no JVM interop -- see
  superproject CLAUDE.md `.cljc`/`.kotoba` runtime priority note), this
  namespace does NOT embed a `java.net.http` (or any other platform)
  HTTP client, unlike some earlier sibling actors' `notify.cljc`. The
  real transport (Resend/Twilio/etc.) is a plain-function INJECTION seam
  (`fn-notifier`) supplied by the HOST application -- this keeps
  `finishing.notify` itself runnable unmodified on JVM Clojure,
  ClojureScript, `nbb`, and `kotoba wasm`/`clojurewasm` alike; only the
  caller-supplied `:send-mail-fn`/`:send-phone-fn` need be
  platform-specific, and that code lives OUTSIDE this repo."
  )

(defprotocol Notifier
  (-send-mail! [n msg] "msg: {:to :subject :body} -> {:status :channel :to ..}")
  (-send-phone-call! [n msg] "msg: {:to :message} -> {:status :channel :to ..}"))

;; ----------------------------- mock (default) -----------------------------

(defrecord MockNotifier [log]
  Notifier
  (-send-mail! [_ {:keys [to subject body]}]
    (let [result {:status :sent :channel :mail :to to :subject subject :body body}]
      (swap! log conj result)
      result))
  (-send-phone-call! [_ {:keys [to message]}]
    (let [result {:status :sent :channel :phone :to to :message message}]
      (swap! log conj result)
      result)))

(defn mock-notifier
  "A deterministic notifier -- no network, records every send to an
  internal log atom (`sent-log`). Default everywhere (dev/tests/demo)."
  ([] (mock-notifier (atom [])))
  ([log] (->MockNotifier log)))

(defn sent-log [^MockNotifier n] @(:log n))

;; ----------------------------- injectable real transport (portable) -----------------------------

(defrecord FnNotifier [send-mail-fn send-phone-fn]
  Notifier
  (-send-mail! [_ msg] (send-mail-fn msg))
  (-send-phone-call! [_ msg] (send-phone-fn msg)))

(defn fn-notifier
  "A Notifier whose real transport is fully INJECTED by the caller as
  plain functions -- `msg -> {:status :channel :to ..}` (mail) / `msg ->
  {:status :channel :to ..}` (phone). Deliberately does NOT ship a
  default Resend/Twilio implementation itself (that would require
  platform-specific HTTP interop inside this repo -- see ns docstring
  'Portability'). Wire a real transport by supplying `:send-mail-fn` /
  `:send-phone-fn` from the HOST application (JVM `java.net.http`,
  Node/cljs `fetch`, a kotoba-server capability call, etc.)."
  [{:keys [send-mail-fn send-phone-fn]}]
  (->FnNotifier send-mail-fn send-phone-fn))

;; ----------------------------- composition -----------------------------

(defrecord DualNotifier [mail-notifier phone-notifier]
  Notifier
  (-send-mail! [_ msg] (-send-mail! mail-notifier msg))
  (-send-phone-call! [_ msg] (-send-phone-call! phone-notifier msg)))

(defn dual-notifier
  "Compose a mail-only Notifier and a phone-only Notifier into one --
  the shape a safety-concern notice needs (mail と 電話 の両方)."
  [mail-notifier phone-notifier]
  (->DualNotifier mail-notifier phone-notifier))

(defn dispatch-safety-concern-notice!
  "Send the safety-concern-notice message to every contact in
  `safety-contacts` ({:name :email :phone}) via BOTH mail and phone,
  using `notifier`. Returns a vector of per-contact per-channel send
  results. A failed send for one contact/channel is recorded as
  `{:status :failed ...}`, never thrown past this call -- one bad phone
  number or a transient network error must never suppress notifying
  every other responsible party of a flagged safety concern."
  [notifier safety-contacts {:keys [subject-line body message]}]
  (vec
   (for [{:keys [name email phone]} safety-contacts]
     {:contact name
      :mail (try (-send-mail! notifier {:to email :subject subject-line :body body})
                 (catch #?(:clj Exception :cljs :default) e
                   {:status :failed :channel :mail :to email :error #?(:clj (.getMessage e) :cljs (str e))}))
      :phone (try (-send-phone-call! notifier {:to phone :message message})
                  (catch #?(:clj Exception :cljs :default) e
                    {:status :failed :channel :phone :to phone :error #?(:clj (.getMessage e) :cljs (str e))}))})))

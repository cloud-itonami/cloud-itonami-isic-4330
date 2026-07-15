# cloud-itonami-4330

Open Business Blueprint for **ISIC Rev.5 4330**: building completion and
finishing (plastering, painting, glazing, floor/wall tiling, finish
carpentry).

This repository designs a forkable OSS business for building-completion/
finishing-project operations coordination: run by a qualified operator so
a community keeps its own operating records instead of renting a closed
SaaS.

## Scope -- this is a COORDINATION-ONLY actor, not equipment control

This is a safety-relevant domain: scaffold/fall hazards, materials
hazards (VOC paint fumes, asbestos or lead-based paint in older
finishes), structural-completion sign-off. **This actor does NOT hold
trade-equipment-control authority, and it does NOT hold structural-
completion-sign-off authority.** Both are the site supervisor / building
official's exclusive authority, always. The Finishing Advisor (LLM) never
issues a trade-equipment-control command and never finalizes a
structural-completion sign-off; the independent **Finishing Governor**
HARD-blocks any proposal that even tries (un-overridable by any human
approval -- see `finishing.governor` ns docstring). This actor
coordinates *potential* trade-crew/equipment dispatch (a proposed
schedule window, a flagged concern, a supply-order proposal) -- it never
directly actuates.

Structurally, EVERY proposal this actor's advisor can produce carries
`:effect :propose`, and the Finishing Governor HARD-holds any proposal
that doesn't -- this is a permanent invariant distinguishing this actor
from `cloud-itonami-isic-4211` (the robotics-premise reference this actor
follows structurally), whose sibling actuation ops DO commit real-world
effects. `cloud-itonami-isic-4211`'s README robotics-premise framing
therefore does NOT apply verbatim here: this actor is deliberately
narrower.

## Core Contract

```text
site record + independent verification
        |
        v
Advisor -> Finishing Governor -> proceed (log/schedule/flag/order proposal), hold, or human approval
        |
        v
coordination artifacts (schedule proposal, safety-concern flag,
supply-order proposal) + audit ledger -- NEVER trade-equipment dispatch,
NEVER a structural-completion sign-off
```

No automated advice can propose a schedule the governor refuses, suppress
a safety-concern flag, or slip a trade-equipment-control/structural-
completion-sign-off marker past the governor -- and a flagged safety
concern always needs a human sign-off (see `Actuation` below).

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `4330`). Required capabilities:

- `:identity`
- `:forms`
- `:audit-ledger`
- `:notifications`

## Implemented slice (`src/finishing`)

`blueprint.edn` names the governor `:finishing-governor` and is now
`:implemented`. This repo implements it end-to-end -- **Finishing
Advisor ⊣ Finishing Governor** -- following the SAME `.cljc` actor
pattern (langgraph-clj StateGraph, mock-by-default advisor, dual
MemStore/Datomic backend, 0→3 phase rollout) every prior
`cloud-itonami-isic-*` actor in this fleet uses, structured after
[`cloud-itonami-isic-4311`](https://github.com/cloud-itonami/cloud-itonami-isic-4311)
(the demolition-domain coordination-only reference), narrowed to
coordination-only authority as described above and adapted to the lower
physical-risk profile of trade-finishing work (see `Actuation` below for
the one structural difference from that reference).

This repo is fully portable `.cljc` with **no JVM interop anywhere in
`src/`** -- `finishing.notify`'s real-transport seam (`fn-notifier`)
takes caller-injected plain functions instead of embedding a
`java.net.http` client, so the actor runs unmodified on JVM Clojure,
ClojureScript, `nbb`, and `kotoba wasm`/`clojurewasm`.

### Closed op-allowlist (4 ops, all `:effect :propose`)

| Op | Ask | Implementation |
|---|---|---|
| `:log-site-record` | trade-progress / material-usage / hazmat-assessment data logging | Normalizes and commits a patch onto the site's ground-truth fields (`:site-verified?`, `:hazmat-survey-completed?`, `:scaffold-working-height-m`, `:fall-protection-installed?`, concern resolution, etc.) and appends an immutable site-record-log entry. No direct capital/safety risk -- MAY auto-commit at phase 3. |
| `:schedule-finishing-operation` | plastering/painting/glazing/tiling/finish-carpentry scheduling proposal | Drafts a proposed work WINDOW (never a trade-equipment-dispatch command or structural-completion sign-off). Escalates when the governor is not clean or confidence is low; MAY auto-commit at phase 3 when clean and confident -- see `Actuation`. |
| `:flag-safety-concern` | surface a scaffold-safety / materials-hazard (VOC paint fumes, asbestos or lead-based paint in old finishes) / structural concern | Drafts a safety-concern flag; ALWAYS escalates to a human, unconditionally. Once approved, `finishing.notify` sends the notice (mail + phone) to the site's supervisor/safety-officer contact roster. |
| `:order-supplies` | materials/equipment procurement proposal | Drafts a supply-order proposal. Escalates above a cost threshold or below the confidence floor; may auto-commit at phase 3 otherwise. |

**Legal basis is data, not code** -- `src/finishing/facts.cljc`'s
`catalog` is the per-jurisdiction EDN source-of-truth the governor checks
every `:schedule-finishing-operation` proposal against (JPN/USA/DEU
seeded; DEU stands in for the EU, the same convention
`demolition.facts`/`construction.facts`/`aerospace.facts` use for EASA).
Every citation below was independently verified against its official
source before being written:

| Jurisdiction | Pre-work hazmat-survey legal basis | Fall-protection legal basis |
|---|---|---|
| 🇯🇵 Japan | 石綿障害予防規則（平成17年厚生労働省令第21号）第3条 -- [e-Gov](https://laws.e-gov.go.jp/law/417M60000100021) | 労働安全衛生規則第518条（高さ2m以上で作業床の設置義務）-- [e-Gov](https://laws.e-gov.go.jp/law/347M50002000032) |
| 🇺🇸 USA | 40 CFR Part 745, Subpart E (EPA Lead RRP Rule, pre-1978 buildings) -- [eCFR](https://www.ecfr.gov/current/title-40/chapter-I/subchapter-R/part-745/subpart-E) | 29 CFR 1926.501 (OSHA, 6ft/1.8m fall-protection trigger) -- [osha.gov](https://www.osha.gov/laws-regs/regulations/standardnumber/1926/1926.501) |
| 🇪🇺 EU (DEU proxy) | Directive 2009/148/EC Art.11 + GefStoffV/TRGS 519 -- [EUR-Lex](https://eur-lex.europa.eu/eli/dir/2009/148/oj) | TRBS 2121 (qualitative -- risk-assessment-based, no fixed EU-wide numeric trigger) -- [BAuA](https://www.baua.de/DE/Angebote/Regelwerk/TRBS/TRBS-2121) |

Japan (2m) and the USA (6ft/1.8m) have real numeric fall-protection
trigger heights; the EU deliberately does NOT --
`finishing.facts/fall-protection-noncompliant?` reports `:qualitative`
there rather than fabricating a number. See `finishing.facts` ns
docstring for the full honesty discipline.

**Governor -- eight HARD checks, ALL un-overridable by human approval:**
unknown op (outside the closed 4-op allowlist), `:effect` not `:propose`,
forbidden action class (trade-equipment-control / direct-actuation /
structural-completion-sign-off-finalization markers), site not
independently verified/registered, legal-basis missing, pre-work hazmat
survey incomplete, fall-protection noncompliant (quantitative
jurisdictions only), unresolved safety concern on file. See
`finishing.governor` ns docstring for the full enumeration, rationale and
real-law citations behind each.

## Actuation

This actor performs **no real-world actuation** -- every committed
record carries `:effect :propose` (see `finishing.governor` ns
docstring). `:flag-safety-concern` NEVER auto-commits at any phase -- it
always needs a human sign-off, even when the governor is completely
clean (`finishing.phase` ns docstring 'Actuation' section,
`finishing.governor`'s `high-stakes` set).

**One deliberate difference from `cloud-itonami-isic-4311`/`cloud-
itonami-isic-4210`:** `:schedule-finishing-operation` here is NOT a
permanent `high-stakes` member. `:log-site-record`, `:schedule-
finishing-operation` and `:order-supplies` BELOW the cost threshold
(`finishing.governor/supply-order-cost-threshold-usd`) MAY all auto-
commit at phase 3 when the governor is clean and confidence is high.
Plastering/painting/glazing/tiling/finish-carpentry scheduling does not
coordinate potential HEAVY-equipment dispatch near structural-collapse /
public-traffic / buried-utility risk the way demolition or road/rail
earthwork scheduling does -- it is a materially lower-stakes coordination
artifact. The eight HARD governor checks still apply UNCONDITIONALLY
regardless of phase; only the routing to a human vs. auto-commit changes.

```bash
clojure -M:dev:run    # demo: full coordination episode + every HARD hold
clojure -M:dev:test   # test suite
clojure -M:lint       # clj-kondo, errors fail
```

## License

AGPL-3.0-or-later.

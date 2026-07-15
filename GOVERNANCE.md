# Governance

`cloud-itonami-4330` is an OSS open-business blueprint for building-
completion/finishing-project operations coordination -- coordination-only,
never trade-equipment control.

## Maintainers

Maintainers may merge changes that preserve these invariants:
- this actor never holds trade-equipment-control authority.
- this actor never holds structural-completion-sign-off authority -- that
  remains the site supervisor / building official's exclusively.
- every proposal this actor's advisor produces carries `:effect
  :propose`, and the Finishing Governor remains independent of the
  advisor.
- hard policy violations (unknown op, non-`:propose` effect, forbidden
  action class, unverified site, missing legal basis, incomplete pre-work
  hazmat survey, fall-protection noncompliance, unresolved safety
  concern) cannot be overridden by human approval.
- `:flag-safety-concern` always requires human sign-off, at every phase,
  unconditionally.
- every proposal, sign-off, log entry and notification path is
  auditable.
- sensitive operating and personal data stays outside Git.

## Decision Records

Architecture decisions should be documented (an ADR or equivalent) when
changing the trust model, storage contract, closed op-allowlist, business
model, operator certification or license.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, safety, audit and
data-flow review.

Certified operators can lose certification for:
- bypassing the Finishing Governor's hard checks or the closed
  op-allowlist
- attempting to extend this actor's authority into trade-equipment
  control or structural-completion sign-off
- mishandling sensitive data
- misrepresenting certification status
- failing to respond to security or safety incidents

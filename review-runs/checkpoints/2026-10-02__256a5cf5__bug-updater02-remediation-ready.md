# BUG-UPDATER-02 current-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 7c71a7038cf1c00b7c28b39c3214653650e389d0
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 4
primary_lens: L2 Identity & provenance DEEP
supporting_lenses:
- L1 Durability & recovery
- L3 Concurrency & authority
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing hardening to preserve

UpdateUtil already has a useful destination-side generation protocol:
- desired source is paired with a monotonically advanced local generation;
- stale expected generation becomes SUPERSEDED;
- pending source/generation is durably published before native update;
- native failure retains pending debt;
- committed source/generation/result is durably recorded;
- matching pending debt is retired with committed publication.

Restore also has durable journal/recovery authority and ordinary mutation
admission. Those mechanisms should remain the basis of the correction.

The defect is that backup/restore and startup coordination still allow foreign
or unowned state to defeat destination-local convergence.

## Same-root residuals

### Foreign updater provenance

Portable restore filtering does not exclude destination-local updater authority:
- ytdlp_source_generation
- ytdlp_committed_source_generation
- ytdlp_committed_source
- ytdlp_committed_result
- ytdlp_pending_source_generation
- ytdlp_pending_source

Merge/Reset can therefore import source-device runtime proof. Current
committedMatches() can accept restored generation/source equality as if it
proved the destination runtime.

### Restore-first startup recovery gap

App recovery is asynchronous. MainActivity can reach updater startup while a
durable Reset still owns Restore authority.

UpdateUtil ordinary mutation is correctly rejected while Reset owns the gate,
but MainActivity consumes the startup failure and does not establish a durable
or same-process retry owner.

### Active/Queued startup deferral gap

MainActivity invokes updateOnStartup() only when current Active/Queued Download
count is zero. If nonzero, no durable updater deferral/retry owner is created.

A desired/committed mismatch or pending updater debt can therefore survive until
another launch/manual action after the blocking Download becomes idle.

### Updater-first / Restore-second overlap

beginMutation() publishes pending generation/source under the short ordinary
mutation boundary, then releases that admission before the long native updater
operation completes.

A Reset can become authoritative after updater admission but before native
completion. The older updater must not later become destination proof for a
different restored desired source.

All four cells share one invariant: final destination updater provenance must be
owned by the destination's current desired source/generation and must converge
under one recoverable authority ordering.

## Exact invariant

Backup/restore may carry portable user intent but may not import source-device
runtime authority.

For any final committed yt-dlp runtime provenance on the destination:

1. the desired source/generation is destination-local;
2. the native runtime result belongs to that exact desired generation;
3. foreign restored committed/pending/generation carriers cannot satisfy that proof;
4. Restore/updater overlap has one deterministic winner/handoff rule;
5. a rejected or deferred convergence attempt leaves one discoverable retry owner;
6. startup convergence does not depend on another app launch when the blocker
   can clear in the same process.

Same-source restore may preserve valid destination-local committed proof.
Changed-source restore must invalidate/reorder incompatible destination-local
proof and reconcile to the new desired source.

## Narrow implementation boundary

Keep portable user intent:
- ytdlp_source
- ytdlp_source_label

Make destination-local authority nonportable:
- source generation
- committed generation/source/result
- pending generation/source

At restore application:
- filter foreign authority carriers from all supported restore forms;
- compare restored desired source to destination pre-restore desired source;
- if changed, advance/assign destination-local generation and invalidate
  incompatible local proof;
- if unchanged, preserve valid local committed proof rather than forcing needless update.

For startup/recovery:
- order updater convergence after active Restore recovery or establish equivalent
  exact retry ownership after gate release;
- when Download/runtime use blocks update, defer with an exact live/durable owner
  that runs after the blocker clears;
- after BUG-UPDATER-04 introduces shared runtime authority, reuse that authority
  rather than using Active/Queued count as correctness ownership.

For updater-first/Restore-second:
- do not hold Restore admission across unbounded native/network work merely as a global lock;
- define explicit ownership/handoff so an old updater result cannot publish as
  proof for a different restored desired source;
- retain enough pending generation state for restart convergence.

Preserve ordinary selectSource generation ordering and SUPERSEDED semantics.

## Forbidden shortcuts

- importing committed/pending/generation carriers and then "fixing" them later at startup
- clearing all local committed proof on every restore, including unchanged-source restore
- forcing auto-update semantics when auto_update_ytdlp=false
- weakening RestoreMutationAdmission to allow updater mutation during active Reset
- swallowing Restore gate rejection and relying on next app launch
- treating Active/Queued count as durable deferral ownership
- converting every blocked updater into a broad recurring worker without exact generation binding
- allowing an older updater result to commit after Restore changed desired source
- holding unrelated global locks across unbounded native/network update work
- duplicating shared runtime authority instead of reusing BUG-UPDATER-04's final contract

## Acceptance matrix

- new backup omits every destination-local updater authority carrier
- legacy/current/typed restore payload containing those carriers cannot import them
- Merge changed-source restore creates destination-local generation/provenance and
  cannot return ALREADY_UP_TO_DATE from foreign proof
- Reset changed-source restore behaves the same
- auto_update_ytdlp=false still permits required changed-source reconciliation
- same-source restore preserves valid destination-local committed proof and does
  not force needless native update
- active durable Reset blocks updater mutation, but after gate release exactly one
  same-process reconciliation owns the changed desired generation
- no updater native mutation occurs while active Reset authority forbids it
- initial Active/Queued blocker defers reconciliation; after exact blocker
  quiescence, same process performs exactly one convergence attempt
- interrupted pending updater debt remains recoverable through that deferral
- updater A admitted first, Reset then restores source B, A completes later:
  A cannot become committed proof for B
- final committed source/generation exactly matches B
- process death across pending publication / Restore publication / native complete /
  committed publication leaves one discoverable owner
- ordinary source A->B ordering and SUPERSEDED tests remain green
- production-wiring coverage exercises real Restore/startup/update coordinator seams

## Test gap

Existing UpdateUtil tests prove important behavior once updateOnStartup() or the
coordinator is directly invoked, but do not prove the full production entry path
eventually invokes it after Restore rejection or Download deferral.

Current restore tests do not prove source-device updater provenance is excluded
from every supported restore form.

No current focused regression proves the updater-first / Restore-second ordering
with a changed restored desired source.

The root is confirmed and remediation-ready on the exact current basis.

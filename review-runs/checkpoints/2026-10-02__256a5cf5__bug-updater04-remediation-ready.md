# BUG-UPDATER-04 current-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 91f1d17a06f02ae5170df892ec67a170f8abde99
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 4
primary_lens: L3 Concurrency & authority DEEP
supporting_lenses:
- L4 Destructive ownership
- L6 Cross-feature semantic propagation
- L1 Durability & recovery
independent_execution: NOT EXECUTED

## Existing hardening to preserve

Download execution ownership is already materially hardened and should not be
replaced by a weaker updater-specific protocol.

The current Download path carries exact execution identity, process/native
ownership, per-Download side-effect lease and global execution-lock/CAS
admission. Those boundaries protect Download-vs-Download and stale-generation
effects.

UpdateUtil also has useful local ordering:
- updater requests serialize behind updateMutex;
- desired source carries an exact generation;
- stale expected generation becomes SUPERSEDED;
- pending generation/source survives native update failure.

The residual is not inside those same-feature protocols. It is the absence of
one shared authority between yt-dlp runtime mutation and yt-dlp runtime consumers.

## Same-scope distinct root

Manual UpdateSettingsFragment update enters UpdateUtil without Download runtime
authority. Startup performs only a point-in-time Active/Queued row count before
calling the updater; that observation does not exclude a later Download claim.

UpdateUtil's updateMutex excludes updater-vs-updater races only.
RestoreMutationAdmission covers short preference/admission sections and does not
span the long native updater mutation.

Current Download execution resolves and uses the app-private yt-dlp runtime.
The exact youtubedl-android 0.18.1 dependency used by the app replaces that
runtime by deleting the live yt-dlp directory, recreating it, then copying the
replacement binary. Its execute path uses the same runtime path.

Therefore a valid Download can enter after destructive updater publication has
begun and before the replacement is usable. This can fail a Download even when
there is only one updater request, no source switch, no Restore, and no stale
updater generation.

That causal independence is why this remains BUG-UPDATER-04 rather than a
BUG-UPDATER-02 subcase.

## Exact invariant

A shared yt-dlp runtime generation may be destructively mutated or replaced only
under authority that excludes every incompatible live or newly admitted native
consumer.

At the final mutation/launch boundary, one of these must be true:

1. updater owns exclusive runtime-generation mutation authority and no ordinary
   consumer can cross native launch until the promoted generation is usable; or
2. publication is generation-safe such that existing consumers retain their old
   generation while new consumers are admitted only to a verified promoted
   generation.

A database count, UI state, WorkManager tag, or earlier zero/idle observation is
never runtime-generation authority.

Every production yt-dlp runtime mutation path and every production yt-dlp native
consumer path must participate in the same authority model.

## Narrow implementation boundary

Preferred correction:

Introduce one app-owned yt-dlp runtime-generation authority with two semantic
roles:
- consumer admission/lifetime for ordinary yt-dlp native execution;
- exclusive mutation/promotion for updater operations.

Bind ordinary Download consumer authority to the real native-use interval, not
merely queue/row state.

Bind updater mutation authority around the full destructive publication risk
interval. Manual update, startup update, automatic/worker update, built-in
source updates and custom --update-to self-update must all use that same
authority.

If the dependency's delete/recreate/copy updater remains in use, the exclusive
authority must cover that whole mutation interval.

A staged/generation-safe publication redesign is acceptable only if it proves
old-generation lifetime, staged validation, exact promotion and failure
recovery. Do not infer atomicity from a mutex alone.

Custom --update-to currently mutates the runtime through an execute API. The
correction must avoid self-deadlock: ordinary execute is a consumer, while the
custom self-update execute is mutation-owned.

Preserve:
- exact Download executionId and process/native ownership;
- per-item side-effect leases and recovery semantics;
- updater desired-generation/SUPERSEDED ordering;
- sibling Download independence;
- Restore authority;
- no broad cancellation.

## Forbidden shortcuts

- keeping only the MainActivity Active/Queued count check
- adding the same count check to manual update
- treating "no active rows" as an exclusive lease
- pausing/cancelling every Download before update without exact semantic authority
- using only updateMutex, which does not cover Download consumers
- protecting only built-in updater paths while custom --update-to bypasses the authority
- taking a consumer lock inside an already-exclusive custom updater path and creating self-deadlock
- holding unrelated global database/Restore locks across unbounded network/native work
- weakening Download exact execution ownership to make updater serialization easier
- treating a passing race test as proof if another production consumer/mutator bypasses the authority

## Acceptance matrix

- D owns a live exact native consumer; manual update waits/defers or otherwise
  cannot destructively replace D's generation
- after D reaches exact quiescence, updater promotes exactly once and releases
  mutation authority
- startup observes zero Active/Queued, then a late D attempts production claim
  before updater promotion; exactly one side wins the shared runtime authority
- updater owns promotion; a new D cannot cross final runtime launch until the
  promoted generation is verified usable
- update fails after mutation authority acquisition; authority is released and
  a later Download is not permanently blocked
- update failure never reports success with a missing/partial runtime
- custom --update-to executes under mutation authority without self-deadlock and
  excludes ordinary consumers
- overlapping updater requests still preserve existing generation/SUPERSEDED behavior
- independent sibling Downloads retain existing exact execution isolation
- cancellation/exception paths release runtime authority exactly once
- production-wiring coverage crosses the real Download native admission and real
  updater mutation seam; helper-only lock tests are insufficient
- source review proves every production yt-dlp consumer and mutator participates

## Test gap

Current UpdateUtil production-wiring coverage exercises updater ordering,
startup reconciliation and native failure/retry, but does not establish a shared
runtime lease against a real Download consumer.

Current Download ownership tests prove exact Download execution authority but do
not include concurrent updater destruction of the shared yt-dlp runtime.

No focused current-basis regression proves:
- live Download + manual updater;
- startup zero-count + late Download claim;
- consumer admission during updater promotion;
- custom-source self-update under shared mutation authority.

The root is therefore confirmed and remediation-ready on the exact current basis.

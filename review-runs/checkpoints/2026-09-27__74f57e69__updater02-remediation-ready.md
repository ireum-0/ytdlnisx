# BUG-UPDATER-02 clean-basis remediation-ready refinement

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: e52ec20278a4bb01cd34302b3bc29c48626ba23c
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L3 Concurrency & authority DEEP
supporting_lenses:
- L1 Durability & recovery
- L2 Identity & provenance
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing behavior to preserve

The selected yt-dlp source is persistent user configuration. Manual source selection persists `ytdlp_source` and then requests an update; startup auto-update reads the same selected source. Custom updater failure semantics remain separately owned by BUG-UPDATER-01 and must not be weakened.

## Exact defect

`UpdateUtil.updateYoutubeDL()` uses a companion Boolean `updatingYTDL`, but the PROCESSING branch does not return. Every caller proceeds to set the flag true and mutate the shared yt-dlp runtime. The flag is never released.

Startup auto-update runs in an independent `SupervisorJob`. Settings/manual updates and source-change updates can therefore overlap it and each other.

The installed runtime has no durable binding to the source request that produced it. A newer source selection can be persisted while an older request is still mutating the same runtime, and completion order determines which artifact remains installed.

## Exact invariant

The yt-dlp runtime is one shared materialized resource. Exactly one update operation may mutate it at a time.

Every mutation must be bound to an immutable requested source/configuration generation. A completion is current success only when that generation is still the authoritative desired generation and the installed runtime provenance is durably recorded for it.

If desired source and installed provenance differ because an operation failed, was cancelled, or the process died, that mismatch is durable recovery state, not success.

## Narrow implementation boundary

Introduce one application/process-wide `YtdlpUpdateCoordinator` (or equivalent) as the sole production owner of source selection plus runtime update mutation.

The coordinator should contain:

- one coroutine `Mutex` for the entire runtime mutation;
- a durable monotonically advancing source/update generation stored with the desired source;
- durable committed runtime provenance containing at least generation, exact requested source, and installed version/result;
- explicit PENDING/COMMITTED (or equivalent) state sufficient for restart reconciliation.

For a source-change action, do not persist `ytdlp_source` independently and then call a separate updater. Route the selection through the coordinator. Under the same serialized operation, publish the desired source/generation, perform the runtime update, then publish committed provenance only after successful completion.

For same-source manual/startup requests, capture the current desired generation and serialize through the same coordinator. Duplicate same-generation requests may coalesce or wait, but must not run a second mutation concurrently.

If a newer desired generation exists before an older queued request acquires the mutex, the older request is SUPERSEDED and must not mutate or report current success. If a generation changes while an older operation is already inside the serialized mutation, the newer operation must remain pending and run after it; the older completion may record its own historical result but must not overwrite newer desired-generation state or present itself as current.

Release process-local ownership in `finally` for success, failure, and cancellation. Remove the sticky Boolean or reduce it to derived UI observation, never authority.

At app startup, detect desired-generation != committed-runtime-generation/source and schedule/perform convergence through this same coordinator. This closes process death between source publication and runtime materialization without relying on the user reopening Settings.

All production entrypoints—startup auto-update, manual update, source-change update, and any actual worker updater—must call this coordinator rather than `YoutubeDL.updateYoutubeDL` directly.

## Forbidden shortcuts

- merely adding `return PROCESSING` to the existing Boolean guard
- a Mutex around only one caller while startup/manual paths remain direct
- clearing `updatingYTDL` without binding results to source generation
- persisting source B immediately while allowing already-running A and B to mutate concurrently
- using installed version string alone as source provenance
- reporting old A success as current after source B superseded it
- silently resetting the selected source to match whichever update finished last
- turning BUG-UPDATER-01 custom ERROR into ALREADY_UP_TO_DATE/DONE
- relying on Activity/ViewModel lifetime as recovery ownership

## Acceptance matrix

- repeated same-source requests: one mutation owner, deterministic coalesced/serialized result
- startup stable A latched; user requests nightly B: no overlap; final desired source/runtime provenance is nightly
- reverse scheduling/completion pressure cannot make older A overwrite newer B as current state
- B is selected while A owns mutation, then process dies after A finishes before B runs: durable desired/pending B survives and startup convergence installs B
- source publication succeeds but runtime update fails: selected source remains explicit desired state, committed runtime provenance remains old, mismatch is visible/retryable
- runtime update succeeds but committed-provenance write fails: do not report current success; restart reconciliation detects uncommitted generation
- cancellation at every boundary releases mutex ownership and retains durable pending/mismatch state
- Settings Activity recreation does not cancel the only durable ownership record
- custom source error retains BUG-UPDATER-01 failure semantics
- stale queued generation returns SUPERSEDED/no-op before runtime mutation
- successful current generation publishes exact source + version provenance before UI reports current success
- startup/manual/source-change production paths all use the same coordinator
- integration test latches real updater boundary for A/B and verifies one runtime mutation at a time; Boolean-only helper tests are insufficient

## Test gap

The CLEAN-basis implementation has no source generation, committed runtime provenance, restart mismatch reconciler, or real ownership primitive. Existing `updatingYTDL` is not authoritative.

This is the same BUG-UPDATER-02 root; no new finding ID or count change.

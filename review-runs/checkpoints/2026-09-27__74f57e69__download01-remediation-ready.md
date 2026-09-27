# BUG-DOWNLOAD-01 clean-basis remediation-ready refinement

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 327e17d7258909aa1c5373b3b7f98c9b6353efd7
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L1 Durability & recovery DEEP
supporting_lenses:
- L2 Identity & provenance
- L3 Concurrency & authority
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing behavior to preserve

The worker already has exact Download/execution ownership semantics. `ensureExecutionOwnedBeforeAttempt()` and related exact-owner gates distinguish a successful current-row observation from an exception: persistence failures propagate instead of being silently interpreted as absence.

True user Pause/Cancel and true execution replacement remain valid normal stop reasons only when current durable state proves them.

## Exact defect

`DownloadAttemptRunner.shouldStopForUserRequest()` weakens that contract by reading the authoritative row through:

`runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()`

A Room/SQLite exception and a proven absent row both become `null`. For a running exact execution E1, the helper therefore translates an indeterminate authority observation into `lostExecutionOwnership=true` and a normal stop result.

That helper is consumed at multiple final-effect boundaries. A normal STOP can let the item actor return without entering the exceptional recovery path. `cleanupAttempt()` contains a second `runCatching(...).getOrNull()` authority read, so a second read failure can also make cleanup release process-local ownership without proof that E1 was deleted or superseded.

## Exact invariant

An authoritative Download-row observation has at least three semantic outcomes:

1. CURRENT — the row was read and exact E1 still owns the operation;
2. REVOKED — the row was read and proves absence, replacement, Pause, Cancel, or another governed stop state;
3. INDETERMINATE — the authoritative read itself failed.

INDETERMINATE must never be consumed as REVOKED.

At every privileged effect boundary, an indeterminate observation must either propagate into the existing exact worker failure/recovery protocol or first establish a durable exact recovery owner for E1. The item actor and process-local owner may not both disappear while durable state can still be Active/PostProcessing E1.

## Narrow implementation boundary

Introduce one typed authoritative-read helper for the attempt runner, for example an internal sealed result carrying `Current(row)`, `Revoked(reason)`, and `Failed(error)`.

Use it in `shouldStopForUserRequest()` and cleanup/finalization authority reads.

Preferred behavior:
- `Current`: evaluate exact execution identity and current user-stop state normally;
- `Revoked`: return the existing governed normal STOP/revocation result;
- `Failed`: throw/propagate the original persistence failure into the attempt's existing exceptional recovery path.

If cleanup executes after such a failure and cannot reread authority, it must retain the exact process-local/durable recovery responsibility rather than call release based on a synthetic null. If the existing exceptional path records DownloadExecutionRecovery debt, reuse that path instead of inventing a parallel journal.

Keep the helper item-scoped. A read failure for A must not globally halt or revoke sibling B.

## Forbidden shortcuts

- replacing `getOrNull()` with `?: return true`
- treating any DAO exception as ownership loss because stopping effects is "fail closed"
- retrying the read indefinitely while holding an execution/side-effect lock
- releasing execution-owner registries when the cleanup read is indeterminate
- converting the error to Paused/Cancelled without a durable user-stop decision
- changing every DAO read in the app instead of the authoritative attempt/cleanup boundaries
- merging this root into BUG-ADMISSION-01 or BUG-CANCEL-02

## Acceptance matrix

- Active/E1, first stop-gate read succeeds current: execution continues
- Active/E1, first stop-gate read proves Paused/Cancelled/replaced: existing exact stop semantics remain
- Active/E1, first stop-gate read throws once then later recovery read succeeds: actor does not silently normal-STOP; exact recovery converges E1
- PostProcessing/E1, same injected read failure: same preservation
- first stop-gate read throws and cleanup read also throws: process-local owner is not released as though row were absent; one durable/discoverable recovery owner remains
- first recovery persistence after an indeterminate read fails: failure remains discoverable/retryable and no false terminal success/STOP is published
- read failure immediately before filesystem publication, History write, replacement cleanup, hard-sub effect, and terminal success commit: each boundary preserves uncertainty rather than inheriting an earlier successful observation
- true row deletion after a successful read remains a normal ownership-loss stop
- true execution replacement E1->E2 cannot let E1 retain or mutate E2 authority
- A read failure does not cancel/stall independent B
- cold restart can converge retained E1 debt without relying on process death as the original correctness barrier
- production Room fault-injection exercises real DownloadWorker/attempt/cleanup wiring; helper-only tests are insufficient

## Test gap

The prior CLEAN-basis checkpoint established this source path but independent fault-injection was not executed. Closure requires a deterministic throwing Room read at the repeated stop gate and both cleanup-reread branches.

This is the same BUG-DOWNLOAD-01 root; no new finding ID or count change.

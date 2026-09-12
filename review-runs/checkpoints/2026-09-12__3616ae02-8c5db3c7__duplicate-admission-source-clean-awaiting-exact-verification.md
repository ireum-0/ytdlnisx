# BUG-DUPLICATE-ADMISSION-01 — full-range independent source review

Date: 2026-09-12

## Exact review state

- Implementation branch: `checkpoint/pre-baseline-review`
- Prior contiguous independently CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Prior rejected implementation head: `e7355f5440d3a13cd8857f6cb5f15c242253cce2`
- Exact remote review-fix head: `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`
- Exact review-fix parent: `e7355f5440d3a13cd8857f6cb5f15c242253cce2`
- Full reviewed chain: `3616ae02... -> 1b3fd138... -> e7355f54... -> 8c5db3c7...`
- Full compare: three commits ahead / zero behind from `3616ae02...`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Original exact-basis root checkpoint: `b3cd2c3c58ebf20e1c670c658b5caf7bf3a8919a`
- Rejected-wave checkpoint: `f788c8b8436b935511aaddc7492c9c5e2e4b1f35`

The completion report also described an unpushed local commit `6ae51c01abe700d12e0603e409e6e08e77f34df2` whose parent is `e7355f54...`. That local commit is not the reviewed implementation state and was not used as source evidence. The implementation branch remote HEAD was independently verified as `8c5db3c7...`.

## Source-semantic verdict

**No remaining source blocker was found in the full original `BUG-DUPLICATE-ADMISSION-01` scope at exact remote `8c5db3c7...`.**

The implementation is a **source-clean closure candidate**, but canonical CLEAN/closure is **withheld pending exact-SHA execution evidence** for `8c5db3c7...`.

Therefore in this checkpoint:

- canonical blocker-count delta: `0`;
- canonical blocker count remains **P0 2 / P1 1 / P2 30**;
- `BUG-DUPLICATE-ADMISSION-01` remains canonically OPEN only because the required exact-final-SHA verification gate is incomplete;
- contiguous independently CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`;
- overall project remains `NOT_CLEAN`.

If exact `8c5db3c7...` verification passes without source changes and the ref remains exact, the next closure checkpoint may close this P2 root, reduce P2 from 30 to 29, and advance the contiguous CLEAN basis to `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc` after final ref recount.

## Full-range source closure

### 1. Original stale-negative duplicate-admission race is closed

At `8c5db3c7...`, `DownloadRepository.insertNewWithDuplicateAdmission(...)` owns current duplicate evaluation and brand-new row insertion in one `RoomDatabase.withTransaction` boundary.

For `URL_TYPE`, it re-reads the current active/queued candidate set and compares type plus canonical YouTube/source identity. For `CONFIG`, it re-reads the same current set and uses the established `DownloadConfigurationDuplicatePolicy.matches(...)` relation. Downloaded History is also re-read at that final boundary, using equivalent URL identity and only History rows whose referenced output still exists. If no duplicate is found, the DAO insert occurs before the transaction returns.

Thus two concurrent normal producers sharing the same Room database cannot both win solely because they observed stale-negative caller snapshots. The original check-then-separate-insert authority gap is removed.

### 2. Production consumers reach the final authority

`DownloadViewModel.queueDownloads()` uses the atomic admission primitive for brand-new normal queue rows. A losing final admission does not publish a second runnable row; the existing UI contract is preserved by recording a non-runnable `Duplicate` result row and the exact existing Download/History identity.

`ObserveSourceWorker` retains advisory duplicate preflight, but a brand-new non-preflight-rejected row reaches `insertNewWithDuplicateAdmission(...)` before it is treated as queued. A final admission duplicate/refusal does not publish another runnable row.

The original finding was stale-negative concurrent publication. No production path in the reviewed scope was found that can still publish two equivalent runnable rows through that old gap.

### 3. F19 equality semantics remain preserved

`DownloadConfigurationDuplicatePolicy` remains the established F19 semantic comparator:

- supported YouTube source spellings canonicalize to one media identity;
- meaningful request fields remain in configuration identity;
- command comparison canonicalizes only positively identified positional source-media tokens and preserves option-owned values;
- distinct media/configuration remain distinct.

The reviewed wave does not rewrite that policy. F19 `BUG-DUPLICATE-01` remains CLOSED.

### 4. Explicit bypasses remain explicit

Archive mode remains outside the row-level Room duplicate reservation because the archive is a separate external authority; its existing preflight behavior is retained and final row admission uses `DISABLED`.

Manual History redownload/quality-replacement rows also use `DuplicateAdmissionMode.DISABLED` at the final admission boundary, preserving the intentional redownload/replacement bypass required by the governing F19 preservation rule.

Restore/recovery and existing-row retry/requeue paths were not converted into synthetic new-row duplicate admission.

## Review-fix residual closure

The rejected `e7355f54...` state introduced a post-insert Observe ownership race:

`final admission inserts Queued row`
→ `worker claims row Active + executionId`
→ `Observe resumes with stale Queued producer object`
→ `generic downloadRepo.update(it)` could full-row Upsert the stale producer snapshot over the worker claim.

At `8c5db3c7...`, `ObserveSourceWorker` records `insertedByFinalAdmission = true` only when the final admission actually inserted the new row. The later generic update now executes only when `!insertedByFinalAdmission`.

Consequences:

- a just-inserted Observe row receives no second unowned full-row producer update;
- the worker claim can no longer be reverted through the rejected-wave continuation path;
- genuinely pre-existing Observe rows retain the pre-existing generic update behavior requested by the review-fix scope;
- duplicate/refused final-admission outcomes remain non-runnable.

The real worker production path selects queued rows, then calls `claimDownloadThroughProductionAdmission(...)`, whose final exact CAS/materialization uses `DownloadDao.claimDownloadForWorkerAndRead(...)`. The DAO changes Queued/Scheduled to `Active`, assigns a fresh `executionId`, checks operation/retry identity and other runnable guards, and returns the materialized claimed row in the same Room transaction. Because Observe no longer performs the post-insert generic full-row update, the concrete stale-claim overwrite chain reviewed at `e7355f54...` is closed.

`DownloadRepository.startDownloadWorker(...)` consumes the queued item objects for scheduling/priority IDs; it does not write those stale objects back to the Download row. A second worker seeing an already claimed row must pass the normal production claim CAS and therefore cannot use the stale queued object itself as mutation authority.

## Regression test source review

The remote review-fix adds `ObserveSourcePostInsertClaimProductionWiringTest`.

The test runs the real `ObserveSourceWorker` against an in-memory Room database and places a narrow hook immediately after the final admission insert has committed. At that boundary it invokes the same production DAO claim primitive `claimDownloadForWorkerAndRead(...)`, verifies the row became `Active` with the injected execution ID, lets Observe continue, then asserts the durable row remains `Active` with that execution ID.

This is a deterministic reproduction of the rejected interleaving and would fail if the stale post-insert generic update were restored.

The earlier `DownloadDuplicateAdmissionProductionWiringTest` remains part of the full range and exercises concurrent independent repository admissions, canonical configuration identity, distinct media/type, explicit disabled bypass, and insert-failure rollback/progress.

## Exact execution-evidence boundary

The completion report's reported executions were run against local commit `6ae51c01abe700d12e0603e409e6e08e77f34df2`, not remote `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`.

Those reported results are useful same-scope evidence but cannot be promoted to exact-final-SHA execution evidence because the remote commit is a different implementation commit and contains a different test-file change.

Direct GitHub evidence for exact remote `8c5db3c7...` at review time:

- commit statuses: none;
- check-runs: `0`;
- Actions workflow runs with `head_sha=8c5db3c7...`: `0`.

Under checklist v6, the source-semantic closure is therefore not yet sufficient to record canonical CLEAN/closure or advance the CLEAN basis.

## Exact next gate

Run focused verification from a fresh checkout/worktree whose exact HEAD is `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`, without merging/cherry-picking/rebasing the unpushed `6ae51c01...` local commit.

Required evidence should include at least:

- `ObserveSourcePostInsertClaimProductionWiringTest`;
- `DownloadDuplicateAdmissionProductionWiringTest`;
- existing Observe production-wiring coverage;
- `DownloadConfigurationDuplicatePolicyTest`;
- relevant worker claim/ownership regression coverage;
- full JVM suite;
- KSP and debug Kotlin compile;
- Android-test Kotlin compile;
- `git diff --check`;
- exact emulator/device identity for instrumentation.

If the exact remote state passes and no source change is made, only a verification/closure checkpoint is needed; the full source range has already been independently re-reviewed here. If exact verification fails or requires a code change, this source-clean candidate is superseded and the resulting new final SHA must receive the appropriate review.

INDEPENDENT EXECUTION: NOT EXECUTED

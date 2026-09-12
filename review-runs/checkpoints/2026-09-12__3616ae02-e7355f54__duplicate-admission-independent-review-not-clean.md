# BUG-DUPLICATE-ADMISSION-01 independent implementation review

- Review base / prior CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Reviewed implementation chain: `3616ae02e56995e795cc52f3074d8c3d1cd2e330` -> `1b3fd138718e05cfea515355145addb71f3b7562` -> `e7355f5440d3a13cd8857f6cb5f15c242253cce2`
- Verified implementation branch final HEAD: `e7355f5440d3a13cd8857f6cb5f15c242253cce2`
- Finding: `BUG-DUPLICATE-ADMISSION-01`
- Independent verdict: **NOT_CLEAN / REVIEW_FIX_REQUIRED**
- Finding disposition: existing **P2 remains OPEN**
- Count delta: **0**
- Canonical blockers after this review: **P0 2 / P1 1 / P2 30**
- CLEAN-basis consequence: **no advance**; remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`

## What the implementation correctly repairs

The core duplicate-admission authority is materially improved and addresses the original stale-negative root at the repository boundary:

1. `DownloadRepository.insertNewWithDuplicateAdmission(...)` executes current duplicate evaluation and the eventual new-row insert under the same Room transaction.
2. URL/type mode re-reads current runnable rows and valid downloaded History in that transaction; configuration mode reuses the established `DownloadConfigurationDuplicatePolicy` matching semantics.
3. Manual new-row publication uses the final admission authority and handles a final duplicate result without publishing another runnable row.
4. Observe publication also reaches the final admission authority rather than relying only on its earlier advisory snapshot.
5. The additive `e7355f54...` commit restores the intentional History-redownload / quality-replacement bypass at the final Manual admission boundary.
6. The implementation-provided repository instrumentation exercises two independent repository instances racing at the durable insertion boundary. Those reported passes are useful evidence, but independent execution was not performed here.

These points are sufficient to establish that the original `check -> separately insert` race is no longer the only authority for normal new-row publication.

## Blocking regression introduced in the Observe producer path

The completed wave cannot be accepted because the Observe wiring introduces a new stale full-row write immediately after the new atomic admission succeeds.

At the prior CLEAN basis `3616ae02...`, Observe's new-row branch was structurally exclusive:

- `id == 0` -> `downloadRepo.insert(it)`;
- only an already-existing queued row entered the `downloadRepo.update(it)` branch.

Therefore a just-inserted new row was **not** immediately rewritten from the producer's stale object snapshot.

At `e7355f54...`, `insertNewWithDuplicateAdmission(...)` returns `Inserted`, Observe assigns the returned id to the same in-memory `DownloadItem`, and the later generic block now sees `id > 0 && status == Queued` and calls `downloadRepo.update(it)` on that just-inserted object.

That second write is not part of the admission transaction and is not ownership-aware.

### Concrete production race

The sequence can now be:

1. Observe's duplicate-admission transaction inserts a new `Queued` row and commits.
2. An already-running `DownloadWorker`, which continuously collects the queued-download DB flow, observes the committed row.
3. The production admission path calls `claimDownloadForWorkerAndRead(...)`, whose DAO CAS changes the exact row from `Queued/Scheduled` to `Active` and assigns a fresh `executionId` inside a Room transaction.
4. Before or after process-local execution-owner publication, Observe resumes with its pre-claim in-memory object, whose status is still `Queued` and whose execution id is still the pre-claim value.
5. `downloadRepo.update(it)` delegates to the generic DAO `update(...)` / `updateRaw(...)` path. That path does not require the current execution token and performs a full-row upsert when the unrelated barrier/debt guards allow it.
6. The stale Observe snapshot can therefore overwrite the worker's just-committed `Active + executionId` claim back to the producer's `Queued` state / stale execution id.

This violates the established worker-ownership invariant that stale full-row snapshots must not erase a newer execution claim. Concrete impact includes a DB row no longer matching the execution owner that has already been materialized/published, causing later owner-checked worker writes to fail and leaving execution/queue recovery state inconsistent.

The defect is attributable to this implementation wave: the immediate post-insert update was not taken for a new row at `3616ae02...`; it becomes reachable only because the new admission call assigns `it.id` before the unchanged generic update condition is evaluated.

## Why the supplied verification does not close this race

The new duplicate-admission instrumentation validates serialization of duplicate decision plus insertion and is relevant to the original root. The reported Observe production-wiring passes are also useful regression evidence. However, the reviewed source still contains the interleaving above, and no reviewed new test deterministically forces:

`Observe final insert commit -> DownloadWorker claim -> Observe producer continuation`

and then asserts that the `Active` status / execution token survives. Passing tests therefore do not establish this missing ownership boundary.

## Required review fix

The correction should preserve the atomic duplicate-admission authority while restoring the prior producer ownership property:

1. a newly inserted Observe row must not be followed by an unowned generic full-row `update(...)` using the producer's stale pre-claim snapshot;
2. preserve any update behavior that is genuinely required for pre-existing Observe rows, without broadening unowned writes;
3. add a deterministic production-wiring regression that forces the post-insert worker-claim interleaving and proves the producer cannot revert `Active` / `executionId` after claim;
4. preserve F19 URL/config comparator semantics, archive behavior, and explicit intentional redownload/replacement/restore/recovery bypasses already retained by the current wave;
5. make the review fix as a separate additive commit on top of `e7355f5440d3a13cd8857f6cb5f15c242253cce2`; do not rewrite the existing chain.

After the review-fix completion report, independently re-review the **full original range** from `3616ae02e56995e795cc52f3074d8c3d1cd2e330` through the new final implementation HEAD, not only the last fix commit.

INDEPENDENT EXECUTION: NOT EXECUTED

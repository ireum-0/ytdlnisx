# F10 runtime review — frozen DOWNLOAD_TEMP root residual confirmed

- exact_implementation_sha: `85d5efabafb0dbb914506a25cfaa3282c901b301`
- implementation_remote_head: unchanged at the exact SHA above
- prior_F10_checkpoint: `9959e05847b0f8c2503b08c30453f55c72f95e31`
- governing_plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- checklist_v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- canonical_ledger_ref: `899328bc91e4008e39a658387396a0106c8666ec`

## Verdict

`F10 / BUG-CLEANUP-01: SOURCE-RESIDUAL-CONFIRMED / EXECUTION-FAILS`.

This is a subcase of the existing F10 mutable-cache-root / durable cleanup recovery root, not a new canonical finding. Count delta: `0`.

Canonical totals remain P0=2, P1=0, P2=24, Overall `NOT_CLEAN`. CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`. F11 remains blocked.

This checkpoint supersedes the F10-specific source-fixed conclusion in the prior harness-residual checkpoint and reconciles the automated `a8005ab0...` general-review checkpoint for F10 only. Separate roots recorded there are unaffected.

## Runtime evidence

The implementation branch itself did not move. The verification agent corrected the focused instrumentation test locally only and stopped without commit/push under the production-failure rule.

The corrected test no longer blocks inside `DownloadCacheOwnership.ownershipLock`. Its second-file deletion seam immediately returns `false`, then the test waits for the exact WorkRequest UUID to reach a genuine retry boundary (`ENQUEUED` with `runAttemptCount >= 1`) before R1 capture, `cache_path` change to R2, process-restart simulation, and process-local binding reset.

Focused execution on the existing API-36 emulator:

`CleanupScheduleCoordinatorProductionWiringTest#cleanupJournalReusesFrozenRootAfterCachePathRebindAndRestart`

reported:

- 1 discovered/executed;
- first attempt: RETRY;
- second attempt: RETRY;
- final attempt: FAILURE;
- initial post-attempt assertions passed: Room row absent, first R1 file absent, second R1 file present, R1 marker and manifest present;
- terminal assertion expected `SUCCEEDED` but observed `FAILED`.

The full 69-test class was correctly not continued after this first focused production-wiring failure.

## Exact production root cause

`CleanUpLeftoverDownloads.prepareCleanupEffectJournal()` freezes both per-Download cache bindings and an exact `DOWNLOAD_TEMP` snapshot when there are no active downloads.

The per-Download cache path is restart-safe: later cleanup calls `deleteExactCacheForTargetOutcome(target, cacheRoot = File(binding.rootPath))`, so the journal's frozen R1 root is used directly.

The `DOWNLOAD_TEMP` snapshot path is not restart-safe across `cache_path` mutation:

1. `AppCacheManager.snapshotExact(DOWNLOAD_TEMP)` stores the canonical root in `AppCacheExactSnapshot.rootPath`.
2. `AppCacheManager.targets()` derives `DOWNLOAD_TEMP` from current `FileUtil.getCachePath(context)`.
3. `AppCacheManager.deleteExact(snapshot)` re-resolves the category through current `targets()` and requires `currentTarget.root.path == snapshot.rootPath`.
4. After R1 -> R2, the journal still carries `snapshot.rootPath = R1` while the newly resolved `DOWNLOAD_TEMP` target is R2.
5. `deleteExact()` therefore returns the category as skipped rather than operating on the frozen R1 snapshot.
6. `CleanUpLeftoverDownloads.executeCleanupEffect()` treats that incomplete deletion as `EffectPhaseRecoveryRequired`.
7. WorkManager retries the same frozen occurrence until `MAX_ATTEMPTS` is exhausted, then returns terminal FAILURE.

Thus the durable journal freezes the old root but the destructive consumer re-consults mutable configuration and rejects that frozen authority. This violates the F10 invariant that admitted/restart recovery must preserve the exact frozen cleanup responsibility across cache-path changes and must not depend on the new mutable current root.

## Repair boundary

The next wave is a production correction, not another harness-only change.

Preferred direction:

- make exact AppCache snapshot deletion capable of honoring the snapshot's frozen canonical root even when a mutable category's current configured root changed;
- preserve app-owned-path confinement by validating the frozen root against stable app-owned roots (app cache / external cache / external files as applicable), rather than accepting arbitrary persisted paths;
- reconstruct category-specific exclusion boundaries relative to the frozen snapshot root when necessary (notably nested Terminal/Logs under DOWNLOAD_TEMP), so widening into protected sibling categories is impossible;
- retain the exact relative-path + size + lastModified identity checks and live-owner protection at deletion time;
- never mutate R2/new current cache merely because an R1 snapshot exists;
- avoid changing serialized `AppCacheExactSnapshot` schema unless exact-source review proves it is required, because it is persisted inside `CleanupEffectJournal`;
- audit all direct consumers of `snapshotExact` / `deleteExact` under Checklist v6 semantic-contract consumer/effect closure before declaring the helper change safe.

The locally corrected focused instrumentation scenario must be preserved/recreated as a committed regression. Run it alone first; only after it passes run the full `CleanupScheduleCoordinatorProductionWiringTest` class on the existing emulator.

If another focused or full-class production-semantic failure appears, preserve first-failure evidence and stop before unrelated changes.

INDEPENDENT EXECUTION: NOT EXECUTED
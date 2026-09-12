# BUG-QUEUE-05 current-basis revalidation checkpoint

- Review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Workflow state: `IMPLEMENTATION_ACTIVE_EXPLORATION_ON_FROZEN_CLEAN_BASIS`
- Active implementation exclusion: `BUG-DUPLICATE-ADMISSION-01` respected; no post-start implementation diff was inspected or relied upon
- Finding: `BUG-QUEUE-05`
- Verdict: existing **P2 remains OPEN / CONFIRMED**
- Count delta: **0**
- Canonical blockers after this revalidation: **P0 2 / P1 1 / P2 30**
- Production changes: **none**

## Fresh-source evidence at the frozen basis

1. `DownloadQueueMainFragment` wires the Clear Queue action to `DownloadViewModel.cancelAllDownloads()`.
2. `DownloadViewModel.cancelAllDownloads()` reaches the repository cancel-all path, which delegates to `DownloadRepository.cancelActiveQueuedWithResult(...)`.
3. `DownloadRepository.cancelActiveQueuedWithResult(...)` builds its cancellation snapshot from `DownloadDao.getActiveAndQueuedDownloadsList()`.
4. `DownloadDao.getActiveAndQueuedDownloadsList()` includes `Active`, `PostProcessing`, `Queued`, `WaitingForMembership`, and `Scheduled`, but omits `Paused`.
5. The same DAO's user-cancellation status contract admits `Paused`, so the snapshot omission is inconsistent with the cancellation boundary rather than an intentional non-cancellable-state rule.
6. `ActiveDownloadsFragment` exposes Resume All through `DownloadViewModel.resumeAllDownloads()`. A paused row left behind by Clear Queue therefore retains resumable execution intent instead of being cleared.

## Test coverage checked at the exact basis

- The exact-basis JVM test tree contains `app/src/test/java/com/ireum/ytdl/work/DownloadQueuePolicyTest.kt`, but that test covers priority observation/candidate selection and does not exercise the Clear Queue -> ViewModel -> repository -> DAO cancellation path or assert that paused rows are cleared.
- The exact-basis `androidTest` tree contains queue/order and queue-undo coverage, but no Clear Queue-named regression test is present.
- No execution result is claimed by this checkpoint; this is a source-level independent revalidation on the frozen CLEAN basis.

## Review conclusion

`BUG-QUEUE-05` remains a valid source-level P2 blocker at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`: Clear Queue can leave `Paused` downloads behind, and those rows remain resumable. This checkpoint does not create a new finding or change canonical blocker counts.

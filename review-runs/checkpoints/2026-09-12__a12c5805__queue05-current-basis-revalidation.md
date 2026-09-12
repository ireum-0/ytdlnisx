# BUG-QUEUE-05 — exact CLEAN-basis revalidation

Date: 2026-09-12 UTC

## Exact review state

- Fixed contiguous independently CLEAN basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Workflow state: `IMPLEMENTATION_DIFF_FROZEN_REVIEW_CONTINUES`.
- Active implementation target: F4 / `BUG-BACKUP-04`, started by explicit user signal. No in-progress F4 implementation diff was inspected or relied upon.
- Finding: existing P2 `BUG-QUEUE-05`.
- Prior exact-basis checkpoint: `cff0f742b4e3fc738ec2ef48d1210207975cce50` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-QUEUE-05` remains valid at exact CLEAN basis `a12c5805...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 29**.
- CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.

## Intervening-range verification

The accepted range `3616ae02... -> a12c5805...` is the duplicate-admission remediation/test-harness range. It changes `DownloadRepository.kt` and `DownloadViewModel.kt` only in duplicate-admission production composition, plus Observe/test surfaces. The duplicate-admission commits do not modify the Clear Queue cancellation path, `cancelActiveQueuedWithResult()`, the DAO status selection used by that path, or Resume All semantics.

Exact final source at `a12c5805...` was re-opened for the blocker-relevant path.

## Exact current production evidence

1. `DownloadQueueMainFragment` maps the `clear_queue` menu action to `downloadViewModel.cancelAllDownloads()`.
2. `DownloadViewModel.cancelAllDownloads()` reaches `cancelAllDownloadsImpl()`, whose repository cancellation stage delegates to `repository.cancelActiveQueuedWithResult(...)` with user-cancel recovery disposition.
3. `DownloadRepository.cancelActiveQueuedWithResult()` constructs its cancellation snapshots from `downloadDao.getActiveAndQueuedDownloadsList()`.
4. Exact `DownloadDao.getActiveAndQueuedDownloadsList()` includes only `Active`, `PostProcessing`, `Queued`, `WaitingForMembership`, and `Scheduled`; it still omits `Paused`.
5. The actual user-cancellation DAO contract is broader: `cancelByUser()` and execution-owned cancellation both explicitly admit `Paused`. The omission is therefore in bulk target discovery, not a rule that Paused is non-cancellable.
6. `ActiveDownloadsFragment` exposes Resume All and calls `DownloadViewModel.resumeAllDownloads()`; the ViewModel reads `dao.getPausedDownloadsList()` and begins the resume path from those surviving paused rows.

Concrete incorrect sequence remains:

`Paused download exists`
→ user invokes **Clear Queue**
→ bulk cancellation snapshot excludes the Paused row
→ row remains durably `Paused`
→ Resume All remains available for paused work
→ the supposedly cleared queue retains resumable execution intent.

This is the same semantic root as the prior `BUG-QUEUE-05` checkpoint, not a new finding.

## Count/root reconciliation

- No new root is created.
- This is not `BUG-QUEUE-01` P3 hardening; the effect is durable surviving resumable work after a user Clear Queue action.
- Count delta remains `0`.

## Test evidence

No independent execution was performed for this checkpoint. The conclusion is exact-source production-wiring revalidation.

INDEPENDENT EXECUTION: NOT EXECUTED
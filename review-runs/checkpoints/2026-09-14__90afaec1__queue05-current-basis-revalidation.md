# BUG-QUEUE-05 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior exact-basis checkpoint: `bf77560729916b489d1aee45ab3352436b20fd74` at `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active implementation wave remains frozen from inspection; implementation branch metadata still resolves to `973424909fd97de758b62f639967c8bae7c0bad7`.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-QUEUE-05` remains valid at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Intervening-range verification

Exact compare `a12c58055fff51b104f8b56fd53b534b8d7e5df4 -> 90afaec157607669ea32fa41877e7f0efcdcca86` is 12 commits ahead. That range is backup-focused and does not modify `DownloadRepository.kt`, `DownloadViewModel.kt`, `DownloadDao.kt`, `DownloadQueueMainFragment.kt`, or `ActiveDownloadsFragment.kt`.

Exact final source at `90afaec1...` was nevertheless re-read for the full production path.

## Exact current production evidence

1. `DownloadQueueMainFragment` still maps the `clear_queue` action to `downloadViewModel.cancelAllDownloads()`.
2. `DownloadViewModel.cancelAllDownloads()` still reaches the bulk repository cancellation path through `cancelActiveQueuedWithResult(...)` with USER_CANCEL semantics.
3. `DownloadRepository.cancelActiveQueuedWithResult()` still constructs its target snapshots from `downloadDao.getActiveAndQueuedDownloadsList()`.
4. Exact `DownloadDao.getActiveAndQueuedDownloadsList()` still selects only `Active`, `PostProcessing`, `Queued`, `WaitingForMembership`, and `Scheduled`; it omits `Paused`.
5. Paused rows remain real resumable execution intent: `ActiveDownloadsFragment` exposes Resume All, and `DownloadViewModel.resumeAllDownloads()` reads `dao.getPausedDownloadsList()` before resuming them.

Concrete incorrect sequence therefore remains:

`Paused Download exists`
→ user invokes **Clear Queue**
→ bulk target discovery omits the Paused row
→ that row remains durably `Paused`
→ Resume All remains available and can revive it
→ the supposedly cleared queue still contains resumable work.

This is a user-visible durable semantic failure, not merely UI hardening.

## Root/count reconciliation

- This is the same existing P2 `BUG-QUEUE-05` root; count delta `0`.
- It remains distinct from P3 `BUG-QUEUE-01` hardening.
- The issue is target-set incompleteness at the Clear Queue semantic boundary: Paused is cancellable/resumable queue state but omitted from bulk cancellation discovery.

## Stable correction boundary

A future correction must make Clear Queue's semantic target set include Paused work, while preserving exact execution/user-cancel recovery semantics for Active/PostProcessing rows and preserving sibling isolation/failure handling.

Focused closure coverage should include paused-only Clear Queue, mixed Active/Queued/Scheduled/Paused batches, USER_CANCEL semantics for exact running executions, no unintended resume after successful Clear Queue, and partial-failure sibling isolation.

INDEPENDENT EXECUTION: NOT EXECUTED
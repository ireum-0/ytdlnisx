# Independent Track A checkpoint — backup, cleanup, date recount

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Review branch parent observed before this checkpoint: `71dfe1fd38be45ee663f7926aeaab9f60505f765`
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Canonical working recount after this pass

- P0: 2
  - F3 `BUG-OBSERVE-01`
  - F11 `BUG-BACKUP-03`
- P1: 3
  - F4 `BUG-BACKUP-04`
  - F12 `BUG-KEYWORD-01`
  - F14 `BUG-METADATA-01`
- P2: 17
  - existing roots: B, C, J, K/F19, L/F20, M/F21
  - F5 `BUG-BACKUP-02`
  - F6 `BUG-BACKUP-06`
  - F7 `BUG-BACKUP-08`
  - F8 `BUG-BACKUP-05`
  - F9 `BUG-BACKUP-07`
  - F10 `BUG-CLEANUP-01`
  - F13 `BUG-METADATA-02`
  - F15 `BUG-DATE-01`
  - F16 `BUG-DATE-02`
  - F17 `BUG-HISTORY-01`
  - F18 `BUG-KEYWORD-02`

P2-B10 (archive identity loses extractor and compares raw URL substring) remains a subcase of P2-B and does not increment the count.

## Newly completed dispositions in this pass

### F10 `BUG-CLEANUP-01` — OPEN P2

Production path at the fixed basis:

- `DownloadSettingsFragment` maps daily/weekly/monthly to a delayed `OneTimeWorkRequest<CleanUpLeftoverDownloads>`.
- The unique-work name is `System.currentTimeMillis().toString()`, so cadence changes do not replace work scheduled under prior names.
- A non-null cadence change does not cancel the existing cleanup tag before enqueueing the new request.
- `CleanUpLeftoverDownloads.doWork()` performs cleanup and returns `Result.success()` without scheduling a successor.
- No startup reconciliation was found that converts the preference into exactly one durable cleanup schedule.

Impact: old and new one-shot cleanup jobs can coexist after cadence changes, while recurring execution stops after those one-shots finish. Reset/toggle scheduling cannot satisfy the Master Plan's stable named recurring-work reconciliation contract.

Acceptance direction: stable unique cleanup identity/token; cadence change replaces/reconciles; disable cancels; worker/startup schedule exactly one successor only if the persisted cadence token still matches; calendar-month semantics for monthly.

### F15 `BUG-DATE-01` — OPEN P2

Production path:

- `HistoryDateFetchWorker` calls `HistoryDateResolutionEngine.resolve()`.
- Minimal lookup exceptions are caught and collapsed to null.
- Compatibility lookup uses the normal one-item metadata path, whose data-fetch defaults include `--ignore-errors`; empty/malformed/mismatched/non-date results collapse through filtering and `singleOrNull()` to null.
- `HistoryDateResolutionEngine` turns compatibility null into `HistoryDateLookupOrigin.NONE`.
- `HistoryDateFetchRepository.checkpointSourceGroup()` persists `NO_DATE` for non-FAILED absence-like origins.

Therefore extractor failure/ambiguity is not durably distinguished from authoritative date absence. The existing source-identity filtering is not sufficient because the typed outcome itself is missing.

Acceptance direction: typed child lookup outcome (`FOUND`, authoritative absence, ambiguous, retryable failure, final failure); persist `NO_DATE` only after successful matching extraction proves absence.

### F16 `BUG-DATE-02` — OPEN P2

Production path:

- Child lookup can produce `HistoryDateLookupOrigin.FAILED`, which `checkpointSourceGroup()` records as item `FAILED`.
- `finalizeWorkerRun()` marks the parent `COMPLETED` whenever pending count reaches zero; it does not gate completion on failed-child count.
- `HistoryDateFetchWorker.doWork()` then returns `Result.success()`.
- WorkManager retry is reserved for coordinator-level exceptions and does not represent retryable child lookup outcomes.

Impact: mixed or all-failed child outcomes can converge to parent `COMPLETED` and WorkManager success; retryable child failures can consume no child retry budget and still terminalize as success.

Acceptance direction: retryable children remain pending while budget remains; return `Result.retry()` while retry is valid; explicit partial-failure/all-failed parent semantics; notification/counts agree with child terminal states.

## Backup/Reset findings independently retained

The preceding focused source pass already established these at the same fixed basis and they remain part of the canonical working count:

- F4 OPEN P1: selected-category capture helpers can swallow failures into empty arrays, conflating failure with valid empty capture.
- F5 OPEN P2: restored thumbnail naming based on backup-local History IDs permits repeated Merge to overwrite a previously restored thumbnail path.
- F6 OPEN P2: imported Download `observeSourceId` may bind to an unrelated live Observe row because backup-local numeric ID equality is treated as authority.
- F7 OPEN P2: preference restore does not round-trip all portable persisted types such as Long/Float and can silently omit unsupported values.
- F8 OPEN P2: persistent Paused downloads are omitted from backup/restore category/schema handling.
- F9 OPEN P2: playlists, History↔Playlist membership, PlaylistGroups, and group membership are omitted from the backup contract.
- F11 OPEN P0: Reset performs sequential destructive mutations across preferences/Room/files/work scheduling without a fully validated pre-mutation plan and durable restore-wide commit/recovery protocol.

F1 `BUG-BACKUP-01` and F2 `BUG-OUTPUT-01` remain CLOSED at this fixed basis.

## Reconciliation with concurrent review checkpoint

Concurrent checkpoint `71dfe1fd38be45ee663f7926aeaab9f60505f765` recorded a narrower provisional `P0 1 / P1 2 / P2 9` count and explicitly left F4/F11 NOT_VERIFIED. That provisional recount is not adopted as semantic authority here: the dedicated fixed-basis Track A source pass has independently completed F4/F11 and F5-F10/F15/F16 production-path review.

## Review Basis rule

This Track A checkpoint does not advance the contiguous Review Basis. It remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d` because the next implementation correction boundary (`2a7703e2644a3bf184a7ac46616f174c1ebfeeb6`) was independently NOT_CLEAN.

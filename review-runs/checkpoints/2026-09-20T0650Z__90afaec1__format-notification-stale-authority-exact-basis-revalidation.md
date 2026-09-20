# BUG-FORMAT-02 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Existing canonical root: P2 `BUG-FORMAT-02`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-FORMAT-02` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A delayed UI/notification carrier may identify a candidate row, but it must not retain mutation authority across later Download lifecycle ownership changes.

Checklist v6 requires stale snapshots and externally replayable carriers to preserve exact generation/ownership identity before they can change durable state.

## Exact production evidence at 90afaec1

### 1. Completion notification carries only numeric Download IDs

`UpdateMultipleDownloadsFormatsWorker` publishes its completion through `NotificationUtil.showFormatsUpdatedNotification(...)`.

`NotificationUtil` stores only:

- `showDownloadsWithUpdatedFormats = true`;
- `downloadIds = <numeric id array>`.

The notification carries no expected status, execution ID, operation ID, retry attempt, issue generation, or immutable notification-generation token.

### 2. The notification is replayable after Download lifecycle changes

`HomeFragment.onResume()` consumes the deep link later and directly calls:

`turnDownloadItemsToProcessingDownloads(ids.toList(), deleteExisting = true)`.

Nothing at this boundary proves that each numeric ID is still in the lifecycle state that produced the format-completion notification.

### 3. The conversion path protects only the Error subcase

`DownloadViewModel.turnDownloadItemsToProcessingDownloads(...)` reloads the current Download row.

If current status is `Error`, it captures an exact snapshot and uses `dao.updateForQueueIfSnapshot(...)` with expected status/execution/operation/retry/issue fields.

For non-Error states, however, the path sets:

`item.status = Processing`

and falls through to:

`repository.update(item)`.

There is no equivalent expected-state/execution-owner predicate for current `Queued`, `Active`, `PostProcessing`, `Paused`, `Saved`, or other non-Error states.

### 4. Concrete stale-authority race remains reachable

1. Download D participates in a format refresh and its numeric ID is embedded in the completion notification.
2. Before the user taps the notification, D is legitimately requeued and claimed by a DownloadWorker, becoming `Active` with a current nonblank execution ID.
3. The old notification is tapped.
4. HomeFragment resolves D by numeric ID only.
5. D is not `Error`, so the Error-only snapshot CAS is bypassed.
6. The stale notification path writes the current row back with `status=Processing` through the ordinary full-row update.
7. The live worker/native execution is not revoked or quiesced by this stale UI transition.

Durable lifecycle state can therefore be rewritten underneath a still-live execution generation.

### 5. Intervening range did not close the root

Exact compare `9edd3e23... -> 90afaec1...` is 21 commits ahead.

Among the core BUG-FORMAT-02 path files, only `DownloadViewModel.kt` changed in that range. Exact final source at `90afaec1...` still contains the same Error-only guarded branch and unguarded non-Error full-row update, so the promoted root remains valid.

## Root reconciliation

- Keep `BUG-FORMAT-02` counted once as P2.
- Count delta: `0`.
- Keep distinct from `BUG-FORMAT-01` / bulk-format partial-success accounting.
- Keep distinct from queue/pause/scheduler roots whose stale authority originates from different carriers.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. treat the notification IDs as navigation hints, not durable mutation authority;
2. revalidate an explicitly allowed current lifecycle state under the same execution-ownership authority used by queue/retry transitions;
3. refuse conversion when current state is worker-owned or represents newer user intent;
4. never overwrite a nonblank current execution generation from the stale notification path;
5. avoid broad full-row mutation where a narrow expected-state transition is sufficient;
6. skip safely if a row was deleted after notification publication;
7. preserve unchanged eligible Saved/Error rows that are still valid for configuration;
8. cover mixed bundles so stale rows are refused without blocking still-valid siblings.

## Verification

- Exact-source production-path review: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED

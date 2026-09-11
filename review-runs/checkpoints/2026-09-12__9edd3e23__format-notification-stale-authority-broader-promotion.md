# BUG-FORMAT-02 — stale completed-format notification can overwrite live Download ownership

Date: 2026-09-12

## Exact review basis

- Independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Historical broader-registry root: P2 `BUG-FORMAT-02`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Overnight source queue: complete; no moving source candidate was used as evidence for this decision.

## Verdict

**CONFIRMED / PROMOTED P2 `BUG-FORMAT-02` at exact CLEAN basis `9edd3e23...`.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 1 / P2 34**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

Overall remains `NOT_CLEAN`.

## Concrete production sequence

### 1. Format-refresh completion publishes only mutable numeric Download IDs

`UpdateMultipleDownloadsFormatsWorker` receives a list of Download IDs, refreshes format data, and in `finally` calls:

`notificationUtil.showFormatsUpdatedNotification(ids + otherIdsInBundle)`

The completed notification therefore survives independently of the Download rows' later lifecycle state. Its authority is only the old numeric ID list; it carries no expected status, execution ID, operation ID, retry attempt, or immutable notification-generation token.

### 2. Notification deep link is intentionally delayed/replayable relative to Download lifecycle

`NotificationUtil.showFormatsUpdatedNotification(...)` stores `showDownloadsWithUpdatedFormats=true` and the `downloadIds` array in a navigation deep link.

When that deep link is later consumed, `HomeFragment.onResume()` calls:

`downloadViewModel.turnDownloadItemsToProcessingDownloads(ids.toList(), deleteExisting = true)`

Nothing at this boundary proves each numeric ID still represents the same lifecycle state that existed when the format notification was produced.

A user can therefore receive the completion notification, leave it pending, and meanwhile retry/requeue/start one of those Download rows before tapping the old notification.

### 3. The conversion path re-reads the current row but does not treat current worker ownership as a veto

`turnDownloadItemsToProcessingDownloads(...)` loads the current row with `repository.getItemByID(id)`, then mutates that same object to:

`item.status = Processing`

For one narrow case — when the reloaded row is currently `Error` and `deleteExisting=true` — the code captures `errorSnapshot` and uses `dao.updateForQueueIfSnapshot(...)` with expected status/execution/operation/retry/issue fields.

For every other current state, including a row that has since become `Queued`, `Active`, `PostProcessing`, `Paused`, `Saved`, or another state not represented by the Error-only guard, the function falls through to:

`repository.update(item)`

`DownloadRepository.update(item)` delegates to the normal full-row DAO update. There is no expected current status/execution predicate for the completed-notification path and no exact live-owner rejection before changing the durable row to `Processing`.

### 4. Concrete incorrect impact

A concrete reachable race is:

1. Download D participates in a multi-format refresh and its ID is embedded into the completion notification;
2. after refresh completion, D is legitimately requeued/retried and a DownloadWorker claims it, publishing current `Active` state and an execution ID;
3. the old format-completion notification is tapped;
4. the deep link looks up D by numeric ID only;
5. because D is not `Error`, the Error-only snapshot CAS is bypassed;
6. the stale notification path writes the full current object back with `status=Processing`;
7. the already-running worker/native execution is not revoked or quiesced by this UI transition.

Durable Download state can therefore say `Processing` while a live execution still owns and mutates the same Download generation. Subsequent UI configuration, cancellation/retry logic, worker terminal publication, or queue admission now observes a lifecycle state that was not authorized by the live execution owner.

The stale notification is thus being treated as mutation authority when it should be, at most, a candidate invitation to open configuration for rows that still satisfy the original expected lifecycle contract.

## Why existing protections do not close this root

- F14 metadata publication guards metadata-owned fields and source identity; it does not govern format-notification UI lifecycle mutation.
- Download execution fencing protects many worker/retry/cancel boundaries, but this completed-notification path does not enter a generation-aware live-owner transition for non-Error rows.
- The Error branch's `updateForQueueIfSnapshot(...)` demonstrates that expected-state fencing exists locally, but it is conditional on the row already being Error and therefore does not protect the stale-notification race against newly Queued/Active/PostProcessing ownership.
- Reading the current row immediately before mutation is not enough: current observation does not grant authority to replace worker-owned lifecycle state.

## Root reconciliation

Count once as historical P2 `BUG-FORMAT-02`.

Keep distinct from:

- promoted P2 `BUG-FORMAT-01`, which owns swallowed per-item format-refresh failures, split Result/Download publication, and false batch-success reporting;
- broader `BUG-QUEUE-02`, whose stale authority originates from a queued-list/UI snapshot and has its own queue/low-quality consequences;
- worker cancellation/pause/scheduler roots, whose state transitions enter through different semantic commands;
- F14 metadata stale-write publication, which is limited to metadata ownership/source guards.

This root is specifically **a completed format-refresh notification retaining numeric-ID mutation authority after Download lifecycle ownership has changed**.

## Required correction boundary

A coherent correction should:

1. make a format-completion notification carry an immutable/expected lifecycle precondition for each affected existing Download, or make notification consumption revalidate an explicitly allowed state under the same execution-ownership authority used by queue/retry transitions;
2. refuse conversion to `Processing` when the current row is worker-owned (`Active`/`PostProcessing`) or otherwise no longer belongs to the lifecycle state authorized by the notification;
3. never overwrite a nonblank current execution generation from a stale notification;
4. avoid full-row mutation from a stale notification when a narrow, expected-state transition is sufficient;
5. define behavior for rows deleted since notification publication: skip safely without recreating them;
6. define behavior for rows already changed by user retry/reconfiguration: stale notification should skip/refuse rather than replace newer intent;
7. preserve legitimate unchanged Saved/Error rows that are still eligible to enter the multi-download configuration flow;
8. keep the existing History-replacement refusal/barrier checks fail-closed;
9. add deterministic production-level regressions covering:
   - completion notification -> unchanged eligible row -> Processing succeeds;
   - completion notification -> row becomes Active with a new execution ID -> stale notification does not change status/execution;
   - completion notification -> PostProcessing -> stale notification refused;
   - completion notification -> newer Queued/retry generation -> newer intent survives;
   - completion notification -> row deleted -> no recreation;
   - Error row changed between read and transition -> existing snapshot CAS still refuses;
   - mixed ID bundle where only still-eligible rows enter configuration.

Do not broaden the fix into `BUG-FORMAT-01` failure accounting or unrelated queue/pause semantics.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED

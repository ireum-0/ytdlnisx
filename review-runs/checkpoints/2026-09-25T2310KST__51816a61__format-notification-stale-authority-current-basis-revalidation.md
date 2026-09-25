# BUG-FORMAT-02 — exact current-basis revalidation

Date: 2026-09-25 +09:00

Exact independently CLEAN implementation basis reviewed:
51816a619b3c85bd2a8d130c84c37f15c662b45e

Governing Master Plan:
fada33a7eed86b1fa2c07065af66f14bf4d24714

Governing checklist:
REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7

Canonical existing root:
P2 BUG-FORMAT-02

Prior exact-basis revalidation:
review-runs/checkpoints/2026-09-20T0650Z__90afaec1__format-notification-stale-authority-exact-basis-revalidation.md

## Verdict

OPEN / CONFIRMED / NOT_CLEAN.

The root remains present at exact CLEAN basis
51816a619b3c85bd2a8d130c84c37f15c662b45e.

Canonical blocker-count delta:
0

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 20

CLEAN_REVIEW_BASIS remains:
51816a619b3c85bd2a8d130c84c37f15c662b45e

## Current exact-source evidence

### Notification still carries only numeric Download IDs

UpdateMultipleDownloadsFormatsWorker publishes successful completion through:

NotificationUtil.showFormatsUpdatedNotification(ids + otherIdsInBundle)

NotificationUtil places only:
- showDownloadsWithUpdatedFormats = true;
- downloadIds = numeric LongArray

into the deep-link arguments.

No exact status snapshot, executionId, operationId, retryAttempt, issue
generation, or notification-generation owner is carried.

The format worker itself has gained stronger current-row/execution-aware format
writes, but that does not transfer mutation authority to the later notification
consumer.

### Delayed notification remains replayable after a newer lifecycle owner exists

HomeFragment.onResume() still consumes the old deep link later and calls:

turnDownloadItemsToProcessingDownloads(ids.toList(), deleteExisting = true)

The numeric IDs are treated as durable mutation targets rather than navigation
hints.

No check at this boundary proves that the current row is still in the lifecycle
state for which the format-completion UI transition was intended.

### Error is guarded; non-Error rows are still unguarded

DownloadViewModel.turnDownloadItemsToProcessingDownloads(...) reloads the
current Download row.

For Error rows it captures an error snapshot and eventually uses
updateForQueueIfSnapshot(...) with expected:
- status;
- executionId;
- operationId;
- retryAttempt;
- issue code/stage.

For every non-Error row, however, it still:
1. mutates the loaded object to status=Processing;
2. falls through to repository.update(item).

There is no equivalent expected-state or exact execution-owner predicate.

Therefore current Queued, Active, PostProcessing, Paused, Saved, Cancelled, or
other non-Error rows can be rewritten through a delayed numeric-ID carrier.

### Concrete current race

1. Download D is part of a format-refresh bundle.
2. The completion notification stores only numeric id D.
3. Before the notification is tapped, D is legitimately queued and claimed by
   DownloadWorker.
4. D becomes Active with a current nonblank executionId.
5. The stale notification is tapped.
6. HomeFragment resolves D by numeric ID only.
7. Since D is not Error, the guarded Error snapshot path is bypassed.
8. The stale UI path writes the current row back through generic
   repository.update(item) with status=Processing.
9. No execution-owner revocation/quiescence accompanies that transition.

A delayed notification can therefore overwrite durable lifecycle state beneath
a live execution generation.

## Root reconciliation

Keep BUG-FORMAT-02 counted once as P2.

No new root is created.

Keep distinct from:
- BUG-FORMAT-01 / bulk-format partial-success accounting;
- BUG-DOWNLOAD-DELETE-SNAPSHOT-01 / other stale full-row Download writers;
- scheduler/queue/pause roots whose stale authority originates from different
  carriers.

The generic full-row repository surface is shared evidence, but this root is
specifically the delayed format-completion notification authority path.

## Stable correction boundary

A coherent correction should:

1. treat notification download IDs only as navigation/candidate hints;
2. define the exact current states that may legitimately be reopened into
   Processing for this format-review UI;
3. refuse worker-owned Active/PostProcessing and any newer Queued or otherwise
   incompatible user/lifecycle intent;
4. never overwrite a nonblank current execution generation from a stale
   notification;
5. perform the allowed transition with a narrow expected-current-state /
   expected-owner predicate rather than generic full-row update;
6. skip deleted rows safely;
7. process mixed bundles independently so one stale row does not erase or
   block valid siblings;
8. preserve existing Error retry/replacement refusal semantics rather than
   weakening their stronger snapshot fencing;
9. add deterministic production-wiring/repository tests for:
   - stale notification after Active claim;
   - stale notification after PostProcessing;
   - stale notification after newer Queued/user intent;
   - deleted row;
   - eligible Saved/non-worker-owned row;
   - mixed bundle with stale + eligible rows;
   - preservation of executionId/operation/retry authority.

Prefer a narrow transition API specific to this UI responsibility. Do not
broaden this wave into generic Download full-row writer cleanup.

## Verification

Exact-source production-path revalidation completed at
51816a619b3c85bd2a8d130c84c37f15c662b45e.

Independent execution was not performed.

INDEPENDENT EXECUTION: NOT EXECUTED

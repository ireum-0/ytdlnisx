# BUG-DOWNLOAD-01 — CLEAN-basis current attribution/revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: d6d1da85e452e7a488ad49c9a704410e55e4baf5
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
active_tooling_wave_inspected: NO

## Independent verdict

BUG-DOWNLOAD-01 remains **OPEN P2** on the fixed independently CLEAN basis
`74f57e695db30b701ad429af311c39a763bfe086`.

This is an existing canonical root. No new finding ID is created and canonical counts do not change.

Current overall remediation state remains NOT_CLEAN.

Independent execution: NOT EXECUTED.

## Exact source proof

In `DownloadAttemptRunner.shouldStopForUserRequest()` the authoritative Room read is:

`runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()`.

A Room/SQLite read failure and a proven absent row therefore both become `latest == null`.

For a running attempt with a nonblank exact execution ID, that makes
`lostExecutionOwnership=true`, and the helper returns ordinary stop semantics through
`shouldStopForDownloadExecution(...)`.

The same file separately proves that this collapse is not the normal ownership contract:
`ensureExecutionOwnedBeforeAttempt()` calls `getNullableDownloadById()` without `runCatching`.
A persistence exception there propagates; proven absence/execution mismatch is handled separately as
`DownloadExecutionOwnershipLostException`.

## Caller propagation

Multiple correctness-relevant boundaries use `shouldStopForUserRequest()`, including output
processing, hard-sub processing, no-History primary-success publication, History/publish flow, and
failure handling.

A true return commonly becomes `AttemptControl.STOP`. In the main attempt runner,
`runSuccessfulDownload(...) == STOP` returns normally from the item actor rather than propagating
the erased Room exception through the exceptional failure/recovery path.

The `finally` block still runs `cleanupAttempt()`.

## Cleanup / recovery consequence

`cleanupAttempt()` performs another authority read under NonCancellable IO using:

`runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()`.

Two relevant cells remain:

1. First stop-gate read fails, cleanup reread succeeds and still shows exact Active/PostProcessing E1.
   The item actor has already returned normal STOP. Cleanup does not terminalize/requeue that exact
   running row, so durable state can remain apparently live after the item actor exits.

2. First stop-gate read fails and cleanup reread also fails.
   `latestStatus == null` makes `stillOwnsAttempt=false`.
   Cleanup enters the "row now belongs to another attempt" branch and releases this execution's
   process-local `DownloadWorkerExecutionOwners` / process owner bookkeeping without proof that the
   durable row was absent or superseded.

Thus an observation failure can propagate as:

authoritative row read failure
-> false ownership-loss/user-stop-compatible STOP
-> no durable recovery carrier from that failure
-> actor returns
-> cleanup either leaves exact Active/PostProcessing state without an actor or can retire local
   ownership on another unreadable observation.

No real Pause, Cancel, row deletion, superseding execution, or native fault is required.

## Root reconciliation

This remains the existing BUG-DOWNLOAD-01 root.

It is distinct from:
- BUG-ADMISSION-01: that root is between claim and actor attachment; this root requires an already
  attached running attempt and a later repeated authority read.
- BUG-CANCEL-02: no real user cancellation or final cancel-effect race is required.
- BUG-PAUSE-03: no batch target-set expansion is involved.

The violated invariant is preservation of uncertainty at an authoritative persistence observation:
`read failed` must not become `row absent / ownership lost`.

## Trigger / lens result

Primary exploratory lens: **L1 Durability & recovery — DEEP**.

Supporting lenses:
- L2 Identity & provenance: failed observation is collapsed into a different authority identity.
- L3 Concurrency & authority: process-local owner release can be based on unreadable rather than
  proven current/superseded durable state.
- L6 Cross-feature propagation: the helper is reused across multiple final-effect/terminal gates.

Relevant blocker obligations:
- preserve one discoverable exact recovery owner after indeterminate authority observation;
- keep real Pause/Cancel/ownership-loss as normal STOP only after successful authoritative proof;
- do not release exact process-local ownership merely because cleanup authority reread also failed;
- preserve sibling isolation.

No new checklist gap was found; checklist v7 already covers durability/recovery, authority
observation, async/owner lifetime, and producer/consumer propagation.

## Verification state

SOURCE-LEVEL ONLY for this revalidation.

No independent Room fault-injection or connected execution was performed.

## Active-wave boundary

The active implementation agent is working on the approved remediation tooling wave under
`tools/remediation/**`.

This checkpoint does not inspect or rely on that active tooling diff.

The current handoff's BUG-CANCEL-02 FIXED-CLOSED decision at
`31f55aca76efb1b956567fca5325b2575779a835` is preserved and unrelated to this revalidation.

## Checkpoint summary

- basis: 74f57e695db30b701ad429af311c39a763bfe086
- finding: BUG-DOWNLOAD-01
- status: OPEN P2 / CONFIRMED
- new finding IDs: 0
- count change: 0
- primary lens: L1 DEEP
- independent execution: NOT EXECUTED
- active tooling wave inspected: NO

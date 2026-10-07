# Checkpoint-only label reconciliation — aliases and rejected Result candidate

checkpoint_kind: CHECKPOINT_ONLY_IDENTIFIER_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 267d85b687cf460db8bbd936b41bf5b2586dd665
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## CLEANUP-STALE-DOWNLOAD-ROW-01

Disposition:
SUPERSEDED_ALIAS(BUG-DOWNLOAD-DELETE-SNAPSHOT-01)

Both labels own the same semantic root:
- cleanup/status-sweep materializes deletion candidates;
- a later lifecycle/recovery/user mutation can change/revive the same row;
- destructive deletion consumes stale numeric-id snapshot authority rather than exact current row state,
  execution/recovery ownership and deletion eligibility.

The later repository audit already keeps BUG-DOWNLOAD-DELETE-SNAPSHOT-01 OPEN P2. Count once.

## BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01

Disposition:
SUPERSEDED_ALIAS(BUG-FORMAT-BG-03)

The historical label owns:
- per-item exception swallowed/ignored;
- Boolean/CAS authority refusal ignored;
- progress advanced anyway;
- aggregate worker/notification success despite material item failure.

Current canonical BUG-FORMAT-BG-03 owns the same semantic contract and further strengthens it with typed
per-item outcomes, cancellation preservation, foreground acceptance, truthful aggregation and residual
responsibility. Count once under BUG-FORMAT-BG-03.

## OBSERVE-SCHEDULER-ASYNC-BARRIER-01

Disposition:
SUPERSEDED_ALIAS(BUG-OBSERVE-03)

Historical alias reconciliation already established exact root equality:
current valid Observe generation has durable ACTIVE recurrence intent but may lose its exact WorkManager
carrier at the asynchronous enqueue acceptance boundary. BUG-OBSERVE-03 is the stable canonical identity.
Count once.

## RESULT-URL-IDENTITY-ALIAS

Disposition:
FALSE_POSITIVE_REJECTED_AS_SEVERITY_BEARING_PRODUCTION_ROOT

The exact-basis final-effect classification proved:
- Result persistence uses exact textual URL equality while other subsystems know provider-specific URL
  equivalence;
- this can under-merge cache metadata, leave stale alias rows, or miss reusable Result state;
- no reviewed production consumer promoted that mismatch into destructive deletion, privileged
  replacement, durable execution ownership or another P0/P1/P2 correctness effect.

The candidate therefore remains useful design evidence but is excluded from confirmed production-root
totals unless new final-effect evidence appears.

## Inventory effect

new distinct production roots: 0
new aliases: 3
new rejected candidates: 1

Current download canonical inventory unchanged.
No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.

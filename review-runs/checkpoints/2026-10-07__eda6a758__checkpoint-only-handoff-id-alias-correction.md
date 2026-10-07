# Checkpoint-only lineage correction — LocalAdd / Terminal / automatic-keyword handoff IDs

Date: 2026-10-07

checkpoint_kind: REPOSITORY_CHECKPOINT_ONLY_LINEAGE_CORRECTION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: ad94530c45e9ef4abd1b8f8b65f641649ee130ff
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8
repository_discovered_canonical_roots_total: NOT_YET_VERIFIED

## BUG-LOCALADD-HANDOFF-01 -> BUG-LOCALADD-02

relationship: SAME_ROOT_ALIAS
distinct_checkpoint_only_root_increment: 0
owning_registry_root: BUG-LOCALADD-02
current_disposition: VERIFIED_CLOSED

Both identifiers own the same semantic boundary:
durable LocalAdd session payload / responsibility -> exact WorkManager acceptance -> restart recovery.

The registry root BUG-LOCALADD-02 explicitly requires the local-add request payload and worker ownership to be durably ordered before enqueue can survive process death. The later BUG-LOCALADD-HANDOFF-01 checkpoint re-described the same loss window as session persistence followed by an unobserved enqueue with no exact request/recovery owner.

Current exact eda6a758 closure evidence already recorded by the completed 136-ID audit:
- LocalAddStorage.beginSession() synchronously commits exact entries plus request/owner identity;
- LocalAddResponsibilityReconciler.publishSession() enqueues exact unique work and awaits Operation.result;
- accepted ownership is durably marked;
- App startup calls reconcileStartup(), which enumerates live owner markers and repairs missing/stale exact work.
Therefore the historical root is closed once, under BUG-LOCALADD-02 lineage.

## BUG-TERMINAL-HANDOFF-01 -> BUG-TERMINAL-05

relationship: SAME_ROOT_ALIAS
distinct_checkpoint_only_root_increment: 0
owning_registry_root: BUG-TERMINAL-05
current_disposition: VERIFIED_CLOSED

Both identifiers own the same semantic boundary:
durable TerminalItem -> exact WorkManager dispatch carrier -> acceptance/restart recovery.

BUG-TERMINAL-05's registry definition is the persisted-Terminal-row / lost-enqueue gap. BUG-TERMINAL-HANDOFF-01 later revalidated that exact gap under a descriptive checkpoint-only ID.

Current exact eda6a758 closure:
- TerminalViewModel.insert() places TerminalItem and stageTerminalDispatchWithinTransaction() in the same Room transaction;
- the carrier stores exact request/generation/command fingerprint authority;
- dispatchTerminalDispatch() delegates publication to WorkManagerHandoffRecovery;
- WorkManagerHandoffRecovery observes exact enqueue acceptance and App startup reconciles Terminal dispatches.
The completed 136-ID audit already classifies BUG-TERMINAL-05 VERIFIED_CLOSED. Count this semantic root once.

## BUG-KEYWORD-HANDOFF-01 / AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 -> BUG-KEYWORD-03

relationship: SAME_ROOT_ALIASES
distinct_checkpoint_only_root_increment: 0
owning_registry_root: BUG-KEYWORD-03
current_disposition: VERIFIED_CLOSED

All three labels describe the same root:
a durable automatic-keyword QUEUED/pending revision could previously commit before any accepted/recoverable WorkManager owner existed.

BUG-KEYWORD-03 explicitly records save/syncNow committing QUEUED or pendingApplyToExisting before enqueueUniqueWork(), discarding Operation, and lacking startup reconstruction. BUG-KEYWORD-HANDOFF-01 and the later AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 describe that same producer/carrier/recovery failure rather than a sibling semantic root.

Current exact eda6a758 closure:
- rule state and stageAutomaticKeywordSyncWithinTransaction() commit in one Room transaction;
- the handoff carrier stores exact rule revision/mode/request generation;
- WorkManagerHandoffRecovery observes enqueue acceptance, retries exact debt, validates worker revision/generation, and reconstructs missing owners at startup;
- the completed 136-ID audit classifies BUG-KEYWORD-03 VERIFIED_CLOSED.

The prior checkpoint-only reconciliation that counted AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 as a new distinct production root is superseded on lineage/count only. Its current CLOSED evidence remains valid for BUG-KEYWORD-03.

## Accounting effect

These historical/checkpoint-only identifiers add zero new semantic roots beyond the 136-ID population.
No canonical download count changes.
The repository total remains NOT_YET_VERIFIED until all remaining checkpoint-only candidates and aliases are exhausted.

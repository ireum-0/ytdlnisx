# Checkpoint-only production reconciliation — automatic-keyword scheduler handoff

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 8a2f009f1b8ce211ce1a486c1a54d87378637dd1
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 — VERIFIED_CLOSED

Historical root:
automatic-keyword rule state could commit QUEUED/pending sync responsibility before an unchecked
WorkManager enqueue, and no restart owner reconstructed a missing request.

Current exact eda6a758 closes that contract end-to-end.

Durable producer boundary:
- save(), setEnabled(), and syncNow() publish the rule revision/manualSyncStatus and
  WorkManagerHandoffRecovery.stageAutomaticKeywordSyncWithinTransaction(...) in the same Room transaction;
- the carrier records rule id, exact revision, exact mode, generation id, request id, unique work name
  and boundary;
- superseding rule revisions tombstone the prior carrier before publishing the new owner.

Asynchronous acceptance:
- dispatchAutomaticKeywordOwnerChange() drives the exact carrier through enqueueAndAwaitInternal();
- performAttempt() waits on the WorkManager Operation result;
- enqueue failure enters retryAfterFailure rather than being relabelled as accepted;
- accepted publication is finalized against the same durable generation.

Consumer authority:
- AutomaticKeywordRuleSyncWorker requires handoffId/requestId/generationId/boundary and exact WorkRequest
  UUID;
- isCurrentAutomaticKeywordSyncRequest() plus exact enabled rule revision/mode is revalidated before
  correctness-relevant mutations.

Restart recovery:
- WorkManagerHandoffRecovery.reconcile() calls reconcileAutomaticKeywordRules();
- every enabled rule durably QUEUED/RUNNING is compared with its exact outstanding carrier;
- a missing/mismatched owner is reconstructed transactionally and dispatched;
- outstanding carriers are then reconciled through the same acceptance/retry state machine.

Disposition:
AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 = VERIFIED_CLOSED.

The historical request-versus-acceptance loss window no longer exists without exact durable recovery
responsibility.

Project inventory effect:
- add one distinct checkpoint-only production root;
- current state: CLOSED;
- current download canonical inventory unchanged.

No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.

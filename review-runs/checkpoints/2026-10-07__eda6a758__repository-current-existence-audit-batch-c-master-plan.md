# Repository current-existence audit — batch C — remaining Master Plan F1-F22 roots

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: b495a8af096ce49d2de742a284366caf38e31ee9
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
master_plan_blob_observed: 507a97c1455793b272298e29f37b945f4cfb55d7

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## Master Plan scope fact

The historical Master Plan's explicit active defect inventory was F1-F22: 22 roots.
It was narrower than TASKS.md/TASKS_DELTA.md and is audited here as a distinct historical remediation set.

Batches A/B already audited six of those roots:
- BUG-BACKUP-01
- BUG-OBSERVE-01
- BUG-OUTPUT-01
- BUG-BACKUP-04
- BUG-KEYWORD-01
- BUG-METADATA-01

This batch audits the remaining sixteen.

## VERIFIED_CLOSED

### BUG-BACKUP-03
Disposition: VERIFIED_CLOSED.
Exact historical final closure:
- review-runs/checkpoints/2026-09-24T1530Z__ee7eea00__f11-final-independent-closure.md
- CLEAN / FIXED-CLOSED at ee7eea00 with all R1-R4 closed.
Current post-KGB production delta is scheduler-only and does not alter the fail-safe Restore coordinator.

### BUG-KEYWORD-02
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-14__f1a159db__f18-completed-wave-closure.md
- current RULE assignments are recomputed from current rule authority while manual state is restored from
  the undo snapshot.
No current post-KGB production change touches this contract.

### BUG-METADATA-02
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-11__36b43464__metadata-source-identity-closure.md
- CLEAN / FIXED-CLOSED at 36b43464.
Later metadata publication closure preserved this prerequisite; current delta is scheduler-only.

### BUG-DATE-01
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-13__97342490__date01-independent-closure.md
- CLEAN / CLOSED.
Current delta is unrelated.

### BUG-DATE-02
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-14__f6e7cf72__f16-date02-closure.md
- CLOSED_AT_F6E7CF72.
Current delta is unrelated.

### BUG-BACKUP-02
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-13__f20833d6__backup02-independent-closure.md.
Current delta is unrelated.

### BUG-BACKUP-05
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-13__a9586835__backup05-independent-closure.md.
Current delta is unrelated.

### BUG-BACKUP-06
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-13__f20833d6__backup06-independent-closure.md.
Current delta is unrelated.

### BUG-BACKUP-07
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-13__97342490__backup07-independent-closure.md.
Current delta is unrelated.

### BUG-BACKUP-08
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-13__1bae1eaf__backup08-independent-closure.md.
Current delta is unrelated.

### BUG-DUPLICATE-01
Disposition: VERIFIED_CLOSED.
Historical current-basis closure:
- 2026-09-12__93d01d2a__duplicate01-current-basis-revalidation.md
- CLEAN FOR F19 ROOT / CLOSED.
The separate duplicate-admission race was explicitly not merged into this root.
Current post-KGB delta is unrelated.

### BUG-CLEANUP-01
Disposition: VERIFIED_CLOSED.
Canonical historical closure:
- 2026-09-19T014000Z__3072ce86__f10-canonical-closure.md
- CLOSED / SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-VERIFIED.
Current post-KGB delta does not touch cleanup cadence/recovery source.

### BUG-HISTORY-01
Disposition: VERIFIED_CLOSED.
Historical closure:
- 2026-09-14__f6e7cf72__f17-history01-closure.md.
The later F18 rule-derived state issue was explicitly separate and does not reopen the History/playlist
relationship atomicity root.
Current post-KGB delta is unrelated.

### BUG-LOCALADD-01
Disposition: VERIFIED_CLOSED_CURRENT_RECONCILIATION.

Historical lineage:
- multiple 2026-09-14 reviews kept F20 OPEN while exact LocalAdd identity residuals were successively
  discovered;
- 55e54888 reached SOURCE-FIXED / EXECUTION-NOT-VERIFIED, not canonical closure;
- 90afaec1 still recorded the root OPEN because the required production execution had not yet been
  reconciled.

Current source:
- LocalAddWorker dedupes through LocalAddStorageIdentityPolicy.identityForEntry;
- filename/stem is not identity;
- content provider identity preserves exact Uri.authority plus exact opaque
  DocumentsContract.getDocumentId() without trim/case-folding;
- unknown identity fails open;
- file paths use canonical file identity;
- final HistoryKeywordAssignmentRepository.insertLocalHistory admission re-reads under
  HistoryReferenceMutationCoordinator + one Room transaction.

Later execution evidence:
- LocalAddWorkerProductionWiringTest contains direct production WorkManager tests for:
  provider-document prefix non-suppression,
  whitespace-distinct opaque document IDs,
  provider-authority namespace case distinction;
- later exact execution evidence records the complete class 6/6 with zero failures/errors;
- the later exact-final verification union reached 242 PASS / 0 FAIL / 0 SKIP / 0 ERROR;
- LocalAdd identity production source has not changed after its 55e54888 correction.

The historical execution gap is therefore closed by later evidence. This audit records the missing
current disposition without rewriting any historical OPEN checkpoint.

## VERIFIED_OPEN

### BUG-PLAYER-01 — P2

Disposition: VERIFIED_OPEN.

Current exact source:
VideoPlayerActivity.savePlaybackPositionForHistoryId:
- immediately updates process-local queue/cache state;
- launches one independent lifecycleScope.launch(Dispatchers.IO) for every persistence request;
- each coroutine later acquires HistoryReferenceMutationCoordinator and writes updatePlaybackPosition.

The coordinator serializes whichever coroutine obtains the lock first; it does not establish logical
submission order between independent launches. Activity lifecycle cancellation also owns those coroutines.
Therefore an older position submission can persist after a newer one, or a late required write can be
lost with Activity destruction.

Current correction boundary:
- one ordered application-scoped persistence owner per History ID or equivalent monotonic sequence;
- latest logical submission must dominate older submissions regardless of dispatcher scheduling;
- Activity destruction must not discard already-authorized durable position responsibility;
- deletion/replacement of the History row must remain correctly fenced under the History mutation domain;
- tests must deterministically invert coroutine execution order and include Activity teardown/recreation.

This root has no proven prior full closure in the reviewed lineage; classify as continuously OPEN, not
REOPENED.

### BUG-QUEUE-01 — P3

Disposition: VERIFIED_OPEN.

Current exact source:
- action-mode Up/Down captures getSelectedIDs() then calls putAtTopOfQueue/putAtBottomOfQueue;
- direct checked-item selection returns adapter.checkedItems without a final status reread;
- inverted/empty selection queries both Queued and WaitingForMembership;
- a row selected while Queued can transition to WaitingForMembership before the Up/Down effect;
- drag onMove has an explicit waiting-row guard, but that guard does not protect the action-mode path.

Current correction boundary:
- queue reorder authority must be derived from current Queued rows at the mutation boundary;
- WaitingForMembership and any row that changed away from Queued must be refused without order mutation;
- selected-ID snapshots are hints, not final reorder authority;
- batch reordering should preserve relative order of authorized current Queued targets and leave refused
  siblings untouched;
- deterministic race test: select A while Queued, transition A to WaitingForMembership before Up/Down,
  prove no reorder mutation for A.

This remains repository-wide P3 and does not affect the current download canonical P0/P1/P2 counts.

## Batch result

roots_audited: 16
verified_closed: 14
verified_open: 2
reopened: 0
not_verified: 0

Cumulative repository lineage progress after batches A-C:
- candidate IDs: 136
- audited: 39
- verified closed/currently not reproduced: 37
- verified open: 2
- reopened: 0 in these audit batches
- not yet audited: 97

Current download canonical remains:
- P0=0
- P1=0
- P2=8

No production source, prompt, active implementation scope, Master Plan or ledger was changed.

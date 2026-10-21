# WP0-B — adopted product policy and source-constrained implementation preflight

checkpoint_status: FINAL
checkpoint_kind: REVIEWER_SOURCE_PREFLIGHT
manual_review_run: NO
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
expected_review_parent: ca92e350afa99c765e93239aa84fcdd2d37d6912
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
previous_contract_blob: 11f8c0fb9a6371600cc7041793f911e53465c906
accepted_policy: SELECTED_UNDO_PROTECTED_UNSELECTED_INTERSECTION_DEFERS_RESTORE_RESTORE_FIRST_REVOKES_OLDER_UNDO; PER_MEMBER_ACCEPTANCE; TARGETED_RESTORE_PRESERVES_UNAFFECTED_VALID_P
review_verdict: POLICY_ACCEPTED_IMPLEMENTATION_BOUNDARY_NOT_YET_PROVEN
implementation_ready_for_full_repair: NO
separately_scoped_read_only_feasibility_preflight: YES
canonical_active_open_p2: 4
canonical_active_ambiguous_p2: 4
canonical_active_unresolved_p2: 8
canonical_status: NOT_CLEAN
root_count_delta: 0
source_edits: 0
tests_run: 0
manual_l2_run: IN_PROGRESS_UNCHANGED

## Source-confirmed contract
- `RestoreTransactionCoordinator.begin` writes the plan/journal and publishes its Restore active pointer under `RestoreMutationAdmission.withRestorePublication`; ordinary Undo acceptance uses `withOrdinaryMutation`. This gives a *candidate* ordering boundary for a final conflict decision before pointer publication, not a proven cross-record owner inventory. See RestoreTransactionCoordinator.kt L428–500 and RestoreMutationAdmission.kt L1–77.
- `DownloadRepository.persistSelectedUndoIntentWithBarrier` may durably record exact selected RESTORE/COMMIT intent in SharedPreferences fallback even if Room writes fail. `pendingUndoResolutionIntent` checks Room, fallback and process-local owner. A Room-only absence check is invalid. See DownloadRepository.kt L1360–1492 and L1640–1798.
- The existing Restore apply calls `clearRestoreTargetedUndoCarriers`, deleting targeted PendingUndo tokens by numeric snapshot ID without testing selected intent. It must not silently be treated as an implementation of the newly selected policy. See DownloadRepository.kt L451–475 and RestoreTransactionCoordinator.kt L981–1048.
- Restore APPLYING can rerun after its Room commit but before journal DATA_COMMITTED. Existing sidecars capture current numeric IDs once; they do not discover retired original A or capture replacement receipts. See RestoreTransactionCoordinator.kt L517–591 and L792–875.
- The ordinary DownloadWorker scans a queued Flow beyond priority IDs; actual `claimDownloadThroughProductionAdmission` lacks replacement-receipt check. A separate WorkManager name cannot be the sole execution guard. See DownloadWorker.kt L265–291, L1238–1319; DownloadSchedulerAdmission.kt L185–310.
- Manual Saved/Error/Cancelled reconfiguration deletes A before duplicate/queue/schedule acceptance. See DownloadMultipleBottomSheetDialog.kt L270–360. Current selected ID snapshot lacks exact incarnation. Independently adopted OPEN P2 root BUG-RECONFIGURE-PREADMISSION-DELETE-01 remains.
- Current handoff is stale: archived matching WP0-B02 Full Report has RESULT=CONTRACT_INCOMPLETE and permanently consumed intent+claim. Do not relaunch. No implementation or test edits are justified from the earlier diagnostic alone.

## Product decision and fail-closed edges
1. Selected Undo first: preserve the exact chosen RESTORE or COMMIT through effect convergence. Defer/reject overlapping Restore BEFORE publishing a new active owner; do not infer implicit COMMIT. Unselected relevant Undo: no automatic selection; return a reversible preownership defer with an explicit UI action to settle it, then retry independently. Restore first: only AFTER durable active-pointer publication, reject late *new* Undo acceptance with an honest result, without retroactively erasing a previously accepted decision. No blocking wait for ordinary Undo while Restore is active.
2. Multi-item operation: per member A/P independently passes current stamp, duplicate/archive/History/cancellation/privacy and authority preconditions; only a committed member may retire A. Failed member retains original and linked effects. Preserve previous successful members and later independent members after one member fails. Never claim a batch-wide all-or-nothing transaction.
3. Partial Restore: classify frozen original A category separately from exact current P category and linked History/source ownership. Reset of A's past category does not automatically revoke valid P; reset/reincarnation of P revokes its previous dispatch. If imported identity collides or decision remains unknown, fence P, retain exact old-effect debt and require explicit safe resolution. Do not restore old A, remap old cache to imported IDs or double-dispatch P.
4. Crash/replay and carrier: exact Restore operation ID + immutable original receipt preimage, same-Room-transaction disposition CAS, idempotent retry after Room/app-journal gap; retained all-state cleanup scanner after WorkManager acceptance/terminal/superseded. Generic deleteResolved/deleteAccepted/deleteExact paths must not erase owed replacement effects. Worker actual claim must consult a durable P-owner mapping or prove a revoked transfer to general scheduling. Unique work KEEP is not a claim gate.
5. Never add new Room table, migration, source global mutex or unproven schema discriminator without separate source proof and authorization. Check reverse R/E/L lock edges and synchronous Snackbar path. Old Room64/65, fallback-only Undo, duplicate/scheduled/partial failure, cache Unproven/RetryableFailure, incognito queue-frozen ON/OFF, and CLOSED notification/duplicate semantics are mandatory evidence obligations.

## Bounded next safe step
The user explicitly authorized prompt preparation, but the full production A→P repair remains NOT_VERIFIED because exact fallback+Room+process Undo intersection and selected-Undo-vs-Reset recovery priority, stable Restore receipt replay, generic Worker P dispatch authority, all deletion paths, and R/E/L lock closure lack proven implementations. A distinct **READ_ONLY, source-only, policy-feasibility** task can verify the exact prepublication exclusion predicate, owner transfer and smallest independently executable coding stage without altering production. It is not a replay of WP0-B02 carrier-only diagnostic and cannot mark this root CLOSED.

Retain manual L2 IN_PROGRESS; no current semantic root or canonical count change. This checkpoint is review evidence, not implementation.

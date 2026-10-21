# WP0-B — unsettled Undo and targeted Restore responsibility: bounded source-contract review

checkpoint_kind: INDEPENDENT_READ_ONLY_SOURCE_CONTRACT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: ae99f021a22c0e3761ea5f005ea92ad3b7880dbf
implementation_reviewed_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_reviewed_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
prior_wp0b02_independent_checkpoint_blob: 836a025dc0c7bec70e30d4b77c864441cd37bba0
prior_wp0b02_private_report_blob: 28d0124b062afccba712c61c77b4ce407e88aeb8
review_basis: CURRENT_PINNED_REMOTE_PRODUCTION_SHA_NO_SOURCE_CHANGE
verdict: FAIL_CLOSED_UNSETTLED_UNDO_POLICY_ESTABLISHED_RESTORE_TRANSFER_NOT_IMPLEMENTED
complete_implementation_contract_proved: NO
full_implementation_ready: NO
new_semantic_roots: 0
root_status_changes: 0
active_open_p2: 4
active_ambiguous_p2: 4
active_closed_p2: 2
active_unresolved_p2: 8
repository_total_roots: 152
repository_open_roots: 53
repository_ambiguous_roots: 4
repository_closed_roots: 95
canonical_clean: NO
source_or_test_changes: NONE
reviewer_build_or_test_execution: NONE
agent_task_or_prompt_authorized_by_this_review: NO
reviewer_handoff_mutation: NONE
ongoing_manual_l2_run: PRESERVED_IN_PROGRESS_NOT_THIS_RUN

## 1. Scope and independent source evidence

This bounded reviewer-owned continuation focuses only on the **contract**, not an agent run or a source repair. It uses the immutable WP0-B02 archived report as diagnostic input and independently re-reads the listed source boundaries on exact implementation `141b5199592369ab953f52c55ebdb72ba300f1a4`.

**VERIFIED SOURCE — Undo persistence and authority.** `PendingUndoCarrier` has exact token, kind, ownerId, authorityGeneration, presentationState, resolverGeneration, resolutionIntent and removal snapshotJson; valid intentions include UNRESOLVED, RESTORE_PENDING, COMMIT_PENDING, RESTORE and COMMIT. `PendingUndoCarrierDao.recordResolutionAttempt` and `recordResolution` preserve durable selection transitions and `getAll` enumerates tokens. The real removal producer `DownloadRepository.removeDownloadTransaction` (L571–656) inserts a REMOVAL carrier with frozen DownloadRemovalSnapshot inside the Room deletion transaction; posttransaction process-local token release is a separate effect. `DownloadRepository.acceptUndoResolution` (L1730–1798) takes ordinary Restore admission and first persists selected intent; `resolveSelectedRemovalUndo` (L848–889) runs the selected RESTORE/COMMIT under ordinary mutation. `restoreRemovalSnapshot` (L2011–2140) uses exact carrier presence as once-only claim and may insert a restored Download at original numeric ID when vacant or allocate another ID, subject to stronger ledger/observe-source checks. Thus a still-authorized RESTORE might insert A or an A successor after a different A→P exchange unless the old authority is excluded/settled by a proven rule; this is a candidate future-interaction hazard, **not** a claim the nonexistent current A→P carrier is already active.

**VERIFIED SOURCE — unresolved choice must not be guessed.** `LowQualityRedownloadRepository.reconcileAbandonedUndoDebt` (L128–205) keeps a PUBLISHED carrier with no durably accepted intent under an exact token recovery owner and returns false; it does NOT silently infer COMMIT in that case. Pending cancellation ledger entries have their own token/reasonCode authority; process-local pending and resolver-in-flight states can prevent debt resolution. A successful durable intent is not equivalent to completed resolver side effects.

**VERIFIED SOURCE — Restore scope and limits.** `RestoreTransactionCoordinator.begin/drive` (L428–591) publishes a durable Restore owner, quiesces WorkManager/native download namespaces, then applies Room and reconciles postcommit in durable phases. During `applyAuthoritativeState` (L930–1052), `targetedDownloadIds` is built from CURRENT rows matching reset status categories and `clearRestoreTargetedUndoCarriers` is called on those IDs. `DownloadRepository.clearRestoreTargetedUndoCarriers` (L451–475) matches removal snapshots' old numeric Download IDs and low-quality pending item IDs, then deletes matching tokens; this routine has no exact replacement-receipt provenance or per-effect obligation transfer. A *retired* original A absent from current Download rows cannot be reliably discovered by the current `targetedDownloadIds` query. Restore then deletes targeted categories, re-inserts backed-up Download rows at new allocated IDs (L1130–1153), and after commit schedules existing final-image runnable Queued/Scheduled Download rows (L1611–1656) without any replacement-specific receipt transfer. WorkManager cancel-by-tag during Restore quiescence is not proof of original A cleanup discharge.

**VERIFIED SOURCE — carrier does not yet protect replacement cleanup.** Existing `WorkManagerHandoffRecovery.finalizeAccepted` can delete accepted non-retained kinds, generic DAO `deleteResolved` purges all resolved, `reconcileSuperseded` deletes after null/terminal WorkInfo, and `DownloadWorker` interprets a nonblank handoffId as SCHEDULE_START. As documented in the independently reviewed WP0-B02 checkpoint, those are falsifiers for blind replacement-kind reuse; no new replacement implementation has been written.

Exact code anchors:
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/models/PendingUndoCarrier.kt#L7-L40
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/PendingUndoCarrierDao.kt#L12-L104
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L571-L656
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1730-L1798
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L2011-L2140
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/LowQualityRedownloadRepository.kt#L128-L205
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L451-L475
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L930-L1052
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L1130-L1153
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L1611-L1656

## 2. Conservative Undo admission contract — a reviewed safety decision, NOT shipped behavior

**CONTRACT DECISION:** In a future A→P exchange, reject before original retirement when *any potentially intersecting Undo authority is unsettled*. This is a fail-closed **admission policy**, not ownership transfer or automatic choice settlement. The exact original observation (database/row incarnation/revision, allowed A status, linked operation) and mapped P must already be proven. Current ID-only selection cannot satisfy this precondition.

Per relevant original A / linked operation, treat the following as ineligible for an exchange until exact authority is resolved:

| Token state or owner | Future A→P admission | Required safe interpretation |
|---|---|---|
| No matching durable PendingUndoCarrier, no pending low-quality token, no relevant selected in-flight process authority AND exact A/P observation still current | May proceed to other admission checks | Absence MUST be revalidated in the final authoritative R/Room boundary; it is not a standalone completion signal |
| UNPUBLISHED producer-prepared/carrier-committed | REFUSE | A producer may publish or transfer token before it is settled; no inferred abandonment |
| PUBLISHED UNRESOLVED, or unknown selection after restart | REFUSE | Cannot infer COMMIT or erase a possible RESTORE |
| RESTORE_PENDING, RESTORE, resolver active/failed/recovery-owned | REFUSE | Preserve accepted restore intent; resolution may write a restored Download |
| COMMIT_PENDING, COMMIT, resolver active/failed/recovery-owned | REFUSE until exact durable convergence | Accepted COMMIT is not proof all ledger/barrier/cleanup mutations have finished |
| CANCELLATION token/linked low-quality CANCELLATION_REQUESTED or indeterminate ownership | REFUSE | Do not silently consume sibling cancellation or parent authority |
| Malformed snapshot, uncertain old-generation identity, fallback intent not matched to exact DB owner, or cross-process authority indistinguishable | REFUSE | No numeric-only claim; preserve evidence and permit explicit user refresh/re-entry when safe |

**Completion/quiescence signal for eligibility:** A positive, final same-transaction check under the Restore admission gate that the current exact original incarnation and P mapping still hold, no intersecting authoritative PendingUndoCarrier/low-quality token/accepted-but-unfinished resolver debt remains, and no accepted process-local/fallback intent can race in a stronger owner. A check of absent rows alone, UI dismissal, elapsed time, a specific numeric ID or `COMMIT` Boolean is not equivalent. Existing code does NOT supply this whole composite predicate. Until such a predicate is proven, REFUSE with original A and linked responsibilities untouched. No new Undo table, silent ownership transfer, or general Undo disablement is approved.

**Regression obligations (unexecuted):** published unknown token on crash; RESTORE_PENDING followed by resolver retry; COMMIT_PENDING interrupted after intent before effect; A removed then reinserted under a new ID; linked cancellation sibling; two concurrent A→P attempts; stale UI after process restore; preflight-refusal must not delete A, mutate owned cache, erase Undo or publish runnable P; accepted UI intent must remain true after rejected exchange. The test needs actual producer/resolver/restart, not a forged carrier alone. A final candidate's thread-affinity checks must ensure synchronous Undo Snackbar acceptance is not blocked on unbounded new leases.

## 3. Restore responsibility-intersection and transfer contract — REQUIRED, NOT IMPLEMENTED

**CONTRACT DECISION:** A replacement receipt must have independently durable identity and remain live through dispatch acceptance and supersession while any exact original A cleanup / P delivery / external-effects acknowledgment is owed. A targeted Restore that may intersect it MUST either establish a durable exact reconciliation/transfer decision in Restore's Room apply transaction or remain fail-closed before destructive effect. Existing source supplies neither a replacement receipt producer nor its Restore consumer.

A later implementation must bind each receipt to immutable A original status/category, A source database/row generation and original owned cache/ledger/Undo/notification identities, admitted P incarnation/operation/execution/request lineage, P current owner/category, and a versioned per-obligation progress state. Matching only the current Download ID set, numeric token, saved original ID or current WorkInfo is insufficient. Restore intersection shall consider (1) whether reset categories or History/low-quality/Undo authority touch retired A's frozen *semantic* owner, (2) whether current P is in a reset category or affected History/source, and (3) whether restoration changes root/cache/notification identity owed by the receipt. Distinguish these intersections; do NOT infer that resetting A's old category alone always mandates canceling an otherwise unaffected P. Where intersection cannot be disproved from exact frozen provenance, block the replacement dispatch and retain the receipt until the rule is resolved rather than executing guessed transfers.

Required state transitions inside a future validated contract:
1. **Restore preparation**: publish existing durable Restore owner and quiesce affected work/native/cache without inferring null/finished WorkInfo proves effect discharge; reject fresh exchange under Restore admission.
2. **Restore Room apply**: discover ALL affected and unaffected replacement receipts, including those whose A is already retired and absent from current Download rows. Freeze exact affected P request generations and revalidate the observed A/P/category/linked authority. In the SAME Room transaction as target-category reset, persist affected dispatch revocation and independently retained/transferred external cleanup debt with exact old roots and operation/execution stamps. Do not remap old cleanup resources onto new imported Download IDs; do not consume or fabricate Undo RESTORE/COMMIT.
3. **Post-commit**: recovery must discover any outstanding replacement receipt and replay idempotent exact cache/ledger/notification obligations or explicitly discharge by ownership-safe terminal skip. A P deleted/replaced by target Restore must NEVER be restarted via stale carrier; an unaffected legitimate P whose WorkManager namespace was quiesced must be redispatched exactly once via reconciled request authority. Existing global `startDownloadWorkerForRestore` final-image scan alone is not a replacement-specific acknowledgment or duplicate-prevention guarantee.
4. **Final retirement**: only when delivery/execution obligation was correctly revoked/transferred/finished, request-race safety is proved and every frozen external obligation is semantically acknowledged. `Unproven` cache ownership is not permission to delete; an exact preserved-unproven terminal skip can be recorded only if it meets adopted cleanup policy, and RetryableFailure remains pending.

**Crucial compatibility boundary:** The current `clearRestoreTargetedUndoCarriers` discards tokens by selected *current* numeric row IDs and the Restore apply path does not contain the proposed replacement receipts. Its existing intended reset semantics are NOT revised by this review. Extending it to replacement state or overwriting Undo carrier intent requires explicit separate source contract and tests. Portable backup/Restore remaps Download IDs and does not serialize app-local replacement cleanup authority; startup recovery must not infer new replacement receipts for Room64/65 old rows or treat old carrier kinds as replacement. A new kind/JSON in existing storage remains a candidate, not a proven migration-free safe correction.

**Counterexample, future naive extension only:** A was Saved and retired in an atomic exchange, P Queued; the only frozen A cleanup debt is stored in an ordinary handoff. Restore resets Saved but A is absent from current-row targetedDownloadIds. The reset and later reconcile therefore have no current A to discover, while WorkManager namespace may be canceled. If generic accepted/resolved/superseded cleanup deletes that handoff, a crash leaves A's cleanup debt unowned; alternatively a generic runnable-P rescan may dispatch with no exact P replacement claim. This is a falsifier of **blind reuse**, not a new confirmed shipped root.

**Material NOT_VERIFIED dependencies:** precise intersection semantics when Reset affects only original A category vs P category, target History/lowquality and existing accepted Undo; recovery/wakeup and effect acknowledgement representation; all direct carrier lifecycle deletions and generic writer/worker contracts; R/E/L reverse edges and UI thread-wait safety; old supported Room64/65 real migration/reopen and original selection-generation trust. These are not silently approved by a design sketch. Implementable full repair remains NOT_VERIFIED and no production edits are authorized.

## 4. Outcome and next boundary

The safe policy for unsettled Undo is **reject and preserve**, not guess/transfer. The Restore invariants are now explicit: exact provenance-based intersection, same-transaction durable dispatch revocation + cleanup-retention/transfer, startup recovery and exact final discharge. The CURRENT source does not implement that extension or prove every affected consumer and lock order. Consequently no full A→P implementation is launch-ready; `BUG-RECONFIGURE-PREADMISSION-DELETE-01` remains OPEN P2, `BUG-INCOGNITO-01` remains OPEN P2, and the previously CLOSED notification/duplicate boundaries remain CLOSED. No new semantic root, severity, root-count or CLEAN-basis change.

Only further independently authorized source-contract review can settle the remaining intersection decision, exact Undo fallback/process authority and cross-feature consumer closure; an implementation authorization must separately name producer/recovery/Restore/Undo/lock-order/source/test/migration scope and must not reuse consumed WP1-04/WP0-B02 instances. This user request authorized source-contract review/checkpoint only. Do NOT reroute NEXT_CHAT.md, author/persist another agent prompt, mutate implementation/Plan/ledger, finish the existing manual L2 run or bootstrap a consumed task.

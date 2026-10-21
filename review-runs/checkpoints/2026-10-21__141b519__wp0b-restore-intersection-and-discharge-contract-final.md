# WP0-B — Restore intersection, carrier transfer, and final discharge: bounded design contract FINAL

date: 2026-10-21
checkpoint_kind: INDEPENDENT_SOURCE_BASED_DESIGN_CONTRACT_FINAL
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_expected_parent: 5f69fe7cac2606c0f2e6b900da3f564711f5f81e
implementation_reviewed_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
previous_undo_restore_contract_blob: cee74527ac0cc1d9d5472c3b589da15fbbf4e12d
wp0b02_independent_review_blob: 836a025dc0c7bec70e30d4b77c864441cd37bba0
wp0b02_agent_report_blob: 28d0124b062afccba712c61c77b4ce407e88aeb8
verdict: DESIGN_RULES_BOUNDED_AND_REVIEWABLE_IMPLEMENTATION_NOT_READY
source_proven_existing_replacement_carrier: NO
proposed_contract: REVIEWER_DESIGN_ONLY
new_root_delta: 0
root_status_delta: 0
active_open_p2: 4
active_ambiguous_p2: 4
active_closed_p2: 2
active_unresolved_p2: 8
total_repository_roots: 152
repository_open_roots: 53
repository_ambiguous_roots: 4
repository_closed_roots: 95
canonical_clean: NO
full_implementation_ready: NO
source_edits: 0
test_executions: 0
reviewer_handoff_write: NO
new_agent_prompt_or_source_authorization: NO
prior_manual_l2_run: PRESERVED_IN_PROGRESS

## A. Exact production basis and source-confirmed facts

1. The real `RestoreTransactionCoordinator` durable phases are PREPARED, QUIESCED, FILES_READY, APPLYING, DATA_COMMITTED, RECONCILING, COMPLETE (L69–76, L517–591). `RestoreGate.admission` defers while the durable active Restore pointer exists or the pointer is unreadable (L319–335); `begin` publishes it before external quiescence (L428–500). This gate is necessary but alone is not a replacement-receipt transfer.
2. `quiesce` computes `hasDownloadReset` from History or any reset Download category, and then cancels DownloadWorker/download/scheduled/cache/format tags and checks Worker plus native ownership (L629–770). Therefore a P in an *unreset* download category may still have its WorkManager request canceled. It requires independently owned dispatch reconciliation, not blind permanent revocation.
3. `capturePostCommitSidecars` (L792–875) is journal-idempotent and captures old CURRENT numeric category download/notification IDs once before Room apply, precisely because APPLYING may be replayed after an SQLite commit and before DATA_COMMITTED journal publication. It does not capture retired A from any replacement receipt. `applyAuthoritativeState` (L925–1265) selects `targetedDownloadIds` from CURRENT rows by reset status, deletes targeted Undo and attached ledger/barrier data, supersedes existing schedule kinds, deletes target categories and inserts imported rows with newly allocated IDs (L981–1048, L1130–1153). Original retired A is absent from that query.
4. `reconcilePostCommit` (L1499–1656) cancels recorded numeric notification IDs, reconciles other namespaces, and requeues final-image runnable Queued/Scheduled rows if the Download namespace was quiesced. This is not an exact request-generation acknowledgment for a replacement receipt; a generic queue scan must not independently authorize a second execution.
5. `PendingUndoCarrier` and DAO have durable token, publication/intent/resolver generation, while `DownloadRepository.persistSelectedUndoIntentWithBarrier` (L1640–1728) also supports a fallback durable selected intent when Room fails. `LowQualityRedownloadRepository.reconcileAbandonedUndoDebt` (L128–204) does not infer COMMIT from a PUBLISHED unresolved carrier. `restoreRemovalSnapshot` (L2011–2140) can reinsert a snapshot at original numeric ID or a freshly allocated ID when the selected RESTORE and stronger source/ledger checks permit. These are real authorities, not disposable UI state.
6. `WorkManagerHandoffCarrier` has no replacement kind/per-effect progress fields (entity L23–61). `WorkManagerHandoffCarrierDao.getOutstanding` scans only PENDING_ENQUEUE/ACCEPTED, `getSuperseded` scans SUPERSEDED; generic `deleteResolved` removes every RESOLVED row (DAO L17–22, L80–82, L178–182). `WorkManagerHandoffRecovery.reconcile` deletes RESOLVED before discovery (L1237–1261), generic acceptance and retry paths delete accepted carriers (L1516–1642, L2118–2201, L2226–2390), and superseded terminal WorkInfo can trigger `deleteExact` (L1486–1515). `replaceOutstandingAndInsertLocked` has kind/boundary scoping and may delete outstanding non-retained kinds; `deleteBoundaryAfterExternalCancellation` also deletes outstanding/superseded by kind/boundary (L2450–2568). All these call surfaces are lifecycle review gates. Worker nonblank handoffId currently means scheduler SCHEDULE_START (DownloadWorker L1140–1158); unknown kinds fail buildRequest (handoff recovery L2754–2774).
7. Room `FormatAuthoritySchema` has download incarnation/revision triggers on INSERT/UPDATE/DELETE and backfill on database open (L7–100), but the existing selected UI `Long`/row projection does not carry a trusted exact observation stamp. `DownloadDao.updateRaw` and full-row update remain writer-closure hazards (L673–701). No selected pending Undo or Restore rule here is a production A→P feature implementation.

## B. Final review-level decision: do NOT transfer or guess unsettled Undo

**Policy: REFUSE BEFORE COMMIT.** A proposed exchange can retire A only after an exact authoritative selection snapshot and P generation are current, plus there is positive evidence that no intersecting durable removal/cancellation Undo, live owner, accepted intent, fallback intent, resolver-in-flight or linked low-quality token can write an A/linked-authority successor. A mere `pending_undo_carriers` row absence is *not* positive proof when a publication is in flight, fallback intent may exist, or selected resolver has not converged. Existing process-local fallback/Undo owner APIs do not supply a proven single Room-only negative predicate; if complete locking/fencing cannot be demonstrated, **deny replacement**, leave A/P user-visible consequences unchanged, and preserve original Undo recovery.

Status rules: UNPUBLISHED/potential producer, PUBLISHED unresolved, RESTORE_PENDING/RESTORE, COMMIT_PENDING/COMMIT before durable final resolver convergence, cancellation token, UNKNOWN and corruption -> REFUSE. Both RESTORE and COMMIT are immutable accepted user choices; no automatic compensation, transfer, deletion or silent selection of COMMIT. Only after exact token convergence, no stronger source/ledger owner and final A/P observation revalidation may the member return to candidate pre-admission. Do not add blocking leases to synchronous main-thread Snackbar acknowledgment. This is a design decision, **not a claim a composite cross-process exclusion API already exists**.

## C. Deterministic Restore intersection matrix for a proposed exact A→P receipt

Define immutable receipt owner `X=(source database identity/lifetime; original A id+incarnation+revision+status/category+operation+owned cache/Undo/barrier/notification identities; admitted P id+incarnation+attempt operation; member+privacy action; version; request-generation lineage)`. Define `C` = exact reset categories, `H` = History/primary-success/linked low-quality reset, `S` = observe-source/other downloaded-owner reset; `Q` = Download WorkManager namespace quiesced; `Old` = frozen original A obligations; `New` = current exact P owner. Matching current numeric download ID, old Bundle, absent current A row, or string-format equality alone is prohibited.

**Proposed decision sequence within Restore's proven authority:**
1. Publish Restore owner, stop new replacement admission, inventory ALL replacement receipts including A already retired, quiesce jobs/native and relevant effect leases. Before Room mutation, capture immutable old receipt owners/effects in a repeatable durable representation. Restore's own `sidecarsCaptured` must not be confused with receipt capture.
2. Under the **same** Restore `applyAuthoritativeState` Room transaction that resets category rows, re-read each exact receipt and classify by frozen A category, P's verified CURRENT incarnation/category, History/source/linked authority intersection, and pending Undo snapshot provenance. If a necessary provenance cannot be reconstructed, FENCE with exact retained debt; do not infer a safe negative intersection.
3. Apply the matrix below. `P_KEEP` means keep logically authorized successor, **not** assume its old WorkManager request still exists. `P_REVOKE` means invalidate old dispatch without deleting frozen A effects; target Restore owns no arbitrary A restoration. `P_FENCE` prohibits further claim while authority is ambiguous.

| Exact condition | P after Restore Room reset | Frozen A/external debt | Required post-commit action |
|---|---|---|---|
| No A, P, History/source/Undo owner intersection and Q=false | P_KEEP | Keep until exact ack | Ordinary exact receipt recovery without new dispatch |
| Only frozen A's former category in C; current P category/linked owners not reset, no duplicate/import collision, exact final P unchanged | P_KEEP | Keep all Old exact cleanup | If Q=true: revoke/fence old request generation, then reissue eligible P exactly once with new request authority; if Q=false preserve its current exact dispatch |
| Former A category reset and a newly imported A-equivalent/duplicate or shared stronger History/operation is possible but cannot be ruled out | P_FENCE | Retain Old and P request tombstone | Validate final imported identity and duplicate/owner semantics; never silently delete P or dispatch it before proof |
| P's own verified category belongs to C, or P is replaced/deleted/reincarnated by Restore | P_REVOKE | Retain/transfer Old; keep no resurrection authority | Revoke exact P request, block stale callbacks and any restart of old P; only final new-image owner may dispatch |
| History/primary-success/linked operation or observe-source reset invalidates receipt's true authority, even when P category not C | P_FENCE, or P_REVOKE only after exact invalidation proof | Retain frozen Old identities; revoke only proven invalid execution owner | Revalidate surviving P and unrelated siblings; if unknown, leave blocked for durable recovery |
| Q=true with P otherwise unaffected and no semantic collision | P_KEEP, dispatch generation fenced | Retain Old | Determine old WorkManager request fate and exactly-once safe re-publication under new lineage, not two parallel queue-wide owners |
| Restore touches only unrelated row/category and Q=false | P_KEEP | Retain | No change to receipt beyond safe progress checkpoint |
| Any currently accepted pending Undo decision or identity collision | P_FENCE / fail closed | Preserve exact accepted choice and cleanup debt | No guessed COMMIT/RESTORE, no deletion or member acceptance until resolved |

Reset of A's former category **alone is NOT proof P must be revoked**; equally it is NOT proof P can safely run if Restore reimports a duplicate or rewrites associated History/low-quality ownership. Decision is based on exact current P+linked authority, not A's historical category alone. A's frozen cache root must never be remapped onto imported A's new ID/incarnation.

## D. Carrier-specific durable state and completion rules

**Preferred conditional substrate:** a NEW discriminated, versioned replacement KIND using existing Room handoff TEXT/fields if exact SQL/reader closure proves sufficient; a separate new table, UUID or global mutex is not assumed. `handoffId` is immutable per exchange; `generationId/requestId` identify one dispatch lineage, not all cleanup. A typed versioned payload must encode frozen Old obligations, separate P delivery, actual Worker claim and effect progress, and exact Restore operation ID + per-receipt decision. Mutable progress needs `WHERE kind=? AND handoffId=? AND generationId=? AND requestId or expected lineage=? AND prior payload/progress version=?` CAS. Reject malformed/unknown version without deletion or execution. Never allow old kinds/old64/65 rows to gain replacement authority from a default.

**Independently owned state dimensions (never collapse):**
- `exchangeAdmission`: one member committed Room A retirement + P Queued/Scheduled (or Saved without dispatch) + exact receipt in ONE transaction. If any CAS/capture/insert fails, roll back A, linked owners, P and receipt, not compensate from a snapshot after external effects.
- `dispatch`: PENDING / ENQUEUE_UNKNOWN / WM_ACCEPTED / CLAIMED / TERMINAL / REVOKED / REPLACED. Operation.result success is **only WM_ACCEPTED**, not Worker claim, execution completion or original cleanup ack. Null/finished WorkInfo never proves that no late enqueue can run; retain exact request tombstone until authoritative lifecycle closure.
- `effectDebts`: exact frozen cache, Undo, ledger/barrier, membership/notification and scheduler/History external work, each NOT_DONE / RETRYABLE / ACK_COMPLETED / ACK_SUPERSEDED / ACK_PRESERVED_UNPROVEN where policy permits. Each effect is retried idempotently after restart with exact original owned resource and terminal progress CAS. `RetryableFailure` stays owed. `Unproven` is not physical deletion; document preserved-unproven disposition only under a validated policy.
- `restoreDisposition`: NONE / A_ONLY_P_PRESERVED / AFFECTED_P_FENCED / P_REVOKED_OLD_EFFECTS_RETAINED / P_RESUMED_NEW_REQUEST; every decision binds exact Restore operation ID and old/new generation and is idempotent across APPLYING replay after Room commit. Re-execution of Restore must not recapture imported rows as original A or spend the same exact cleanup twice.

**Hard ordering:** Before any proposed replacement producer is enabled, patch/audit all new-kind paths in `WorkManagerHandoffRecovery.reconcile`, `reconcileCarrier`, `reconcileSuperseded`, `finalizeAccepted`, `retryAfterFailure`, `replaceOutstandingAndInsertLocked`, `deleteBoundaryAfterExternalCancellation`, DAO `deleteExact`/`deleteAccepted`/`deleteResolved`/kind-boundary delete and all other writers; route replacement receipts to a kind-specific discoverer *before* generic deletion. Accepted/terminal/superseded dispatch cannot delete a receipt with outstanding effects. New `DownloadWorker` discriminator must not alias SCHEDULE_START. Preserve old kinds' contracts. State transitions with side effects must use exact immutable identity, versioned payload and Room CAS; WorkManager waits and filesystem effects are outside the Room transaction, followed by exact conditional ack.

**Last-owner retirement predicate:** Finalizer may mark receipt RESOLVED only if, atomically under exact generation/progress CAS: (a) no still owed dispatch/worker execution/retry or possible late enqueue is unaccounted for; (b) all original effect debts are ACK_COMPLETED, ACK_SUPERSEDED or justified policy-specific ACK_PRESERVED_UNPROVEN, and no RetryableFailure remains; (c) accepted Undo choice has been fulfilled by its proper original authority, not overwritten; (d) any Restore transfer has a durable successor owner/terminal decision and RECONCILING final image cannot redispatch old P; (e) no linked stronger operation or newer incarnation is touched. Generic `deleteResolved` may remove this *already fully discharged* record only if RESOLVED CAS cannot be bypassed; ideally kind-scoped explicit finalization and generic protection. Neither worker SUCCESS, `Operation.result` acceptance, null/finished WorkInfo, UI dismissal, nor one numeric ID match is sufficient. Crash before effect ack replays idempotently; crash after progress CAS but before deletion is safe; crash after Room restore apply but before journal DATA_COMMITTED re-runs same operationId-scoped decision.

**Startup/restart owner:** `App` runs Restore recovery before ordinary handoff and cancellation recovery (App.kt L175–230). Replacement recovery must obey that gate and not dispatch while Restore is active. After RECONCILING, one durable scanner must find both outstanding dispatches and receipt-only cleanup debt, including revoked/superseded requests outside `getOutstanding`. A missing WorkInfo alone must never allow a second executable attempt without exact generation revocation/negative proof. Any recovery unknown-version, conflict or uncertain authority preserves record and reports blocked, not automatic deletion.

## E. Source-semantic and execution closure gates (not run)

These are explicitly NEW proposed production-wiring tests and code-review obligations, **not existing PASS results**:

1. Saved-only reset: A retired Saved, valid P Queued not targeted, WorkManager namespace canceled, no restored duplicate: retain A cleanup; P reissues once under new generation; no old P worker claim.
2. Saved-only reset with an imported Saved duplicate/linked History conflict: P FENCED, not auto-deleted, exact semantic conflict resolved before claim. Same for an unrelated concurrent replacement.
3. Queued-only/Scheduled-only reset directly targets P: exact P dispatch REVOKED, A cleanup debt survives; restored rows may receive new IDs and cannot inherit old request/old cache.
4. History-only reset while P category survives, or low-quality ledger/observe-source reset: intersect exact linked owner; cannot restart P until validated. Unaffected siblings preserved.
5. Crash at Restore active-pointer publication, quiescence, after sidecar capture, after Room apply before DATA_COMMITTED journal, during RECONCILING, before/after each cleanup ack and before deletion: recovery uses exact operationId, retains obligations, no duplicate P or A resurrection.
6. Undo published unresolved / RESTORE_PENDING / RESTORE, COMMIT_PENDING / COMMIT effect pending, in-flight fallback record and no Room row, and linked cancellation: rejected exchange leaves original owner intact; user choice preserved after process death. Check old numeric ID reused/Restore remapped.
7. Request accepted before cleanup, unknown/null WorkInfo, request terminal/supersession and late Operation.result: no cleanup loss or second claim. Verify existing schedule and format kinds unchanged.
8. Cache marker mismatch/old root replaced, Unproven and RetryableFailure: no deletion of newer owner or unsupported path; progress acknowledged or retained as precisely specified, including restart.
9. Exact original and P selection generations, two concurrent P, privacy OFF→ON→OFF with updated action revision, partial batch member failures, dismissal and generic full-row writer/DAO/old64–65 reopen: no stale owner admission, no implicit incognito changes, preserved CLOSED notification/duplicate roots.
10. Deterministic L→E→R vs R→E / R→L cyclic-wait interleavings, Undo Snackbar synchronous acceptance and Restore/native quiescence; audit every reverse acquisition and independent cancellation debt owner. Test does not equal semantic proof by timeout alone.

**Blocking NOT_VERIFIED evidence:** The current source has no replacement-kind producer/worker/Restore consumer, no full cross-process composite Undo exclusion, no all-writer CAS closure, no adopted imported-duplicate policy proof, no authoritative late-enqueue fencing for Q, no complete R/E/L reverse-edge closure, and no exact-final-SHA runtime test or old-generation reopen result. These are concrete implementation-stage obligations; the design above is a bounded proposed contract, not a certified implementation. Do not set FULL_IMPLEMENTATION_READY=YES or label BUG-RECONFIGURE-PREADMISSION-DELETE-01 CLOSED on this basis.

## F. Recommendation and authority boundary

The minimal repair candidate is a per-member Room atomic A→P exchange plus existing-kind-discriminated retained carrier with independent dispatch/effect progress, **only if** implementation resolves B–E. If not, the safe separately authorized containment is to decline destructive reconfiguration before deleting A (preserve existing Undo/Restore/History), explicitly reporting refused members without claiming full feature closure. A staged compensation design is not independently proved. Logical future source order: (i) exact UI observations/Undo gating and lock caller audit; (ii) carrier kind/DAO/recovery/Worker without a live producer; (iii) Restore intersection/transfer and idempotence; (iv) enable atomic producer under all preconditions; (v) source-semantic review and focused+broader actual tests, old64/65 reopen, user privacy, CLOSED paths. Never enable an unsupported producer between commits.

This reviewer continuation is only authorized to make an append-only reviewer checkpoint; it does not authorize implementation, tests, device, private lifecycle fields, a new prompt, or source mutation. The separate manual L2 run stays IN_PROGRESS at its pinned basis, not FINAL here. No canonical finding or count changes, and no CLEAN basis advancement. **The requested bounded design-contract review is complete; a working implemented fix is not established.**

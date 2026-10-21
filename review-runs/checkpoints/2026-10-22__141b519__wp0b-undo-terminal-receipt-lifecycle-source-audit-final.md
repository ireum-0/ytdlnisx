# WP0-B Undo terminal-receipt lifecycle — source audit and guarded design supplement

checkpoint_kind: INDEPENDENT_BOUNDED_SOURCE_AUDIT
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: ac17e938f42d257ec110a70c38c23069f15f6451
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
source_changes: NONE
test_execution: ZERO
private_write: NONE
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
preceding_inventory_checkpoint_blob: edc60f04c995a9680f5c4b2d4b5763cd4ae31ab8
WP0B03_report_blob: 32e061767277344a902e8d7958ed78cb22338e2f
review_result: EXISTING_CARRIER_CAN_NOT_JUST_BE_RETAINED_TERMINAL_PROGRESS_DESIGN_STILL_NOT_SOURCE_CLOSED
new_semantic_roots: 0
root_state_delta: 0
canonical_P2_OPEN: 4
canonical_P2_AMBIGUOUS: 4
canonical_P2_UNRESOLVED: 8
canonical_all_OPEN: 53
canonical_all_AMBIGUOUS: 4
canonical_all_CLOSED: 95
canonical_clean: NO
manual_L2_run: IN_PROGRESS_UNCHANGED
full_implementation_ready: NO
first_bounded_implementation_stage_ready: NO
new_agent_prompt: NO
next_chat_changed: NO

## 1. Source-confirmed terminal effect and current deletion behavior

**Removal COMMIT.** `DownloadRepository.commitRemovalSnapshotLocked` handles no-linked-item by deleting PendingUndoCarrier and returning, or commits the exact pending low-quality child, conditionally settles the parent and deletes the carrier, all inside `database.withTransaction`. A stronger authority/different generation branch also deletes exact carrier. This yields durable Room effects but NO surviving token-keyed terminal outcome. Source [DownloadRepository.kt L1960–2013](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1960-L2013).

**Removal RESTORE.** `restoreRemovalSnapshot` uses the carrier's presence as a once-only transaction claim; it checks `pendingUndoCarrierDao.get(token)` before re-inserting the saved download and rebinds a linked low-quality child or History barrier, then deletes the token INSIDE the successful Room transaction. On parent/source authority loss it instead commits/consumes the token. A terminal marker cannot simply be added AFTER this transaction: there is no durable carrier left if process death intervenes. Nor may a retained terminal carrier be interpreted as the original once-only restore capability, or a replay could insert another row. [DownloadRepository.kt L2020–2168](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L2020-L2168).

**Cancellation RESTORE / COMMIT.** `restorePendingCancellation`, recovery restore and `commitPendingCancellationLocked` update the Download/linked low-quality operation then delete the PendingUndoCarrier in their Room transactions. A selected RESTORE can yield a stronger-authority non-restoration terminal result when the parent is cancelled, terminal, absent or changed. Consequently **accepted intent is NOT itself the final-effect disposition**: any terminal receipt must name what was actually applied or safely superseded, without rewriting the original selected RESTORE as an unselected COMMIT. [DownloadRepository.kt L3816–4028](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L3816-L4028); [L4050–4228](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L4050-L4228).

**Postcommit cleanup.** `finishUndoResolverSuccess` removes process-local authority/snapshot before `clearUndoIntentFallback`. The latter uses R/SharedPreferences `commit()` but catches failures. Thus (Room effect + carrier delete) and (independent fallback removal) are NOT one transaction. A fallback-only selected record after process death can also be outstanding even if Room intent update failed: absence of Room's final intent does not license a COMMIT. [DownloadRepository.kt L1360–1492](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1360-L1492), [L1640–1730](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1640-L1730), [L1895–1939](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1895-L1939).

**Current Room representation.** `PendingUndoCarrier` has token/kind/ownerId/authorityGeneration/resolutionIntent/presentationState/resolverGeneration/snapshotJson/creation/update; it has NO terminal effect ACK or terminal progress/version field. `PendingUndoCarrierDao` provides token insert/lookup/getAll, first-choice intent CAS, presentation/resolver updates and unconditional delete(token); it has no exact terminal CAS/discharge query. Keeping a row and placing an unreviewed sentinel into `resolutionIntent` is not safe: `LowQualityRedownloadRepository.reconcileAbandonedUndoDebts` enumerates getAll; unknown intent is parsed null, published undecided rows can be retained or recovery can take normal commit path, and the existing removal-restoration path still uses row presence as its positive claim. [PendingUndoCarrier.kt L7–47](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/models/PendingUndoCarrier.kt#L7-L47); [PendingUndoCarrierDao.kt L10–90](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/PendingUndoCarrierDao.kt#L10-L90); [LowQualityRedownloadRepository.kt L88–276](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/LowQualityRedownloadRepository.kt#L88-L276).

**Migration evidence.** Current DBManager schema version is 65; `Migration(58,59)` created pending_undo_carriers and `Migration(59,60)` added presentationState. `Migration(64,65)` only adjusts format_batch_payloads and FormatAuthoritySchema; old persisted Undo tokens have NO terminal discriminator. Versioned new states require real pre-change fixtures and old64/65 reopen; an arbitrary new marker cannot silently be inferred for old rows or a stale v1 fallback. [DBManager.kt L89–108](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/DBManager.kt#L89-L108), [Migrations.kt L445–465, L543–546](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/Migrations.kt#L445-L465).

**Startup/Restore.** `LowQualityRedownloadRepository.reconcileAbandonedUndoDebts` discovers tokens from Room PendingUndoCarrier and linked low-quality pending items, not raw fallback preference prefix. Restore `publishPreferences` replays destination-local keys; BackupSettingsUtil explicitly excludes fallback prefix from portable settings. So a failed fallback cleanup may outlive both Undo's Room carrier and a settings Reset, with no existing durable terminal evidence to classify it. [LowQualityRedownloadRepository.kt L88–187](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/LowQualityRedownloadRepository.kt#L88-L187), [BackupSettingsUtil.kt L24–53](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/util/BackupSettingsUtil.kt#L24-L53), [RestoreTransactionCoordinator.kt L1399–1458](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L1399-L1458).

## 2. Restart / crash matrix — proposed stronger contract, not implementation

| Boundary | Durable possibility today | Required future no-loss disposition |
|---|---|---|
| before any selected-intent persistence | Room unresolved/unpublished may exist; process-local selection may be in flight | no accepted-choice claim without durable same-token authority, fail closed on uncertain write |
| fallback selected, Room attempt writes all fail | fallback selected (v1) + original carrier or linked token if accessible | classify SELECTED or UNKNOWN, never absent/unselected; old v1 target proof may be unknown |
| Room pending/final intent accepted | same token has intent+resolver generation, fallback perhaps also | preserve first choice and original immutable target, do not create competing claim |
| before Room effect transaction | old carrier must remain actionable for the exact owner | no terminal ACK yet, allow only its authorised resolver/recovery |
| immediately after Room effect transaction commits | today the carrier can be deleted and fallback still selected | **new** effect+terminal receipt must commit in same Room transaction, with exact generation/outcome and linked reason; replay cannot treat receipt as an actionable Undo |
| after fallback delete fails or process dies | retained Room terminal must prove stale fallback, not reinterpret as unfinished Undo | idempotently remove only exactly matching fallback after verifying exact receipt, keep terminal until cleanup commits |
| after verified fallback cleanup, before terminal GC | Room terminal exists with no selected fallback | safe delayed GC by exact token/generation/receipt, no pending-selection resurrection |
| after terminal GC completes | no fallback/no unsettled carrier, no process owner | Restore admission may consider the completed token discharged, provided all stronger linked/cleanup duties proven |

The last three transitions must be serialized against a Restore prepublication final check, and must avoid blocking synchronous UI Snackbar acceptance. **A Room terminal marker written BEFORE the Room effect can lie; a marker written AFTER separate Room commit can be lost.** These are strict commit-order invariants, not proofs that the existing schema supports them.

## 3. Source-bounded conditional design and all-consumer obligations

**Preferred conditional design, not approved:** a token-keyed terminal state co-committed with each actual Room Undo effect, with frozen ORIGINAL target kind/identity, original accepted intent, effective terminal disposition (APPLIED_RESTORE, APPLIED_COMMIT, SUPERSEDED_BY_STRONGER_AUTHORITY or an accurately represented no-effect branch), final-generation/outcome identity, and monotonic fallback-cleanup ACK. Terminal state must be **non-actionable** to every direct UI/recovery resolver and Restore-targeted deletion. An optional retained original PendingUndoCarrier row could potentially store it if all readers/DAO methods gain an exact terminal discriminator; a separate existing durable journal might also work if it offers Room transaction semantics. Neither alternative has been proved source-closed or approved as a table/schema change. Distinguish source-supported fact from proposed design.

**Required producer/consumer closure:**
- Removal: `commitRemovalSnapshotLocked`, `restoreRemovalSnapshot`, early stronger authority, membership and History refusal, rebind and exact once-only restoration, legacy compatibility overloads.
- Cancellation: direct RESTORE, direct COMMIT, both recovery resolvers, `commitPendingCancellationLocked`, stronger parent/child branch, published/unpublished token and old pending reasonCode.
- DAO: `PendingUndoCarrierDao.insert/get/getAll/recordResolutionAttempt/recordResolution/recordResolverGeneration/delete`, all exact-token delete callers including targeted Restore reset; avoid unqualified generic delete treating terminal as pending or pending as terminal.
- Recovery: `LowQualityRedownloadRepository.reconcileAbandonedUndoDebt/reconcileAbandonedUndoItem`, token-scoped scheduled retry, App startup and any lower-level cleanup path; raw fallback prefix must be enumerated with typed decode/read-error outcome, not `runCatching` to null for absence proof.
- Admission: M→R final Restore check classifies exact target intersection (including removed A/History/low-quality owner) and terminal ACK under same exclusion before active pointer; unsupported process writer remains NOT_VERIFIED.
- Preferences: persist selected v2 fallback only after trusted original identity exists, failure to save must not consume UI choice; terminal cleanup never deletes selected record for a different owner/generation; portable settings reset preserves destination-local keys.
- Compatibility: old v1 fallback and Room58–65 persisted rows keep their old meaning, with UNKNOWN/fail-closed handling when immutable target, end-state or source generation cannot be proved. No retroactive 'version upgrade' via current numeric Download ID.

**Lifecycle risk beyond Room:** actual status/row/linked operation may converge in Room while cache/notification/external cancellation remains separately owned. The terminal receipt must identify which semantic final effect is settled, and it must not falsely ACK independent cleanup effects. Preserve existing cleanup owners. Do not reuse WorkManager handoff terminal states as Undo-effect ACK without exact ownership proof.

**Question still requiring source closure:** Can all existing destructive/terminal writers and recovery consumers safely preserve and later garbage-collect a non-actionable terminal row without a migration, and can a v2 fallback safely embed immutable original identity across a Room failure while preserving old-v1 behavior? Current positive proof is INSUFFICIENT. Do not infer 'schema free' or 'migration required' without testable exact kind/field representation plus migration fixtures and all writer audits. No new global mutex, table, schema migration or implemented feature is authorized by this review-only checkpoint.

## 4. Mandatory NEW production checks (not executed)

1. Fallback-only selected RESTORE/COMMIT plus injected triple Room intent failure; restart and full raw key inventory; exact target identity or UNKNOWN.
2. Removal COMMIT with no linked child; Room effect commit then crash before fallback clear; no second effect, terminal discovered and cleaned.
3. Removal RESTORE with recycled old numeric ID/History barrier/source stop; no resurrected A from retained terminal, exact rebind/stronger authority.
4. Cancellation RESTORE with terminal/cancelled parent; outcome says superseded not silently COMMIT selection, no lost user choice claim.
5. Cancellation COMMIT, token deleted in Room, fallback deletion fails, process death then Restore begins; terminal proves done and GC converges.
6. Terminal write crashes BEFORE, DURING, AFTER Room transaction; fallback write/cleanup before-after; no ambiguous successful acceptance.
7. Room getAll and raw fallback decoder exceptions: admission UNKNOWN and no pointer, no implicit COMMIT or false disjoint claim.
8. Old schema 58/59/60/64/65 fixture created with original writers; no new terminal marker collision, no automatic authority from old 0/blank/string.
9. Concurrency: final M→R Restore publication vs accepted token, stale external fallback clear vs newer generation, exactly one owner per token, no UI main-thread unbounded lock.
10. Existing CLOSED notification and duplicate-admission routes, linked low-quality sibling, backup destination-local settings and genuine disjoint partial Restore stay preserved.

## 5. Independent verdict and next governed action

The source establishes one specific **design blocker**, not a new independent production root: terminal-progress representation and consumer closure are missing for the adopted Undo-vs-Restore policy. Current `BUG-RECONFIGURE-PREADMISSION-DELETE-01` remains OPEN P2 as previously established, with active P2 four OPEN + four AMBIGUOUS = eight unresolved; repository-wide 53 OPEN + 4 AMBIGUOUS + 95 CLOSED. Existing manual L2 review remains IN_PROGRESS, source SHA unchanged, and no tests/build/device work occurred.

A potential narrowly scoped next task is **read-only producer/consumer closure + v1/v2 fallback identity discrimination preflight**; an executable Undo prepublication-guard prompt or full A→P production prompt is NOT source-proven at this SHA. This is a recommendation only, not a new authorization or launch. WP0B03 was consumed by verified report; do not reissue its bootstrap or mutate NEXT_CHAT on a review-only continuation. The live stale capsule is superseded by immutable WP0B03 report. No actual code, runtime or schema correction has been performed.

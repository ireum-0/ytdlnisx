# WP0-B — exact Undo responsibility inventory and Restore prepublication admission: independent source-based contract

checkpoint_kind: BOUNDED_REVIEWER_SOURCE_CONTRACT
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: df69fffdcd034082babd26f8369d81caa3164128
implementation_reviewed_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_changed: NO
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
preceding_WP0B03_review_blob: 000ccb582dad79f9f445b1e1a9237d88e16f698a
WP0B03_consumed_report_blob: 32e061767277344a902e8d7958ed78cb22338e2f
product_policy: SELECTED_UNDO_PROTECTED_UNSELECTED_INTERSECTION_DEFERS_RESTORE_RESTORE_FIRST_REJECTS_OLDER_UNDO; PER_MEMBER_A_P; PARTIAL_RESTORE_KEEP_VALID_P
independent_verdict: CURRENT_INVENTORY_INSUFFICIENT_EXACT_CONTRACT_SUPPLEMENTED
bounded_source_contract_implementation_ready: NO
full_A_to_P_implementation_ready: NO
root_status_delta: 0
new_semantic_root_count: 0
active_P2_OPEN: 4
active_P2_AMBIGUOUS: 4
active_P2_UNRESOLVED: 8
repository_roots_OPEN: 53
repository_roots_AMBIGUOUS: 4
repository_roots_CLOSED: 95
review_basis: NOT_CLEAN_UNCHANGED
manual_L2_run: IN_PROGRESS_UNCHANGED
tests_executed: ZERO
source_edits: ZERO
implementation_prompt_persisted: NO
handoff_changed: NO

## 1. Current producers, carriers, consumers and terminal effect

**VERIFIED_SOURCE — removal producer.** `DownloadRepository.deleteForUndoInternal` registers a token under ordinary Restore admission, enters a Room transaction, snapshots A (Download + HistoryReplacementBarrier + linked LowQuality item), stores PendingUndoCarrier.REMOVAL including snapshotJson/token and deletes A. Only after commit does it mark the process authority durable/publish a snapshot/perform exact cache cleanup. A can therefore be ABSENT from current Download rows while its Undo is live. Source: [DownloadRepository.kt L499–650](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L499-L650). A numeric current-row scan is neither complete target inventory nor identity proof.

**VERIFIED_SOURCE — cancellation producer.** `beginUndoableCancellation` can insert PendingUndoCarrier.CANCELLATION and leave linked LowQuality item at CANCELLATION_REQUESTED/reasonCode=token. Cancellation carrier has no DownloadRemovalSnapshot; its target correlation is the exact linked item, operation state and current Download, not carrier JSON. Source: [DownloadRepository.kt L3587–3715](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L3587-L3715); [PendingUndoCarrier.kt L7–41](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/models/PendingUndoCarrier.kt#L7-L41).

**VERIFIED_SOURCE — selection and fallback.** `acceptUndoResolution` takes `RestoreMutationAdmission.withOrdinaryMutation` R, moves a token to an exact RESTORE/COMMIT resolving state, writes an independently durable SharedPreferences fallback first, then attempts Room pending/final marker. Its v1 fallback value is exactly kind | authorityGeneration | resolverGeneration | intent under token-prefixed key. It lacks original Download identity, original category, linked owner and terminal ACK. `fallbackUndoResolutionIntent` wraps reads/decode in `runCatching` and returns null on errors: null cannot serve as exhaustive negative proof. Source: [DownloadRepository.kt L1360–1440](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1360-L1440), [L1640–1798](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1640-L1798), [RestoreMutationAdmission.kt L19–80](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreMutationAdmission.kt#L19-L80).

**VERIFIED_SOURCE — completion/fallback residue.** `finishUndoResolverSuccess` removes the process-local authority and pending snapshot and calls `clearUndoIntentFallback` afterward; that cleanup catches failures and leaves the durable fallback for later handling. A crash/failure after a Room effect is committed but before fallback removal thus leaves a selected-looking fallback without necessarily an active unresolved Undo. This is not itself proof of a current incorrect Download mutation, but defeats a fallback-only 'pending' predicate. [DownloadRepository.kt L1895–1955](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1895-L1955).

**VERIFIED_SOURCE — discovery.** `LowQualityRedownloadRepository.reconcileAbandonedUndoDebts` enumerates exact tokens from pending linked items plus Room PendingUndoCarrier.getAll, not from the raw SharedPreferences fallback prefix. It preserves unresolved published UI without accepted durable action, but other paths intentionally choose COMMIT for abandoned/unpublished cases, which cannot be silently substituted for the newly adopted Restore prepublication policy. Absent Room rows do not enumerate fallback-only residue. Source: [LowQualityRedownloadRepository.kt L88–276](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/LowQualityRedownloadRepository.kt#L88-L276). The repository's ordinary Undo recovery semantics remain historical, not presumed incorrect by the new prospective policy.

**VERIFIED_SOURCE — settings preservation.** `BackupSettingsUtil.isPortablePreferenceKey` explicitly excludes the `pending_undo_intent_fallback:` prefix. `RestoreTransactionCoordinator.publishPreferences` restores destination-local/nonportable keys from a snapshot across settings Reset. Thus the fallback is NOT normally lost merely by portable settings reset; it may instead persist as a stale residue. [BackupSettingsUtil.kt L24–53](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/util/BackupSettingsUtil.kt#L24-L53), [RestoreTransactionCoordinator.kt L1399–1458](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L1399-L1458).

**VERIFIED_SOURCE — Restore owner.** `RestoreTransactionCoordinator.begin` currently holds an operation mutex, prepares the plan/journal, then uses R to publish the durable active pointer WITHOUT inventorying tokens. Ordinary resolution cannot acquire R afterward while pointer active. Current targeted `clearRestoreTargetedUndoCarriers` deletes numeric-target tokens without selected intent check; it cannot be used as a prepublication conflict classifier. [RestoreTransactionCoordinator.kt L428–500, L980–1048](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L428-L500); [DownloadRepository.kt L451–475](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L451-L475).

**Process topology limit.** Main `app/src/main/AndroidManifest.xml` declares no `android:process` on inspected app components; this does NOT independently prove the merged manifest, libraries, independent processes and every writer use one R instance. Cross-process completeness remains NOT_VERIFIED; do not introduce a new global lock merely to assume proof. [AndroidManifest.xml](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/AndroidManifest.xml).

## 2. Four decisive counterexamples against naive fixes

CE1 (selected/fallback-only): A removal is durably committed; user selects RESTORE, fallback succeeds while Room intent update fails; Restore publishes active pointer without inspecting choice; resolver requires R and cannot finish under active Restore. Waiting after pointer is a cyclic dependency, not a fix.

CE2 (current-ID omission): pending removal owns the snapshot and A is absent; a category Reset asks for current IDs only. It misses A's original category and the possible History/linked intersection. Re-reading a recycled same numeric ID cannot authenticate old A.

CE3 (stale success residue): Undo Room resolution commits and removes its current carrier, while clearing the independent fallback fails or process dies before it; a 'fallback exists therefore unresolved' guard may block unrelated Reset indefinitely. Conversely 'Room absent therefore resolved' can erase a truly fallback-only selected choice. The two states can become observationally indistinguishable without durable outcome identity.

CE4 (settings + legacy): portable settings Reset preserves fallback-prefixed destination-local records. An old v1 key/value cannot be upgraded to trusted original target/generation by stamping a current Download ID. Malformed/unreadable/unknown version must mean UNKNOWN, not absence or automatic COMMIT.

## 3. Conservative admissibility decision table — proposed design, NOT yet implemented

The final guard, if later authorized, must execute inside the SAME M→R Restore publication interval on the immutable validated plan, before `publishActive`. The guard must never await an ordinary Undo resolver or hold the process-local undo monitor across Room/file/SharedPreferences/WorkManager waits. The safety predicate is:

- `CLEAR`: complete, error-distinguishing inventory of pending Room removal/cancellation carriers, linked low-quality tokens/operations, raw fallback-prefix keys, process-local prepared/selected/claimed authority and any durable completion tombstones; every relevant producer is serialized by R (or an independently proved durable cross-process exclusion). For each token, validate frozen original target/category/linked source against plan; prove no relevant unsettled authority and no UNKNOWN. Only then publish the exact pointer under the still-held publication R.
- `SELECTED_INTERSECTION`: accepted exact RESTORE or COMMIT without a proved terminal effect/ACK; reject/defer BEFORE pointer and preserve exact resolver/recovery owner, allowing convergence then an independent user-initiated retry.
- `UNSELECTED_INTERSECTION`: published unresolved or producer-prepared capability whose target intersects; reject/defer BEFORE pointer, require explicit user choice, never silently select COMMIT.
- `UNKNOWN`: fallback-only without target, malformed/read-error, unknown legacy token version, missing linked snapshot, owner-generation mismatch, selected vs completed outcome not distinguishable, or unsupported process writer. Fail CLOSED before pointer, keep prior state, expose a truthful recover/retry/manual-resolution result; do not claim a choice was settled or force a guessed target.
- `PROVED_DISJOINT` or `PROVED_TERMINAL`: permit only if the independent target/terminal proof is complete. The fact that a Room row or a process-local authority is absent is not that proof.

Requested policy respects precedence: selected Undo before Restore publication wins; unselected *intersecting* capability defers for choice; once Restore pointer is durably published, a later old Undo selection must not mutate imported state. An already active Restore resumes its own recovery instead of entering the preownership guard. Nonintersecting Undo should not block a known-disjoint partial Reset, but UNKNOWN may require conservative refusal. Distinguish honest `RejectedBeforeOwnership` UI from `RecoveryPending` automatic-recovery promise.

## 4. Minimal durable proof gap and conditional structure

**NOT_VERIFIED**: existing Room carrier + fallback v1 + process-local registry cannot positively correlate every old fallback-only choice with an original exact A/low-quality source after Room failure, nor distinguish exact selected intent after durable effect from a completed-but-uncleared fallback. This is a representation deficiency, not a reason to bypass unknown cases. A new table/global mutex/schema migration is NOT yet approved.

**PROPOSED conditional owner model for further preflight:**
1. Producer ties a trustworthy original token and immutable target/category/linked owner identity to the same accepted Undo before it can become actionable. Where Room is unavailable at selection, the independently durable fallback must carry enough versioned original identity/owner generation and action to recover without a later ID re-stamp.
2. Resolver effects and one monotonic terminal receipt must be atomically coupled in Room (if that can be proved with existing carrier representation and all readers/writers). Otherwise after Room commit but before fallback erase, crash leaves indistinguishable stale state. A possible ordering is Room effect + terminal receipt → confirmed fallback removal → terminal receipt retirement; intermediate failures retain exact terminal proof. Such a terminal extension is ONLY a design candidate and requires exhaustive source-writer/DAO/recovery/old64-65 review. The old PendingUndoCarrier model currently has no terminal-ACK field.
3. Recovery/guard enumerates raw fallback prefix with strict decoding and associated Room/ledger/tombstone. Unknown v1 residue must be preserved or explicitly resolved via a separately reviewed user-safe path; no automatic TTL, fabricated numeric owner, or global COMMIT.
4. Any change to generic `PendingUndoCarrierDao.delete`, `clearRestoreTargetedUndoCarriers`, `LowQualityRedownloadRepository` direct/abandoned reconciliation, removal and cancellation resolver branches, fallback cleanup, settings restoration or startup must prove complete consumer closure. True completion is exact durable committed effect plus monotonically recorded owner discharge; Snackbar dismissal, UI success, Room-row absence and elapsed time are not it.

**Important crash ordering:** writing a terminal fallback marker BEFORE the Room effect commits would lie after crash. Removing the Room carrier BEFORE independent terminal proof would lose an accepted fallback-only responsibility. Any proposed two-store protocol must prove crashes after each durable boundary without circular R waits.

## 5. Mandatory future verification, no execution

NEW real-production tests, not written or executed: (a) published removal with absent A and Saved/History partial Reset; (b) cancellation CANCELLATION_REQUESTED linked child and Cancelled-only Reset; (c) selected RESTORE/COMMIT fallback accepted with injected three Room intent failures, verify prepointer no mutation, restart owner intact; (d) Room resolver effect commit then fallback clear failure, verify terminal receipt/cleanup safety and no permanent false conflict; (e) malformed/raw old v1 fallback and Room unreadable; (f) unrelated token/known-disjoint partial Reset; (g) deterministic two orderings selected-Undo/R pointer with prepublication barriers; (h) process restart after every Room/SharedPreferences boundary and supported old64/65 reopen; (i) settings-only Reset preserving fallback; (j) synchronous Snackbar acceptance under competing R without UI-unbounded wait; (k) correlated/native/linked owner changes and unsupported process-state fail closed. Assert actual pointer publication, SQL owner, selected intent, consumer effect, visibility and no unrelated sibling deletion, not just helper return values.

## 6. Verdict and routing boundary

The current source supports a **specific failure inventory and conditional monotonic terminal ownership design**, not a proven safe and live-ready first implementation stage. The remaining smallest decisive review task is to source-close all exact Undo resolution/deletion writers and old-v1 fallback migration/UNKNOWN recovery and determine whether an existing carrier can implement Room-atomic terminal progress without damaging existing recovery semantics. If impossible, explicitly authorize and preflight an independently durable versioned fallback + terminal marker change only after verifying producer/consumer/recovery and old-schema effects. A diagnostic prompt may be separately authorized later; do not relaunch consumed WP0B03 or confuse another agent source-only claim with reviewer verdict.

No new semantic root, current root closure, source patch, test, or private lifecycle transition. Adopted three product policies unchanged; root BUG-RECONFIGURE-PREADMISSION-DELETE-01 remains OPEN P2. Manual L2 run stays IN_PROGRESS. NO canonical launch-ready bootstrap for consumed tasks.

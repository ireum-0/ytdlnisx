# WP-0 source-contract preflight addendum — INCOGNITO and BG04 — independent review

checkpoint_kind: INDEPENDENT_SOURCE_CONTRACT_PREFLIGHT
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: a68f1f912c7efffaa2a3fd0106238a33def3b258
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
reviewed_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_plan_sha: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_sha: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
active_inventory_blob: 729d971350314712913c2127f299cd8750ce8bc1
review_basis: CURRENT_PINNED_IMPLEMENTATION_WITH_CANONICAL_V55_FINAL
finding_count_delta: 0
canonical_active_open_p2: 8
canonical_active_ambiguous_p2: 0
canonical_clean: NO
root_status_changed: NO
implementation_changed: NO
prompt_or_handoff_changed: NO
tests_executed_by_reviewer: NONE
android_or_device_operations: NONE
wp0_semantic_conclusion: INCORRECT_CURRENT_SOURCE_PATHS_CONFIRMED
wp0_implementation_launch_readiness: NOT_ESTABLISHED_PENDING_INCognito_TEMPORAL_AND_BG04_TARGET_CONTRACT

## Independent verdict and scope

Read the actual pinned implementation source and caller/DAO/worker boundaries for existing OPEN P2 roots `BUG-INCOGNITO-01` and `BUG-FORMAT-BG-04`. This is **a same-root source-contract expansion with zero new semantic root**, not a new count, closure, or canonical reviewer task reassignment. Preserve V55 consumption/STOP and scheduler WINDOW-01/RESTORE-01 prior CLOSED decisions. No source build/test, AVD command, protected local-state inspection, or future implementation permission was exercised.

### BUG-INCOGNITO-01: verified current wrong-effect paths

1. **Selection producer and collapse:** `ConfigureMultipleDownloadsAdapter.getCheckedItemsOrNull` returns `null` both when selection mode is OFF and when selection mode is ON but the explicit selected set is empty (`res.toList().ifEmpty { null }`); the mutation path treats either as all Processing. This makes an explicit no-selection click capable of widening to all current Processing rows. Source: [adapter lines64–81](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/adapter/ConfigureMultipleDownloadsAdapter.kt#L64-L81).
2. **ViewModel and DAO:** `updateProcessingIncognito` chooses broad `dao.updateProcessingIncognito` for null or empty selection. Its selected branch calls `dao.updateIncognitoByIDs`, whose SQL has only `id IN (:ids)` and lacks `status='Processing'`. `areAllProcessingIncognito` returns count-of-true `> 0`, the wrong nonempty-all predicate for a mixed set. Source: [ViewModel lines3105–3120](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L3105-L3120), [DAO lines1238–1248](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt#L1238-L1248).
3. **UI authority and lifecycle:** `DownloadMultipleBottomSheetDialog` initial icon paint takes a selection through `getCheckedItemsOrNull` inside Dispatchers.IO. Its incognito click uses the previous icon alpha as desired-state authority and again reads mutable adapter selection inside IO; this does not bind one Main-thread selection/decision to one committed database effect. Source: [dialog initial paint lines386–399](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadMultipleBottomSheetDialog.kt#L386-L399), [click lines526–548](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadMultipleBottomSheetDialog.kt#L526-L548).
4. **Newly expanded temporal consumer:** the worker receives `admission.claimedItems` and constructs `DownloadAttemptRunner(downloadItem = downloadItem)` before execution. In the runner, History persistence checks its held `downloadItem.incognito`; log setup and producer `publicationSemantics["incognito"]` also use this captured object. The DAO mutation of a Processing row after the runner captured its item does **not** itself update that held object. Consequently, a mutable DB change does not prove changed in-flight History/log/producer behavior. Sources: [claim-to-runner lines1370–1416](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1370-L1416), [fingerprint incognito lines1540–1553](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1540-L1553), [logging lines2556–2574](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L2556-L2574), [history lines3480–3490](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L3480-L3490). **Source-verified split:** held worker state versus mutable Room row; whether every live timing produces a stale value in practice is not independently runtime-executed here.

**Recommended basic selection contract, not yet product-authorized:** Outside selection mode select all *current* Processing rows; inside explicit selection mode use selected distinct existing Processing IDs; explicit empty means no-op. An empty eligible set is all-incognito=false with no mutation. Nonempty-all means zero eligible false rows. Classification/mutation should observe the same transactionally admitted set under Restore admission, and UI should reflect committed result. Do not globally redefine `getCheckedItemsOrNull` (other format flows reuse it). Include duplicate, missing, deleted/recreated, transitioned-status, Restore refusal and DB-failure negative cases.

**Critical unresolved timing choice; not safe to prescribe as a trivial DAO/UI patch:**
- **A: execution-frozen policy.** An active attempt's privacy/History/log/fingerprint flags are committed at admission. If a Processing row is already active, the UI must not silently claim a changed privacy effect for the current attempt; such a change must be refused or clearly restricted to a future attempt and displayed truthfully. Must prove what identifies an active owner and what happens at restart.
- **B: forward-effective live policy.** A committed UI toggle is intended to govern *subsequent* privacy-relevant effects of that same active attempt (including terminal History), requiring an exact admitted transactional read/commit decision under the worker's real ownership and a coherent response to producer/logging fingerprint and restart. **Already-emitted logs cannot be treated as retroactively absent.** Review privacy promise and previously persisted data; prevent a present-day flag change from rewriting prior evidence or adopting a different producer generation.
- It is **NOT_VERIFIED** which temporal meaning is required by accepted product behavior or pinned prior source contracts. Neither a worker change nor a contradictory "History suppressed" final assertion should be authorized until the reviewer resolves this against source/product evidence or obtains the owner's explicit policy decision. A future product decision must preserve all supported old-state/restart obligations.

The temporal observation is a **same-root propagation/consumer subcase**, not evidence supporting a ninth P2 or an automatic rewrite of DownloadWorker.

### BUG-FORMAT-BG-04: verified bypass, unresolved publication target

- `FormatSelectionBottomSheetDialog` uses `captureFormatRefresh` → `fetchCapturedFormats` → `publishCapturedFormats` then on success `listener.onFormatsUpdated(result)`. Audio and video callbacks later call `resultViewModel.update(this)` after editing `resultItem.formats`.
- `ResultViewModel.update` launches detached ViewModel work; `ResultRepository.update` then invokes `ResultDao.update` (full-row Room `@Update(onConflict=REPLACE)`) rather than the narrow `ResultDao.publishOwnedFormats`. Automatic `ResultViewModel.updateFormatItemData` also fetches by URL and calls `update(this)` without carrying foreground cache authority. This is source-verified lack of the guarded cache-publication lease at independent write sites, **not** an independently executed stale interleaving.
- Source: [selection onFormatsUpdated](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/FormatSelectionBottomSheetDialog.kt#L209-L245), [audio](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadAudioFragment.kt#L258-L281), [video](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadVideoFragment.kt#L290-L319), [ResultViewModel](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/ResultViewModel.kt#L224-L230), [auto refresh](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/ResultViewModel.kt#L315-L330), [DAO narrow/full](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/ResultDao.kt#L50-L55).
- The correct automatic refresh target—one captured Result row or all authorized rows with the same URL—and the unsaved view-only `id=0` boundary need exact caller/product confirmation before schema/API changes. Require stale A/newer B, intervening metadata, delete/recreate, URL drift, cancellation and restore tests. Do not silently make multi-row semantics single-row or vice versa.

## WP-0 outcome and mandatory next boundary

**Review:** actual current wrong-effect paths are confirmed for the existing OPEN roots; no status/count/CLEAN changes and no new implementation completed.

**WP-1 source correction:** the incognito set-domain portion is sufficiently concrete to design a narrow fix, but an implementation prompt claiming the whole root's correction is **NOT PREFLIGHT-READY** while its in-flight temporal contract and transaction/lifecycle convergence owner remain unresolved. Resolve A vs B through existing accepted requirements or explicit owner decision; then reviewer must author/preflight/persist any separately authorized implementation prompt, preserving exact-SHA verification and report/intent/claim gates. Do not use the consumed V55 launch capsule.

**WP-2 BG04:** independently resolve original Result target semantics before authorizing durable writer/DAO changes; the source-verified unguarded paths establish a real review target, not authorization for a global Result semantics rewrite.

**No deadline-driven gate:** proceed by satisfied evidence, correct ownership and explicit STOP boundaries. Source analysis can proceed without the V55 AVD; Android completion evidence remains missing and is not waived.

**Independent execution:** GitHub source and evidence read only. No source, tests, build, AVD, private handoff, protocol, Master Plan, ledger, or finding inventory changed by this reviewer checkpoint.

# BG-04 — reviewer-established durable member and Result write-authority contract

date: 2026-10-13_KST
checkpoint_kind: REVIEWER_SOURCE_SEMANTIC_CONTRACT_DECISION
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_expected_parent: be23f35e5d2a2fff310d199db2c82e03fdf060ca
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_private_head: 9a0b893dc44e7d9645fbeff286ebabdcfdf34815
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
source_changes_authorized: NO_REVIEW_ONLY
prompt_or_handoff_transition_authorized: NO
device_or_database_mutation_authorized: NO
independent_execution: NOT_EXECUTED
implementation_diff_examined: EXACT_PUBLISHED_BASE_ONLY_UNPUBLISHED_V11_DRAFT_NOT_AVAILABLE_AS_AUTHORITATIVE_SOURCE
contract_decision: FIXED_SEMANTIC_REQUIREMENTS
implementation_storage_shape: NOT_SELECTED_UNTIL_SOURCE_AND_MIGRATION_COMPATIBILITY_PREFLIGHT
canonical_root_status_bg04: OPEN
canonical_root_delta: 0
canonical_active_p2: 6
confirmed_open_p2: 6
ambiguous_p2: 0
repository_wide_verdict: NOT_CLEAN

## Evidence, limitations, and precise defect boundary

This is a contract decision, NOT an implementation/closure review and NOT a new manual trigger-3 run. V11 independent FINAL is `review-runs/checkpoints/2026-10-13__db339e1__fmt-bg01-bg05-v11-member-authority-scope-stop-independent-review.md`. Its immutable private V11 report is `ytdlnisx/reports/2026-10-13__FMT_BG01_BG05_V11_GATED_SINGLE_WAVE__fmt-bg01-bg05-v11-20261012-77818bc9__FULL_REPORT.md` (blob 985b6b6d4ff5dccfb26504f05da03c86a09fbac1). V11 reports unchanged 17-file unpublished candidate (9 tracked modified, 8 untracked), no tested commits or device/test activity; none of the unpublished candidate's H2 stage internals are independently source-certified here.

Exact published source at db339e1:
- `DownloadRepository.kt` blob 8125ef4b1a09872c83be876e4f98f14981553246, lines 686-716, permits Saved -> Processing by a final endpoint-snapshot comparison where executionId/operationId remain blank and retryAttempt remains 0. The transaction checks endpoint values, not whether an intervening lifecycle occurred.
- `DownloadDao.kt` blob e0b260e5d23f5d1f3b10fc51880bbbc5465ee9c1, lines 697-715/754-783/970-987, checks borrowed/current execution fields and several preserved barriers; it does NOT provide a format-member lifetime proof across external work.
- `UpdateMultipleDownloadsFormatsWorker.kt` blob 3dee9181140cf760c9cde9dc651e5ea6b350d07c, lines 46-82, reads Download and Result before the fetch, then writes Result before reloading the Download and copies the latest execution identifier onto an earlier Download snapshot. Both are independently visible production wrong-effect paths; BG04 stays OPEN.
- `ResultDao.kt` blob d6c22f6fd6c77f107dc134e7b189269163ac83df, lines 50-51, has a general @Update(REPLACE); `ResultItem.kt` blob e0265756c7ab31e799a1ce694b98e35b3724c5f2 lacks a persisted format-cache freshness token.
- `WorkManagerHandoffCarrier.kt` blob d3ebded2745cfbd872c0d8b2334257613b5936af, has semantic generationId and exact requestId, but the published data model has no per-Download durable member incarnation bound to those identities. `RestoreMutationAdmission.kt` is process-local and does not span network fetches; its short admission must not replace durable authority.
- `DBManager.kt` blob 3ac3dc3b64afe84f4ec5a11924441bea6f6123b1 uses published schema63; `Migrations.kt` contains version61->62->63 transitions. Draft schema64 and the new format-batch payload are preserved, unpublished local candidate evidence only.

**ABA proof obligation:** old H1 and new H2 must be observably distinguishable even if the endpoints of Download id, status, URL, executionId, operationId, retryAttempt, issue fields and configuration coincide. A predicate over those repeated endpoints cannot make H1 current. The exact WorkRequest UUID proves request identity, not member lifecycle. A process-local lock or cancellation does not satisfy persistent revocation.

## Binding correctness contract

### C1. Separately durable identities

For each member D admitted to a format batch, persist an **incarnation/authority discriminator** M(D) that changes on each new authority admission or revocation, including a round trip Saved -> Processing -> Saved, without relying on executionId, operationId, retryAttempt, timestamps, thread ownership or a later-reloaded worker token. The currently committed member token must be distinguishable from every still-possible older attempt's token. The producer must atomically bind the exact D id, URL/source identity, relevant format configuration fingerprint, semantic batch generation, and this member token. The exact WorkRequest UUID remains a separate retry/request identity: new request UUID for retry, same semantic batch and member authority only while that authority is still valid.

For each potentially shared Result-cache target, persist an **independent format-write freshness/ownership discriminator** C(K), where K identifies the actual cached target being overwritten (exact Result id plus source URL, with the existing source-key/duplicate-row policy preserved). A Download-owned token by itself does not authorize cache publication. C(K) changes when another format authority is admitted to the same cache target, and on format-affecting replace/delete/recreate/Restore; absence or identity change must reject an old writer. For a missing Result snapshot, old work has no permission to insert a replacement or upsert a new cache row just because a matching URL later reappears. Shared URL/cache rows require one explicit winning cache-write authority; unrelated member jobs cannot each assume independent ownership of the same Result.

These durable discriminators may be implemented with guarded current-row columns or a separately indexed durable authority table, but **no selected storage representation is approved in this checkpoint**. Any equivalent existing primitive must first prove the same discriminator and atomic revocation under every real producer; source inspection at the published SHA has not shown one. A storage design may not reuse a Download execution token or a batch/request UUID alone as member incarnation.

### C2. Every relevant producer invalidates before another owner is visible

Every successful Saved/Processing reclassification, accepted new format batch, repeat format configuration request, requeue/retry/configuration or URL change that affects the fetched result, deletion/recreation, import/Restore/recovery replacement, and other material producer that supersedes format ownership **must** atomically update/revoke the relevant M(D) with the row mutation. If the cache target's ownership is superseded, atomically advance/revoke C(K) as well. A short ordinary/Restore mutation boundary and one Room transaction are required at durable commit; no network call or mutable global UI job may hold that transaction. A refused/zero-row state transition must not claim a new owner or report a successful member mutation.

An implementation must enumerate and review real producers/writers including DownloadViewModel, DownloadRepository, DownloadDao direct SQL and full-row helpers, FormatBatchHandoff/Recovery, all ResultDao writers, RestoreTransactionCoordinator, delete/undo/import/backup restore, process restart and previous WorkManager work. Any writer that can bypass the discriminator invalidates the solution; route it through a shared transaction/CAS or prove it cannot intersect the protected lifecycle.

### C3. Exact effect-time check, atomic field-scoped publication

The worker captures only immutable members' M(D), C(K), bound source/configuration and batch/request identities before external fetch. Fetch output is **data, never write authority**. After fetch, acquire the existing short Restore ordinary-mutation admission and one Room transaction; verify the exact current batch generation, WorkRequest admission, membership D id + M(D) + URL + eligible status/format-config, barriers/refusals and current C(K) + Result id/URL/source identity **at the mutation boundary**, not in a separate preflight check.

Update **only format-owned Download fields** and the intended Result.formats cache fields; never copy current executionId/status/issue into a stale full-row DownloadItem and never use general Result @Update(REPLACE) as an unchecked worker cache write. If Result was present in the captured publication contract, both Download and Result mutations must commit together or be rolled back; a failed Result fence cannot leave an old Download result marked as success. If Result was absent at capture, the contract must explicitly choose no Result insertion and permit a fenced Download-only update, or reject and recover under a freshly owned cache target. Do not invent Result rows from a stale absence observation.

A failed comparison has **zero stale Download writes, zero stale Result writes, no false completion/progress and no false carrier resolution**. Treat it as a classified refusal/supersession for the old member and pass any genuinely owed next-generation work to the current owner; never suppress valid H2/R2 or unselected captured siblings. Cancellation and Restore supersession must invalidate outgoing authorization before late callbacks or final notifications.

### C4. Restart, Restore and migration

Membership/cache authority must survive process death and be reconstructed from the same committed Room facts as the format batch handoff. Old WorkManager work that carries only numeric ids or lacks a non-blank proven member/cache token must be treated as **not authorized to write**; reconcile any actually owed format work by safely capturing the *current* eligible item under new authority rather than silently success-acknowledging or losing it. A missing/corrupt payload cannot mean successful completion.

Published DB schema is 63. Draft local schema64 has not been published or independently validated as a deployed DB; do not assume schema65 or overwrite/destroy an existing 64. If a schema change is necessary, first establish whether the protected unpublished 63->64 draft can be safely extended, verify exact existing migration/exported Room schema contracts including 61->62->63->64, and prove migration on historical 61 and 63 records as well as any genuinely existing older writer/carrier states. If any 64 database may already exist, or existing migrations/schema signatures collide, STOP and require a distinct compatible version/explicit scope decision. Old rows receive a safe non-authoritative legacy value, not a forged fresh credential; new format work obtains a fresh bound token in a guarded transaction. Preserve live WAL/SHM, Restore journal, app identity and historical evidence. No APK installation, live DB mutation or schema rewriting is authorized by this decision.

### C5. Required negative and recovery proofs before closure

Tests must exercise controlled H1 fetch suspension, D Saved -> Processing -> Saved, H2 admission, late H1 Result/Download attempt, H2 current completion, and the true final effects. Assert H1 writes neither D nor Result and never falsely finishes H2. Also test identical blank endpoint tokens, same-URL two-member shared cache, reconfigure/change URL, delete/recreate (including identifier reuse), cancelled/retried WorkRequest R1->R2, process death and restart, exact source/group generation, Restore supersession/partial category scope, missing/corrupt carrier payload, legacy 61/63/unpublished64 compatibility, batch other-only notification and BG03 per-member partial success truth. Fault between two publication steps must roll back, not leak half a refresh. Tests must be executed on the **actual exact final committed SHA**, with applicable focused and broader real production-wiring methods; test source presence, a V11 zero-execution report, or a local unpushed 17-file draft is not closure-grade.

## Disposition and authorized next action

The correctness contract C1–C5 is fixed for future design/preflight. Its invariant demands a durable member discriminator and separate cache-write fence **or production proof of an equivalent existing persistent mechanism**; the current published source and supplied V11 report do not establish that an implementation is complete or which storage shape is safe. Selecting concrete columns/table, expanding a migration, rewriting existing 17 protected candidate files, running a device test, installing an APK, authoring V12 or changing private `NEXT_CHAT.md` requires a **separately authorized** implementation/scope step. The present user request asked for contract resolution, not implementation launch. The independent reviewer may recommend the narrowest additive schema only after safely resolving draft64 and live DB compatibility. No automatic prompt, agent lifecycle change, canonical status change or CLEAN advancement follows this checkpoint.

Final status: BG04 OPEN P2 (same semantic root); other five active P2 roots remain OPEN; active P2 count 6, AMBIGUOUS 0. V11 proper Gate1 STOP remains valid. No source, tests, runtime, protected local state or published implementation branch modified.

INDEPENDENT EXECUTION: NOT EXECUTED

# WP0-A — user-approved queue-time incognito freeze / input-provenance contract — FINAL

date: 2026-10-21
checkpoint_kind: INDEPENDENT_SEMANTIC_CONTRACT_PREFLIGHT
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: d169916be5c0874cb457e1b8d11814a572967afd
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_policy_a_checkpoint_blob: 602920b98b8713c9b3f313ff8d0b9b06ff6117ca
prior_wp1_03_report_blob: 52f8bb091c2734edc979fcf506e2259aa4f313b1
user_product_decision: AT_QUEUE_REGISTRATION_FREEZE_EXPLICIT_INCOGNITO_ON_OFF_THROUGH_SCHEDULED_AND_DOWNLOAD
per_attempt_execution_policy: A_EXECUTION_FROZEN_AFTER_WORKER_CLAIM_NO_LIVE_RETROACTIVE_TOGGLE
implementation_authority: SEPARATE_USER_APPROVAL_NEW_INSTANCE_ONLY
canonical_root: BUG-INCOGNITO-01
canonical_root_status: OPEN_UNCHANGED
active_unresolved_p2: 7
active_open_p2: 3
active_ambiguous_p2: 4
canonical_clean: NO
reviewer_executed_tests: NONE
implementation_source_changed: NO
agent_lifecycle_changed_by_review_checkpoint: NO

## Accepted product semantics; no outstanding UI policy question

On each deliberate queue registration action the explicit effective incognito setting for THAT candidate (ON or OFF) is frozen as its durable Queued/Scheduled attempt configuration. A scheduled download must retain that queue-registration value while waiting for the start time. A later queued/running global preference or row-toggle must not silently override it. The successful worker claim receives that exact committed row setting and freezes it for the executing attempt's History, logs, and producer fingerprint (previously accepted policy A). A separate, explicitly authorized reconfiguration/retry for a NEW attempt may choose a NEW queue-registration setting, subject to original ownership/provenance. There is no automatic future resample at wall-clock/native start. Do not mutate global `SharedPreferences.incognito` as a side effect of enqueue; this is a per-DownloadItem property. No unnecessary confirmation dialog is allowed. If the current candidate is unproven/stale, preserve durable state, refuse only that action and show latest current setting so the user can retry explicitly.

## Actual production basis and why old/new distinguishability is mandatory

At pinned `141b5199592369ab953f52c55ebdb72ba300f1a4`:
- `DownloadViewModel.queueProcessingDownloads` obtains current Processing rows; `queueDownloads(items)` copies plain mutable `DownloadItem` inputs into `sourceSnapshots` at method entry. The source and caller's *observation-time* revision/incarnation are absent; snapshot-at-entry alone cannot retroactively reconstruct them.
- `DownloadDao.updateForQueueIfSnapshot` checks status/execution/operation/retry/issue/barriers and then `updateRaw(item.copy(executionId = ""))` overwrites all columns including `incognito`. A newer committed Processing preference can be replaced with an older queue object.
- `ConfigureDownloadBottomSheetDialog`, `DownloadFragmentAdapter.getDownloadItem`, direct `DownloadBottomSheetDialog` and multi-item dialog update paths send mutable configured items; `DownloadItem`'s Room/Parcelable fields include `incognito` but no origin token or explicit per-field change intent. The incoming Boolean cannot distinguish inherited old OFF from a user's deliberate OFF. Earlier WP1_03 STOP correctly refused a guessed fix.
- `FormatAuthoritySchema` has `format_authority_clock` AUTOINCREMENT and DOWNLOAD row revision/incarnation recorded on SQL row mutations, plus a CURRENT/DELETED state. `FormatAuthorityDao.get("DOWNLOAD", id.toString())` retrieves it; a Room transaction can read both DownloadItem and row authority together at the **actual observation boundary**. Original-generation and later-generation values can be distinguished within the same live authority lifecycle when the captured row's incarnation/revision is kept, versus an altered or deleted/recreated row. A later fresh DB revision read is NOT an observation token for an earlier object.
- `RestoreMutationAdmission` gates ordinary mutations during Restore; `RestoreTransactionCoordinator` deletes and remaps imported Download IDs inside an actual Restore transaction, so an incoming pre-Restore observation must be refused after any supersession. Validate entire database-origin/root generation and clock continuity for backup/Reset/import/open. If old and new persisted origins can share the same revision/incarnation pair after Restore or database recreation, add a demonstrably distinct durable discriminator **before** using a CAS proof, or STOP before prompt source mutation; never assume numeric ID or Boolean resolves it.

## Minimal approved implementation contract and collision discriminator

**Distinguish two input types at actual producer origin, not by guessing from values**:

1. **Explicit UI enqueue/reconfigure:** the UI click/confirm command binds a concrete ON/OFF selection and explicit intent to the candidate; when the original existing-ID DownloadItem was read, preserve a matching nonpersistent observation envelope (identity, observed revision+incarnation, origin scope, status and relevant operation/claim) through clones/Parcel/fragment/coroutine until mutation. The envelope is not a new durable Download row field. Re-fetch or reject safely if the envelope is missing or its origin cannot be proven; an incoming Boolean alone is not evidence of a user choice. A fresh explicitly confirmed reconfiguration can establish new authority only by a new observation, never by relabeling a stale DTO with current token.
2. **System-origin queueProcessing/automatic/restore/retry:** use a DB-row observation protected atomically with its existing authority stamp; preserve actual source status. If a later privacy edit or other authority advance occurs, refuse stale publication or prove a fresh atomic current-state update that carries exactly the selected setting into Queued/Scheduled, duplicate CONFIG identity, WorkManager, scheduler and later worker. Do not globally reinterpret Error/Saved/History callers as Processing.
3. **Old unversioned existing-ID input:** absence of captured origin means UNKNOWN, not current. Do not silently trust stale input, invent identity, or overwrite it. Refuse this candidate and refresh its displayed current settings, permitting user re-entry of the same action without unnecessary modal prompts. New `id=0` item has explicit new-item semantics and receives new identity after transactional insert. Distinguish naturally new values from old serialized positive-ID objects.
4. **Atomic publication ownership:** prior duplicate detection, archive-unavailable Error publication, History metadata hydration, CONFIG fingerprint and retry/schedule writers must either be done under the same proven generation/admission or be reversible/fenced at their OWN mutation points. A failed enqueue must not leave false Duplicate/Error/History side effects or start WorkManager. For a successful candidate, the final persisted row's `incognito` must equal what the UI claims and the in-memory handed-to-worker value. Avoid a generic full-row rewrite of unrelated current metadata.
5. **Never rely solely on** bool equality (OFF→ON→OFF ABA), numeric ID (delete/recreate), status, empty execution/operation IDs, an after-the-fact current revision, a process-only token surviving Restore incorrectly, or a speculative new schema. If source proof shows identity collision between old/new persisted states, the implementation must add a narrowly authorized durable discriminator with full migration/producer/consumer review; absent that authorization, STOP with evidence, not a partial write. No broad schema change is preapproved by this checkpoint.

**Scope boundary:** root BUG-INCOGNITO-01 only; queue-click and relevant configuration producer carriers, narrow DAO/VM/repository callers, incognito adapter/multiple and single dialog/fragment boundaries only when source-driven; exact tests. Inspect but do not rewrite DownloadWorker, fingerprint/History, global scheduler, BG format root, schema/migration, general Restore, or format authority global session. Any newly unavoidable durable discriminator/schema change requires independent reviewer and owner scope approval before edit; preserving historical source and closure evidence is mandatory.

## Direct test and completion signal

Meaningful success is: **a committed Queued/Scheduled row has exactly the queue-click chosen ON/OFF, remains unchanged while waiting, the successful worker claim holds that value through History/log/fingerprint, and a later stale predecessor cannot republish over it**. An aborted/ambiguous candidate has no runnable worker or leaked preparatory side effect and displays a truthful refusal/current setting. Deterministic tests must cover:
- direct ON and OFF, paired audio+video, multi-current Processing selected/empty/mixed, ordinary background queue, scheduled delayed start, exact worker-held effects;
- manual explicit toggle vs inherited stale value, concurrent DB privacy edits between observation/queue/publish, intentional OFF replacing prior ON, two-order/latch, old object after Restore/reinsert, ID/revision ABA;
- source transitions Error→Processing, Error same-settings retry, History metadata preparation, normal Processing/direct Queued/Scheduled and notification Saved→Processing, duplicate CONFIG/archive-unavailable preparatory writes, Undo and Saved/Cancelled/reconfigure;
- DB transaction abort, Restore admission, process death, recovery/retry and older supported migrations. Assert final DB + WorkManager/fingerprint/History correctness through real production paths (Android execution only separately authorized). Tests cannot just observe button alpha, helper return, or a mock worker.

**WP0-A product choice is now fixed.** Exact original observation/revision capture and collision-safe old DB roots are a source implementation PRECONDITION, not a repeated user-question. Reviewer may preflight a new distinctly authorized implementation prompt only if the source can prove an old/new discriminator, or bound a guaranteed fail-closed unversioned path without falsely promoting it to a persisted known generation. Do not auto-relaunch consumed WP1_03. The current checkpoint does not itself change source or canonical finding status.

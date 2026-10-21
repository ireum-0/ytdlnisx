# WP0-B02 replacement-carrier contract diagnostic — independent completion review FINAL

date: 2026-10-21
checkpoint_kind: INDEPENDENT_DIAGNOSTIC_COMPLETION_AND_SOURCE_COUNTEREXAMPLE_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_expected_parent: e104e229db1341f871d9799fcfe494c0032952a0
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_review_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
reviewed_task_instance_id: WP0B02_REPLACEMENT_CARRIER_CONTRACT_20261021_141B519_01
reviewed_prompt_origin_commit: 70c55a6e54ba4f867bcb439df148c44b415b0c3c
reviewed_prompt_blob: 3bbc44cbf604de556f8818463867e55000e68a9b
reviewed_private_report_path: ytdlnisx/reports/2026-10-21__WP0B02__wp0b02-20261021-cb723ca4__FULL_REPORT.md
reviewed_private_report_commit: 30d8e73724685729264e9539b327f5a4fed161ea
reviewed_private_report_blob: 28d0124b062afccba712c61c77b4ce407e88aeb8
verified_intent_commit: e07e6011089ddc45227dedad49596aa18c9d2e66
verified_intent_blob: a873194b642469d931e731e2fac6561c1a4519b0
verified_claim_commit: 19a2e0047a95ba1a230ed720d4c20e16d1cde3b9
verified_claim_blob: 30059f62d08a44cbfebcc6ba7be87df05f8cf77b
reviewer_verdict: VALID_COMPLETED_READ_ONLY_DIAGNOSTIC_CONTRACT_INCOMPLETE_NOT_CLEAN
diagnostic_result: CONTRACT_INCOMPLETE
full_implementation_ready: NO
new_canonical_roots: 0
canonical_root_disposition_changes: 0
active_confirmed_open_p2: 4
active_ambiguous_p2: 4
active_closed_p2: 2
active_unresolved_p2: 8
clean_basis_advanced: NO
source_changes_published: NO
tests_executed_by_agent: ZERO_REPORTED
tests_executed_by_reviewer: ZERO
build_or_device_executed_by_agent: NONE_REPORTED
next_task_or_implementation_authorized_by_this_review: NO
reviewer_private_handoff_changed: NO
existing_manual_l2_run: UNCHANGED_IN_PROGRESS

## Independent receipt and publication checks

At review, private ytdlnisx-review HEAD is the exact report commit `30d8e73724685729264e9539b327f5a4fed161ea`. Bounded private forward comparison from the previous verified launch-ready handoff `8aa4f56ba8f623293d2d6879a3e346cf0c32e9b0` is three strictly forward commits, adding only the task's intent, matching claim, and Full Report. Intent `e07e601` has exactly one parent `8aa4f56` and adds only its deterministic path; claim `19a2e004` has exactly one parent `e07e601` and adds only its deterministic claim path; report `30d8e737` has exactly one parent `19a2e004` and adds only this new immutable report. Their independently fetched exact blob fields agree on task ID, prompt path, prompt SHA, origin, protocol, implementation base and one 32-hex claimant ID. No same-task relaunch is permitted. The Full Report's statement that 444 prior blobs and local protected state were unmodified is AGENT_REPORTED; the bounded private commit delta independently proves only the three new private paths and no private preexisting path mutations. Agent Windows worktree hashes were not independently reproduced here.

The exact remote implementation HEAD is unchanged at `141b5199592369ab953f52c55ebdb72ba300f1a4`; no implementation range exists to review or promote. The report explicitly records zero source/test edits, zero compilation, zero test methods and zero Android operations. This is no test PASS, no candidate patch, and no permission to modify production or close roots.

## Independently checked source boundaries

1. **CE1—generic acceptance is not replacement cleanup acknowledgement (future naive extension counterexample):** `WorkManagerHandoffRecovery.finalizeAccepted` conditionally calls `dao.deleteAccepted` for non-retained accepted kinds at lines 2137–2189. `WorkManagerHandoffCarrierDao.deleteResolved` deletes any RESOLVED row without replacement-obligation predicates (DAO lines 166–181); startup `WorkManagerHandoffRecovery.reconcile` invokes `dao.deleteResolved` at lines 1237–1261 before scanning outstanding rows. The existing code has NO replacement-kind producer, so this proves unsafe *blind reuse*, not a newly executing shipped A→P cleanup-loss path.
2. **CE2—dispatch supersession is not external cleanup discharge (future naive extension counterexample):** `WorkManagerHandoffRecovery.reconcileSuperseded` (lines 1484–1515) cancels/observes the exact WorkInfo then `deleteExact` when null or finished, with a narrow null tombstone exception but no check of original A cache/Undo/notification effect progress. Adding the new kind only to retention lists does not establish cleanup continuity across finished request supersession.
3. **CE3—new kind cannot simply use existing Worker input:** `WorkManagerHandoffRecovery.buildRequest` (lines 2754–2774) errors on unknown kind; `DownloadWorker.doWork` (lines 1140–1158) interprets any nonblank handoffId as `SCHEDULE_START`. Passing an unrecognized replacement request using this unchanged path can misclassify the request without a valid replacement-specific worker claim. Existing scheduled and other kinds remain separate.
4. **Established root persists:** `BUG-RECONFIGURE-PREADMISSION-DELETE-01` remains OPEN at this SAME implementation SHA, per canonical WP0-B FINAL blob `4deda55b0a9a37f375f54849b58aa22f37b3b19b`. The Saved/Errored/Cancelled producer starts ID-only asynchronous A→P conversion and the multi-download dialog deletes A before candidate duplicate/queue or schedule success. No source correction occurred. Stale-ID ABA remains a subcase of existing OPEN `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`, not a second active new root.
5. **Lock-order source observations are unresolved:** report traces retry and single cancellation as L→E→R and Saved notification R→E and Clear Queue R→L→E. These are source-supported cyclic-wait possibilities, not observed Android deadlocks or independently promoted new canonical roots. A candidate ordering is not proof of every caller closure.
6. **Undo/Restore/provenance:** no existing carrier kind, row stamp alone, Undo resolver or Restore journal supplies the full committed exchange, frozen original cleanup, worker acceptance, supersession and final-ack ownership. Report's proposed one-member Room exchange with a versioned retained carrier is an architectural candidate only. In particular, unsettled Undo's accepted RESTORE/COMMIT and targeted Restore remapping/transfer are NOT_VERIFIED; no compatibility/old-schema runtime test was performed.

Source links (all exact immutable implementation SHA):
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt#L2137-L2189
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/WorkManagerHandoffCarrierDao.kt#L166-L182
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt#L1237-L1261
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt#L1479-L1515
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt#L2754-L2774
- https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1140-L1158

## Root/count and governance disposition

No OPEN/CLOSED/AMBIGUOUS classification changes are justified by a read-only carrier-design diagnosis without a candidate, test or new independent wrong-effect root. Canonical active scope remains 4 OPEN P2 + 4 AMBIGUOUS P2 + 2 CLOSED P2 = 8 unresolved, NOT_CLEAN, with no CLEAN Review Basis advancement. Preserve earlier CLOSED notification `BUG-RECONFIGURE-01`, CLOSED `BUG-DUPLICATE-ADMISSION-01`, current BG02/BG03, exact cache-ownership guards and the WP0-A incognito policy. The preexisting manual L2 review marked `manual_review_run: YES`, `IN_PROGRESS`, pinned at 141b519, remains its existing run: this distinct `manual_review_run: NO` evidence checkpoint does NOT finalize/replace/restart it or promote another lens.

## Minimum necessary follow-on contract, recommendation only

Before ANY functional replacement correction can be safely authorized, settle one-member A/P selection provenance and all stale full-row writers; define explicit pending-Undo exclusion vs exact intent-transfer; define replacement-specific carrier immutable payload, CASed progress, startup discovery and wakeup, worker claim acknowledgment, revocation without cleanup loss and final effect discharge; define targeted Restore preservation/transfer including old snapshot identities; and remove E→R/R→L reverse waits with audited callers. Only after these source-contract gates and separate user authorization should implementation/test/device scopes be considered. A predestructive fail-closed containment option may be separately approved but must not claim feature repair. Do not create the next implementation or diagnostic prompt, change user handoff agent-routing fields, run Android or use the stopped WP1-04 draft in this completion review.

Latest canonical WP0-B defect and manual L2 state remain authoritative. This checkpoint is a narrower diagnostic completion-evidence record, not a new competing manual review or a new canonical root classification.

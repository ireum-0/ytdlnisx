# Manual v7 review — 359ddbf9 — L6 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 1b014856b5402e19e0f4b9167de0e563b30f6cec

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
review_parent_sha: 7c110ed34aaf2d90d1c1da55366af342240851f7
implementation_sha: 359ddbf9bf534009be095ad1bffea8ec45c899e4

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: 11caa19a94b3a299a1b2f451464a14ef991bc10c
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

Governance remained fixed for this run.

## Independent verdict

NOT_CLEAN.

Exact reviewed implementation:
359ddbf9bf534009be095ad1bffea8ec45c899e4

Open canonical roots:
- BUG-BACKUP-11 — OPEN P2
- BUG-PAUSE-03 — OPEN P2
- BUG-CANCEL-02 Terminal publication/effect-quiescence — OPEN under the existing P2 root

Preserved closure:
- BUG-TERMINAL-11 — FIXED-CLOSED

New P0/P1/P2 finding IDs: 0.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

CLEAN_REVIEW_BASIS remains:
74f57e695db30b701ad429af311c39a763bfe086

Independent execution: NOT EXECUTED.

The preserved local-only candidate
b811724047f6ddfbd7c9c534f876e7f36e5f27d8
was not used as source authority.

## Primary DEEP lens

primary_deep_lens: L6 Cross-feature semantic propagation

L6 was the final not-yet-DEEP lens on this exact SHA.

### BUG-CANCEL-02

Cross-feature chain:

cancellation request
-> durable dispatch supersession
-> Terminal execution convergence from NATIVE_FINISHED
-> exact active token removal
-> row deletion authority
-> already-running worker can still reach publication
-> cache maintenance can lose positive live-owner protection.

The same root reaches row lifetime, publication effect lifetime, process-local ownership, and cache-maintenance destructive authority.

Execution/publication recovery protects restart and re-entry, but is not a same-process barrier against the still-running worker interval.

Result: OPEN.

### BUG-BACKUP-11

Cross-feature chain:

SAF picker
-> persisted provider grant + command_path locator
-> backup serializes portable command_path locator
-> merge/reset restore replays locator without destination-side grant proof
-> executable consumers bind/use the locator
-> capability failure is discovered later at publication.

Executable consumers include:
- Terminal durable command materialization;
- DownloadType.command item creation;
- switching to DownloadType.command;
- command History redownload defaults.

AndroidHistoryFileDeletionGateway is a safe sibling consumer because it separately checks persisted write authority. That does not repair the unsafe restore-to-execution path.

Result: OPEN.

### BUG-PAUSE-03

Cross-feature chain:

Pause-All snapshots Active/PostProcessing rows
-> only those rows receive USER_PAUSE authority
-> cancelAllWorkByTag("download")
-> ordinary/scheduled DownloadWorker requests share the same tag
-> a late sibling can be cancelled without USER_PAUSE authority
-> generic cleanup/recovery can requeue it
-> Resume All only enumerates Paused rows.

Result: OPEN.

## Trigger map

- Module A: BUG-BACKUP-11 FAIL / OPEN
- Module B: BUG-CANCEL-02 FAIL / OPEN; BUG-PAUSE-03 FAIL / OPEN; BUG-TERMINAL-11 preserved PASS
- Module C: BUG-BACKUP-11 FAIL / OPEN
- Module F: prior Terminal generation closure preserved PASS; no new schema delta
- Module H: BUG-BACKUP-11 FAIL / OPEN
- Module I: BUG-CANCEL-02 FAIL / OPEN

No blocker-relevant triggered module is deferred.

## Thread-affinity candidate

Current source confirms FileUtil.moveFile() enters withContext(Dispatchers.Main).

The direct raw-file branch can perform directory traversal and Files.move, or pre-26 copyTo plus delete, while still in that Main-dispatch region.

This is a real thread-affinity/hardening candidate.

Reconciliation:
- historical M3 was detached Terminal move ownership;
- historical MainActivity ANR/OOM was unbounded shared-text input;
- no current canonical finding for this exact FileUtil behavior was found.

Disposition:
- P1/P2 correctness-blocker severity: NOT_VERIFIED
- no new finding ID
- no canonical count change

## L1-L6 final coverage

Fresh BASELINE review was performed for all six lenses.

Final exact-SHA coverage:
- L1 Durability & recovery: DEEP
- L2 Identity & provenance: DEEP
- L3 Concurrency & authority: DEEP
- L4 Destructive ownership: DEEP
- L5 Platform contract closure: DEEP
- L6 Cross-feature semantic propagation: DEEP

remaining_not_yet_deep: NONE
next_not_yet_deep_lens: NONE

Another same-SHA manual review must not invent another lens rotation without materially new evidence.

## Local-only stop-rule evidence

The current no-push stop-rule checkpoint reports a focused row-convergence failure for local candidate b8117240.

Because that candidate is not GitHub-authoritative, this manual review does not classify the local failure mechanism.

The existing no-push diagnostic remains the next implementation-agent action. Production source changes and push remain unauthorized until that diagnostic classifies the failure.

## Root reconciliation

- BUG-CANCEL-02 row/publication/recovery/cache effects = one root
- BUG-BACKUP-11 Terminal/command-download consumers = one root
- BUG-PAUSE-03 scheduler/requeue/Resume-All effects = one root
- FileUtil thread-affinity candidate = uncounted
- b811 local failure = evidence about existing BUG-CANCEL-02 until independently classified

No duplicate root was counted.

## Review retrospective

The final L6 promotion found no new canonical P1/P2 root.

It confirmed downstream propagation for every current open root and completed all six lenses on the exact remote SHA.

## Checklist evolution

No checklist gap confirmed.

Checklist v7 already covers:
- semantic-contract fan-out
- asynchronous request vs completion
- positive live authority
- external capability projection
- scheduler handoff
- maintenance/live-owner separation
- thread affinity

No checklist or lens-policy change is proposed.

## Final checkpoint summary

- manual_review_run: YES
- manual_review_run_status: FINAL
- manual_review_start_parent: 1b014856b5402e19e0f4b9167de0e563b30f6cec
- pinned implementation: 359ddbf9bf534009be095ad1bffea8ec45c899e4
- intermediate: 7c110ed34aaf2d90d1c1da55366af342240851f7
- verdict: NOT_CLEAN
- new finding IDs: 0
- canonical totals: P0=0 / P1=0 / P2=19
- all L1-L6 lenses: DEEP
- next same-SHA lens: NONE
- local b811 candidate remains non-authoritative
- next governed action remains the existing no-push b811 diagnostic
- independent execution: NOT EXECUTED

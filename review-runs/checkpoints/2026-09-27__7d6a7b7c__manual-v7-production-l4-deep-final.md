# Manual correctness review — 7d6a7b7c — production L4 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 8e543bf3dbb4d110c15ff7989e34ec1a001471d8

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: f373154bc495bb2070d7a65ca976eee513c59743
intermediate_checkpoint:
review-runs/checkpoints/2026-09-27__7d6a7b7c__manual-v7-production-l4-deep-intermediate.md
intermediate_commit: f373154bc495bb2070d7a65ca976eee513c59743

implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
implementation_parent: 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a
implementation_branch: checkpoint/pre-baseline-review

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: d9d112148965c0e4151e653015842dc783f52916
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

The review-only Astra XHigh 13-root batch authorization at
8e543bf3dbb4d110c15ff7989e34ec1a001471d8 is part of the run-start review
parent. It changes implementation sequencing only, not finding disposition.

Governance and implementation remained fixed for the whole manual run.

## Independent verdict

NOT_CLEAN.

Tooling:
- BUG-TOOLING-01 = FIXED-CLOSED
- BUG-TOOLING-02 = FIXED-CLOSED
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=0

Production active-remediation counts:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

No new finding ID was created.

INDEPENDENT EXECUTION: NOT EXECUTED

## Findings

### Tooling destructive ownership — no residual

The exact-old CAS publication path remains within the narrow Protocol Section
1.1 exception.

The production completion path:
- publishes exact TestedSha only;
- binds the lease to the exact destination ref and exact accepted old SHA;
- proves accepted old destination is an ancestor of TestedSha;
- stops on lease rejection without retry/reconciliation;
- performs exact post-write SHA and 0/0 verification.

No plain --force, implicit lease, leading-plus refspec, reset, clean, rebase,
amend, squash, branch-force, or update-ref production path exists in the
reviewed tooling surface.

Disposable acceptance-fixture mutation helpers are not production publication
paths.

Tooling remains FIXED-CLOSED.

### BUG-DOWNLOAD-01 — OPEN P2

Current finalization still performs an authoritative Download-row reread through
runCatching(...).getOrNull().

A Room read failure therefore becomes null. With an expected nonblank execution
ID, that can be interpreted as ownership loss and reach exact
DownloadWorkerExecutionOwners / DownloadWorkerProcessOwners release and
process-local bookkeeping removal.

An indeterminate read still authorizes destructive owner retirement. This is
the existing BUG-DOWNLOAD-01 root.

### BUG-PAUSE-03 — OPEN P2

pauseAllDownloads() preserves the hardened exact per-item USER_PAUSE protocol,
but after processing the captured exact set still invokes:

WorkManager.getInstance(application).cancelAllWorkByTag("download")

A sibling admitted after the original snapshot has not acquired that
Pause-All operation's durable USER_PAUSE authority, yet the final shared-tag
transport mutation can revoke its WorkManager carrier.

The destructive target set remains wider than the semantic target set. This is
the existing BUG-PAUSE-03 root.

### BUG-RUNTIME-01 — OPEN P2

installBundledFfmpegPayload() still deletes the live payloadRoot before
replacement verification and extracts directly into the same path.

Failure during extraction/dependency materialization can therefore destroy the
last valid live generation before a verified replacement has been durably
published.

This remains BUG-RUNTIME-01.

### BUG-TERMINAL-06 — OPEN P2 / PARTIALLY FIXED

AppCacheManager correctly serializes maintenance with
CacheMaintenanceAuthority and rechecks each entry before deletion.

Its Terminal protection predicate, however, still delegates to
TerminalCacheOwnership.isLiveOwnedRoot(), whose live result requires a valid
marker plus process-local TerminalExecutionRegistry activity.

Current production also has:
- durable TerminalExecutionRecovery witnesses;
- marker-revoked Terminal recovery carriers;
- explicit recovery discovery.

Those durable authority classes are not consumed by AppCacheManager's final
destructive predicate.

After process death, or after marker revocation for partial-publication
recovery, generic Terminal cache maintenance can still treat exact
recovery-owned filesystem state as removable.

This is the existing BUG-TERMINAL-06 residual.

### BUG-HISTORY-04 — OPEN P2 / PARTIALLY FIXED

deleteDuplicates() still captures duplicateGroups before the destructive
transaction boundary, performs assignment merges separately, and later deletes
the captured IDs.

The current URL/type-derived HistoryDuplicateIdentity is not reread and
revalidated for retained and removed rows inside one final relationship
transaction immediately before deletion.

A stale candidate can therefore still authorize row deletion after identity
change, and failure between separate merge/delete phases can leave partially
applied relationship state.

This remains BUG-HISTORY-04.

### BUG-MIGRATION-01 — OPEN P2 / PARTIALLY FIXED

Video-folder migration still retires the physical old source inside
moveFileToFileDirectory()/moveFileToContentTree() before durable History path
publication.

Only after moveFileToDestination() returns does the caller later execute the
History-row path update.

Failure/process death in that interval can leave durable History pointing at a
source that was already removed, without an authoritative old-to-new recovery
carrier.

This remains BUG-MIGRATION-01.

### No additional L4 root

Other reviewed destructive paths contain material hardening:
- explicit History delete-with-files revalidates record target snapshots and
  retained references under the History reference coordinator;
- Terminal attempt cleanup records recovery/quarantine state before ownership
  revocation and uses exact task-token scope;
- Download output cleanup uses exact execution/marker ownership in multiple
  paths;
- tooling CAS never uses its lease to authorize a non-forward update.

Those controls do not close the six open roots above, but no independent new
destructive root was established.

## Review retrospective

This same-SHA review did not reuse the prior L3 verdict as L4 proof.

The review followed destructive authority to actual delete/release/cancel/
replacement effects and compared the identity available at that point with the
semantic owner that authorized the effect.

The six production issues found are already separately represented in the
canonical 13-root inventory. They were therefore retained as existing roots
rather than duplicated.

The tooling CAS was reviewed specifically because its command spelling contains
force-with-lease. Its semantics remain exact-old CAS over a separately proven
strictly forward update, with no retry/rewrite fallback, so L4 does not reopen
BUG-TOOLING-02.

## Checklist evolution

No checklist or protocol change is required.

Existing checklist rules already require:
- discovery is not mutation authority;
- exact owner revalidation at the destructive boundary;
- positive live authority preservation;
- no widening from exact identity to coarse tags/IDs/path snapshots;
- old authority preservation until replacement publication is durable.

Those rules were sufficient to classify every current L4 path reviewed.

## Trigger/module status

- tooling remote branch destructive authority:
  TRIGGERED / PASS at source level
- exact-old lease failure:
  TRIGGERED / PASS — no retry/reconciliation
- Download execution-owner retirement:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01
- Pause-All transport revocation:
  TRIGGERED / OPEN — BUG-PAUSE-03
- runtime live-generation replacement:
  TRIGGERED / OPEN — BUG-RUNTIME-01
- Terminal maintenance vs durable owner namespace:
  TRIGGERED / OPEN — BUG-TERMINAL-06 / Module I
- History dedupe destructive identity:
  TRIGGERED / OPEN — BUG-HISTORY-04
- video-folder migration source retirement:
  TRIGGERED / OPEN — BUG-MIGRATION-01
- independent additional L4 semantic root:
  NOT ESTABLISHED
- new schema/ABI/external-representation trigger from tooling CAS:
  NONE

## L1-L6 coverage

- L1 Durability & recovery: BASELINE
- L2 Identity & provenance: BASELINE
- L3 Concurrency & authority: DEEP
- L4 Destructive ownership: DEEP
- L5 Platform contract closure: BASELINE
- L6 Cross-feature semantic propagation: BASELINE

primary_deep_lens: L4 Destructive ownership

primary_deep_selection_reason:
R1/R3 — after L3 closed the tooling final-writer race, the highest-relevance
remaining lens was whether durable delete/release/cancel/replace effects are
still scoped to the exact proven owner at the final mutation boundary.

remaining_not_yet_deep:
- L1
- L2
- L5
- L6

next_not_yet_deep_lens: L1 Durability & recovery

next_lens_selection_reason:
R2 — the strongest remaining cross-root risk is crash/restart responsibility:
BUG-DOWNLOAD-01, BUG-RUNTIME-01, BUG-TERMINAL-06, and BUG-MIGRATION-01 all
depend on preserving durable responsibility across failure boundaries.

## Checkpoint summary

manual_review_run_status: FINAL
overall_verdict: NOT_CLEAN

implementation_sha:
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

tooling_counts:
P0=0 / P1=0 / P2=0

production_counts:
P0=0 / P1=0 / P2=13

new_finding_ids: 0

same_sha_deep_coverage:
- L3 Concurrency & authority
- L4 Destructive ownership

next_not_yet_deep_lens:
L1 Durability & recovery

authorized implementation batch remains:
BUG-DOWNLOAD-01; BUG-LOCALADD-06; BUG-UPDATER-02; BUG-SCHEDULER-05;
BUG-ABI-01; BUG-HISTORY-04; BUG-MIGRATION-01; BUG-RUNTIME-01;
BUG-TERMINAL-06; BUG-COOKIE-03; BUG-BACKUP-11; BUG-PAUSE-03;
BUG-RESUME-01

Finding dispositions are unchanged by the batch authorization and remain open
until independent post-publication review.

INDEPENDENT EXECUTION: NOT EXECUTED

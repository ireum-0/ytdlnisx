# Manual correctness review — 7d6a7b7c — L4 destructive ownership DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 8e543bf3dbb4d110c15ff7989e34ec1a001471d8

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 8e543bf3dbb4d110c15ff7989e34ec1a001471d8
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

Later review-only batch authorization at
8e543bf3dbb4d110c15ff7989e34ec1a001471d8 is part of the run-start review
parent. It authorizes implementation sequencing only; it changes no finding
disposition and no production source.

Governance is fixed for this manual run.

## Current disposition

Overall: NOT_CLEAN.

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

This run creates no new finding ID.

INDEPENDENT EXECUTION: NOT EXECUTED

## Tooling L4 — destructive ownership

The current tooling CAS path remains source-level consistent with Protocol
Section 1.1.

Complete-Wave has one production remote mutation path. It:
- binds the source to exact TestedSha;
- binds the expected old destination to the exact final observed SHA;
- proves the accepted old destination is an ancestor of TestedSha;
- uses only the exact-ref / exact-old force-with-lease spelling;
- stops on lease rejection without retry/reconciliation;
- requires exact post-write remote equality and 0/0 ahead/behind.

The tooling tree contains no plain --force, implicit/tracking lease, leading-plus
refspec, reset, clean, rebase, amend, squash, branch-force, or update-ref path in
the completion implementation.

Acceptance-fixture update-ref and ordinary push calls are confined to disposable
synthetic repositories and are not production mutation paths.

No tooling L4 residual is established.

## Production destructive roots — current source

### BUG-DOWNLOAD-01 — OPEN P2

The same authoritative read failure has an L4 final effect.

DownloadWorker finalization still obtains latestStatus with:

runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()

A Room read exception becomes null. For a nonblank expected execution ID this
can make stillOwnsAttempt false and enter the branch intended for proven
ownership transfer. That branch releases the exact
DownloadWorkerExecutionOwners / DownloadWorkerProcessOwners entries and removes
process-local bookkeeping.

An indeterminate authority read therefore authorizes owner retirement. This is
the existing BUG-DOWNLOAD-01 root, not a new finding.

### BUG-PAUSE-03 — OPEN P2

pauseAllDownloads() still performs the exact per-item USER_PAUSE protocol and
then executes a final shared-tag:

WorkManager.getInstance(application).cancelAllWorkByTag("download")

A sibling admitted after the original exact snapshot did not acquire this
Pause-All operation's USER_PAUSE authority, but the shared-tag transport
mutation can still revoke its WorkManager carrier.

The final destructive target set is therefore wider than the durable semantic
target set. This remains BUG-PAUSE-03.

Other intentional global operations such as application-wide cancel/exit have
separate semantics and do not make the Pause-All broad cancellation authorized.

### BUG-RUNTIME-01 — OPEN P2

installBundledFfmpegPayload() still deletes the live payloadRoot before
replacement verification:

if (payloadRoot.exists()) payloadRoot.deleteRecursively()

The replacement is then extracted into the same live path, required-dependency
copies may fail inside narrower runCatching blocks, and the revision marker is
written after direct in-place construction.

A failed replacement can therefore destroy the last previously usable runtime
before a verified new generation has been committed. This remains the
destructive half of BUG-RUNTIME-01.

### BUG-TERMINAL-06 — OPEN P2 / PARTIALLY FIXED

AppCacheManager deletion remains serialized by CacheMaintenanceAuthority and
rechecks each entry immediately before delete.

For Terminal entries, however, the final predicate still calls only:

TerminalCacheOwnership.isLiveOwnedRoot(current)

That helper accepts a marker only when its task token is present in the
process-local TerminalExecutionRegistry.

TerminalExecutionRecovery now has durable exact witnesses and
TerminalCacheOwnership has explicit marker-revoked recovery carriers, but
AppCacheManager's destructive classifier does not consume either durable
authority class.

After process death, or after marker revocation for a partial-publication
recovery carrier, generic Terminal-cache maintenance can therefore observe no
process-local live owner and remove exact recovery-owned files.

This is the existing BUG-TERMINAL-06 same-root residual.

### BUG-HISTORY-04 — OPEN P2 / PARTIALLY FIXED

HistoryViewModel.deleteDuplicates() still:
1. snapshots duplicateGroups;
2. merges assignments for each captured pair in separate calls/transactions;
3. later calls deleteHistoryRecords() with the captured duplicate IDs.

The current History rows' HistoryDuplicateIdentity is not reread and compared
inside the same final relationship-mutation transaction before delete.

A row whose URL/type changes after discovery can therefore still be removed by
stale ID, and process failure between assignment merges and final delete can
leave a partially applied relationship state.

This remains BUG-HISTORY-04.

### BUG-MIGRATION-01 — OPEN P2 / PARTIALLY FIXED

migrateDefaultVideoFolderInternal() still holds the History reference
coordinator while iterating, but moveFileToFileDirectory() /
moveFileToContentTree() copy/rename the physical source and delete the old
source before History path publication.

Only after moveFileToDestination() returns does the caller later execute:

historyDao.updateDownloadPathById(item.id, updatedPaths)

A failure/process death after source retirement and before that durable path
update can therefore leave the History row pointing to the retired old path
without an authoritative old-to-new recovery carrier.

The same movedPathMap also permits multiple History references to consume one
physical move result without making the source-retirement/publication transition
itself crash-safe.

This is the established BUG-MIGRATION-01 root, not a new finding.

## Other L4-triggered production boundaries

The reviewed current source contains substantial hardening that should not be
misclassified as new roots:

- History file deletion with explicit user delete-with-files revalidates stored
  target snapshots and retained references under HistoryReferenceMutationCoordinator
  before record deletion.
- Terminal worker staging cleanup records recovery/quarantine state before
  marker revocation and uses exact task-token ownership for attempt-local
  cleanup.
- Download cleanup/output paths increasingly use exact execution side-effect
  leases and ownership markers.
- tool-only CAS publication is forward-only despite force-with-lease spelling.

These controls do not close the six existing roots above, but they prevent
widening them into broader deletion findings.

## Trigger/module status

- tooling remote branch destructive authority:
  TRIGGERED / PASS at source level.
- tooling lease rejection:
  TRIGGERED / PASS; no retry/reconciliation/overwrite.
- Download exact owner retirement:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01.
- Pause-All transport revocation:
  TRIGGERED / OPEN — BUG-PAUSE-03.
- runtime generation replacement:
  TRIGGERED / OPEN — BUG-RUNTIME-01.
- Terminal maintenance vs durable live/recovery owner namespace:
  TRIGGERED / OPEN — BUG-TERMINAL-06; Module I applies.
- History dedupe destructive identity:
  TRIGGERED / OPEN — BUG-HISTORY-04.
- video-folder migration source retirement/publication:
  TRIGGERED / OPEN — BUG-MIGRATION-01.
- additional independent L4 semantic root:
  NOT ESTABLISHED.
- newly triggered ABI/schema/external-representation module caused by the
  tooling CAS range:
  NONE.

## L1-L6 coverage

- L1 Durability & recovery: BASELINE
  Existing production roots above include recovery gaps, but no new L1 root is
  created by this same-SHA pass.
- L2 Identity & provenance: BASELINE
  destructive identities above map to existing exact roots; tooling CAS retains
  explicit source/old-destination identity.
- L3 Concurrency & authority: DEEP from prior same-SHA manual run.
- L4 Destructive ownership: DEEP in this run.
- L5 Platform contract closure: BASELINE
  no new platform-specific destructive contract was triggered by the tooling
  CAS commit.
- L6 Cross-feature semantic propagation: BASELINE
  current destructive consumers were traced to their known canonical roots; no
  new unclassified consumer was established.

primary_deep_lens: L4 Destructive ownership

primary_deep_selection_reason:
R1/R3 — after L3 closure of the tooling CAS race, the highest-relevance
remaining lens is whether delete/release/cancel/replace effects remain scoped to
the exact proven owner at the final mutation boundary. Current source exposes
six already-canonical production roots under that lens and no new tooling root.

remaining_not_yet_deep:
- L1
- L2
- L5
- L6

next_not_yet_deep_lens: L1 Durability & recovery

next_lens_selection_reason:
R2 — with L3/L4 DEEP on this SHA, L1 is the strongest remaining production-wide
lens because BUG-DOWNLOAD-01, BUG-RUNTIME-01, BUG-TERMINAL-06, and
BUG-MIGRATION-01 all depend on crash/restart responsibility and durable
recovery carriers.

## Remaining scope before FINAL

- fresh-check implementation/review/private refs;
- reconcile only compatible forward review movement;
- append FINAL on the fixed implementation/governance basis;
- update handoff review tip/current manual checkpoint and same-SHA lens
  coverage;
- do not alter the authorized 13-root Astra batch prompt or finding counts
  unless new evidence appears.

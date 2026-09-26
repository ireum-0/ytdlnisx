# Manual correctness review checkpoint — cf737451 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: `f00e4fe3fe225dde22ed066f9dcad8099014775a`

## Frozen review basis

- implementation branch: `checkpoint/pre-baseline-review`
- implementation_sha: `cf7374510decad9f308fdc3f1b528e731a3ea4f1`
- plan/remediation_sha: `2145847a1054da28398b730b9be0ca728668f967`
- review/remediation_parent_sha: `f00e4fe3fe225dde22ed066f9dcad8099014775a`
- ledger/remediation_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- Master Plan blob: `507a97c1455793b272298e29f37b945f4cfb55d7`
- Master Plan SHA-256: `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`
- Review Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Review Checklist v6 SHA-256: `61f4c1f9c278601a773058d28682ca36c8f5465e67445458608dace8d5ca8c95`
- SOURCE_ARTIFACTS blob: `bdee5f5efeee81426433c64ceb975208dc41a299`
- TASKS blob: `fae11a65fc7fe1e58bd725d64355ce4c242f398c`
- TASKS_DELTA blob: `96869f414efe6c3c33d4586eecf08cccb18375cc`
- CURRENT_STATUS blob: `0dd569290dfc209763b4f94c2041bad492641d44`

Exact-SHA execution evidence at checkpoint time:
- GitHub statuses: 0
- GitHub check runs: 0
- independent Gradle/instrumentation execution: NOT EXECUTED

## Same-SHA lens progression

Previous current-SHA coverage:
- L1 DEEP
- L2 BASELINE
- L3 DEEP
- L4 BASELINE
- L5 BASELINE
- L6 BASELINE

This run promotes:
- **L2 Identity & provenance -> DEEP**

Selection reason:
- direct relevance to the current provider-authority remediation;
- L2 was not yet DEEP;
- same-SHA anti-starvation also requires a non-duplicative identity/provenance scope rather than re-reading only BUG-TERMINAL-03.

## Scope reviewed

Provider-intent identity:
- `TerminalItem`
- `TerminalProviderDestinationOption`
- `TerminalCommandIntentMaterializer`
- `TerminalCommandPlanFactory`
- `TerminalCommandPlanner`
- Terminal UI Folder-picker insertion
- Terminal row/carrier/fingerprint/request identity
- stale-request worker admission

Cross-domain Terminal/Download identity:
- `YtdlpProcessIdentity`
- `TerminalDownloadWorker`
- `DownloadWorker`
- `NotificationUtil`
- `CancelTerminalNotificationReceiver`
- `CancelDownloadNotificationReceiver`
- `TerminalCancellationCoordinator`
- Manifest receiver wiring
- same-numeric-ID unit/instrumentation source tests
- prior independent checkpoint `2026-09-20T0816Z__90afaec1__terminal-download-notification-collision-classification.md`

## Existing P2 — BUG-TERMINAL-03 remains OPEN

No status change.

Current-format provider authority is correctly part of exact durable identity:
- configured provider metadata is materialized into `TerminalItem.command`;
- the same command is staged into TERMINAL_DISPATCH `confirmedUrl`;
- SHA-256 command fingerprint covers that command;
- WorkRequest input carries command/fingerprint;
- worker admission requires exact current row/carrier/request/generation/fingerprint equality;
- planner removes provider metadata before native parsing and gives explicit provider metadata precedence over current configured provider unless an authored native output path governs.

Legacy same-schema state remains semantically incomplete:
- old row/carrier can match each other while omitting the original provider identity;
- current planner can then donate current `command_path` authority to the old intent.

L2 classification:
same BUG-TERMINAL-03 residual; no new root.

## Existing canonical P1 — BUG-TERMINAL-02 current-basis revalidation

Canonical `TASKS.md` and `ledger/remediation:TASKS.md` still say:
- `BUG-TERMINAL-02` — P1 — State OPEN.

That historical failure path states that Terminal and Download with the same numeric row ID share:
- bare native process identity;
- raw running notification ID;
- Download cancel receiver/capability;
- destructive cross-record cancellation.

Current source no longer reproduces that exact P1 path.

### Process identity separation

Current production:
- Download native identity = `download:<downloadId>:<executionId>`
- Terminal native identity = `terminal:<terminalId>`

`TerminalDownloadWorker` uses `YtdlpProcessIdentity.terminal(itemId)`.
`DownloadWorker` uses `YtdlpProcessIdentity.download(id, executionId)`.

Equal numeric row IDs therefore do not alias the native process registry.

### Cancel capability separation

Terminal progress notification uses:
- `CancelTerminalNotificationReceiver`
- extra `EXTRA_TERMINAL_ID`
- action URI domain `terminal`

Download progress notification uses:
- `CancelDownloadNotificationReceiver`
- Download ID + exact execution ID
- action URI domain `download`

Terminal cancellation converges Terminal dispatch/execution ownership and does not call `DownloadRepository.cancelByUser()`.

Equal numeric row IDs therefore do not give a Terminal cancel capability authority over an unrelated Download row.

### Same-number notification family separation

For equal numeric row IDs:
- Download running notification = `90000 + downloadId`
- Terminal running notification = `99000 + terminalId`

So the exact historical same-number notification collision is not reproduced.

Prior independent review at exact basis `90afaec157607669ea32fa41877e7f0efcdcca86` already reached the same current-basis classification:
`NOT REPRODUCED / REJECT AS CURRENT P0/P1/P2 CANDIDATE`.

The current `YtdlpProcessIdentity.kt` blob is identical to that reviewed basis. Other relevant files changed later but current source inspection confirms the domain separation remains present.

### Registry/review reconciliation gap

This is **not a new status transition first discovered by this run**. Prior review already established the historical P1 path was not reproduced.

However, canonical `TASKS.md` and ledger `TASKS.md` still say OPEN.

Record:
- review current-basis decision: historical exact P1 failure path not reproduced;
- canonical registry/ledger state: OPEN;
- reconciliation: **gap remains**;
- this review does not perform ledger closure.

## New L2 candidate — arithmetic notification-family collision

A narrower identity residual is source-confirmed.

Current helpers are arithmetic offsets over independent auto-generated IDs:

```
downloadRunningNotificationId(downloadId) = 90000 + downloadId
terminalNotificationId(terminalId)        = 99000 + terminalId
```

Therefore:
- Download ID 9001 -> notification ID 99001
- Terminal ID 1 -> notification ID 99001

Both `downloads.id` and `terminalDownloads.id` are independent Room `@PrimaryKey(autoGenerate = true)` Long values. No production invariant reviewed here bounds the difference so these arithmetic families are globally disjoint.

### Confirmed direct effect

Both paths use the same untagged application notification namespace. A colliding integer ID can therefore cause one running notification to replace or cancel the other notification.

This disproves the stronger statement that the arithmetic helpers form a globally disjoint notification namespace.

### What this does NOT reintroduce

The original BUG-TERMINAL-02 P1 destructive path is still absent:
- native process IDs are typed/namespaced;
- PendingIntent action domains are distinct;
- receivers are feature-specific;
- Download cancellation requires exact Download execution identity;
- Terminal cancellation does not mutate Download durable state.

A collision between different numeric IDs therefore does **not** by itself establish the former cross-record cancellation/process-kill P1.

### Severity / blocker classification

- source-confirmed UI/notification identity collision: YES
- durable cross-record mutation from this residual: NOT ESTABLISHED
- process identity collision: NO
- exact WorkManager/foreground-service semantic effect of this collision: NOT_VERIFIED
- P0/P1/P2 blocker severity: **NOT_VERIFIED**

Do not promote this candidate to a new confirmed P0/P1/P2 without proving a blocker-relevant effect.

It remains a concrete nonblocking-or-platform-effect candidate for L2/L5 follow-up.

## Candidate rejection discipline

Candidate: same numeric Terminal/Download ID still cancels the wrong durable row.
- Rejected. Separate receiver, action domain, process domain, and Download execution token prevent the historical path.

Candidate: same numeric Terminal/Download ID still shares running notification ID.
- Rejected for the exact same-number pair: bases 90000 and 99000 differ.

Candidate: arithmetic offsets are globally disjoint for all independent IDs.
- Rejected. Difference-of-9000 pairs collide.

## Provisional current-SHA coverage

- L1: DEEP
- L2: **DEEP**
- L3: DEEP
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

primary_deep_lens: L2

## Effectiveness — provisional

L2:
- coverage_level: DEEP
- new_confirmed_P0_P1_P2: 0
- existing_finding_status_changes: 0 newly established in this run
- confirmed_residuals_or_subcases:
  - BUG-TERMINAL-03 legacy provider identity omission
  - arithmetic notification-family identity collision (blocker severity NOT_VERIFIED)
- rejected_candidates_with_proof:
  - original BUG-TERMINAL-02 same-ID native/cancel cross-record mutation
  - same-number notification collision
- not_verified_candidates:
  - blocker/platform effect of cross-offset notification collision
- checklist_gaps_triggered: 0
- cross_feature_propagation_hits: 2
- lens_scope_reviewed:
  provider identity; dispatch fingerprint; process identity; PendingIntent capability identity; notification identity; historical current-basis review reconciliation.

## Remaining scope

- re-run L1/L3/L4/L5/L6 gates for this invocation rather than inheriting the prior verdict;
- refresh semantic-contract and Module B/C/F/H closure for BUG-TERMINAL-03;
- classify the cross-offset notification candidate without speculative platform severity;
- fresh-fetch implementation/review heads;
- append and verify one FINAL checkpoint.

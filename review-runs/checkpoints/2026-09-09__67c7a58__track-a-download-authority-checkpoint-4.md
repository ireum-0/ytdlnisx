# Track A Download authority checkpoint 4

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`

Ledger reference (not modified): `899328bc91e4008e39a658387396a0106c8666ec`

Independent execution: NOT EXECUTED

Working canonical count remains: `P0 0 / P1 0 / P2 8`.

This checkpoint continues independent Track A while Cluster T is being implemented separately. No in-progress Cluster T diff is reviewed here.

## 1. P2-J/J3 confirmed — ordinary committed History can lose to a late Pause/Cancel

The ordinary History path and History-replacement path do not establish the same semantic authority after the primary History mutation commits.

For a replacement, a successful authoritative replacement transaction immediately sets `historyReplacementCommitted = true`. Later cancellation/ancillary work is explicitly prevented from undoing that committed primary result.

For an ordinary History insert, `historyKeywordAssignments.insertHistory(historyItem)` returns the durable inserted History ID, but no equivalent committed-success authority is established. Later keyword work still runs through `withOwnedExecutionSideEffect(downloadItem)`, whose exact authority gate throws `CancellationException` when a durable same-execution user stop has won.

`runHistoryPersistence()` rethrows that cancellation. Attempt cleanup then consumes the user-stop carrier. The user-stop semantic protocol recognizes `COMMITTED_HISTORY_ALREADY_WON` only through committed History replacement detection. An ordinary History row is not considered stronger than the late Pause/Cancel.

Therefore a concrete production sequence exists:

1. E1 publishes exact output.
2. ordinary `HistoryItem H1(downloadId=X, downloadPath=D1)` is durably inserted.
3. a late user Pause/Cancel carrier becomes durable before the next owned History/completion side effect.
4. the ordinary side-effect authority check observes the stop and throws cancellation.
5. user-stop convergence commits Paused/Cancelled because committed-success detection is replacement-only.
6. H1 remains durable.

This is existing P2-J, not a new canonical P2.

Required invariant:

> Once an ordinary History success for the exact Download execution is durably committed, later same-execution user-stop requests cannot rewrite that execution into a pre-success state. They may affect only ancillary post-commit work; the committed success remains finalization authority.

## 2. P2-J/J3 downstream Resume path reaches E2

The late-Pause case is not merely a stale UI status.

Paused resume/reset DAO paths use the low-quality/history-replacement runnable guard. That guard excludes committed History replacements but does not exclude ordinary committed History rows.

`resumePausedIfExecutionOwned(...)` and `resetPausedToQueued()` can therefore turn the ordinary-success Paused row back into Queued state after H1 already exists.

A later worker can then claim E2. Queue admission guards are also replacement-specific. This connects the late-stop semantic downgrade to renewed producer execution.

Correction scope for P2-J must therefore include:

- startup abandoned-row recovery;
- worker post-History ancillary/error handling;
- late Pause/Cancel precedence;
- single/all Resume paths;
- generic status/runnable DAO guards;
- repository mutation guards that currently recognize only committed replacements.

## 3. P2-J ordinary History post-commit phases need exact identity

The ordinary path contains multiple post-insert operations, including automatic keyword application and optional duplicate-choice handoff. These operations occur after the History insert transaction has already returned an exact `historyId`.

The correction must distinguish primary semantic success from ancillary post-commit debt. A failure or cancellation in ancillary work must not reopen producer authority.

However `HistoryItem.downloadId` alone is not sufficient exact execution identity: History rows do not persist operationId/executionId and multiple History rows may share one downloadId.

The preferred authority is therefore an exact execution-bound durable success/finalization phase that records the specific committed History identity (`historyId` or an equivalently strong immutable commit identity) before generic recovery/user-stop/status mutation can run.

## 4. P2-B/B5 generation ordering confirmed at journal inventory boundary

`PublicationRecoveryJournal.findDownload()` calls `readAll(context).filter { ... }` and performs no generation ordering or semantic supersession selection.

`readAll()` derives its record order from `storageDirectory.listFiles()` and does not sort it into a semantic execution order.

`recoverPriorPublication()` iterates every matching prior execution record. Recovered destination paths are unioned while `sourceDestinations[artifact.sourcePath] = destination` uses iteration-order overwrite behavior for repeated source paths.

Therefore filesystem enumeration order can influence which historical destination is treated as the source-to-destination mapping, while multiple generations may remain in the recovered final path set.

No timestamp or monotonic generation field exists in `PublicationRecoveryJournal.Record`; executionId/attemptId are identities, not a semantic order.

This remains P2-B/B5 and strengthens the requirement for an explicit single authoritative generation/supersession relation.

## 5. P2-C retained after rollback-path falsification attempt

The provider replay finding remains valid, with a more precise impact model.

If D1 is already the durable exact destination for source S but S remains because source retirement failed, the next FileUtil provider route may cross a new provider create boundary before `onDestinationReserved(D2)` discovers that the journal already owns D1.

At that point FileUtil attempts to roll D2 back.

- If rollback succeeds, D2 is removed, but the source-retirement debt is still unresolved. The attempt throws and a later retry can repeat provider create -> rollback instead of converging S against existing D1.
- If rollback cannot be positively proven, the route becomes `UNRESOLVED_EXTERNAL_SIDE_EFFECT`/UNKNOWN and preserves a terminal fence.

Thus the correction is not merely "delete D2 better". It must recognize `D1 exact durable + source still exists` before crossing provider create and perform source-retirement convergence against D1.

This remains P2-C; no count change.

## 6. P2-H boundary — DownloadExecutionRecovery preferences are not added

The SharedPreferences-backed `DownloadExecutionRecovery` journal was reviewed as a possible additional fail-open namespace.

It is materially different from `PublicationRecoveryJournal.readAll()`:

- `pendingDownloadIds()` discovers a durable base numeric key even if later fields are malformed;
- `readPending()` validates paired fields/enums and throws for incomplete/unknown semantic state rather than dropping it;
- candidate recovery catches such failure and calls `deferRecovery()`;
- deferred debt retains/schedules a live recovery owner while the durable key remains discoverable.

Therefore this namespace is currently fail-closed for the reviewed malformed-carrier cases and is not added to P2-H.

P2-H remains focused on discovery APIs that collapse unreadable/malformed namespaces to healthy-empty, especially `PublicationRecoveryJournal` and Terminal recovery-carrier enumeration.

## 7. `--download-archive` no-output success — B7 test case, not new finding

The app can add `--download-archive` from the production `prevent_duplicate_downloads=download_archive` setting.

When yt-dlp reports an archive hit and `finalPaths` is empty, DownloadWorker treats it as a legitimate no-output completion path. Because there are no authoritative output files, no artifact publication journal is created for that result.

A process death before Download finalization can therefore make a later execution rerun the producer without a publication witness.

Current source review does not establish a concrete duplicate file/destructive effect from this archive-hit replay itself, so it is not promoted to another P2. It should nevertheless be included in P2-B/B7 regression coverage: a corrected generation/finalization lifecycle must not rely on non-empty publication artifacts as its only proof that a producer generation already completed.

## 8. F2 mtime/directory authority regression search

A focused scan of exact `67c7a58...` did not find the historical `recoverPathsFromDirectory()` production fallback in the current DownloadWorker.

Current `walkTopDown()/lastModified()` uses found in the exact worker are diagnostic enumeration or ordering of already-supplied/validated subtitle candidates, not promotion of destination-directory membership into `finalPaths` authority.

No new P2 was added from this direction.

## 9. P3 candidate retained — duplicate-choice ancillary handoff

After an ordinary History insert detects an existing duplicate History item, DownloadWorker calls `PendingDuplicateDownloadStore.add(newHistoryId, existingHistoryId)` and logs that user choice is required.

The store uses synchronous SharedPreferences `commit()` but does not check the Boolean result. A production consumer for this pending-pair store has not yet been established from the exact review basis, so this remains an unclassified/P3 candidate rather than a canonical blocker.

It is distinct from Master Plan F19 `BUG-DUPLICATE-01`, which concerns canonical media identity matching.

Do not fold this candidate into P2-J unless further evidence shows it can reopen primary producer authority or cause destructive mutation.

## 10. Current correction implications

Cluster D still remains the correct grouped boundary: P2-B + P2-C + P2-J.

The correction must cover both safety and liveness across:

1. exact producer generation identity;
2. immutable effective producer/output semantic identity;
3. producer/post-processing completion before publication;
4. deterministic prior-generation continuation or supersession;
5. exact provider destination vs source-retirement state;
6. publication complete;
7. ordinary/replacement/no-History semantic commit;
8. exact committed success identity;
9. late user-stop precedence;
10. repository/DAO runnable guards;
11. finalization;
12. authority retirement.

No new canonical P2 was added.

Working count remains:

`P0 0 / P1 0 / P2 8`

INDEPENDENT EXECUTION: NOT EXECUTED

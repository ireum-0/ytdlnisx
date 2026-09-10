# Independent Track A checkpoint — handoff / metadata / source-authority follow-up

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Working count: `P0 2 / P1 3 / P2 23`

## Observe Retry Download handoff — no additional blocker

The fixed-basis `ObserveRetryDecisionReceiver` creates a durable handoff before removing the Download-decision notification, and waits for the exact enqueue Operation outcome through `WorkManagerHandoffRecovery.enqueueAndAwait()`.

`prepareObserveRetryDownload()` uses a deterministic semantic handoff identity for the same source / canonical URL / config fingerprint / Download action and reuses an existing outstanding carrier instead of creating a second generation for duplicate taps.

`ObserveSourceWorker` consumes `handoffId`, `handoffRequestId`, and `configFingerprint`; a source reconfiguration or stopped source resolves/refuses the old exact carrier before queueing. Fetch failure under a confirmed handoff returns retry responsibility to the same semantic decision rather than scheduling an ordinary Observe successor. The decision is resolved only after the target was inserted or duplicate policy explicitly refused it, using `markObserveRetryResolved(handoffId, requestId)`.

No separate stale-consumer blocker is confirmed here. The Ignore follow-up can still rely on the ordinary Observe scheduling namespace after the Ignore decision is already durable; missing ownership for arbitrary ACTIVE Observe sources remains part of existing `BUG-OBSERVE-HANDOFF-01`, not a new root.

## History-date WorkManager handoff — no additional blocker

`HistoryDateFetchManager.startOrReconnect()` durably creates/reconnects an operation before enqueue and does not observe `enqueueUniqueWork()`'s Operation result. However this namespace has a startup recovery owner: `App.onCreate()` runs `HistoryDateFetchManager.reconcile()`, which enumerates nonterminal operations and re-enqueues exact `operationId` unique work.

`HistoryDateFetchWorker` loads only the exact operation from its input, checks terminal/cancel state, and calls `ensureRunning(operationId)` around each lookup/checkpoint boundary. This is materially different from Terminal/LocalAdd orphan intent, which has no startup owner.

Immediate same-process enqueue-failure handling can be hardened, but no new durable restart fixed point is confirmed. Existing F15/F16 date-semantic findings remain unchanged.

## F10 `BUG-CLEANUP-01` — P2 retained

`DownloadSettingsFragment` maps daily/weekly/monthly to a delayed `OneTimeWorkRequest<CleanUpLeftoverDownloads>` whose unique work name is the current timestamp. Changing cadence therefore creates another independent delayed work rather than replacing the prior cadence generation. Only the off/null case cancels all work by the cleanup tag.

`CleanUpLeftoverDownloads.doWork()` performs cleanup and returns success; it does not schedule a successor. Thus a configured recurring cadence can execute once and stop, while old and new one-shot generations can coexist after settings changes. No startup cadence reconciliation was found.

## F13 `BUG-METADATA-02` — P2 retained

`ResultRepository.getSingleMetadataFromUrl()` validates returned metadata with `ExtractorSourceIdentityPolicy.matchesRequestedSource()`. The production enrichment API `updateDownloadItem()`, however, does not call that validated helper. It directly uses `getSingleMetadataFromSource()` / `fetchSingleMetadataFromSource()` and passes the result to `applyMetadata()`.

`UpdateMultipleDownloadsDataWorker` calls `resultRepo.updateDownloadItem(item)` in production. A fresh result with a mismatched extractor source can therefore update title / author / playlist title / duration / website / thumb / mediaPublishedAt of the requested Download.

## `BUG-CACHE-ROOT-01` — P2 retained

The cache-path picker stores a new `cache_path` through `changePath()` without the active Download / Terminal guard that is used for destructive clear-cache actions. `FileUtil.getCachePath()` re-reads the current preference every call. The cache root is therefore mutable during a live generation rather than pinned to its execution identity.

This confirms the existing cache-root authority finding; no new count is added.

## F3 `BUG-OBSERVE-01` — additional HardSub consumer impact, same P0 root

`YTDLPUtil` data fetching uses `--ignore-errors`. The untyped `ResultRepository.getResultsFromSource()` API therefore cannot tell consumers whether an empty/partial result is authoritative or the product of extractor/entry failure.

`HardSubScanWorker` explicitly retries thrown lookup failures, but when the same failure is collapsed into a successful empty list it sees `availableSubtitles = empty`, concludes that the requested subtitle is absent, and calls `historyDao.updateHardSubScanState(id, removed = true, done = false)`.

`HistoryDao.getHardSubScanCandidates()` permanently excludes rows with `hardSubScanRemoved = 1`. `resetHardSubDoneForRescan()` only resets rows whose `hardSubDone = 1`, so this failed-as-empty observation is not equivalent to a normal retry.

This is another concrete consumer of the same missing AUTHORITATIVE/PARTIAL/FAILED source-result contract already represented by P0 F3. It must not be counted as a new root. F3 acceptance should cover all absence-sensitive consumers, not only Observe sync deletion.

## Candidate not promoted — cache deletion versus recovery debt

`AppCacheManager` can delete the configured Download temp tree, while publication journals themselves live under `context.filesDir/publication-recovery`. The current review did not establish that a cache-deletion action can delete the sole remaining durable recovery authority/data after all existing active/recovery fences have converged. No new finding/subcase is recorded from that candidate.

Count remains `P0 2 / P1 3 / P2 23`.

INDEPENDENT EXECUTION: NOT EXECUTED

# BUG-HARDSUB-GENERATION-01 — exact-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Implementation in-progress/newer diff used as evidence: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior root checkpoint: `review-runs/checkpoints/2026-09-10__c2294c87__hardsub-generation-backup-revalidation.md`

## Verdict

**NOT_CLEAN — existing P2 `BUG-HARDSUB-GENERATION-01` is reconfirmed OPEN at `aa1616a2...`.**

This is a revalidation of an already-counted semantic root, not a new blocker.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 3 / P1 3 / P2 25**
- CLEAN Review Basis remains: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact production authority chain

### 1. Repeated Scan Now creates a newer producer generation but does not fence an already-running consumer

`ProcessingSettingsFragment` permits each `hard_sub_scan_now` click to call `HardSubScanWorker.enqueueWithGeneration()` again. Its local `latestHardSubHandoffId` is used only to discard a late enqueue-result callback before showing a Toast. It is not a durable mutation lease for `HardSubScanWorker` and does not prevent an older worker from continuing semantic mutations.

### 2. The producer creates exact durable generation/request identity

`WorkManagerHandoffRecovery.prepareHardSub()` creates a fresh `WorkManagerHandoffCarrier` with:

- `kind = HARD_SUB_SCAN`;
- `generationId = handoffId`;
- an exact random `requestId`;
- fixed unique work name `hard_sub_scan_worker`.

The WorkRequest built for `HardSubScanWorker` carries both `handoffId` and `handoffRequestId` in input data.

Thus exact producer generation identity exists and is transported to the consumer.

### 3. Accepted HardSub carrier is removed before consumer completion

`WorkManagerHandoffRecovery.performAttempt()` marks a successful enqueue accepted and then deletes accepted carriers for every kind except `OBSERVE_RETRY_DOWNLOAD`. `reconcileCarrier()` applies the same policy to an already-accepted non-Observe carrier.

Therefore the HardSub handoff row is an enqueue-acceptance carrier, not a durable worker-lifetime current-generation authority.

### 4. HardSubScanWorker never consumes the transported generation

At exact `aa1616a2...`, `HardSubScanWorker.doWork()` never reads `handoffId` or `handoffRequestId` and never asks whether its scan generation is still current before:

- resetting hard-sub scan state;
- updating a History row's hard-sub scan state;
- selecting a History candidate for replacement;
- publishing a replacement Download row.

Consequently, a worker belonging to a superseded REPLACE generation can continue durable mutations after a newer Scan Now generation has been prepared/accepted.

This violates the checklist's generation/positive-live-authority rule: scheduler replacement/producer supersession is not consumer mutation authority unless the stale consumer is fenced at the correctness-relevant mutation boundary.

## Non-atomic replacement marker reservation

For a candidate with requested subtitles, `HardSubScanWorker` computes:

`HistoryRedownloadMarker.regular(item.id)`

and then performs:

1. `downloadDao.countPendingByPlaylistMarker(marker)`;
2. if zero, constructs a `DownloadItem`;
3. separately calls `downloadDao.insert(downloadItem)`.

The count and insert are not one atomic semantic reservation.

`DownloadItem`'s Room entity has indexes only for:

- `status`;
- `downloadStartTime`;
- `orderPosition`.

There is no unique index or equivalent DB-enforced uniqueness for the History-redownload marker in `playlistURL`.

Therefore two stale/current HardSub consumers can both observe zero pending rows and each insert a distinct Queued replacement for the same History target.

## Why later download admission does not collapse the duplicate semantic publication

The download admission/committed-replacement guard is tied to the exact Download row identity: a History replacement is recognized as committed when the History row's `downloadId` equals that Download's id. If two replacement Download rows were published for the same History marker, a successful commit by the first row does not make the second row the same committed Download generation.

Thus downstream execution serialization does not repair the earlier duplicate publication authority. The second distinct Download can remain a separate replacement attempt.

## Concrete incorrect impact

A repeated Scan Now can produce this sequence:

`generation A accepted / worker A running`
→ `user starts generation B`
→ producer-side A is superseded/replaced
→ worker A has no consumer-side generation check and continues
→ worker B also evaluates the same History candidate
→ A and B each observe marker count 0 before either insert commits
→ both insert separate Queued History-redownload Downloads
→ duplicate download/replacement work can execute for the same History target.

The stale worker can also mutate hard-sub scan state after supersession even when no duplicate Download is inserted.

## Root reconciliation

This remains the existing `BUG-HARDSUB-GENERATION-01` root established by the prior independent checkpoint.

It is distinct from:

- generic/ordinary `BUG-DOWNLOAD-HANDOFF-01`, which owns initial runnable queue → WorkManager acceptance/recovery;
- scheduler START/END generation fencing in `BUG-SCHEDULE-01`;
- `BUG-OBSERVE-HANDOFF-01`, which owns Observe worker generation/revocation.

The HardSub root owns the exact scan-generation consumer fence plus atomic publication/reservation of its History-redownload semantic target. Count delta is therefore zero.

## Required correction boundary

A coherent correction must provide both sides of the root:

1. **Worker-lifetime exact generation authority**
   - retain or otherwise provide a durable/queryable current HardSub generation for the semantic worker lifetime;
   - `HardSubScanWorker` must consume its exact generation/request identity;
   - superseded workers must fail/stop without mutating History scan state or publishing replacement Downloads;
   - revalidate current generation at the final mutation/publication boundary, not merely once at worker start.

2. **Atomic replacement semantic reservation**
   - reservation/publication for a regular History-redownload marker must be atomic under the History replacement semantic key;
   - a stale/current pair must not both pass a separate zero-count check and insert separate replacement Downloads;
   - use the smallest architecture-consistent transaction/unique durable identity/reservation mechanism that proves this invariant.

The fix must preserve retryable subtitle lookup behavior, foreground retry handling, exact History replacement authorization, and unrelated low-quality replacement semantics.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review. Source-level production wiring was reviewed at the exact fixed basis.

INDEPENDENT EXECUTION: NOT EXECUTED

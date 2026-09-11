# BUG-HARDSUB-GENERATION-01 — current-basis generation/reservation revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `d6e1c1940070efc0763d8c3e250c43204e223452` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- No moving implementation diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

`YTDLPUtil` / ResultRepository extraction code changed in the F3 range, so the HardSub subtitle-lookup consumer was checked for semantic interaction. The established blocker, however, is downstream worker-generation authority plus atomic History-redownload publication, and those boundaries remain unchanged.

## Verdict

**NOT_CLEAN — existing P2 `BUG-HARDSUB-GENERATION-01` remains OPEN at `9c5191c3...`.**

- Canonical blocker-count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## 1. Exact producer generation still exists only through enqueue acceptance

Current `WorkManagerHandoffRecovery.prepareHardSub()` still creates a durable carrier with:

- kind `HARD_SUB_SCAN`;
- generation id equal to the handoff id;
- exact random request id;
- fixed unique work name `hard_sub_scan_worker`.

The corresponding WorkRequest carries the exact handoff/request ids.

However current `performAttempt()` still deletes accepted carriers for every kind except `OBSERVE_RETRY_DOWNLOAD`. `reconcileCarrier()` applies the same non-Observe accepted-carrier deletion rule.

Therefore HardSub still has exact **enqueue** generation identity, but not a durable worker-lifetime current-generation authority once WorkManager accepts the request.

## 2. HardSubScanWorker still ignores the transported generation

Current `HardSubScanWorker.doWork()` still does not read or validate its handoff id / request id.

It therefore has no exact consumer-side current-generation predicate before:

- reset of History hard-sub scan state;
- `updateHardSubScanState(...)` mutation;
- replacement candidate publication;
- final downstream Download-worker start.

Repeated Scan Now / REPLACE can therefore supersede producer-side ownership while an already-running older worker continues semantic mutations.

## 3. Replacement semantic reservation remains non-atomic

For a subtitle-positive History candidate, the current worker still computes:

`HistoryRedownloadMarker.regular(item.id)`

then performs:

1. `downloadDao.countPendingByPlaylistMarker(marker)`;
2. if zero, creates a replacement Download;
3. separately calls `downloadDao.insert(downloadItem)`.

There is no transaction/unique reservation connecting the zero-count observation to publication.

The current Download entity still does not expose a unique database key for the History-redownload marker stored in `playlistURL`.

Thus stale/current workers can both observe zero and publish distinct replacement Download rows for one History target.

## 4. F3 extraction changes do not close generation or reservation authority

Current HardSub subtitle lookup calls `ResultRepository.getResultsFromSource(..., singleItem = true)` and uses the returned subtitle set to decide whether the History item needs a hard-sub replacement.

The F3 range strengthened source extraction authority for Observe/keyword membership semantics. Even if subtitle lookup is more conservative or differently routed, that does not establish either missing HardSub invariant:

- whether this scan worker generation is still current at mutation time;
- whether one semantic History-redownload target has exactly one durable replacement reservation.

A correct subtitle observation can still be consumed by a superseded scan worker, and two correct observations can still race through the non-atomic marker check/insert.

## Concrete failure sequence remains

`scan generation E1 accepted / running`
→ `user starts E2`
→ accepted E1 carrier is no longer a durable worker-lifetime authority`
→ E1 does not validate generation`
→ E1 and E2 inspect same History item`
→ both pass marker count == 0 before either insert commits`
→ both insert distinct Queued replacements`
→ duplicate replacement/download work can proceed for the same History target.

Even without duplicate publication, E1 can still write History scan-state after supersession.

## Root reconciliation

Keep this as existing canonical P2 `BUG-HARDSUB-GENERATION-01`, counted once.

It owns:

- consumer-side HardSub scan generation fencing;
- superseded-worker mutation prevention;
- atomic semantic reservation/publication for regular History-redownload marker identity.

Keep distinct from:

- P2 `BUG-DOWNLOAD-HANDOFF-01` ordinary runnable queue -> WorkManager acceptance/recovery;
- P2/P0 Observe generation roots;
- scheduler START/END generation fencing;
- subtitle availability/extraction correctness itself.

## Correction boundary remains

1. retain/provide durable current HardSub generation authority through semantic worker completion;
2. make `HardSubScanWorker` consume exact handoff/request generation and revalidate immediately before each correctness-relevant History mutation and replacement publication;
3. superseded workers exit without semantic side effects;
4. make History-redownload replacement reservation atomic by semantic marker identity;
5. preserve generation authority across process death rather than depending only on process-local latest-generation state;
6. preserve producer enqueue acceptance/retry behavior;
7. add overlapping E1/E2, pre-History-mutation, pre-Download-insert, simultaneous zero-count, process-restart, and no-duplicate-after-first-commit regressions.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED

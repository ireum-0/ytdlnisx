# BUG-OBSERVE-01 — completion review at fffb6b13

Date: 2026-09-11

## Review basis

- Previous independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Reported implementation final SHA: `fffb6b131a7b9cb35cf8a218b42863df9e332430`
- Independently verified implementation branch HEAD: `fffb6b131a7b9cb35cf8a218b42863df9e332430`
- Verified exact relation: `aa1616a2... -> fffb6b13...`, one direct child commit, no unreported intermediate commit
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Original independent finding checkpoint: `3f4ff817160c250af56435459b4a52fb2524f31e`

## Verdict

**NOT_CLEAN / P0 `BUG-OBSERVE-01` REMAINS OPEN / REVIEW-FIX REQUIRED**

The implementation correctly introduces explicit source-snapshot authority and closes the original destructive-absence path at the final mutation gate, but it over-applies `AUTHORITATIVE` to Observe run-lifecycle success. Because the current yt-dlp typed source path deliberately marks every successful extraction as `PARTIAL`, yt-dlp-backed Observe sources can become permanently stuck in first-run semantics and fail to process future uploads when `getOnlyNewUploads` is enabled.

Canonical blocker-count delta: **0**.
Canonical count remains **P0 3 / P1 3 / P2 25**.
The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.

## Correctly implemented parts

### Explicit authority carrier

`SourceSnapshot` now carries `AUTHORITATIVE`, `PARTIAL`, or `FAILED`. Destructive absence permission is explicitly limited to `AUTHORITATIVE`.

### NewPipe completeness tracking

The reviewed playlist/channel paths now downgrade authority when:

- per-item conversion is dropped;
- a continuation/page is incomplete;
- a later page fails after earlier items were accumulated;
- channel enumeration cannot positively establish an eligible enumerable tab.

A genuinely completed empty first page with no continuation remains capable of producing authoritative empty.

### yt-dlp fail-closed behavior

`getFromYTDLSnapshot()` recognizes that the current source request uses `--ignore-errors` and tolerant callback parsing. It therefore does not treat a successful plain list as complete membership authority.

### ResultRepository propagation

`getSourceSnapshotFromSource()` preserves the typed result across source routing. Failed NewPipe may fall back; a partial primary result is not silently upgraded to authoritative.

### Observe destructive boundary

`ObserveSourceWorker` now computes destructive missing-member candidates only when the snapshot is `AUTHORITATIVE`. `PARTIAL` and `FAILED` snapshots cannot enter History/file/reference deletion merely because prior members are absent from the returned list. Authoritative-empty remains able to enter legitimate absence reconciliation.

This fixes the concrete original chain:

`partial extraction -> plain successful list -> incomingLinks -> destructive absence`

at the destructive authority boundary.

## Remaining same-root correctness residual

### Authority is incorrectly reused as run-lifecycle completion authority

The new implementation sets:

`sourceIsAuthoritative = (snapshot.authority == AUTHORITATIVE)`

and then uses that value not only for destructive absence but also for `finishRunAndSchedule(..., countRun = ...)`.

For `getOnlyNewUploads && item.runCount == 0`, the PARTIAL path:

1. records currently returned items into `ignoredLinks`;
2. calls `finishRunAndSchedule(..., countRun = sourceIsAuthoritative)`;
3. because PARTIAL means `sourceIsAuthoritative == false`, `runCount` remains `0`.

The same worker checks `getOnlyNewUploads && item.runCount == 0` again on the next run.

### Why this becomes permanent for yt-dlp-backed sources

`YTDLPUtil.getFromYTDLSnapshot()` currently calls `SourceSnapshotAuthority.fromYtdlp(..., ignoredChildErrors = true)` for every successful typed yt-dlp extraction. Therefore successful yt-dlp source snapshots are always `PARTIAL` under the current request model, including runs with usable positive items.

Concrete production sequence:

`Observe source relies on yt-dlp`
-> successful usable yt-dlp result
-> snapshot = `PARTIAL`
-> `getOnlyNewUploads && runCount == 0`
-> returned items added to baseline ignore set
-> `countRun = false`
-> durable `runCount` stays `0`
-> next scheduled run again takes first-run baseline branch
-> newly appearing uploads are also added to ignore set instead of entering normal download filtering
-> source can remain in this loop indefinitely.

This is a real production regression from `aa1616a2...`: before this commit, a successful first scan called `finishRunAndSchedule()` with its default `countRun = true`, so first-run baseline initialization advanced `runCount` and later runs entered normal new-upload processing.

The same over-coupling also means `endsAfterCount` cannot advance on a source whose successful typed extraction remains PARTIAL indefinitely.

## Dependent automatic-keyword note

`AutomaticKeywordRuleSyncWorker` was also changed to refuse baseline/full-sync/discovery on `PARTIAL`. Since the current yt-dlp typed producer always reports `PARTIAL`, yt-dlp-only keyword sync can repeatedly retry and never apply usable positive results. `BUG-KEYWORD-01` remains a distinct existing P1 root; this observation is not counted again here. The review-fix must avoid claiming that dependent root closed.

## Root reconciliation

This residual is kept inside existing P0 `BUG-OBSERVE-01` for the current review-fix cycle rather than split into a new root. It is caused by the same newly introduced source-authority contract being applied too broadly and violates F3's preservation requirement for normal additions/run behavior while fixing destructive absence.

`BUG-OBSERVE-HANDOFF-01` remains separate: this review does not change generation/supersession/revocation ownership.

No new canonical root is counted.

## Stable review-fix boundary

The next fix must keep the destructive rule intact:

- only an `AUTHORITATIVE` source-membership snapshot may authorize destructive absence;
- `PARTIAL` / `FAILED` must never delete prior source members based on omission.

But source-membership authority must not be conflated with unrelated run-lifecycle semantics.

Required correction properties:

1. Separate **destructive absence authority** from **usable run / baseline / scheduling lifecycle** decisions.
2. Preserve safe positive-item processing from a PARTIAL snapshot where current production semantics permit it.
3. `getOnlyNewUploads` must not remain forever in `runCount == 0` merely because a usable extractor is conservatively non-authoritative for absence.
4. Do not solve this by simply declaring all yt-dlp output authoritative. With current `--ignore-errors` and tolerant parsing, that would reopen the original P0 destructive bug.
5. Either:
   - establish a truthful yt-dlp completeness signal for runs that can genuinely be authoritative (for example by changing the typed source-membership extraction contract so ignored child/parse errors are detected rather than unconditionally possible), or
   - model baseline/run lifecycle independently so a usable PARTIAL run can make safe forward progress without treating omitted items as absent.
6. If a partial first-run baseline cannot safely distinguish unseen old members from future uploads, make that ambiguity explicit; do not silently ignore every future item forever.
7. Preserve authoritative-empty destructive sync behavior.
8. Preserve normal positive additions, canonical URL filtering, retry-confirmation semantics, scheduling, and ends-after-count behavior.
9. Keep `BUG-KEYWORD-01` separate; if shared authority changes affect it, adapt conservatively and test its existing baseline/discovery state without claiming closure.
10. Keep `BUG-OBSERVE-HANDOFF-01` separate and unchanged.

## Required regressions

At minimum, add production-level coverage for:

- yt-dlp-backed usable PARTIAL snapshot + `getOnlyNewUploads=true` + `runCount=0` -> the source must not remain permanently trapped in first-run baseline semantics;
- a later genuinely new item after baseline handling -> can reach normal positive processing without granting destructive absence authority;
- PARTIAL snapshot still cannot produce destructive deletion candidates;
- authoritative snapshot still supports legitimate source removal and authoritative-empty sync;
- `endsAfterCount` / run lifecycle does not become permanently non-advancing solely because absence authority is conservative;
- dependent automatic-keyword adaptation does not silently make an otherwise usable source permanently inert.

The existing added instrumentation test only verifies the direct destructive-absence gate; it does not exercise this run-lifecycle composition.

## Preserved prior closures

The reviewed changed-file set does not modify the previously CLEAN History duplicate identity implementation files. No exact-source regression was found against that closure in this range.

Master Plan and authoritative ledger remain unmodified.

INDEPENDENT EXECUTION: NOT EXECUTED

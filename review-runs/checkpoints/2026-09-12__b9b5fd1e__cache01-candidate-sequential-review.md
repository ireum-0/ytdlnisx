# BUG-CACHE-01 overnight candidate — sequential independent review

Date: 2026-09-12

## Exact review basis

- Latest independently CLEAN canonical basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Candidate base: `36b43464b8106d90d672ba94718ccee58f38974f`
- Candidate SHA: `b9b5fd1ea636a528d92d1a0be577dab6d038d214`
- Candidate branch: `candidate/overnight-20260912-cache-live-owner`
- Governing root checkpoint: `cbb91dc8ae8e01642807ca0316567197245afd63`
- Queue task: 004 / P2 `BUG-CACHE-01`, merged semantic root with historical `BUG-CLEANUP-02`

Exact branch verification found the candidate branch still points to `b9b5fd1e...`, whose sole parent is exact queue base `36b43464...`. Comparing latest canonical CLEAN basis to the candidate yields merge base `36b43464...`; each side is one commit ahead, so the candidate remains isolated and unintegrated. The canonical branch remains at `9edd3e23...`.

## Verdict

**CANDIDATE CLEAN FOR ROOT / READY FOR SEPARATE REPLAY OR REIMPLEMENTATION.**

This verdict applies to the completed isolated candidate only. It does not close the canonical blocker because the candidate has not been integrated into the latest CLEAN canonical history.

Canonical count delta: `0`.

Canonical blocker count remains **P0 2 / P1 1 / P2 34**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Independent source review

The governing invariant is that destructive cache maintenance and positive live Download/Terminal execution ownership must share one ordering authority, and exact live-owned artifacts must not be deleted or moved at the filesystem mutation boundary.

### Shared authority window

The candidate introduces `CacheMaintenanceAuthority`, a process-local `Mutex` exposed as both `withExecutionAdmission` and `withMaintenanceWindow`. This is not used as a cosmetic wrapper: the real Download claim path and real Terminal admission path consume the execution-admission side, while destructive deletion and cache import consume the maintenance side.

### Download admission wiring

`claimDownloadThroughProductionAdmission(...)` enters `CacheMaintenanceAuthority.withExecutionAdmission` before its existing per-Download execution lease and claim CAS. The claimed row receives a fresh execution ID and `DownloadWorkerExecutionOwners.claim(...)` publishes the exact process-local owner before the admission window exits. Therefore maintenance cannot interleave between durable claim materialization and process-local owner publication.

Once admission releases the maintenance mutex, subsequent maintenance sees the exact execution owner. This closes the stale-negative sequence that previously allowed `zero active count -> new execution claim -> delete live staging`.

### Terminal admission wiring

`TerminalExecutionRegistry.admit(...)` also enters `CacheMaintenanceAuthority.withExecutionAdmission`. Inside that window it installs the provisional process-local active token, establishes the durable Terminal execution witness, checks native generation authority, and runs Terminal publication recovery admission before returning `ACQUIRED`. Therefore a maintenance window cannot begin after Terminal admission has crossed into a live execution while still missing its process-local owner token.

### Destructive delete wiring

`AppCacheManager.delete(...)` holds `withMaintenanceWindow` across target resolution, enumeration, live-owner revalidation, and each delete. For every entry it walks ancestors to the target root and checks Download live marker/root ownership and Terminal live-root ownership. A live-owned entry is skipped and contributes an incomplete result rather than being deleted.

This preserves abandoned-owner cleanup: provenance markers remain usable after the exact process-local execution owner has exited, so explicitly owned abandoned artifacts can again be deleted while unknown/unproven material retains the existing fail-closed handling.

### Cache import/move wiring

`MoveCacheFilesWorker` holds the maintenance window across `CacheImportPlanner.collect(...)` and the complete exact move loop. The planner excludes marker-owned Download or Terminal roots whose exact execution is currently live. Because new owner admission is blocked for the whole collect-and-move interval, the previous TOCTOU sequence `manifest built as abandoned -> same root becomes live -> artifact moved away from live owner` is closed.

### Latest CLEAN basis compatibility

The latest canonical CLEAN commit `9edd3e23...` is the F14 metadata-only publication correction. The candidate and canonical branch diverge directly from `36b43464...`, and the F14 changed-file set is disjoint from this cache-maintenance/admission patch. No evidence found that F14 changes the ownership/admission contracts reviewed here. Integration still must be performed separately and re-reviewed on the resulting cumulative canonical state.

## Test evidence handling

Luna reported:

- full JVM suite: 612/612 PASS;
- focused cache ownership suite: 29/29 PASS;
- `CacheMaintenanceProductionWiringTest`: 1/1 PASS;
- KSP/debug/androidTest compile: PASS;
- `git diff --check`: PASS;
- `DownloadWorkerCleanupProductionWiringTest`: 1 test timed out in untouched real-worker retry wiring.

The timeout is neither converted to PASS nor treated as an automatic semantic failure. Independent exact-source review of this candidate did not find the timeout to invalidate the BUG-CACHE-01 correction boundary, and the candidate does not modify that reported retry wiring. It remains test evidence to re-run during eventual canonical integration/review if practical.

## Integration consequence

Do **not** merge/cherry-pick/rebase/transplant automatically. The candidate is based on the older `36b43464...` queue basis. If selected for remediation, replay or reimplement the semantic change onto the then-current canonical implementation HEAD, resolve any interaction with later fixes explicitly, run focused production regressions, and independently re-review the cumulative canonical result before closing `BUG-CACHE-01` or advancing the CLEAN basis.

INDEPENDENT EXECUTION: NOT EXECUTED

# Independent correctness review checkpoint

Timestamp: 2026-09-12T06:25:00Z

## Frozen review basis

- implementation `checkpoint/pre-baseline-review`: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Master Plan `plan/remediation`: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review-governance bootstrap: `05d43e321403a564e194f2d55d77116d93587a87`
- ledger reference: `899328bc91e4008e39a658387396a0106c8666ec`
- governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` as present at frozen review bootstrap

The implementation SHA is frozen for this review run and will not be advanced if production moves later.

## Completed scope

- Fresh-fetched all four required branch heads.
- Read latest review-governance change: historical `BUG-DUPLICATE-01` closed at independently accepted current basis; canonical blocker count now `P0 2 / P1 1 / P2 32` before evaluating the new implementation.
- Identified current implementation delta as `BUG-CACHE-02`: reject provider-only cache staging roots before native/raw-filesystem consumers.
- Loaded v6 core invariants and mandatory execution order.

## Provisional findings/count

- Provisional canonical inventory: `P0 2 / P1 1 / P2 32`.
- `BUG-CACHE-02`: under active independent revalidation; no disposition change claimed yet.
- No new finding established at this checkpoint.

## Confirmed fixed invariants

None newly confirmed yet in this run.

## Open candidates/questions

1. Does every production cache-root consumer receive only a directly usable raw-filesystem root after the new resolver, including Download, Terminal, maintenance, ownership markers, migration, and cleanup?
2. Can legacy/provider-only `cache_path` values still leak through any direct preference reader or through `formatPath()` bypassing `getCachePath()`?
3. Does the settings selection rejection preserve an effective app-owned fallback without persisting an unusable provider identity?
4. Are removable-storage/raw paths rejected or accepted according to actual native/File authority rather than a lossy URI-to-path translation?
5. Required execution evidence for exact `9aebcb7e...` remains to be checked.

## Remaining review scope

- Trace `cache_path` creation/storage and every production reader.
- Trace Download and Terminal native staging request construction through exact filesystem use.
- Trace cache ownership/maintenance/migration/cleanup consumers.
- Inspect tests and exact-SHA execution evidence.
- Compare provider/raw path assumptions to exact Android SAF/filesystem semantics.
- Recount current canonical findings and write a final checkpoint immediately before verdict.

## Exact upstream semantic basis used so far

- Android SAF distinction carried forward as a required semantic premise: a persisted `content://`/DocumentsProvider grant is provider authority and is not by itself proof of native raw-filesystem authority.
- Kotlin/Room/WorkManager semantics have not yet been relied on for a closure claim in this run.

INDEPENDENT EXECUTION: NOT EXECUTED

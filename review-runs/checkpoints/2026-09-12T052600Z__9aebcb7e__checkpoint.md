# Independent correctness review checkpoint

- Checkpoint time: 2026-09-12T05:26:00Z
- Frozen implementation SHA: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Frozen plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review-governance bootstrap SHA: `a2114f4bde7c1ebe828497ea5e6aa08c6836ded0`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Review scope completed

- Fresh-fetched all four required branches and froze exact SHAs.
- Read current review-governance delta for `9aebcb7e...`.
- Began full-path review of `BUG-CACHE-02` and reopened `BUG-CACHE-01` rather than relying on the implementation diff.
- Confirmed `FileUtil.getCachePath()` now rejects `content://` as direct native cache authority and resolves non-app-owned raw roots by current filesystem writability, with fallback to app-owned default.

## Provisional findings/count

- Provisional canonical gate inherited from frozen current governance pending independent confirmation: `NOT_CLEAN`, P0 2 / P1 1 / P2 33.
- `BUG-CACHE-02`: OPEN candidate; current question is whether effective cache-root re-resolution after execution/recovery admission can switch namespaces.
- `BUG-CACHE-01`: REOPENED candidate; current question is whether Download positive-owner publication and filesystem owner-marker publication are ordered under the same cache-maintenance authority.
- No new independent P0/P1/P2 finding yet.

## Confirmed fixed invariants so far

- Static provider-only `content://` cache values are no longer accepted as native/raw cache roots by `getCachePath()`.
- Provider grant alone is not treated by the cache resolver as direct raw filesystem write authority.

## Open candidates/questions

1. Terminal: admission/recovery root versus later plan/staging root if `getCachePath()` changes result during one execution.
2. Download: whether a live execution can exist after admission but before its exact filesystem ownership marker is established, allowing maintenance to act on a stale-negative marker decision.
3. Whether final destructive mutation boundaries revalidate against sufficient positive authority for the exact current generation.
4. Exact execution evidence at the frozen SHA.

## Remaining review scope

- TerminalDownloadWorker, TerminalExecutionRegistry, TerminalPublicationRecovery, TerminalCommandPlanFactory, Terminal cache ownership.
- DownloadSchedulerAdmission, DownloadWorker/DownloadAttemptRunner, DownloadCacheOwnership.
- AppCacheManager and MoveCacheFilesWorker destructive mutation boundaries.
- Master Plan CACHE invariants and v6 authority/revalidation requirements.
- Exact upstream semantic basis and execution evidence.

## Exact upstream semantic basis used so far

- Android SAF/provider authority is not assumed to imply raw/native filesystem authority.
- Filesystem writability probes are treated as time-varying observations, not durable execution-scoped authority unless frozen/carried by production state.

This checkpoint is append-only review evidence. It does not modify `checkpoint/pre-baseline-review` or application source.

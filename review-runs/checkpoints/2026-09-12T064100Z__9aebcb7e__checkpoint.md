# Independent correctness review checkpoint

Timestamp: 2026-09-12T06:41:00Z

## Frozen review basis

- implementation `checkpoint/pre-baseline-review`: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Master Plan `plan/remediation`: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review-governance bootstrap: `05d43e321403a564e194f2d55d77116d93587a87`
- ledger reference: `899328bc91e4008e39a658387396a0106c8666ec`
- v6 checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

Implementation SHA remains frozen for this review.

## Completed scope

- Reconstructed cache selection/persistence through `FolderSettingsFragment`.
- Reconstructed `FileUtil.getCachePath()` and `resolveRawCachePath()` including legacy preference fallback.
- Reconstructed Download staging consumer (`DownloadWorker` -> `DownloadAttemptRunner.rawTempFileDir`).
- Reconstructed Terminal staging consumer (`TerminalCommandPlanFactory` -> `<cache>/TERMINAL/<taskId>`).
- Reconstructed cache deletion and migration consumers (`AppCacheManager`, `MoveCacheFilesWorker`) and confirmed they also derive roots through `FileUtil.getCachePath()`.
- Checked exact-SHA GitHub execution surfaces: no commit status contexts, no check-runs, no Actions run for `9aebcb7e...`.
- Compared the source contract to Android SAF/app-specific storage semantics.

## Provisional findings/count

- Canonical governance count before this implementation revalidation: `P0 2 / P1 1 / P2 32` after the frozen review branch closed historical `BUG-DUPLICATE-01` (`P2 -1`).
- `BUG-CACHE-02`: source-semantic defect appears FIXED at `9aebcb7e...`, but required exact-SHA production-path execution evidence remains NOT_VERIFIED, so canonical disposition remains `P2 OPEN` provisionally.
- No new P0/P1/P2 finding established.

## Confirmed fixed invariants

Provisional source-level closure for the original `BUG-CACHE-02` authority-loss path:

1. `content://` cache selections are rejected before becoming the persisted `cache_path`.
2. Legacy/provider-only `cache_path` values resolve to null and fall back to an app-owned filesystem cache root rather than being converted into a raw-looking `/storage/...` path.
3. Download and Terminal native staging obtain the effective cache root from the same resolver.
4. Cache maintenance/migration likewise consumes the effective resolver; current maintenance authority/live-owner protections remain in the path.
5. A non-app-owned raw path is accepted only through direct filesystem writability evidence; a persisted SAF permission is intentionally not treated as raw-path authority.

## Open candidates/questions

- No surviving source-level P0/P1/P2 candidate identified in the BUG-CACHE-02 correction domain.
- Exact-SHA execution remains the material closure gap. The added instrumentation source is not execution evidence.
- Independent reviewer has not run Gradle/device instrumentation in this environment; label remains `INDEPENDENT EXECUTION: NOT EXECUTED`.

## Remaining review scope

- Final consumer/effect recount against the frozen implementation SHA.
- Final branch-head recount to prove the frozen implementation/Plan/ledger references did not move during the review target.
- Write final checkpoint before verdict.

## Exact upstream semantic basis

- Android `ACTION_OPEN_DOCUMENT_TREE` returns a directory tree represented through `DocumentsProvider`; descendants are accessed using tree/document URIs and `DocumentsContract`, and persistable permissions preserve URI/provider access. This does not constitute a contract for an equivalent native raw filesystem pathname.
- Android app-specific directories returned through `getExternalFilesDir()` / app cache directories are directly app-accessible without storage permission on modern supported Android versions; they are suitable raw `File` roots while available.
- v6 invariant 11/18 consumer closure applies because BUG-CACHE-02 changes the cache-root authority predicate; the review therefore followed the new predicate through Download, Terminal, maintenance, and migration effects rather than reviewing only `FileUtil`.

INDEPENDENT EXECUTION: NOT EXECUTED

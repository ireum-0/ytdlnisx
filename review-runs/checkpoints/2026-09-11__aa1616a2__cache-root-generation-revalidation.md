# BUG-CACHE-ROOT-01 — cache-root generation authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-CACHE-ROOT-01`

The exact CLEAN basis still treats the mutable `cache_path` preference as if it were a stable execution-generation namespace. Download and Terminal generations can capture artifacts under one root and later re-read a different root for ownership, cleanup, retry, publication recovery, or reconciliation after the user changes the preference.

## Mutable preference remains the cache-root source of truth

Exact `FileUtil.getCachePath(context)@aa1616a2...` reads `cache_path` from `PreferenceManager` on each invocation and returns that current preference-derived path.

Exact `FolderSettingsFragment@aa1616a2...` lets the cache directory picker write the new value directly through:

`CACHE_PATH_CODE -> editor.putString("cache_path", path)`

followed by `editor.apply()`.

The cache-path change itself does not call the screen's `hasActiveDownloads()` gate. That gate is used for other destructive cache operations, but changing the root can occur while a Download or Terminal execution/recovery generation is live.

## Download generation still spans multiple current-root reads

Exact `DownloadWorker.kt@aa1616a2...` demonstrates the same generation using cache-root authority at multiple different times:

- admission creates `DownloadAttemptRunner` with `rawTempFileDir = File(FileUtil.getCachePath(context), downloadItem.id.toString())`;
- later artifact ownership uses `DownloadCacheOwnership.recordArtifacts(cacheRoot = File(FileUtil.getCachePath(context)), ...)`;
- recovered-execution retirement and artifact-manifest removal likewise re-read the current root;
- cache deletion uses `DownloadCacheOwnership.deleteIfOwned(cacheRoot = File(FileUtil.getCachePath(context)), ...)`;
- retry reset calls `resetYtdlpTempDirectoryUnsafe(rawTempDirectory, ...)`, which computes a fresh `cacheRoot = File(FileUtil.getCachePath(context)).canonicalFile` and rejects the captured temp directory when `tempDirectory.parentFile != cacheRoot`.

Concrete same-process sequence remains:

1. execution E1 captures temp root `C1/<downloadId>`;
2. user changes `cache_path` to C2 while E1 is alive;
3. later E1 ownership/cleanup/retry logic re-resolves C2;
4. output/marker state remains under C1 while current-root operations target C2;
5. retry reset can fail E1 solely because its valid captured directory no longer has the current preference root as parent.

This is not only a cleanup inconvenience: ownership/provenance and recovery authority can be split across namespaces for one exact execution generation.

## Terminal generation also re-resolves the mutable root

Exact `TerminalDownloadWorker.kt@aa1616a2...` similarly reads the current cache root at distinct phases:

- `TerminalExecutionRegistry.admit(..., cacheRoot = File(FileUtil.getCachePath(context)), ...)`;
- the Terminal command plan is then constructed;
- app-cache output directory is later created from a fresh `FileUtil.getCachePath(context)` read under `TERMINAL/<terminalTaskToken>`;
- `reconcileTerminalPublicationRecovery()` later calls `TerminalPublicationRecovery.reconcile(..., cacheRoot = File(FileUtil.getCachePath(context)), ...)`.

A cache preference change between those boundaries can therefore make admission/recovery reason about a different namespace from the directory holding the live execution's exact marker/artifact manifest.

## Existing quiescence checks do not close the root

The settings screen has active Download/Terminal checks for clear-cache and some migration operations, but not for committing a new `cache_path` value. Conversely the workers do not pin the chosen root in an immutable execution-generation carrier and consistently reuse it later.

Thus neither accepted remedy from the original finding is present:

- root changes are not refused/migrated until all live and recoverable old-root generations are quiescent; and
- exact Download/Terminal generations do not own a pinned root used through staging, ownership, retry, cleanup, publication recovery, and restart discovery.

## Required correction boundary carried forward

1. Cache-root identity must be generation-scoped for every correctness-relevant Download and Terminal execution/recovery carrier.
2. Prefer persisting or otherwise durably tying the exact canonical root to the execution generation before external/native work begins, and use that exact root for staging, ownership markers/manifests, retry reset, cleanup, publication finalization, and recovery.
3. Alternatively, changing the preference must be refused/deferred/migrated until all live workers, native processes, publication journals, producer records, ownership markers, and other durable recovery debt under the old root are provably quiescent. An active-row count by itself is not sufficient.
4. Startup recovery must discover old-root debt after a preference change; it cannot rely solely on the new global preference value.
5. Do not silently treat the current preference as proof that an earlier generation's directory is unsafe or unrelated.
6. Add production-path tests that mutate `cache_path` between Download admission/temp creation and artifact recording/retry cleanup, between Terminal admission/output creation/recovery, and across process restart with durable old-root debt.

## Root/count and basis reconciliation

- `BUG-CACHE-ROOT-01` was already counted as one P2 root.
- This exact-basis review reconfirms it; no new root is introduced.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED

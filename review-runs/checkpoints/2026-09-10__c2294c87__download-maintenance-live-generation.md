# Independent Track A checkpoint — Download maintenance vs live generation

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Canonical working recount

- P0: 2
- P1: 3
- P2: 21

No new root is counted here. This checkpoint strengthens the already-counted `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` root with cache-maintenance subcases D2/D3.

## BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — D2 live DOWNLOAD_TEMP deletion race

`CleanUpLeftoverDownloads` reads `getActiveDownloadsCount()` and, if the snapshot is zero, calls `AppCacheManager.delete(setOf(DOWNLOAD_TEMP))`.

`AppCacheManager` maps `DOWNLOAD_TEMP` to the configured app download cache root and recursively enumerates/deletes every entry below that root except nested category exclusions such as Terminal and Logs. It does not validate per-Download operation/execution ownership or acquire the Download admission/lease used by active workers.

Concrete race:

1. cleanup observes active count = 0;
2. a queued/new Download claims execution and creates its exact Download ownership marker/staging root;
3. cleanup enters `AppCacheManager.delete(DOWNLOAD_TEMP)` using the earlier zero-count decision;
4. the current execution's marker, artifact manifest, and staging files are eligible for recursive deletion.

The same TOCTOU exists in the manual cache-clear path. `FolderSettingsFragment` checks active Download/Terminal counts immediately before deletion, but the check and recursive filesystem mutation are not one shared admission/maintenance lease. A new execution can start after the check.

### Recovery-carrier scope

`PublicationRecoveryJournal` itself is stored under `context.filesDir/publication-recovery`, and `DownloadExecutionRecovery` uses app-private SharedPreferences. The `DOWNLOAD_TEMP` recursive delete therefore does not erase these durable carriers directly.

That does not make D2 safe: surviving recovery state may still point at a source/staging generation that maintenance has deleted, and the current worker loses the ownership marker/artifacts required for normal publication and exact cache handling.

## BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — D3 cache import mutates live owned generations

`FolderSettingsFragment` starts `MoveCacheFilesWorker` from the user-visible Move Cache action without the active-download/terminal gate used by Clear Cache.

`MoveCacheFilesWorker` calls `CacheImportPlanner.collect(cacheRoot)` and physically moves each selected source to `Downloads/YTDLnisx/CACHE_IMPORT`.

`CacheImportPlanner.collect()` is provenance-safe with respect to ambient files: it enumerates only `DownloadCacheOwnership.listOwnedRoots()` and `TerminalCacheOwnership.listOwnedRoots()`, then only exact artifact-manifest files. This means F2 `BUG-OUTPUT-01` remains CLOSED.

However the ownership proof has no liveness dimension. `DownloadCacheOwnership.listOwnedRoots()` validates marker schema/downloadId/operationId/executionId and directory identity, but it does not ask whether that exact generation is currently Active/native-owned/publication-pending. A valid marker for a live execution is therefore a valid cache-import source.

Concrete sequence:

1. Download E is Active and has an exact valid ownership marker plus manifest/artifacts;
2. user invokes Move Cache;
3. planner accepts E because its ownership proof is valid;
4. `MoveCacheFilesWorker.moveExact()` moves E's current artifact out of its staging root while E still owns/uses it.

This is the same root as D2: maintenance authority is derived from a snapshot/ownership proof that establishes identity but not current mutability/liveness.

## Acceptance additions

- Introduce one shared cache-maintenance exclusion/admission protocol with Download/Terminal execution ownership.
- A maintenance action may mutate an owned root only after proving that exact `(subject, operationId, executionId)` is terminal/non-live and no publication/recovery owner still needs the source.
- `activeCount == 0` alone is not sufficient; the proof and mutation must be protected against new admission.
- Global recursive `DOWNLOAD_TEMP` deletion must not bypass per-generation ownership/liveness checks.
- Cache import must distinguish `owned` from `retired/importable`; valid live ownership is a reason to skip, not a reason to move.
- Tests: zero-count -> concurrent admission -> delete; manual clear -> concurrent admission; Move Cache while active; publication/recovery pending roots; terminal/quarantined roots; unknown legacy content remains untouched.

## Review Basis

No Review Basis advancement. It remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

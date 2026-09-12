# BUG-CACHE-02 canonical cumulative review — NOT_CLEAN

Date: 2026-09-12

## Exact review basis

- Prior recorded CLEAN basis entering Task 005: `6cb93d12298427dbca2a596f5b6a6a6e82e41997`
- Completed implementation HEAD: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Exact parent: `6cb93d12298427dbca2a596f5b6a6a6e82e41997`
- Exact range: `6cb93d12298427dbca2a596f5b6a6a6e82e41997..9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Range topology: exactly one commit ahead / zero behind, merge base exact prior recorded basis
- Governing isolated candidate review: `review-runs/checkpoints/2026-09-12__53a3c440__cache02-candidate-sequential-review.md`
- Candidate SHA: `53a3c44037a4a4b08a3ab5f60b5ad768b6d1b39b`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger: reference-only `899328bc91e4008e39a658387396a0106c8666ec`

The implementation branch remote HEAD was independently re-verified at exact `9aebcb7e...` after the completion report. The commit is the exact direct child of `6cb93d12...` and changes only:

1. `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`
2. `app/src/main/java/com/ireum/ytdl/ui/more/settings/FolderSettingsFragment.kt`
3. `app/src/androidTest/java/com/ireum/ytdl/util/CacheStorageAuthorityProductionWiringTest.kt`

## Verdict

**NOT_CLEAN.**

The static provider-only authority bug is materially improved: persisted `content://` values no longer pass through `formatPath(...)` into native raw-filesystem staging, unsupported SAF selections are rejected before persistence in the settings path, and static unsupported raw paths fall back to the app-owned default.

However the completed current source still violates the cache-root authority contract across a live execution, and the cumulative review also disproves one premise of the immediately preceding `BUG-CACHE-01` closure.

Canonical consequences:

- `BUG-CACHE-02`: **OPEN / residual remains**; no P2 decrement.
- `BUG-CACHE-01`: **REOPENED** because its live-owner/maintenance invariant is not actually satisfied by the current production composition.
- Canonical blocker count delta from the previously recorded state: `P2 +1`.
- Resulting blocker count: **P0 2 / P1 1 / P2 33**.
- The contiguous CLEAN basis cannot remain `6cb93d12...`; the last still-proven basis before the reopened CACHE-01 implementation is `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

Overall remediation state remains `NOT_CLEAN`.

## BUG-CACHE-02 residual: effective cache root is re-resolved after execution authority is acquired

### Static correction that is sound

At `9aebcb7e...`, `FileUtil.getCachePath(context)` now:

- returns the app-owned default for blank configuration;
- rejects `content://` before any `formatPath(...)` conversion;
- unwraps `file://` only through the URI path;
- accepts app-owned filesystem roots;
- accepts a non-app-owned raw filesystem path only when its current path/parent supplies direct writable-directory evidence;
- otherwise falls back to the app-owned default.

`FolderSettingsFragment.changePath(...)` also calls `isSupportedCachePathSelection(...)` before persisting a cache-path selection, so ordinary SAF/provider selections are refused instead of stored as native staging authority.

Those changes close the original static `content:// -> formatted raw path` failure sequence.

### Current production failure sequence

The resolver is nevertheless a **time-varying resolver**, not an execution-scoped authority. A non-app-owned raw path is accepted only while its current `File` probe is writable; every later call may return a different effective root.

Terminal production observes that resolver multiple times for one execution:

1. `TerminalDownloadWorker` calls `TerminalExecutionRegistry.admit(...)` with `cacheRoot = File(FileUtil.getCachePath(context))`.
2. `TerminalExecutionRegistry.admit(...)` holds `CacheMaintenanceAuthority.withExecutionAdmission`, establishes the process/durable execution witnesses, and invokes `TerminalPublicationRecovery.admit(...)` against exactly that supplied cache root.
3. After `Admission.ACQUIRED` returns and the shared admission window is gone, `TerminalCommandPlanFactory.create(...)` calls `FileUtil.getCachePath(context)` again to build `TERMINAL/<taskToken>`.
4. The worker later calls `FileUtil.getCachePath(context)` yet again when it creates the actual Terminal output directory and owner marker.

`TerminalPublicationRecovery.admit(...)` discovers marker-revoked Terminal recovery roots under the **supplied cache root**. Therefore the root whose recovery namespace was admitted must be the same root later used for this native execution.

A concrete supported-state transition remains possible:

`configured external raw root R is writable`
→ `Terminal admission/recovery checks R and returns ACQUIRED`
→ `R becomes unavailable/non-writable before later getCachePath() call`
→ `getCachePath()` silently falls back to app default D`
→ `Terminal plan/staging/owner marker are created under D`
→ `marker-revoked recovery carrier under D for the same Terminal subject was never part of the R admission decision`
→ `native execution may proceed in a recovery namespace that was not admitted`.

The same semantic problem can occur when any mutable preference producer changes the effective cache value between those observations. The static settings rejection narrows one producer but does not make `getCachePath()` execution-scoped, and legacy/raw configured paths remain deliberately supported.

This violates the governing CACHE-02 requirement that unsupported/revoked values fail closed or fall back **before ownership claim/native staging**. Falling back after exact Terminal execution/recovery authority was already acquired is not the same authority decision.

### Required correction shape

Resolve one canonical effective raw-filesystem cache root at the execution-admission boundary and carry that exact root through all cache ownership, recovery, plan, staging, provenance, and cleanup operations for that execution. Do not re-resolve a mutable preference/writability predicate into a different root after execution authority has been acquired.

If the bound root becomes unusable before native work, fail/recover against that exact bound root or perform a separately serialized re-admission of the replacement root. Do not silently switch namespaces after admission.

A focused production test must force the resolver outcome to change between Terminal admission and staging and prove that native execution cannot proceed against an unadmitted replacement root/recovery namespace.

## Preserved-closure failure: BUG-CACHE-01 must be reopened

The prior `6cb93d12...` closure correctly observed that Download claim publication occurs inside `CacheMaintenanceAuthority.withExecutionAdmission`, but it treated process-local owner publication as sufficient live-cache visibility. The actual destructive liveness predicate requires more.

### Exact current composition

`claimDownloadThroughProductionAdmission(...)` publishes a fresh exact `(downloadId, executionId)` into `DownloadWorkerExecutionOwners` before leaving the shared admission window.

But `AppCacheManager.isLiveOwnedEntry(...)` recognizes a Download cache entry as live only through `DownloadCacheOwnership.isLiveOwnedMarker(...)` / `isLiveOwnedRoot(...)`. Those functions first require a valid filesystem owner marker and only then compare the marker execution ID with `DownloadWorkerExecutionOwners`.

The current Download worker does **not** publish that marker inside the shared admission window. After claim returns, it constructs `DownloadAttemptRunner` with a cache path, and only later the initial `resetYtdlpOutputDirectory(..., beforeRetry = false)` reaches `DownloadCacheOwnership.prepareAttempt(...)`, which calls `ensureMarker(...)` before native execution.

`prepareAttempt(...)` is outside `CacheMaintenanceAuthority.withExecutionAdmission`.

### Concrete stale-negative mutation race

A reachable marker-rotation case is enough to violate the closed invariant:

1. a prior abandoned execution has left an empty/cleanable Download carrier and stale owner marker for Download ID `N`;
2. E2 successfully claims Download `N` and publishes its exact process-local owner inside `withExecutionAdmission`, then releases the shared cache authority;
3. maintenance acquires `withMaintenanceWindow`, enumerates the stale marker, and its live-owner revalidation reads the stale marker execution ID, so `isLiveOwnedMarker(...)` returns false even though E2 is already the positive process-local owner;
4. before maintenance performs `File.delete()` on that already-approved marker entry, E2 runs `prepareAttempt(...)` outside the shared authority, rotates the stale marker, and writes E2's current exact marker at the same path;
5. maintenance resumes and deletes that same path using the stale negative decision.

The mutation boundary therefore still permits a current live execution's exact owner marker to be removed after a stale-negative liveness check. The shared mutex does not order marker publication against the delete because marker publication is outside the mutex.

This directly contradicts the governing `BUG-CACHE-01` invariant:

> destructive cache maintenance and positive live Download/Terminal execution admission share one ordering authority; exact live-owned roots/artifacts must not be deleted or moved at the filesystem mutation boundary.

The earlier closure statement that no current-generation cache interval existed without sufficient ownership proof is not supported by the current production composition and is superseded by this fresh exact-source review.

### Required correction shape

Do not repair this by weakening identity to a coarse database `Active` status.

The correction must ensure that, once the Download process-local execution owner has been positively published under the shared cache authority, maintenance/import cannot destructively classify the same Download subject from a stale marker generation and then mutate a marker/root that E2 replaces outside the authority window.

Acceptable designs include a conservative subject-level live fence derived from the exact process-local execution owner (so stale marker/material for that Download ID is skipped while any exact live execution owns the subject), or moving the necessary filesystem ownership publication into a correctly recoverable shared admission protocol. The chosen design must preserve exact execution identity for semantic ownership and avoid creating a DB-claimed-but-unrecoverable state if marker publication fails.

The same rule must be applied to cache import/move discovery, not only `AppCacheManager.delete(...)`, because import is also a filesystem mutation boundary.

## Semantic-contract / consumer closure

The Task 005 report listed many `getCachePath()` consumers, and no direct `content:// -> formatPath()` native staging bypass was found in the reviewed changed scope. That is necessary but not sufficient: changing `getCachePath()` from a stable formatting lookup into a current-writability-dependent fallback resolver is a semantic-contract delta. Consumers that call it more than once during one operation must be audited for **same-operation root stability**, not only for direct bypass spelling.

At minimum the correction review must cover:

- Terminal admission/recovery -> command plan -> staging root -> ownership marker -> publication recovery;
- Download claim -> attempt runner -> `prepareAttempt`/marker -> native output -> artifact manifest -> cleanup/recovery;
- `AppCacheManager` destructive enumeration/revalidation/delete;
- `CacheImportPlanner.collect(...)` + `MoveCacheFilesWorker` exact move loop;
- startup/retry recovery paths that discover cache-root-scoped carriers;
- settings/restore or other preference producers that can change `cache_path`.

## Test coverage assessment

The new `CacheStorageAuthorityProductionWiringTest` provides useful static production evidence for:

- provider-only primary/non-primary SAF fallback;
- unsupported raw-path fallback;
- app-owned filesystem-path acceptance.

It does not exercise an execution in which the effective root changes between admission and staging, nor the stale-marker replacement race between Download process-owner publication and maintenance mutation.

## Implementation-agent verification evidence

Treated as implementation evidence, not independent execution:

- focused JVM cache/ownership suites: 20/20 reported PASS;
- cache storage instrumentation: 3/3 reported PASS;
- cache maintenance instrumentation: 1/1 reported PASS;
- full JVM suite: 612/612 reported PASS;
- KSP: reported PASS;
- debug Kotlin compile: reported PASS;
- Android-test Kotlin compile: reported PASS;
- `git diff --check`: reported PASS;
- instrumentation device: Medium_Phone_API_36.1(AVD), API 36, x86_64;
- Room migration: reported NOT REQUIRED.

These results do not cover the two exact interleavings above and do not override the source-level blockers.

## Next action

Keep the implementation branch at exact `9aebcb7e2eb3c87813d49ea6c68b35d365f41738` as the review-fix starting point unless the remote branch advances. Produce one focused additive review-fix that:

1. binds one effective raw cache root to each Download/Terminal execution before cache-scoped authority is acquired;
2. prevents silent root switching after admission without re-admission;
3. closes the Download process-owner -> filesystem-marker stale-negative maintenance/import gap;
4. preserves CACHE-01 abandoned-owner cleanup, fail-closed unknown material, exact artifact identity, lock ordering, and CACHE-02 provider-only rejection/fallback semantics;
5. adds deterministic race/root-switch production coverage.

No Room migration is implied by this review finding.

INDEPENDENT EXECUTION: NOT EXECUTED
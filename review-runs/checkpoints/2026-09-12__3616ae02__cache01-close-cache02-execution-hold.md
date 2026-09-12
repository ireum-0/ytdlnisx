# Independent cumulative cache review — CACHE-01 closure / CACHE-02 execution-evidence hold

Date: 2026-09-12

## Exact review scope

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Last still-proven contiguous CLEAN basis entering review: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- First cache implementation: `6cb93d12298427dbca2a596f5b6a6a6e82e41997`
- CACHE-02 implementation: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Review-fix final SHA: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Verified final parent: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Verified remote implementation HEAD: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Verified cumulative topology: `93d01d2a...` is merge base, final is 3 commits ahead / 0 behind.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger: reference-only `899328bc91e4008e39a658387396a0106c8666ec`

This review re-reviewed the full cumulative cache scope from the still-proven CLEAN basis through the review-fix HEAD. It does not review only the last commit.

## Verdict

**PARTIAL CLOSURE / OVERALL NOT_CLEAN**

- P2 `BUG-CACHE-01`: **CLOSED** at `3616ae02...`.
- P2 `BUG-CACHE-02`: **SOURCE-SEMANTIC FIXED, EXECUTION EVIDENCE INCOMPLETE / REMAINS OPEN**.

Canonical count delta: **P2 -1**.

Resulting canonical blocker count: **P0 2 / P1 1 / P2 31**.

Overall remediation state remains `NOT_CLEAN`.

The contiguous independently CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` because the cumulative cache implementation scope still contains the open CACHE-02 execution-evidence gate. Neither `6cb93d12...`, `9aebcb7e...`, nor `3616ae02...` is promoted to CLEAN basis by this checkpoint.

## BUG-CACHE-01 — CLOSED

The reopened claim-to-marker race is closed by the final production composition.

### Admission and stale-marker interval

`claimDownloadThroughProductionAdmission(...)` runs inside `CacheMaintenanceAuthority.withExecutionAdmission`. It resolves the execution cache root under that shared window, acquires the per-Download side-effect lease, revalidates under the Download global execution lock, performs the durable claim/CAS, and publishes the exact `(downloadId, executionId)` process-local owner before the shared cache window is released.

`DownloadCacheOwnership.isLiveOwnedRoot(...)` and `isLiveOwnedMarker(...)` now use `DownloadWorkerExecutionOwners.hasLiveOwner(downloadId)` as a conservative subject-level liveness fence. Therefore an E1 marker cannot provide a stale negative while a fresh E2 process owner exists but E2 has not yet rotated the filesystem marker. The old marker does not become authority for E2; it is only prevented from authorizing destructive mutation during the live crossover interval.

After the exact process owner is released, the conservative fence disappears and the marker/manifest provenance rules again determine whether abandoned material is cleanable/importable.

### Destructive boundary closure

`AppCacheManager.delete(...)` holds `CacheMaintenanceAuthority.withMaintenanceWindow` across target resolution, enumeration, live-owner revalidation, and each actual delete effect.

`MoveCacheFilesWorker` holds the same maintenance window across `CacheImportPlanner.collect(...)` and the entire exact move loop. `CacheImportPlanner.collect(...)` skips live Download roots through the same subject-level liveness fence and skips live Terminal roots through exact Terminal token liveness.

A new execution cannot publish its owner while either destructive interval holds the shared maintenance authority, so the previous collect/check -> new owner -> delete/move race is not reachable.

### Lock-order review

The final relevant order is one-way:

- `CacheMaintenanceAuthority -> Download per-subject side-effect lease -> Download global execution lock -> durable claim/CAS -> process owner publication`
- `CacheMaintenanceAuthority -> TerminalExecutionRegistry mutex -> durable witness/native-liveness/publication-recovery admission`

Maintenance-side liveness checks do not acquire the Download global mutex or TerminalExecutionRegistry mutex. Download ownership lookup is a process-local concurrent map; Terminal liveness uses `TerminalExecutionRegistry.isActiveNow(...)`, which reads only the separate synchronized active-token map. No blocker-level AB/BA cycle was identified in the reviewed cache authority graph.

### Actual execution evidence supplied with the completion report

The implementation completion report records actual execution of `CacheMaintenanceProductionWiringTest`: **2/2 test cases passed** on the emulator before the Gradle task later failed during post-run `aapt.exe` cleanup with Windows access denied.

The exact final test source includes the production claim/CAS stale-marker test: E1 stale marker -> E2 claim and process-owner publication -> production delete/import check before marker rotation -> E2 marker rotation -> production delete/import check after rotation -> owner release -> abandoned artifact becomes importable/cleanable.

The Gradle task itself is not classified PASS because it failed after execution, but the two test cases did execute and pass. This is sufficient actual execution evidence for the CACHE-01 race closure when combined with the independently verified production source semantics.

## BUG-CACHE-02 — source fixed, execution gate remains

The original provider-only path correction remains intact: `content://` values do not become native raw paths, supported raw roots are positively resolved, and unsupported selections fall back/reject before native staging.

The review-fix also closes the source-semantic execution-root residual:

### Terminal

`TerminalDownloadWorker` resolves one canonical `boundCacheRoot` before cache-scoped admission and stores it as the execution root. The same root is passed into `TerminalExecutionRegistry.admit(...)`, `TerminalPublicationRecovery.admit(...)`, `TerminalCommandPlanFactory.create(...)`, the `TERMINAL/<taskToken>` staging path, marker creation, and worker-side publication recovery.

The production worker no longer re-resolves `cache_path` after ACQUIRED for normal execution planning/staging. The only fallback resolution in `reconcileTerminalPublicationRecovery(...)` is used when no execution-bound root exists; an admitted execution supplies its bound root.

### Download

Download claim resolves the canonical cache root inside the shared execution-admission window and publishes it through `onCacheRootBound(...)` keyed by exact execution ID. `DownloadWorker` requires that bound root before constructing `DownloadAttemptRunner`.

The attempt carries the same root through output-plan resolution, raw temp staging, yt-dlp request construction, info-json operations, retries, hard-sub staging, artifact manifests, publication/finality cleanup, and exact cache retirement. Missing bound authority fails rather than silently re-resolving a replacement root.

`DownloadProducerRecovery` now uses the persisted generation `outputRoot` for recovery/retirement instead of consulting mutable `cache_path` state after restart.

No source-semantic residual equivalent to the prior R-admitted -> D-staged sequence survived the final exact-source review.

### Why CACHE-02 is not closed yet

The final source adds a production WorkManager regression in `TerminalExecutionProductionWiringTest` that deliberately changes `cache_path` after Terminal admission and proves staging remains under the already-admitted root while a recovery carrier exists in the fallback namespace.

However, the completion report states that the Terminal/cache-storage instrumentation invocation failed during installation with `INSTALL_FAILED_INSUFFICIENT_STORAGE`, and **zero tests executed** in that invocation.

The earlier static cache-storage instrumentation at the prior implementation SHA does not execute the new review-fix production path. Focused JVM tests and compile checks are useful evidence, but they do not replace this triggered production-wiring execution requirement for the exact residual that caused the review-fix.

Accordingly:

- CACHE-02 source semantics: **FIXED**.
- New blocker-level code defect found: **NO**.
- Required production root-switch execution evidence at exact final SHA: **NOT EXECUTED**.
- Canonical disposition: **OPEN on execution-evidence gate only**.

A verification-only rerun on unchanged exact SHA `3616ae02...` is sufficient next action. No code change is requested unless that test actually fails.

## Other verification evidence from completion report

Evidence reported by the implementation agent, not independently executed by this reviewer:

- focused JVM cache tests: `16/16` passed;
- full JVM suite: `613/613` passed;
- KSP: PASS;
- debug Kotlin compile: PASS;
- Android-test Kotlin compile: PASS;
- `git diff --check`: PASS;
- cache maintenance instrumentation: `2/2` actual test cases passed, followed by post-run aapt access-denied task failure;
- Terminal/cache-storage instrumentation: `0` executed due `INSTALL_FAILED_INSUFFICIENT_STORAGE`;
- Room migration: NOT REQUIRED.

These claims remain implementation-agent evidence. The independent reviewer did not execute tests.

## Preserved scope

No reviewed cache change modifies the already-closed F3/F12/F13/F14/BUG-KEYWORD-04/F19 duplicate-identity semantics. No Room schema change is present in the cumulative cache range. `BUG-DUPLICATE-ADMISSION-01` and other unrelated open roots remain unaffected.

INDEPENDENT EXECUTION: NOT EXECUTED

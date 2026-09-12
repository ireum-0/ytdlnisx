# BUG-CACHE-01 canonical cumulative closure review

Date: 2026-09-12

## Exact review basis

- Prior independently CLEAN basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Completed implementation HEAD: `6cb93d12298427dbca2a596f5b6a6a6e82e41997`
- Exact parent: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Range: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c..6cb93d12298427dbca2a596f5b6a6a6e82e41997`
- Range topology: exactly one commit ahead / zero behind, merge base exact prior CLEAN basis
- Governing candidate review: `b713f4e9736be7455c38cc5fc8a52114404e65f1`
- Candidate SHA: `b9b5fd1ea636a528d92d1a0be577dab6d038d214`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger: reference-only `899328bc91e4008e39a658387396a0106c8666ec`

The implementation branch remote HEAD was independently re-verified at exact `6cb93d12...` after the completion report. The exact range contains only the reported BUG-CACHE-01 implementation/test file set and no schema, metadata-publication, automatic-keyword, or authoritative review/ledger/plan files.

## Verdict

**CLEAN / BUG-CACHE-01 CLOSED.**

Canonical count delta: `P2 -1`.

Resulting canonical blocker count: **P0 2 / P1 1 / P2 32**.

Overall remediation state remains `NOT_CLEAN` because unrelated blockers remain open.

Contiguous independently CLEAN basis advances to:

`6cb93d12298427dbca2a596f5b6a6a6e82e41997`

## Root and governing invariant

`BUG-CACHE-01` is the merged semantic root that includes the historical cleanup/live-owner race. Its governing invariant is:

> destructive cache maintenance and positive live Download/Terminal execution admission share one ordering authority; exact live-owned roots/artifacts must not be deleted or moved at the filesystem mutation boundary.

The pre-fix failure sequence was:

`maintenance observes no live owner / abandoned candidate`
→ `new Download or Terminal execution claims the same cache subject`
→ `maintenance continues from stale negative ownership evidence`
→ `new live artifacts can be deleted or moved`.

## Exact-source closure

### Shared ordering authority

`CacheMaintenanceAuthority` is a process-local coroutine `Mutex` with two views over the same lock: `withExecutionAdmission` and `withMaintenanceWindow`. The exact production changes use this same authority on both sides of the race rather than adding an unrelated helper lock.

The added lock edges are one-way:

- `CacheMaintenanceAuthority -> Download per-execution side-effect lease -> Download global claim coordination -> durable claim CAS -> Download process-local owner publication`;
- `CacheMaintenanceAuthority -> TerminalExecutionRegistry.mutex -> provisional/exact Terminal owner publication and durable recovery admission`.

Maintenance-side liveness checks do not acquire those admission mutexes in reverse. Download liveness queries the exact process-local execution-owner registry; Terminal liveness uses `TerminalExecutionRegistry.isActiveNow(...)`, which reads only the dedicated process-local active-token lock. No new `Download/Terminal admission lock -> CacheMaintenanceAuthority` edge was found in the completed changed scope, so no AB/BA cycle was introduced by this correction.

### Download production admission

`claimDownloadThroughProductionAdmission(...)` enters `CacheMaintenanceAuthority.withExecutionAdmission` before the existing Download lease/global-claim/CAS protocol. A successful claim materializes a fresh exact execution ID and publishes `DownloadWorkerExecutionOwners.claim(downloadId, executionId)` before leaving the shared authority window.

The underlying claim policy is otherwise preserved: durable recovery/finality fences, native-process authority, capacity/hard-sub revalidation, low-quality cancellation, History replacement finality, and exact durable claim CAS still run in their previous relative authority path.

Therefore a maintenance pass cannot cross the claim-materialization-to-owner-publication gap. If maintenance owns the authority first, new Download admission waits. If Download admission owns it first, maintenance begins only after the exact live owner is visible.

The later cache marker/attempt preparation remains fail-closed before native use: Download cache ownership establishes the marker before creating/reusing the numeric staging directory and refuses unproven pre-existing material. The reviewed source did not expose a new interval in which current-generation artifacts exist without either exact ownership proof or a failure-before-native outcome.

### Terminal production admission

`TerminalExecutionRegistry.admit(...)` also enters `CacheMaintenanceAuthority.withExecutionAdmission` before its registry mutex. Within that authority window it installs the provisional active token, inspects/establishes the durable Terminal execution witness, checks native-generation liveness, and performs publication-recovery admission. `ACQUIRED` is returned only after those fences succeed.

Failure paths clear the provisional in-process token while preserving/converging durable recovery evidence as required. A maintenance pass therefore cannot begin after Terminal admission has become positively live while still missing its exact process-local task token.

### Destructive deletion boundary

`AppCacheManager.delete(...)` is now `suspend` and holds `withMaintenanceWindow` across:

1. current target resolution;
2. current filesystem enumeration;
3. per-entry ancestor walk and exact live-owner revalidation;
4. the actual filesystem delete operations.

Live Download entries require a valid marker/root bound to the exact `(downloadId, executionId)` process-local owner. Live Terminal entries require a valid Terminal marker whose task token is currently active. Such entries are skipped and make the deletion result incomplete rather than being mutated.

Because admission uses the same mutex, the old stale-negative interleaving is closed. Abandoned proven material remains cleanable after the exact process-local owner exits.

The consumer-contract delta from `AppCacheManager.delete()` becoming suspending is also propagated coherently. The settings UI invokes it from `lifecycleScope` and performs the call on `Dispatchers.IO`; `CleanUpLeftoverDownloads` is already a coroutine worker path. No `runBlocking` or detached fire-and-forget adapter was introduced merely to satisfy the new contract.

### Cache import / move boundary

`MoveCacheFilesWorker` is now a `CoroutineWorker`. It holds `CacheMaintenanceAuthority.withMaintenanceWindow` around both `CacheImportPlanner.collect(cacheRoot)` and the complete exact move loop.

`CacheImportPlanner.collect(...)` continues to admit only explicit Download/Terminal marker-backed artifact manifests and now excludes roots whose exact Download execution or Terminal task token is currently live. Unknown/legacy material is not promoted into the import manifest. Because the same maintenance window remains held from manifest construction through all filesystem moves, a new execution cannot slip into the previous `collect -> new owner -> move` TOCTOU gap.

### Identity and abandoned-owner behavior

Download liveness is not inferred from a coarse Active status: it requires the marker's exact execution ID to match `DownloadWorkerExecutionOwners` for the same Download ID. Terminal liveness likewise requires the marker's exact task token to be present in the active Terminal registry.

Once those process-local exact owners exit, the durable provenance markers/manifests may again identify abandoned app-owned artifacts for cleanup/import. The fix therefore does not turn all historical cache material into permanently undeletable state.

## Production composition and preserved closures

The completed range does not modify the F13/F14 metadata-publication production files or the F12/BUG-KEYWORD-04 automatic-keyword production files. The Download admission modification wraps the existing claim authority protocol rather than replacing its durable finality/CAS checks. No regression was found in the previously closed metadata/keyword roots from this cache-maintenance correction.

No Room entity/schema/migration file changes occur in the exact range. A Room migration is not required for this implementation.

## Scheduled-checkpoint reconciliation

While this completion was being finalized, scheduled/narrow independent checkpoints were appended on `review/remediation` for the unchanged `6cb93d12...` source. The latest such checkpoint (`006f3dd68e4e4bfaa340d4b949070a5eb6468c14`) already classified the BUG-CACHE-01 source-semantic defect as FIXED but withheld canonical closure because exact production-path runtime evidence was unavailable to that round.

The explicit completion report subsequently supplied execution evidence for the completed SHA, including an actual execution of `CacheMaintenanceProductionWiringTest` on an API-36 emulator: 1 test executed and passed. The later Gradle failure occurred during post-run aapt cleanup and does not convert the already executed test into a non-execution. The separately attempted `DownloadWorkerCleanupProductionWiringTest` failed before execution because emulator installation reported insufficient storage; it remains zero tests executed and is **not** classified as PASS.

This canonical completed-result review does not override a surviving source blocker from the scheduled checkpoint: there was none. It reconciles the scheduled checkpoint's evidence-only hold with the newly supplied completion evidence plus the exact-source/production-composition review above.

## Implementation-agent verification evidence

Treated as evidence, not independent execution:

- focused JVM cache tests: 20/20 reported PASS;
- full JVM `:app:testDebugUnitTest`: 612/612 reported PASS;
- KSP: reported PASS;
- debug Kotlin compilation: reported PASS;
- Android-test Kotlin compilation: reported PASS;
- `git diff --check`: reported PASS;
- `CacheMaintenanceProductionWiringTest`: actual instrumentation execution 1/1 reported PASS; Gradle later failed during post-run aapt cleanup on Windows;
- `DownloadWorkerCleanupProductionWiringTest`: FAIL BEFORE EXECUTION because emulator install failed with `INSTALL_FAILED_INSUFFICIENT_STORAGE`; zero tests executed; historical timeout remains unconverted to PASS.

The reviewer did not independently execute these tests.

## Consequence

Task 004 / P2 `BUG-CACHE-01` is canonically closed at `6cb93d12298427dbca2a596f5b6a6a6e82e41997`.

The next dependency-eligible canonical implementation target may proceed from this new CLEAN basis. The already independently reviewed Task 005 / `BUG-CACHE-02` isolated candidate remains reference-only and must be replayed/reimplemented on the current canonical line rather than merged/cherry-picked/transplanted wholesale.

INDEPENDENT EXECUTION: NOT EXECUTED
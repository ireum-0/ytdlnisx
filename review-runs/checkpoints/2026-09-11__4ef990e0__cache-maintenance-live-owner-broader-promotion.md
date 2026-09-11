# Cache maintenance vs positive live temp ownership — broader-registry promotion

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Broader-registry inputs: historical P2 `BUG-CLEANUP-02` and P2 `BUG-CACHE-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active implementation wave: P2 `BUG-METADATA-02` / F13 from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**

## Verdict

**NOT_CLEAN — current source still allows cache maintenance to delete/move artifacts owned by a positive live Download/Terminal execution.**

For current canonical counting, historical `BUG-CLEANUP-02` and `BUG-CACHE-01` are merged as two consumers/subcases of one semantic P2 root: **cache maintenance lacks a shared positive-live-owner authority barrier at the actual filesystem mutation boundary.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1` (not +2)

Resulting canonical count: **P0 2 / P1 2 / P2 27**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Current source evidence

### A. Scheduled leftover cleanup — historical BUG-CLEANUP-02 subcase

`CleanUpLeftoverDownloads` reads the active Download count once. When that point-in-time count is zero it calls:

`AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))`

There is no shared cache-maintenance/live-execution lock spanning:

`zero active-count observation -> enumeration -> each destructive delete`.

A queued Download can therefore be claimed after the zero-count observation and before `AppCacheManager.delete()` reaches its newly created temp root.

`DownloadWorker` claims runnable rows into exact live executions and then constructs its attempt temp root from:

`File(FileUtil.getCachePath(context), downloadItem.id.toString())`

The worker's own execution/side-effect locks do not include `CleanUpLeftoverDownloads` or `AppCacheManager`.

### B. Manual Clear Cache — same root, another consumer

`FolderSettingsFragment.deleteCacheCategories()` calls `hasActiveDownloads()` immediately before entering `appCacheManager.delete(categories)`. That is a useful point check, but it does not prevent a new Download/Terminal owner from appearing after the check and before destructive filesystem mutation.

The confirmation UI can therefore reach the same stale-negative race:

`no active work observed -> new owner admitted -> cache manager enumerates/deletes live entries`.

### C. Cache import/move — historical BUG-CACHE-01 subcase

The `move_cache` settings action enqueues `MoveCacheFilesWorker` without first establishing active-work quiescence.

`MoveCacheFilesWorker` builds a manifest through `CacheImportPlanner.collect(cacheRoot)` and moves every manifest artifact out of the configured cache root.

`CacheImportPlanner.collect()` deliberately selects marker-owned Download and Terminal roots through:

- `DownloadCacheOwnership.listOwnedRoots(root)`;
- `TerminalCacheOwnership.listOwnedRoots(root)`.

It then enumerates the explicit artifact files for those roots. The presence of a valid ownership marker proves provenance, not abandonment: a currently live Download/Terminal execution can be exactly the owner that created the marker.

No liveness check, execution-token revocation, lease, or maintenance-exclusive barrier is performed before `MoveCacheFilesWorker.moveExact()` moves the file.

Thus a live owner can have an exact marked artifact moved away from its staging root while it is still executing/finalizing.

### D. AppCacheManager ignores live ownership entirely

`AppCacheManager.delete()` resolves category roots, recursively collects entries, and calls `File.delete()` on them deepest-first. For `DOWNLOAD_TEMP`, it does not parse `DownloadCacheOwnership` markers, query exact execution liveness, or take a lease preventing a new claim while deletion runs.

Existing `DownloadCacheOwnership` and `TerminalCacheOwnership` primitives are useful provenance/recovery mechanisms, but maintenance does not consume them as positive-live-authority barriers.

## Root reconciliation

Count this once.

- Historical `BUG-CLEANUP-02` = scheduled cleanup consumer/subcase.
- Historical `BUG-CACHE-01` = cache-import/move consumer/subcase.
- Manual Clear Cache is a third current consumer of the same missing maintenance/live-owner authority contract.

Keep distinct from existing P2 `BUG-CACHE-ROOT-01`:
- `BUG-CACHE-ROOT-01` owns **generation-scoped cache-root identity** when mutable `cache_path` changes from C1 to C2 during live/recoverable work;
- this promoted root owns **maintenance mutation against a positive live owner within the same root**, even when the configured cache path never changes.

Keep distinct from `BUG-MOVE-01`, which concerns partial SAF publication deleting uncopied source files after a destination-copy failure.

## Governing invariant

Checklist v6 core invariant 7 applies directly: cleanup/recovery/maintenance must not mutate a resource away from an exact currently live owner unless an explicit revocation/transfer protocol has won.

A point-in-time active-count check or ownership marker alone is not destructive authority.

## Required correction boundary

A coherent correction should establish one shared maintenance-vs-execution protocol across scheduled cleanup, manual clear, and cache import/move:

1. maintenance must acquire a cache-maintenance authority/lease that prevents new Download/Terminal staging owners from being admitted while destructive enumeration/mutation is in progress; or perform per-root final revalidation under an equivalent protocol that blocks ownership acquisition until that mutation commits;
2. exact positive live Download/Terminal owners must be excluded at the actual delete/move boundary;
3. marker presence alone must never mean stale/abandoned; liveness/generation must be proven;
4. Download/Terminal admission must participate in the same ordering contract, not merely publish an owner after maintenance already authorized destruction;
5. preserve safe deletion/import of genuinely abandoned, explicitly owned artifacts;
6. unknown/unproven files remain fail-closed rather than being recursively deleted or moved merely because they are under the configured cache root;
7. add deterministic races for:
   - scheduled cleanup zero-count -> Download becomes Active before deletion;
   - PostProcessing owner during cleanup;
   - manual clear negative check -> new Download/Terminal owner before deletion;
   - live Download artifact selected by cache import;
   - live Terminal artifact selected by cache import;
   - abandoned owned artifact still removable/importable;
   - maintenance in progress -> attempted new execution admission is blocked/deferred until maintenance releases authority.

## Independent execution

INDEPENDENT EXECUTION: NOT EXECUTED

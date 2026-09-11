# BUG-CACHE-02 — SAF custom cache authority lost to raw filesystem path

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Broader-registry source: historical P2 `BUG-CACHE-02`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active implementation wave: P2 `BUG-METADATA-02` / F13 from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**

## Verdict

**NOT_CLEAN — broader-registry P2 `BUG-CACHE-02` is independently reproduced at exact `4ef990e0...` and promoted into the current canonical blocker inventory.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 2 / P2 28**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Concrete production chain

`FolderSettingsFragment` exposes the custom cache directory using `ACTION_OPEN_DOCUMENT_TREE`, requests read/write/persistable URI grants, and on success stores the selected URI string directly in the `cache_path` preference through `changePath(..., CACHE_PATH_CODE)`.

The selected authority is therefore provider/document authority: `content://...` plus the persisted SAF grant.

`FileUtil.getCachePath(context)` does not preserve that authority. For any nonblank preference it returns:

`formatPath(preference)`

`formatPath()` strips the external-storage document/tree URI prefix and reconstructs a raw filesystem-looking `/storage/.../` path.

Normal Download execution then consumes `FileUtil.getCachePath(context)` as a raw filesystem root. `DownloadWorker` constructs the per-download temporary directory with:

`File(FileUtil.getCachePath(context), downloadItem.id.toString())`

and the Download cache ownership/staging implementation performs ordinary `java.io.File` canonicalization, `mkdirs()`, marker writes, manifest writes, and native yt-dlp output under that root.

There is no provider-backed staging implementation at that boundary and no configuration-time proof that a SAF grant also grants direct raw/native access to the derived pathname.

Thus a supported UI sequence can be:

`user selects writable SAF tree`
→ `persisted URI grant succeeds`
→ `cache_path stores provider URI`
→ `getCachePath()` discards provider identity and derives raw path`
→ `Download claims work`
→ raw File/native staging fails or becomes inaccessible even though the UI accepted the cache location.

## Why current helper checks do not close it

`FileUtil.canWriteToDestination()` is provider-aware when passed a `content://` URI, but the cache-path selection flow does not use it to establish a typed execution mode or reject a provider-only cache root before persisting the setting.

Once the preference is consumed through `getCachePath()`, downstream cache ownership/recovery operates on a String/raw File root; the original SAF authority is no longer available to those consumers.

`AppCacheManager` also only treats app-owned filesystem roots as available ownership roots, which further demonstrates that a custom SAF-selected cache tree is not represented as the same typed cache authority throughout execution/maintenance.

## Root reconciliation

Count this once as `BUG-CACHE-02`.

Keep distinct from:
- P2 `BUG-CACHE-ROOT-01`: mutable cache-root generation identity when one execution/recovery generation spans C1 -> C2 preference changes;
- promoted P2 `BUG-CACHE-01` live-maintenance root: cleanup/import/clear mutation against a positive live owner within an otherwise unchanged cache root;
- broader `BUG-DUPLICATE-03`: download-archive SAF/provider authority;
- broader `BUG-TERMINAL-03`: Terminal destination-output SAF authority.

This root is specifically **loss of the cache location's storage-authority type before Download/Terminal staging consumers use it**.

## Required correction boundary

A coherent correction must choose one explicit cache-storage authority model and preserve it through every consumer:

1. either restrict live yt-dlp/Terminal cache staging to app-owned or otherwise directly writable filesystem roots that are positively validated as raw/native-accessible;
2. or implement a provider-backed staging abstraction that never converts an SAF grant into assumed raw-path authority;
3. validate the selected cache mode at configuration time and again before an execution generation claims it;
4. reject/fallback/migrate an inaccessible provider-only cache target before claiming the Download rather than failing later after staging ownership is created;
5. keep retry, output provenance, hard-sub/staged-quality, publication recovery, cache maintenance, and restart recovery on the same typed cache authority contract;
6. preserve the current app-owned default cache as a safe supported filesystem fallback;
7. define recovery for an already-persisted SAF custom cache whose grant is revoked or whose provider/raw mapping is no longer usable;
8. add scoped-storage regressions for primary and non-primary SAF trees, revoked grants, app-owned default cache, any intentionally supported raw external root, normal Download staging, retry, and maintenance.

No schema migration is implied by this review alone.

## Independent execution

INDEPENDENT EXECUTION: NOT EXECUTED

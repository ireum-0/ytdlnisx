# BUG-TERMINAL-03 — preserve SAF authority for Terminal output-folder selection

Date: 2026-09-12

## Exact review basis

- Independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Broader-registry source: historical P2 `BUG-TERMINAL-03`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Luna task 005 was RUNNING during this review; its moving candidate branch/diff was not inspected or relied on.

## Verdict

**NOT_CLEAN — historical P2 `BUG-TERMINAL-03` is independently reproduced at exact CLEAN basis `9edd3e23...` and promoted into the current canonical blocker inventory.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 1 / P2 31**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

Overall remains `NOT_CLEAN`.

## Exact production evidence

### 1. Terminal Folder action loses provider authority before command execution

`TerminalFragment` exposes a folder action using `ACTION_OPEN_DOCUMENT_TREE`, requests read/write/persistable URI permission, and on success calls `takePersistableUriPermission(...)` for the selected tree.

However, instead of retaining the selected `content://` tree as a typed destination authority, the result callback inserts:

`FileUtil.formatPath(result.data?.data.toString())`

into the command text.

The persisted provider grant therefore proves provider/document authority, but the authored Terminal command receives a reconstructed raw `/storage/.../` pathname. The shared command parser subsequently treats an authored absolute `-P/--paths` destination as a native filesystem destination and deliberately preserves it as the direct native target.

A provider-backed tree can therefore be accepted by Android and granted persistable write authority while the actual yt-dlp command is later asked to write through a raw path for which that grant supplies no native filesystem authority.

### 2. Configured Terminal output path has the same authority-loss class

`TerminalCommandPlanFactory.create(...)` reads `command_path` directly from SharedPreferences as `downloadLocation`.

It then constructs the execution environment with both:

- `downloadLocation = downloadLocation` (which may still be the provider URI), and
- `formattedDownloadLocation = FileUtil.formatPath(downloadLocation)`.

It independently evaluates `destinationWritable = FileUtil.canWriteToDestination(downloadLocation, context)`, which is provider-aware for a `content://` destination.

`TerminalCommandPlanner.create(...)` chooses direct native writing when caching is disabled and that destination is reported writable. In that case it emits yt-dlp `-P` using `environment.formattedDownloadLocation`, not the provider URI/DocumentFile authority that established writability.

Thus the exact sequence can be:

`SAF content:// destination is writable through persisted/provider authority`
→ `destinationWritable = true`
→ `cache_downloads = false`
→ planner selects direct write`
→ `-P` receives FileUtil.formatPath(content://...) raw pathname`
→ native yt-dlp lacks the authority represented by the SAF grant`
→ Terminal output can fail despite the UI/configuration having accepted a writable destination.

This is not merely a malformed hand-authored command edge case; it exists in the normal configured Terminal path.

### 3. Why app-cache publication does not close the root

The planner already has a safer app-cache path for non-direct execution and later provider-aware publication can preserve destination authority. But the two paths above route execution away from that safe staging/publication model before output is produced.

Provider writability must not itself authorize raw/native direct writing after provider identity has been discarded.

## Root reconciliation

Count once as existing broader-registry `BUG-TERMINAL-03`.

Keep distinct from:
- P2 `BUG-CACHE-02`: typed authority of the cache/staging root itself;
- P2 `BUG-DUPLICATE-03`: typed authority of the global download-archive storage;
- P2 `BUG-MOVE-01`: partial SAF publication/move cleanup semantics after source output already exists;
- Terminal terminal-state/bookkeeping roots, which concern success/failure classification after output publication.

`BUG-TERMINAL-03` specifically owns loss of **Terminal destination storage-authority type before native execution**.

## Required correction boundary

A coherent correction must:

1. preserve SAF-selected Terminal destinations as provider-backed identities rather than converting them into raw paths before execution;
2. when the destination is provider-only, force execution into app-owned/native-accessible staging and publish through provider-aware copy/move after yt-dlp succeeds;
3. permit direct yt-dlp output only for a destination positively proven to be native/filesystem writable independently of an SAF grant;
4. ensure the Folder UI path does not inject a lossy raw pathname into command text as if it represented the selected tree authority;
5. keep explicit authored native absolute paths supported only under the existing validated native-path contract; do not reinterpret `content://` selection as such a path;
6. preserve output provenance, Terminal execution generation, cancellation, retry/recovery, cache publication, and final success/failure semantics;
7. add production-level regressions for provider-only SAF trees, primary and non-primary document trees, cache enabled/disabled, revoked grant, app-owned/raw native destinations, Folder-button insertion, configured `command_path`, and successful provider-aware publication from staging.

No schema migration is implied by this review alone.

INDEPENDENT EXECUTION: NOT EXECUTED

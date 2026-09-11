# BUG-DUPLICATE-03 — preserve SAF authority for custom download-archive storage

Date: 2026-09-12

## Exact review basis

- Independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Broader-registry source: historical P2 `BUG-DUPLICATE-03`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN — historical P2 `BUG-DUPLICATE-03` is independently reproduced at exact current CLEAN basis `9edd3e23...` and promoted into the current canonical blocker inventory.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 1 / P2 30**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` because this is an exploratory finding, not an implementation-range regression.

## Concrete current-source chain

### 1. The settings UI establishes provider authority, not raw-path authority

At exact `9edd3e23...`, `DownloadSettingsFragment` launches `ACTION_OPEN_DOCUMENT_TREE`, requests read/write/persistable URI grants, and on success calls `takePersistableUriPermission(...)` for the selected tree.

It then stores `result.data!!.data.toString()` directly in the `download_archive_path` preference. The durable configured identity is therefore the selected SAF `content://...` tree plus its persisted provider grant.

### 2. `getDownloadArchivePath()` discards that authority

`FileUtil.getDownloadArchivePath(context)` reads the configured preference and returns:

`"${formatPath(folder)}download_archive.txt"`

`formatPath(...)` converts external-storage document/tree URIs into filesystem-looking `/storage/.../` paths by stripping provider URI structure and reconstructing a raw pathname.

No positive contract proves that a persisted SAF tree grant also grants direct `java.io.File` or native yt-dlp access to that reconstructed raw path. Provider write authority and raw-filesystem authority are distinct on scoped-storage Android.

### 3. Production duplicate consumers use the raw reconstruction

The production duplicate-check paths consume `FileUtil.getDownloadArchivePath(...)` as an ordinary file path. `DownloadViewModel` and `ObserveSourceWorker` open it through `File(...)`/`useLines` when building archive duplicate membership.

Thus a tree that the settings UI successfully authorized through SAF can still become unreadable to duplicate detection solely because the authority type was discarded before consumption. The code treats read failure as an empty archive, so duplicate protection can silently fail open rather than preserving the configured archive semantics.

### 4. yt-dlp integration retains a raw-path fallback

`YTDLPUtil` still supplies `FileUtil.getDownloadArchivePath(context)` to the `--download-archive` option when download-archive duplicate prevention is enabled and no generation-private archive path was supplied by the caller.

A native process cannot consume an arbitrary SAF tree URI grant merely because the app's ContentResolver can. Converting the URI to a pathname does not establish native write authority.

Current generation-private archive handling in some Download execution paths may protect those specific writers from direct mutation of the global archive, but it does not repair the configured global archive identity used by duplicate readers and fallback consumers. Therefore it does not close this root.

## Root reconciliation

Count `BUG-DUPLICATE-03` once as a distinct P2 root.

Keep distinct from:
- `BUG-CACHE-02`, which owns SAF/provider authority loss for the **cache/staging root**;
- `BUG-CACHE-ROOT-01`, which owns cache-root generation identity across preference changes;
- `BUG-CACHE-01`, which owns maintenance mutation against positive live cache owners;
- broader `BUG-TERMINAL-03`, which owns Terminal destination-output authority.

The shared architectural smell is provider identity being collapsed into a pathname, but the durable configured resource, consumers, user-visible failure, and repair boundary are different. Do not merge the counts merely because a future typed-storage abstraction could serve multiple roots.

## Required correction boundary

A coherent repair must preserve download-archive storage as a typed authority from configuration through every reader/writer:

1. represent a custom SAF archive as provider/tree identity rather than converting it into assumed raw path authority;
2. read archive membership through `ContentResolver`/DocumentFile or an equivalent provider-aware abstraction when the configured authority is SAF;
3. ensure every writer either updates the provider-backed archive through app-owned provider APIs or uses an app-private generation archive and explicitly commits its delta to the provider-backed durable archive;
4. never pass a reconstructed SAF pathname to native yt-dlp unless direct filesystem authority has been positively established independently of the SAF grant;
5. preserve the safe default app-owned archive behavior;
6. define behavior for revoked/missing grants and inaccessible archive documents without silently treating them as an empty valid archive;
7. keep duplicate matching semantics separate from storage authority so a storage repair does not accidentally broaden substring/identity matching;
8. add scoped-storage regressions for primary and non-primary SAF trees, revoked grants, provider-readable/provider-writable archive files, app-owned default archive, duplicate checks from both normal queueing and Observe Source paths, and archive write/merge behavior after a successful download.

No schema migration is implied by this review alone.

INDEPENDENT EXECUTION: NOT EXECUTED
# BUG-DUPLICATE-03 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Existing canonical root: P2 `BUG-DUPLICATE-03`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-DUPLICATE-03` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A persisted SAF tree grant is provider-backed authority. It is not proof that a reconstructed `/storage/...` pathname is directly readable/writable through `java.io.File` or by native yt-dlp.

Download-archive duplicate protection must preserve the selected storage authority from configuration through every reader and writer. An inaccessible archive must not be silently reinterpreted as an authoritative empty archive.

## Exact production evidence at 90afaec1

### 1. Settings establishes and persists SAF authority

`DownloadSettingsFragment` launches `ACTION_OPEN_DOCUMENT_TREE` with read, write, and persistable URI permission flags.

On success it calls `takePersistableUriPermission(...)` and stores:

`result.data!!.data.toString()`

directly in the `download_archive_path` preference.

The durable configured identity is therefore the selected `content://` tree plus its provider grant.

### 2. FileUtil discards that authority

`FileUtil.getDownloadArchivePath(context)` reads the configured preference and returns:

`"${formatPath(folder)}download_archive.txt"`.

`formatPath(...)` converts external-storage document/tree URI spellings into filesystem-looking `/storage/.../` paths.

Unlike the later cache-staging correction in the same file, this archive path does not reject provider-only `content://` authority or retain a typed provider identity.

### 3. Normal queue duplicate detection consumes the reconstructed raw path

`DownloadViewModel.detectAndMarkDuplicates()` reads:

`File(FileUtil.getDownloadArchivePath(context)).useLines { ... }`

inside `runCatching` and converts any failure to an empty list before `DownloadArchiveIdentity.parseLines(...)`.

When duplicate mode is `download_archive`, archive membership is then checked against that potentially empty authority.

A provider-readable archive whose reconstructed raw path is not directly readable therefore becomes semantically indistinguishable from an empty archive and duplicate protection fails open.

### 4. Observe Source has the same authority loss

`ObserveSourceWorker` performs the same raw `File(getDownloadArchivePath(...)).useLines` read and the same failure-to-empty collapse before archive duplicate decisions.

Thus the defect is not limited to one UI producer.

### 5. Native yt-dlp fallback also consumes the raw reconstruction

`YTDLPUtil` still supplies:

`downloadArchivePath ?: FileUtil.getDownloadArchivePath(context)`

to `--download-archive` when archive duplicate prevention is enabled.

Generation-private archive paths can protect specific execution paths from direct global mutation, but the fallback/global configured archive remains a raw path derived from SAF configuration.

A native process does not inherit arbitrary provider-tree access merely because the app's `ContentResolver` holds a persistable grant.

### 6. Intervening range does not close the archive authority root

The range from the earlier promotion basis to `90afaec1...` modifies `FileUtil`, `DownloadViewModel`, `ObserveSourceWorker`, and `YTDLPUtil`, but exact final source still preserves every authority-loss step above.

The cache path gained an explicit provider-vs-raw staging distinction; download archive storage did not.

## Concrete impact

User selects SAF archive tree
→ app persists valid provider grant and URI
→ archive helper converts it to assumed raw path
→ queue/Observe raw File read fails
→ failure becomes empty archive
→ an already archived media source can be treated as not archived and downloaded again.

For fallback native writing, the same authority mismatch can prevent yt-dlp from updating the configured authoritative archive even though the UI accepted the SAF location.

## Root reconciliation

- Keep `BUG-DUPLICATE-03` counted once as P2.
- Count delta: `0`.
- Keep separate from `BUG-CACHE-02` (cache/staging SAF authority), `BUG-TERMINAL-03` (Terminal output destination authority), and duplicate identity matching semantics.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. retain custom archive storage as a typed provider/tree authority rather than reconstructing assumed raw access;
2. read provider-backed archive membership through ContentResolver/DocumentFile or an equivalent provider-aware abstraction;
3. commit archive writes through provider-aware app code, or use an app-private generation archive with an explicit durable merge into the provider archive;
4. never pass a reconstructed SAF pathname to native yt-dlp unless direct filesystem authority is independently proven;
5. preserve the safe app-owned default archive path;
6. fail closed or expose recoverable archive-unavailable state for revoked/missing/inaccessible grants rather than treating it as empty;
7. keep storage-authority repair separate from duplicate media-identity matching.

## Verification

- Exact-source settings -> persisted authority -> queue/Observe/native consumer trace: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED

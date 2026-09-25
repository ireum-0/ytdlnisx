# BUG-DUPLICATE-03 — exact current-basis revalidation

Date: 2026-09-26 +09:00

Exact independently CLEAN implementation basis reviewed:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

Governing Master Plan:
fada33a7eed86b1fa2c07065af66f14bf4d24714

Governing checklist:
REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7

Canonical existing root:
P2 BUG-DUPLICATE-03

Prior exact-basis revalidation:
review-runs/checkpoints/2026-09-20T0718Z__90afaec1__download-archive-saf-authority-exact-basis-revalidation.md

## Verdict

OPEN / CONFIRMED / NOT_CLEAN.

The root remains present at exact CLEAN basis
17a492a7891c159fcdd66905ecc72d8e9f90aadf.

Canonical blocker-count delta:
0

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 18

CLEAN_REVIEW_BASIS remains:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

## Governing invariant

A persisted SAF tree grant is provider-backed authority.

It is not proof that a reconstructed /storage/... pathname is directly
readable/writable through java.io.File or by native yt-dlp.

Download-archive duplicate protection must preserve the selected authority
through every reader and writer. An inaccessible archive must not be silently
reinterpreted as an authoritative empty archive.

## Current exact-source evidence

### Settings persists provider authority

DownloadSettingsFragment still:
- launches ACTION_OPEN_DOCUMENT_TREE;
- requests read/write/persistable URI permissions;
- calls takePersistableUriPermission(...);
- stores result.data.data.toString() directly into download_archive_path.

The configured identity is therefore the selected content:// tree plus its
persisted provider grant.

### FileUtil still discards provider identity

FileUtil.getDownloadArchivePath(context) still:
- reads download_archive_path;
- runs formatPath(folder);
- appends download_archive.txt;
- returns a String.

For provider-backed tree URIs, formatPath reconstructs a filesystem-looking
path rather than retaining a typed provider/tree authority.

There is no provider-aware archive handle returned to callers.

### Normal queue duplicate detection still fails open

DownloadViewModel.detectAndMarkDuplicates() still reads:

File(FileUtil.getDownloadArchivePath(context)).useLines { ... }

inside runCatching.

Any read failure is converted to listOf(), then parsed as archive membership.

Therefore a valid provider-backed archive that cannot be opened through the
reconstructed raw path is semantically treated as an empty archive.

When prevent_duplicate_downloads == download_archive, a source already present
in the configured authoritative archive can therefore be admitted again.

### Observe Source has the same authority loss

ObserveSourceWorker still uses:

File(FileUtil.getDownloadArchivePath(context)).useLines { ... }

and collapses failure to emptyList() before
DownloadArchiveIdentity.parseLines(...).

The Observe duplicate decision therefore fails open under the same provider-only
SAF condition.

### Native yt-dlp fallback still receives reconstructed raw path

YTDLPUtil still adds:

--download-archive
downloadArchivePath ?: FileUtil.getDownloadArchivePath(context)

when download-archive duplicate protection applies.

Execution paths that explicitly inject a generation-private archive are
stronger, but the fallback/global configured path remains derived from the SAF
tree URI and can be passed to native yt-dlp as if it were direct filesystem
authority.

The app's persisted ContentResolver grant is not transferred to native yt-dlp
by that pathname reconstruction.

## Concrete current failure

1. User selects a provider-backed archive directory through SAF.
2. App persists the content:// tree URI and grant.
3. FileUtil reconstructs a raw /storage/.../download_archive.txt path.
4. That raw path is not directly readable for the provider.
5. queue/Observe raw File read fails.
6. failure becomes an empty archive.
7. a source already present in the actual provider archive is treated as not
   archived.
8. duplicate protection admits a repeat download.

For native fallback writing, the same authority mismatch can prevent yt-dlp
from updating the configured authoritative archive even though the UI accepted
that storage location.

## Root reconciliation

Keep BUG-DUPLICATE-03 counted once as P2.

No new root is created.

Keep separate from:
- BUG-CACHE-02 / cache and staging SAF authority;
- BUG-TERMINAL-03 / Terminal output destination authority;
- duplicate identity/canonicalization semantics;
- queue reservation/handoff roots.

## Stable correction boundary

A coherent correction should:

1. represent custom archive storage as a typed authority:
   - app-owned raw File authority for the default/raw path; or
   - provider/tree URI authority for SAF;

2. never reconstruct a provider tree URI into assumed native filesystem
   authority unless direct raw access is independently proven;

3. provide one provider-aware archive read abstraction used by:
   - DownloadViewModel duplicate detection;
   - ObserveSourceWorker duplicate detection;

4. distinguish:
   - archive successfully read and empty;
   - archive unavailable/inaccessible/revoked;
   - archive content present;

5. fail closed or expose a recoverable archive-unavailable state when the
   configured archive cannot be read, rather than treating failure as empty;

6. for download execution, keep yt-dlp on an app-owned generation-private/raw
   archive file when native direct access to the configured authority is not
   proven;

7. merge/commit successful generation archive updates back to the configured
   provider-backed authoritative archive through app/provider-aware I/O;

8. preserve the existing safe app-owned default archive path without forcing
   SAF machinery onto it;

9. preserve current duplicate identity parsing/matching behavior unless a
   separate canonical root explicitly authorizes changing it;

10. add deterministic production-wiring/storage tests for:
    - default app-owned raw archive read/write;
    - provider-backed SAF archive read;
    - inaccessible/revoked provider archive does not become empty;
    - queue duplicate detection against provider archive;
    - Observe duplicate detection against provider archive;
    - yt-dlp request never receives reconstructed SAF raw path;
    - successful generation-private archive merge into provider authority;
    - merge failure retains recoverable durable/visible debt rather than
      silently declaring the authoritative archive updated.

Prefer a narrow DownloadArchiveStorage/Authority abstraction rather than
teaching unrelated FileUtil callers to guess URI semantics.

If durable merge/retry debt would require a new schema or a broader
publication-recovery redesign, stop before that expansion and report the exact
boundary. Reuse existing publication/recovery primitives where they already
provide a correct fit.

## Verification

Exact-source current-basis revalidation completed at
17a492a7891c159fcdd66905ecc72d8e9f90aadf.

Independent execution was not performed.

INDEPENDENT EXECUTION: NOT EXECUTED

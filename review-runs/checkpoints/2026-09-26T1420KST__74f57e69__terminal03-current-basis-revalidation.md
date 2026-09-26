# BUG-TERMINAL-03 — exact current-basis revalidation

Date: 2026-09-26 +09:00

Exact independently CLEAN implementation basis reviewed:
74f57e695db30b701ad429af311c39a763bfe086

Governing Master Plan:
fada33a7eed86b1fa2c07065af66f14bf4d24714

Governing checklist:
REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7

Canonical existing root:
P2 BUG-TERMINAL-03

Prior exact-basis revalidation:
review-runs/checkpoints/2026-09-20T0732Z__90afaec1__terminal-saf-authority-exact-basis-revalidation.md

## Verdict

OPEN / CONFIRMED / NOT_CLEAN.

The root remains present at exact CLEAN basis
74f57e695db30b701ad429af311c39a763bfe086.

Canonical blocker-count delta:
0

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 17

CLEAN_REVIEW_BASIS remains:
74f57e695db30b701ad429af311c39a763bfe086

## Governing invariant

A successful SAF tree grant establishes provider-backed document authority.

It does not establish native filesystem authority for yt-dlp.

Terminal planning must preserve destination authority type through execution:
provider-only destinations must stage under an app/native-accessible root and
publish through provider-aware APIs.

Direct yt-dlp output is allowed only for independently proven raw/native
filesystem authority.

## Current exact-source evidence

### Terminal Folder UI still discards provider identity

TerminalFragment launches ACTION_OPEN_DOCUMENT_TREE with persisted read/write
grant flags.

On success it takes persistable URI permission, then inserts into the authored
command:

FileUtil.formatPath(result.data?.data.toString())

instead of preserving the selected content:// tree as provider-backed
destination metadata.

The UI therefore converts a provider grant into a raw-looking command path.

That raw-looking path later appears to TerminalCommandPlanner as an authored
native -P/--paths destination.

### Configured command_path still has the same authority loss

FolderSettingsFragment correctly persists the original content:// tree URI in
command_path.

TerminalCommandPlanFactory reads that provider identity into downloadLocation,
but also computes:

formattedDownloadLocation = FileUtil.formatPath(downloadLocation)

and:

destinationWritable = FileUtil.canWriteToDestination(downloadLocation, context)

canWriteToDestination is provider-aware, so a persisted SAF grant can make
destinationWritable=true even though native yt-dlp cannot write the formatted
raw path directly.

TerminalCommandPlanner still computes:

writesDirectly =
    configDeclaresOutputPath ||
    (!cacheDownloads && destinationWritable)

and, when no authored path exists but writesDirectly is true, emits:

-P = environment.formattedDownloadLocation

Therefore:

provider command_path is writable through ContentResolver
→ destinationWritable=true
→ cache_downloads=false
→ planner selects direct native output
→ yt-dlp receives a reconstructed raw pathname
→ persisted SAF grant does not authorize that native path.

### Folder-authored command path bypasses provider-aware planning entirely

TerminalCommandPlanner preserves an authored absolute home -P after path syntax
validation.

The Folder UI itself currently manufactures such an authored path from the SAF
URI.

The planner has no remaining signal that this authored path originated from a
provider grant.

Thus the UI-created command can force native direct output even when
cache_downloads=true.

### Safe provider publication path already exists

When usesAppCache=true, TerminalDownloadWorker stages under the execution-bound
app cache root.

It then publishes through:

FileUtil.moveFile(
    originDir = outputDirectory,
    destDir = downloadLocation,
    ...
)

downloadLocation can remain the original content:// provider URI.

FileUtil.moveFile has provider-aware DocumentFile/ContentResolver publication,
exact output reservation/publication journaling, and downstream Terminal
publication-recovery integration.

The defect is therefore the planning/admission boundary choosing native direct
output for provider-only destinations, not absence of a safe provider
publication mechanism.

## Concrete failures

### Folder picker path

1. user chooses provider tree S in Terminal Folder UI;
2. app receives persisted provider authority content://S;
3. UI inserts FileUtil.formatPath(content://S) into the command;
4. planner sees an authored absolute -P;
5. authored path selects direct native execution;
6. yt-dlp attempts output through a raw path not authorized by the SAF grant.

### Configured command_path path

1. settings stores content://S in command_path;
2. provider-aware canWriteToDestination(S) returns true;
3. user has cache_downloads=false;
4. planner selects writesDirectly=true;
5. -P receives FileUtil.formatPath(S);
6. native yt-dlp receives a path whose writability was never proven.

## Root reconciliation

Keep BUG-TERMINAL-03 counted once as P2.

No new root is created.

Keep separate from:
- BUG-CACHE-02 / app cache/staging root authority;
- BUG-DUPLICATE-03 / download archive SAF authority;
- BUG-MOVE-01 / provider publication failure/recovery;
- BUG-TERMINAL-05 / durable Terminal WorkManager dispatch.

The existing provider-aware FileUtil.moveFile publication path is supporting
evidence, not a separate finding.

## Stable correction boundary

A coherent correction should:

1. introduce or reuse a typed Terminal destination authority:
   - NativeRaw for an independently validated raw filesystem path;
   - SafTree/provider authority for content:// tree destinations;

2. keep Folder picker selection as provider metadata rather than inserting a
   lossy raw -P string into the authored command;

3. for configured command_path:
   - preserve the persisted content:// URI;
   - classify provider authority separately from raw/native write authority;
   - never infer native writability from ContentResolver writability;

4. force provider-only destinations through:
   - app-owned execution-scoped staging;
   - existing provider-aware publication/recovery;

5. permit direct native output only when:
   - destination is explicit user-authored native path and the native-path
     contract accepts it; or
   - configured destination is independently proven raw/filesystem-writable;

6. ensure cache_downloads=false does not bypass staging for provider-only
   destinations;

7. preserve explicit user-authored native paths that are not generated from the
   Folder SAF picker;

8. preserve existing:
   - Terminal durable dispatch ownership;
   - execution generation identity;
   - output provenance marker;
   - cancellation/native quiescence;
   - publication journal/reservation;
   - retry and recovery semantics;

9. keep display formatting separate from storage authority;

10. add deterministic production-wiring coverage for:
    - Folder-picked provider destination with cache enabled;
    - Folder-picked provider destination with cache disabled;
    - configured provider command_path with cache enabled;
    - configured provider command_path with cache disabled;
    - raw configured native destination with cache disabled remains direct when
      independently writable;
    - explicit user-authored raw -P remains direct under existing validated
      native-path contract;
    - provider path never appears as reconstructed raw -P in the yt-dlp request;
    - provider publication goes through staging + FileUtil.moveFile and exact
      publication recovery.

Prefer a narrow Terminal destination-authority model rather than changing
generic FileUtil.formatPath semantics.

No Room schema migration is expected.

## Verification

Exact-source current-basis revalidation completed at:
74f57e695db30b701ad429af311c39a763bfe086

Independent execution was not performed.

INDEPENDENT EXECUTION: NOT_EXECUTED

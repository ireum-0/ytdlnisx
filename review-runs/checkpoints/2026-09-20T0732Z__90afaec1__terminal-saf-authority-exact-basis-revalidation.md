# BUG-TERMINAL-03 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Existing canonical root: P2 `BUG-TERMINAL-03`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-TERMINAL-03` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A successful SAF tree grant proves provider-backed document authority. It does not prove that native yt-dlp or ordinary raw filesystem APIs can write the reconstructed `/storage/...` pathname.

Terminal output planning must preserve the authority type through execution. Provider-only destinations must stage in an app/native-accessible location and publish through provider-aware APIs.

## Exact production evidence at 90afaec1

### 1. Terminal Folder UI immediately discards provider authority

`TerminalFragment` exposes a Folder action through `ACTION_OPEN_DOCUMENT_TREE` and requests read/write/persistable URI grants.

On result it calls `takePersistableUriPermission(...)`, but then inserts into the authored command:

`FileUtil.formatPath(result.data?.data.toString())`

rather than preserving the selected `content://` tree identity.

The user-approved provider destination is therefore converted to a raw filesystem-looking string before command execution.

### 2. Authored Terminal paths are treated as direct native destinations

`TerminalCommandPlanner` parses authored `-P/--paths` and preserves an explicit home destination as native/direct output after validating the path syntax.

Thus a path inserted by the Folder UI is no longer associated with its SAF grant; it is treated as an authored native filesystem destination.

### 3. Configured `command_path` has the same authority-loss boundary

`TerminalCommandPlanFactory` reads `command_path` into `downloadLocation` and constructs both:

- `downloadLocation` — original configured value;
- `formattedDownloadLocation = FileUtil.formatPath(downloadLocation)`.

It sets:

`destinationWritable = FileUtil.canWriteToDestination(downloadLocation, context)`.

`canWriteToDestination()` is provider-aware for `content://` and can therefore return true because the app has a persisted SAF grant.

`TerminalCommandPlanner` then computes direct execution when caching is disabled and `destinationWritable` is true.

For that direct path it adds:

`-P = environment.formattedDownloadLocation`

which is the reconstructed raw pathname, not the provider URI/DocumentFile authority that established writability.

Concrete sequence:

`content://` tree is provider-writable
→ `destinationWritable=true`
→ `cache_downloads=false`
→ planner chooses direct native write
→ yt-dlp receives reconstructed raw `-P`
→ SAF grant does not authorize that raw/native access.

### 4. Safe staging/publication path exists but is bypassed

When `usesAppCache=true`, `TerminalDownloadWorker` stages under the execution-bound app cache root and later calls provider-aware `FileUtil.moveFile(..., destDir=downloadLocation, ...)` with publication recovery journaling.

That path can preserve provider authority.

The defect is that Folder-authored raw paths and configured provider-writable destinations can select direct execution before this safe staging/publication boundary.

### 5. Intervening changes did not close the root

The range from the earlier promotion basis to `90afaec1...` modified `FileUtil`, `TerminalCommandPlan.kt`, and `TerminalDownloadWorker`, but exact final source still contains both authority-loss paths above.

The later output provenance/publication recovery improvements do not make a provider-only raw path natively writable.

## Concrete impact

A Terminal destination can be accepted by the Android UI as writable while the actual yt-dlp execution is sent to a raw path for which the SAF grant provides no direct authority.

That can make Terminal output fail or bypass the provider-aware publication model solely because provider identity was collapsed into pathname syntax.

## Root reconciliation

- Keep `BUG-TERMINAL-03` counted once as P2.
- Count delta: `0`.
- Keep separate from `BUG-CACHE-02` (cache/staging authority), `BUG-DUPLICATE-03` (download archive authority), and `BUG-MOVE-01` (publication failure after output exists).
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. keep SAF-selected Terminal destinations as provider-backed identities;
2. prevent Folder UI from inserting a lossy raw path as if it were native authority;
3. force provider-only destinations through app-owned staging plus provider-aware publication;
4. permit direct native yt-dlp output only when raw/filesystem write authority is independently proven;
5. preserve explicit user-authored native paths under the existing validated native-path contract;
6. preserve current output provenance, execution-generation, cancellation, retry and publication-recovery invariants;
7. add provider-only primary/non-primary SAF regressions for Folder selection and configured `command_path`, with cache enabled/disabled.

## Verification

- Exact-source UI -> planner -> native/publication trace: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED

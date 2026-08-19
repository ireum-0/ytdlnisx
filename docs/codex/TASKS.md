# Task Registry

Snapshot basis: `main@41d0acf`, reviewed 2026-08-19.

## Status values

- `READY`: valid current work that may be selected when it matches the request.
- `BLOCKED`: requires a listed decision/dependency.
- `LATER`: valid backlog, not current priority.
- `DONE`: implementation is present in the reviewed source; future regressions may reopen it.

Do not implement all READY tasks in one branch.

## Active correctness tasks

### BUG-BACKUP-01 — Remap History replacement markers during restore

**Status:** READY  
**Priority:** P0  
**Severity:** Blocker

Backup restores History with newly generated IDs but does not rewrite `history-redownload:<id>` values embedded in restored download rows. `DownloadWorker` later trusts that marker to replace History and delete prior media.

Required result:

- parse and remap replacement markers through `importedHistoryIdMap`;
- reject/neutralize an unmappable marker;
- verify the target source identity before destructive replacement;
- add reset and merge-restore tests with ID collision/missing-target cases.

### BUG-KEYWORD-01 — Require an authoritative automatic-keyword baseline

**Status:** READY  
**Priority:** P0  
**Severity:** High

An empty `List<ResultItem>` currently completes baseline even though the extractor API does not prove that the snapshot was complete.

Required result:

- model complete/authoritative-empty/incomplete-or-failed fetch state;
- complete baseline only from an authoritative snapshot;
- add empty/incomplete -> later nonempty regression coverage.

### BUG-KEYWORD-02 — Recompute derived RULE assignments on History Undo

**Status:** READY  
**Priority:** P1  
**Severity:** Medium

History Undo restores a RULE assignment snapshot when the numeric rule ID still exists, even if that rule was edited to a new condition/revision during the Undo window.

Required result: restore user-owned/manual state and recompute current RULE state, or persist/validate rule revision before restoring derived rows.

### BUG-METADATA-01 — Prevent stale full-row metadata writes

**Status:** READY  
**Priority:** P1  
**Severity:** Medium

`UpdateMultipleDownloadsDataWorker` can overwrite concurrent download-row changes because it writes a pre-fetch object after re-reading only `status`.

Required result: patch only owned metadata columns or use a row revision/compare-and-set contract; add a concurrent scheduling/configuration test.

### BUG-HARDSUB-01 — Distinguish subtitle lookup failure from no subtitles

**Status:** READY  
**Priority:** P1  
**Severity:** Medium

`HardSubScanWorker` converts lookup exceptions to an empty subtitle list and marks the item removed from scanning.

Required result: preserve cancellation, classify lookup failure separately, leave the candidate retryable, and bound retries.

## Remaining product/maintenance tasks

### FILE-02 — Represent missing and inaccessible History files

**Status:** READY  
**Priority:** P1

Introduce a lazy/cancellable visible-item file state such as `EXISTS`, `MISSING`, `PERMISSION_REQUIRED`, `UNKNOWN`, `CHECKING`. Do not equate `File.exists()==false` with permission failure and do not perform a full-library scan at app startup.

### HIST-01 — Expand History filters after measurement

**Status:** LATER  
**Priority:** P3  
**Dependencies:** FILE-02 and representative query measurement

Candidate additions remain file state, subtitle state, watch completion, output location, uploader, and size range. Do not add indexes without query-plan evidence.

## Revalidated completed work

### PRIV-01 — Redact normal download diagnostics

**Status:** DONE

`DownloadWorker` uses shared sensitive-text redaction for command/output/log and user-visible diagnostic boundaries; focused redactor tests are present.

### QG-01 — Pull-request compile and unit-test checks

**Status:** DONE

`.github/workflows/android-pr.yml` runs patch whitespace checks, debug Kotlin compilation, and debug unit tests without signing secrets.

### QG-02 — Harden main release workflow

**Status:** DONE

Main verification, signing/release build, artifact upload, signing-file cleanup, and notification responsibilities are separated; actions are SHA-pinned and permissions are read-only at workflow level.

Repository branch protection is still a settings-level gap, not part of this completed code task.

### DB-01 — Representative migration tests

**Status:** DONE

Populated migration cases through Room 52 cover retained History/Observe Source/download retry and automatic-keyword state. Connected execution remains separate release evidence.

### FAIL-01 — Structured download outcomes/issues

**Status:** DONE

`DownloadOutcome`, `DownloadIssue`, stages, codes, suggested actions, redacted bounded details, and partial-success representation are implemented.

### FAIL-02 — High-confidence failure classification

**Status:** DONE

`DownloadIssueClassifier` implements typed/stage-aware classification with bounded input and UNKNOWN fallback.

### FAIL-03 — Structured failure information and safe actions

**Status:** DONE

Errored-download UI uses structured issue information, log/settings actions, and bounded retry entry points.

### RETRY-01 — User-initiated safe retry

**Status:** DONE

Persistent retry metadata, same-settings/reconfigured strategies, cancellation/valid-output guards, and attempt limits are implemented.

### FILE-01 — Copy paths and open file locations

**Status:** DONE

Stored locations distinguish raw/file/content values; UI can copy file/URI values, copy a common parent, attempt to open it, and fall back to safe parent text.

### FILE-03 — App-owned cache management

**Status:** DONE

`AppCacheManager` scans/deletes verified app-owned categories with protected exclusions and partial-result reporting; folder settings gate cache/log cleanup while active downloads are present.

### RUNTIME-01 — On-demand runtime diagnostics

**Status:** DONE

User-triggered diagnostics cover yt-dlp/Python, ffmpeg/ffprobe, aria2c, QuickJS, cookie presence, destination/storage, notifications, battery optimization, timeouts, redaction, and copyable results.

### PRESET-01 — Audit/unify configuration models

**Status:** DONE

ADR-0001 records the accepted SharedPreferences/versioned-JSON model and precedence.

### PRESET-02 — Minimal download presets

**Status:** DONE

Preset create/rename/apply/delete, sanitized mapping, and a global Quick Download preset are implemented without a Room migration.

### PLAYER-01 — Establish a queue-state owner

**Status:** DONE

`PlaybackQueueState` owns queue item order, playable-path/media-key indexes, shuffle state, and per-item playback positions and is covered by focused tests. Playback lifecycle/UI responsibilities remain in `VideoPlayerActivity`.

### TERM-01 — Terminal dry-run

**Status:** DONE

Terminal preview and execution are derived from the same `TerminalCommandPlan`; preview output is redacted and the planner removes forbidden external ffmpeg-location options.

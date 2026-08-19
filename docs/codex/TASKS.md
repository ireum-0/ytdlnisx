# Improvement Task Status

This file reconciles the older improvement plan with the implementation
audited on 2026-07-30. It is a status record, not permission to implement
additional work.

The active correctness defects below were revalidated against
`checkpoint/pre-baseline-review@73d3836665f5f2e6e232e327eef1d968054d0539`
on 2026-08-19. This defect list intentionally excludes repository settings,
quality-gate/process configuration, and documentation-only drift.

## Defect priority

- **P0 — Critical:** destructive data-integrity or safety failure that can
  target the wrong persistent record, delete/replace unrelated user data, or
  otherwise make the affected workflow unsafe to use.
- **P1 — High:** serious correctness or data-integrity defect with broad or
  silent user-visible impact; should be fixed before relying on the affected
  workflow as production-ready behavior.
- **P2 — Medium:** significant correctness/reliability defect with narrower
  impact or a less destructive failure mode; fix after P0/P1 items.
- **P3 — Low:** limited-impact correctness defect or edge-case inconsistency
  that does not materially threaten persistent user data or core workflow
  integrity.

## Status values

- **Implemented:** the intended capability is present in the current code.
- **Partial:** important behavior exists, but a documented gap remains.
- **Deferred:** proposed work is not implemented or lacks an approved design.
- **Historical:** retained only as context; no longer an active task.

Before changing any item, inspect the current code and use
[`docs/future-work.md`](../future-work.md) for the maintained recommendation,
priority, and complexity.

## Active correctness defects

### P0 — BUG-BACKUP-01 — Remap History replacement markers during restore

**State:** Open

Backup restore inserts History rows with newly generated IDs and builds
`importedHistoryIdMap`, but restored download rows do not remap History
replacement markers stored in `playlistURL`. This affects both regular
`history-redownload:<id>` markers and quality-replacement
`history-redownload:<id>:quality:<height>` markers. `DownloadWorker` later
parses the embedded numeric History ID and uses it as the replacement target.
After reset restore the target can be missing; after merge restore the stale ID
can collide with an unrelated live History row and cause the wrong record/media
to be replaced or deleted.

Required result:

- parse and remap replacement markers through `importedHistoryIdMap` while
  restoring queued, scheduled, cancelled, errored, and saved downloads;
- reject or neutralize an unmappable marker instead of preserving a stale ID;
- validate source identity/URL before destructive History replacement;
- cover reset restore, merge restore, ID collision, missing target, regular
  marker, and quality marker cases.

### P0 — BUG-OBSERVE-01 — Require authoritative source snapshots before sync deletion

**State:** Open

Observe Sources treats a source fetch as usable for destructive `syncWithSource`
reconciliation whenever the fetch returns normally. It compares the returned
URLs with `alreadyProcessedLinks`, then deletes matching History rows and their
associated files for processed URLs missing from that returned list. The source
fetch contract does not prove that the returned list is complete. The yt-dlp
metadata request uses `--ignore-errors`, so per-item extraction failures can
produce a partial list without failing the overall call; NewPipe paging can also
return a successful accumulated list when a page yields no usable items. A
partial snapshot can therefore make media that is still present at the source
look removed and trigger destructive local History/media deletion.

Required result:

- carry an explicit complete/authoritative source-snapshot state alongside the
  returned items;
- allow `syncWithSource` deletion only after a complete authoritative snapshot;
- on partial, failed, or ambiguous fetches, preserve History/media and processed
  membership state and retry/report the source failure;
- add regressions for yt-dlp ignored item errors, incomplete NewPipe paging, and
  empty/partial pages proving that no local record or file is deleted.

### P0 — BUG-OUTPUT-01 — Do not adopt unrelated recent destination files as download outputs

**State:** Open

`DownloadWorker` falls back to `recoverPathsFromDirectory()` when parsed or
moved output paths are unavailable. That helper recursively accepts every file
in the destination whose modification time is newer than roughly two minutes
before the current download started, without proving an expected filename,
source identity, temp-origin mapping, or ownership by the current download.
These broad candidates can become `finalPaths`. In History replacement flows a
recovered file can pass only the media/quality checks, be persisted as the
replacement History path, and then cause the prior History media to be deleted.
Deferred hard-sub paths can likewise operate on recovered destination files.
Thus an unrelated file modified concurrently in the same destination can be
attached to, modified by, or substituted for the current download.

Required result:

- recover outputs only from provenance tied to the current operation, such as
  exact move results, expected output names/manifests, or verified temp-origin
  mappings;
- never accept an arbitrary recent destination file merely because of mtime;
- if output ownership cannot be proven, preserve recoverable temp artifacts and
  fail safely instead of committing History replacement;
- require verified output provenance before quality validation, hard-sub
  mutation, History replacement, or old-media deletion;
- add move-failure/output-resolution tests with unrelated recent destination
  files for normal, hard-sub, and History replacement downloads.

### P1 — BUG-KEYWORD-01 — Require an authoritative automatic-keyword baseline

**State:** Open

Automatic keyword synchronization carries only `List<ResultItem>` from the
extractor into the rule engine. `recordBaseline()` completes the baseline when
that list is empty as long as no database write failed. An empty result does
not prove that extraction was complete or that the playlist was authoritatively
empty, so a transient/incomplete empty fetch can mark the baseline complete.
A later successful fetch can then classify pre-existing playlist items as newly
discovered content when apply-existing is disabled.

Required result:

- carry fetch completeness separately from the result list, including a
  trustworthy authoritative-empty state;
- complete a baseline only from a complete/authoritative snapshot;
- keep incomplete/failed/ambiguous empty fetches retryable without advancing
  baseline state;
- add empty/incomplete -> later nonempty regression coverage.

### P1 — BUG-METADATA-01 — Prevent stale full-row metadata writes

**State:** Open

`UpdateMultipleDownloadsDataWorker` loads a download row, performs potentially
slow metadata enrichment, then reloads only the current `status` before writing
the enriched object with `updateWithoutUpsert()`. Other row fields changed
concurrently while metadata is being fetched can therefore be overwritten by
the stale pre-fetch object.

Required result:

- update only metadata columns owned by the enrichment worker, or use a row
  revision/compare-and-set merge contract;
- preserve concurrent scheduling, configuration, path, and other user/workflow
  edits;
- add a regression test that mutates a download row while metadata lookup is in
  progress.

### P2 — BUG-KEYWORD-02 — Recompute derived RULE assignments on History Undo

**State:** Open

History delete Undo snapshots keyword assignment rows. `restoreHistory()`
restores RULE assignments whenever the same numeric rule ID still exists, but
it does not prove that the rule still has the same revision, condition, or
keywords. If a rule is edited during the Undo window, the restored History row
can receive assignments derived from the old rule definition.

Required result:

- restore user-owned/manual assignment state from the snapshot;
- recompute current RULE-derived assignments from the current rule definition,
  or persist and validate an immutable rule revision before restoring derived
  rows;
- add Undo coverage where a rule is edited or replaced while the History row is
  deleted.

### P2 — BUG-METADATA-02 — Validate source identity before applying download metadata

**State:** Open

`ResultRepository` has `getSingleMetadataFromUrl()` specifically to reject an
extractor result whose trusted source identity does not match the requested
URL. `updateDownloadItem()`, however, fetches fresh metadata through
`getSingleMetadataFromSource()` and applies it directly; its cache-first path
also permits a fresh result to participate in enrichment without first proving
that it belongs to the download URL. If an extractor returns an unrelated item,
metadata such as title/author/thumbnail, duration, website, formats, subtitles,
or publication date can be merged into the wrong download row and later
materialized into History.

Required result:

- validate every fresh metadata result against the requested download URL using
  `ExtractorSourceIdentityPolicy` before merging or applying it;
- reject a mismatched fresh result in both fresh-first and cache-first lookup
  paths and do not combine it with a valid cached record;
- preserve canonical-equivalent and explicitly supported provider redirects;
- add regressions for accepted canonical equivalents and rejected unrelated
  extractor results.

### P2 — BUG-DATE-01 — Preserve extractor failure instead of ambiguous NO_DATE

**State:** Open

History publication-date resolution catches a failed minimal lookup and falls
back to the compatibility path. The compatibility path uses the generic
metadata fetch, whose yt-dlp request allows ignored item errors and can therefore
produce an empty result without an exception. If that happens after the minimal
lookup already failed, the resolver can return `NONE`, and the ledger persists
`NO_DATE`. That makes a failed or incomplete extraction indistinguishable from
a trustworthy result proving that no publication date exists.

Required result:

- carry success/completeness/failure state for each lookup path rather than
  reducing it to nullable date values;
- persist `NO_DATE` only after a successful authoritative lookup that actually
  establishes absence of a date;
- preserve failure/retry state when extractor errors or ambiguous empty output
  prevent that conclusion;
- add coverage for minimal failure + compatibility empty/ignored-error cases.

### P2 — BUG-DATE-02 — Do not mark a date-fetch operation COMPLETED with failed items

**State:** Open

Individual History date-fetch items can correctly enter `FAILED`, but
`finalizeWorkerRun()` marks the parent operation `COMPLETED` whenever there are
no `PENDING` items. It does not check whether any child items failed. A run with
one or many failed items can therefore present a successful completed operation
and terminal notification even though the requested backfill did not fully
succeed.

Required result:

- derive the terminal operation state from child outcomes, not only pending
  count;
- represent partial/all-failed runs explicitly as failure or partial-success
  according to the operation-state contract;
- make terminal UI/notification counts reflect failed items;
- add mixed success/failure and all-failed finalization tests.

### P2 — BUG-BACKUP-02 — Use collision-free custom-thumbnail paths during restore

**State:** Open

Custom thumbnails are restored before new History IDs are allocated and are
written to `restored_<oldHistoryId>.<extension>`. The old ID comes from the
backup rather than the live database, and `writeBytes()` overwrites an existing
file at that path. During merge restore, two independent backups can legitimately
contain different History rows with the same old numeric ID. Restoring the
second backup then overwrites the thumbnail file still referenced by the first
restored History row, silently changing or corrupting another record's custom
thumbnail.

Required result:

- allocate a unique restored-thumbnail path tied to the newly allocated History
  identity or a collision-resistant generated token;
- never overwrite a thumbnail path referenced by another History row;
- clean up newly written thumbnail artifacts if the corresponding History
  restore fails;
- add merge-restore tests for two backups sharing an old History ID, repeated
  restore, different extensions/content, and reset restore.

### P3 — BUG-QUEUE-01 — Keep membership-waiting selections out of queue reorder actions

**State:** Open

The queued screen displays and allows selection of both `Queued` and
`WaitingForMembership` rows. Per-item move controls and drag handling correctly
block membership-waiting rows, but the contextual multi-select Move Up/Move
Down actions do not apply that restriction. Select-all and inverted selection
explicitly include membership-waiting IDs, while the repository reorder
implementation rewrites only `Queued` rows. A visible selection can therefore
contain rows that the requested reorder silently ignores, producing a mismatch
between the selected action and the resulting order.

Required result:

- hide/disable contextual reorder when the selected set contains a
  membership-waiting row, or define and implement a consistent ordering contract
  that includes those rows;
- make direct, mixed, select-all, and inverted selections follow the same rule
  as drag and per-item move controls;
- add focused selection/reorder regressions for membership-waiting rows.

## Current status

| ID | Status | Current implementation and remaining evidence |
|---|---|---|
| `PRIV-01` | Implemented | Normal diagnostics use redaction/sanitization policies. New log and export paths must use the same policy. |
| `QG-01` | Implemented | Pull requests compile debug Kotlin and run JVM unit tests with minimal workflow permissions. |
| `QG-02` | Implemented | The release workflow compiles, tests, builds, signs, and publishes artifacts with CI-managed secrets. Device and ABI smoke evidence remains outside workflow success. |
| `DB-01` | Partial | Representative migration smoke coverage and exported schemas exist through Room version 53. Broader connected upgrade execution, especially real old databases and 52→53, remains required. |
| `FAIL-01` | Implemented | Download outcome, issue, stage, and supporting policy types are present. |
| `FAIL-02` | Implemented | High-confidence failures are classified. Unknown external messages deliberately remain unclassified. |
| `FAIL-03` | Implemented | Structured issue information and safe user actions are exposed in relevant download/history flows. |
| `RETRY-01` | Implemented | User-initiated retry is guarded by retry and ownership policies. It is not an automatic retry of every failure. |
| `FILE-01` | Implemented | Copy/open/share/location actions use URI and provider-aware fallbacks. Exact-folder support still varies by provider. |
| `FILE-02` | Implemented | Present, missing, and inaccessible states are represented in history/file actions. |
| `FILE-03` | Implemented | App-owned cache/storage cleanup is separated from user-owned deletion. Provider and permission limitations still apply. |
| `RUNTIME-01` | Implemented | On-demand runtime probes report local component health with redacted diagnostics. Per-ABI release evidence remains necessary. |
| `PRESET-01` | Implemented | Existing settings, templates, and preset precedence were reconciled in the download configuration model. |
| `PRESET-02` | Implemented | Minimal local download presets are implemented in SharedPreferences according to ADR-0001. Portable preset import/sync is not implemented. |
| `HIST-01` | Partial | History already supports broad search, filtering, grouping, file-state handling, and source-publication-date ordering/backfill. Additional filters should follow measured demand. |
| `PLAYER-01` | Partial | `PlaybackQueueState` centralizes queue data, but lifecycle, Media3, subtitle, PiP, URI, and navigation behavior remains concentrated in `VideoPlayerActivity`. |
| `TERM-01` | Implemented | Terminal command planning includes a dry-run/preview path and argument policy. |

## Newly implemented capability

The current branch also stores media source-publication time through result,
download, and history records; reads provider-specific dates; displays and
sorts them in History; defines missing-date policies; and offers an explicit
metadata backfill. This feature was not present in the older task registry.

## Active recommendations

The next work should be selected from
[Recommended Future Work](../future-work.md), not from the old READY/BLOCKED
ordering. Immediate items are:

1. resolve terminal Room projection warnings;
2. execute and extend device-backed Room migration coverage;
3. add focused source-publication-date propagation regressions;
4. test exported share entry points with hostile/malformed input;
5. define and prove an ABI support policy.

Each remains subject to the user's requested scope and the project working
rules.

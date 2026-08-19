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

### P0 — BUG-BACKUP-03 — Make reset restore fail-safe instead of destructively partial

**State:** Open

`restoreData(..., resetData = true)` performs a long sequence of irreversible
mutations across SharedPreferences, History, keyword/group tables, Observe
Sources, download queues, cookies, templates, and other state. The sequence is
wrapped only in an outer `runCatching`; it is not staged or transactionally
rolled back as a restore operation. Several reset branches delete existing live
data before later categories are parsed/inserted, and restored queued work can
be started before the remaining restore stages finish. If any later database,
filesystem, preference, or scheduling operation throws, the function returns
failure while earlier live data can already be deleted or partially replaced.
The restore UI then reports only a generic failure and has no rollback path.
The parser also does not require the backup `app` marker or a supported
`backup_format_version` before offering the destructive Reset action.

Required result:

- fully parse and validate the backup manifest, supported format version,
  category payloads, and required references before mutating live state;
- stage restored files such as custom thumbnails before committing references;
- apply related Room reset/restore mutations through a transaction or an
  explicit rollback-capable restore plan;
- defer WorkManager scheduling and preference replacement until the database
  portion has committed, or compensate those side effects on failure;
- quiesce or otherwise isolate conflicting live workers while a reset restore
  is committing;
- add injected-failure tests after early, middle, and late restore stages and
  prove that a reported failure leaves the pre-restore live state intact.

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

### P1 — BUG-BACKUP-04 — Fail backup creation when a selected category cannot be captured

**State:** Open

The category helpers in `BackupSettingsUtil` catch their own read/serialization
exceptions and return an empty JSON array. `SettingsViewModel.backup()` therefore
cannot distinguish a legitimately empty category from a category whose database
read or serialization failed. It can write that empty substitute into the file,
move the file successfully, and let the UI report that the backup was created.
A user can consequently keep an apparently successful backup that silently
omits an entire selected data category and discover the loss only during a later
restore.

Required result:

- propagate category capture failures through a typed `Result`/exception instead
  of converting them to empty arrays;
- abort a normal backup when any selected category cannot be captured, or mark
  an explicitly incomplete artifact that destructive restore refuses to use;
- capture interdependent database data from a consistent snapshot where cross-
  table relationships matter;
- add fault-injection tests for every selected category and prove that a failed
  category cannot produce a success-labelled backup.

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

### P2 — BUG-BACKUP-05 — Include paused downloads in backup and restore

**State:** Open

Paused downloads are persistent queue records and are displayed in the active
Downloads screen alongside active/post-processing rows. The backup category
list, default all-category backup, and `RestoreAppDataItem` have categories for
queued, scheduled, cancelled, errored, and saved downloads, but no paused
category. `backupQueuedDownloads()` reads only the queued/waiting queue, while
paused rows are exposed separately by `DownloadRepository`. A user who creates
an all-category backup while downloads are paused therefore receives a
success-labelled backup that cannot reconstruct those paused jobs.

Required result:

- include paused download rows in the backup format, with an explicit restore
  status contract that does not unexpectedly auto-start them;
- preserve their configuration, operation/retry metadata, and safe queue order;
- define backward compatibility for older backup versions that lack the paused
  category;
- add all-category round-trip tests containing paused, queued, and scheduled
  rows simultaneously.

### P2 — BUG-BACKUP-06 — Remap or reject every imported numeric reference

**State:** Open

Restore correctly builds maps for several newly allocated database IDs, but
some persisted numeric references still bypass those maps. In Youtuber-group
restore, members and parent/child relations use `youtuberGroupIdMap`, while the
`historyVisibleChildYoutuberGroups` preference is written back using the old
backup IDs. Generic settings backup can likewise carry ID-valued group
preferences. For History keyword assignments whose source type is
`LEGACY_OBSERVE_SOURCE`, restore falls back to the old `sourceId` when no
Observe Source mapping exists instead of proving that the old ID still denotes
the same source. During merge restore, a reused numeric ID can therefore point
at an unrelated live group/source; during reset restore it can point nowhere.
This leaves UI visibility state and keyword provenance attached to the wrong
persistent identity.

Required result:

- enumerate ID-bearing fields/preferences in the backup schema and give each an
  explicit remap/validation rule;
- remap visible/hidden Youtuber-group IDs through `youtuberGroupIdMap` before
  committing preferences;
- for legacy Observe Source assignments, use a restored mapping or validate a
  stable source identity before retaining a live ID; otherwise drop/quarantine
  the stale derived attribution rather than guessing by number;
- add merge/reset tests with intentionally colliding old group/source IDs and
  verify the restored references resolve to the intended entities only.

### P2 — BUG-BACKUP-07 — Preserve playlists and playlist groups in app-data backup

**State:** Open

The default app-data backup includes settings, History, keyword/youtuber data,
download queues, cookies, templates, shortcuts, search history, and Observe
Sources, but it has no category or serializer for `Playlist`,
`PlaylistItemCrossRef`, `PlaylistGroup`, or `PlaylistGroupMember`. The restore
model likewise has no fields for those tables, and `restoreData()` only recreates
History rows with newly allocated IDs; it does not rebuild playlist membership
or playlist-group membership from those restored rows. A success-labelled
all-category backup therefore cannot reconstruct user-created playlists, their
History membership, or playlist grouping after a reset/restore.

Required result:

- include playlists, playlist-to-History cross references, playlist groups, and
  playlist-group memberships in the backup format and default all-category
  selection;
- restore playlist IDs and History IDs through explicit old-to-new mappings
  before inserting cross references and group memberships;
- define merge semantics for same-name playlists/groups and reject ambiguous
  numeric-ID fallback;
- add reset and merge round-trip tests with one History item in multiple
  playlists and playlists belonging to multiple groups.

### P2 — BUG-BACKUP-08 — Restore every supported SharedPreferences value type

**State:** Open

`BackupSettingsUtil.backupSettings()` serializes every preference in
`SharedPreferences.all` and records the runtime type name, so `Long` and `Float`
values are written to otherwise successful app-data backups. `restoreData()`,
however, handles only `String`, `Boolean`, `Int`, and string-set values; every
other type falls through the set branch and is silently ignored. The app
currently persists real user/runtime state with the missing types: the player
stores per-History playback-position cache entries with `putLong`, while
subtitle text size, hold playback speed, and speed presets use `putFloat`.
A reset restore clears preferences first and then cannot reconstruct those
values; a merge restore silently leaves the destination device's old values
instead of applying the backup. The backup can still report success in both
cases.

Required result:

- restore `Long` and `Float` with the corresponding SharedPreferences editor
  methods and reject unknown serialized types instead of silently ignoring them;
- define whether transient ID-keyed caches such as player playback positions
  belong in portable settings backup at all, and if retained, remap/validate
  their History identity separately from value-type restoration;
- preserve supported primitive types losslessly through backup/restore and
  version the schema if type encoding changes;
- add round-trip tests covering String, Boolean, Int, Long, Float, StringSet,
  reset restore, merge restore, and malformed/unknown type names.

### P2 — BUG-DUPLICATE-01 — Canonicalize media identity for config duplicate checks

**State:** Open

The `config` duplicate policy compares a `RequestConfiguration` that includes the
raw URL string. Two requests for the same YouTube video can therefore compare as
different when one uses `youtu.be/<id>` and another uses a canonical watch,
mobile, or music URL even when the request-changing settings are otherwise the
same. The normal queue History path also computes canonical-equivalent History
candidates but then performs the config command comparison against the exact-URL
History list. This is inconsistent with the URL/type duplicate mode and allows
canonical-equivalent requests to bypass duplicate prevention and consume a
second download/storage slot.

Required result:

- compare stable/canonical media identity separately from request-changing
  configuration fields;
- treat supported canonical-equivalent URLs for the same media as the same
  source while keeping distinct media IDs distinct;
- use the canonical-equivalent History candidate set consistently in config
  duplicate checks;
- add active-queue and History regressions covering youtu.be, watch, mobile,
  and music URL variants.

### P2 — BUG-CLEANUP-01 — Make automatic leftover cleanup actually recurring

**State:** Open

The cleanup preference exposes `daily`, `weekly`, and `monthly` cadences, but
changing the preference enqueues a single `OneTimeWorkRequest` delayed until the
next calculated date. `CleanUpLeftoverDownloads` performs one cleanup and
returns `Result.success()` without scheduling the next occurrence. The selected
cadence therefore silently stops after its first run. In addition, each enabled
preference change uses a timestamp as a new unique-work name, so changing the
cadence can leave an older delayed cleanup request scheduled alongside the new
one instead of replacing a single logical cleanup schedule.

Required result:

- use a recurring WorkManager contract or explicitly schedule the next one-time
  run after each successful execution;
- use a stable unique-work identity for the cleanup schedule and replace/update
  it when the cadence changes;
- cancel the prior schedule when changing cadence or disabling cleanup;
- add WorkManager tests proving repeated daily/weekly/monthly execution and
  single-schedule behavior after preference changes/restarts.

### P2 — BUG-LOCALADD-01 — Do not treat a bare filename stem as local-file identity

**State:** Open

`LocalAddWorker` first checks stronger tree/document/path identities, but it also
builds a global `existingBaseNames` set and silently skips a candidate whenever
its filename without extension is already present. The same set is updated after
an item is accepted, so two distinct files selected in one batch from different
directories but sharing a name such as `video.mp4` are collapsed even when their
URIs/tree paths are different. A basename alone is not a stable file identity;
legitimate local media can therefore be omitted from History without a conflict
prompt or error.

Required result:

- use persisted tree/document/URI identity as the primary duplicate key;
- never silently discard a distinct file solely because another History/batch
  item has the same basename;
- if a heuristic duplicate check is desired, require additional evidence such
  as size/duration/hash and surface ambiguous cases for user choice;
- add same-name/different-directory and same-name/same-batch regressions.

### P2 — BUG-HISTORY-01 — Preserve playlist membership across History delete and Undo

**State:** Open

`HistoryRepository.deleteRecords()` deletes `PlaylistItemCrossRef` rows for the
selected History IDs before deleting the History rows themselves. The single-item
record-only delete flow offers Undo, but its snapshot contains only the
`HistoryItem` and keyword assignments; `restoreHistory()` reinserts only that
History row and those keyword assignments. Any playlist memberships that existed
before deletion are therefore permanently lost even when the user immediately
chooses Undo. The relationship delete and History delete are also separate DAO
calls rather than one Room transaction, so an exception after the cross-reference
delete but before the History delete can leave the History record present while
silently stripping its playlist memberships.

Required result:

- snapshot all `PlaylistItemCrossRef` rows for a History item before offering
  record-only delete Undo and restore those memberships with the History row;
- perform playlist-membership deletion and History deletion in one database
  transaction so either both commit or neither does;
- make bulk/file-backed deletion paths use the same atomic relationship-removal
  primitive;
- add regressions for a History item in multiple playlists, Undo, deletion
  failure between relationship and History mutation, and repeated delete/restore.

### P2 — BUG-PLAYER-01 — Serialize playback-position persistence by History item

**State:** Open

`VideoPlayerActivity` saves playback position from several independent paths,
including lifecycle callbacks, media-item transitions, explicit close, and
playback completion. `savePlaybackPositionForHistoryId()` immediately updates
in-memory/cache state but launches each Room `updatePlaybackPosition()` as a
separate `lifecycleScope.launch(Dispatchers.IO)` coroutine. There is no per-item
serialization, sequence number, or compare-and-set guard on the DAO update.
Those database writes can therefore commit out of call order. In particular, a
newer terminal save of `0` ms after playback completion can be overwritten by an
older nonzero save that finishes later, causing a completed item to resume from
a stale position on a later launch; rapid seeks/lifecycle transitions can
similarly persist an older position than the latest observed state.

Required result:

- serialize playback-position database writes per History ID or attach a
  monotonically increasing revision/timestamp and reject stale writes;
- ensure the completion reset to `0` cannot be superseded by a previously
  launched nonzero write;
- keep SharedPreferences cache and Room state under the same ordering contract
  instead of allowing the durable stores to disagree;
- add deterministic concurrency tests that delay an earlier write past a later
  write, including completion reset, rapid seek, pause/stop/destroy, and queue
  transition cases.

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
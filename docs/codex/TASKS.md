# Improvement Task Status

This file reconciles the older improvement plan with the implementation
audited on 2026-07-30. It is a status record, not permission to implement
additional work.

The active correctness defects below were revalidated against
`checkpoint/pre-baseline-review@73d3836665f5f2e6e232e327eef1d968054d0539`
on 2026-08-20. This defect list intentionally excludes repository settings,
quality-gate/process configuration, and documentation-only drift.

There are **28 active correctness defects** in this checkpoint. The previous
`BUG-BACKUP-09` entry was removed during revalidation because the user-facing
restore parser explicitly resets `CookieItem`, `CommandTemplate`, and
`TemplateShortcut` primary keys to `0L` before calling `restoreData()`. The
previous claim that merge restore passes backed-up primary keys directly into
the repositories therefore does not describe the production restore path.
Defense-in-depth normalization at the `restoreData()` boundary may still be a
hardening improvement, but it is not an active correctness defect at this
checkpoint.

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

**Failure path:** app-data restore inserts History rows with newly generated
primary keys and records those mappings in `importedHistoryIdMap`. Download rows
are restored separately. Their `playlistURL` can contain durable replacement
markers such as `history-redownload:<oldHistoryId>` or
`history-redownload:<oldHistoryId>:quality:<height>`, but those embedded History
IDs are not remapped through `importedHistoryIdMap`. Later,
`DownloadWorker` parses `HistoryRedownloadMarker` from `playlistURL`, takes the
embedded numeric ID as the History replacement target, loads that row, and can
replace the row and delete the previous media after the replacement succeeds.

**Why this is a defect:** after reset restore the old ID can refer to no row; in
merge restore the same number can already belong to an unrelated live History
row. Numeric equality across independent databases is not stable identity. A
stale marker can therefore target the wrong History row and its media.

Required result:

- parse and remap replacement markers through `importedHistoryIdMap` while
  restoring every download category that can contain them;
- reject or neutralize an unmappable marker instead of preserving a stale ID;
- validate the intended source/media identity before any destructive History
  replacement or previous-media deletion;
- add reset, merge, ID-collision, missing-target, regular-marker, and
  quality-marker regressions.

### P0 — BUG-BACKUP-03 — Make reset restore fail-safe instead of destructively partial

**State:** Open

**Failure path:** the restore UI parses the selected JSON categories into a
`RestoreAppDataItem` before invoking `restoreData()`. The defect is therefore
not that later categories are still being JSON-parsed after destructive work
starts. The actual problem is that `restoreData(..., resetData = true)` then
performs a long sequence of live mutations across SharedPreferences, History,
keyword/group tables, Observe Sources, download queues, cookies, templates,
filesystem-backed thumbnails, and WorkManager side effects. The sequence is
wrapped in `runCatching`, but it is not one atomic restore transaction and has
no rollback plan. Earlier categories can be deleted/replaced before later
categories are applied, inserted, or scheduled. Restored queued work can also
be started before all restore stages have committed.

The UI-side parser also does not require a valid backup `app` marker and a
supported `backup_format_version` before presenting/performing the destructive
Reset path, so syntactically parseable input is not the same as a validated
backup contract.

**Why this is a defect:** an exception in a later database, filesystem,
preference, or scheduling step can return a restore failure after earlier live
state has already been removed or replaced. The user receives a generic failure
without the pre-restore state being restored.

Required result:

- fully parse and then **semantically validate** the backup manifest, supported
  format version, category payloads, and cross-row references before mutating
  live state;
- stage filesystem artifacts before committing references to them;
- apply related Room reset/restore mutations transactionally, or implement an
  explicit rollback-capable restore plan where one Room transaction is not
  possible;
- defer WorkManager scheduling and preference replacement until the durable
  database portion has committed, or provide compensating rollback;
- quiesce or isolate conflicting live workers while the reset restore commits;
- inject failures at early, middle, and late commit stages and prove that a
  reported failure does not leave a destructively partial restore.

### P0 — BUG-OBSERVE-01 — Require authoritative source snapshots before sync deletion

**State:** Open

**Failure path:** `ObserveSourceWorker` obtains a plain `List<ResultItem>` from
`ResultRepository.getResultsFromSource()`. When `syncWithSource` is enabled, it
canonicalizes URLs from that list, treats the set as the current source
membership, finds previously processed URLs that are absent, resolves matching
History rows, and can delete those History rows and their associated files.
There is no accompanying result that proves the extraction represented the
entire source.

The yt-dlp metadata request includes `--ignore-errors`, so individual item
failures can be skipped while the overall extraction still returns normally.
NewPipe pagination can likewise return an accumulated successful list without a
separate completeness proof. A non-throwing list is therefore not equivalent to
an authoritative snapshot.

**Why this is a defect:** a transient partial extraction can make still-present
source media appear removed, turning an extraction-quality problem into local
data/file deletion.

Required result:

- return explicit snapshot completeness/authority alongside extracted items;
- permit destructive `syncWithSource` reconciliation only for a complete,
  authoritative snapshot;
- preserve History/media and previously processed membership on failed,
  partial, or ambiguous fetches and retry/report the source failure instead;
- add ignored-item-error, incomplete-paging, empty-partial-page, and transient
  extractor-failure regressions proving that local media is not deleted.

### P0 — BUG-OUTPUT-01 — Do not adopt unrelated recent destination files as download outputs

**State:** Open

**Failure path:** several hard-sub and output-recovery paths in `DownloadWorker`
fall back to `recoverPathsFromDirectory()` when authoritative parsed/moved paths
are unavailable. The helper recursively scans a bounded directory depth and
accepts files whose `lastModified()` is newer than approximately two minutes
before the current yt-dlp attempt started. The time window does not prove that a
file was produced by this download. In some hard-sub/replacement recovery paths
those candidates can flow into later burn-in, quality validation, `finalPaths`,
History persistence, or previous-media cleanup.

**Why this is a defect:** an unrelated file created or modified concurrently in
the same destination can satisfy the time test and be treated as current-job
output. This is especially dangerous for replacement flows because committing a
wrong recovered path can be followed by deletion of the actual old media.

Required result:

- recover only outputs with provenance tied to the current operation, such as
  exact move results, verified expected names/manifests, or a proven temp-origin
  mapping;
- never use destination mtime alone as ownership evidence;
- fail safely and preserve recoverable temp artifacts when ownership cannot be
  established;
- require verified output provenance before hard-sub mutation, quality
  acceptance, History replacement, or old-media deletion;
- add focused hard-sub and History-replacement recovery tests containing
  unrelated recent destination files; normal-download cases should remain as
  non-regression coverage for any future use of the same fallback.

### P0 — BUG-HISTORY-02 — Close retained-reference TOCTOU before History file deletion

**State:** Open

**Failure path:** user-facing History deletion with `deleteAssociatedFiles`
prepares a `HistoryDeletionValidation`, then `executePreparedHistoryFileDeletion()`
revalidates the selected records and calls `retainedStoredTargets(selectedIds)`
once to protect files referenced by other History rows. It immediately passes
that snapshot through `excludeTargetsReferencedBy()` and then calls
`HistoryFileDeletionEngine.execute()`, which invokes the filesystem/provider
delete. After deletion the code revalidates only the selected records' own
stored-target snapshots before removing their History rows; it does not prove
that the retained-reference set is still unchanged. `ObserveSourceWorker` uses
the same snapshot-then-execute pattern for sync-driven History/media deletion.

**Why this is a defect:** another download/import/History mutation can create a
new reference to one of the candidate paths after the retained-reference query
but before the actual delete. The selected record can remain unchanged, so the
post-delete selected-record check still passes even though the file has become
shared. A file currently referenced by an unrelated live History row can
therefore be deleted through a production-reachable race.

Required result:

- make reference protection authoritative at the destructive boundary rather
  than relying on a stale retained-reference snapshot;
- serialize/transactionally coordinate reference acquisition and deletion where
  possible, or recheck all relevant live references immediately before each
  destructive filesystem/provider mutation under an ownership protocol that
  prevents a new reference from appearing until deletion commits;
- apply the same contract to user-driven and Observe Source deletion paths;
- continue revalidating the selected record's own target snapshot in addition
  to retained references;
- add deterministic races where another History row begins referencing the same
  raw path or canonical-equivalent content/document target between validation
  and deletion, proving the shared file is preserved.

### P1 — BUG-BACKUP-04 — Fail backup creation when a selected category cannot be captured

**State:** Open

**Failure path:** category helpers in `BackupSettingsUtil` wrap their database
read/serialization work in `runCatching` and return an empty `JsonArray` after a
failure. `SettingsViewModel.backup()` therefore receives the same representation
for a legitimately empty category and for a category that failed to read or
serialize, writes the resulting JSON, and can still move the file and report
backup success.

**Why this is a defect:** the artifact can be labelled successful while silently
omitting a selected category. The loss is only discovered when the backup is
needed for restore.

Required result:

- propagate category capture failure through a typed result or exception;
- abort normal backup creation if any selected category cannot be captured, or
  explicitly mark an incomplete artifact that destructive restore rejects;
- capture interdependent tables from a consistent snapshot where relationship
  integrity matters;
- add per-category fault injection proving capture failure cannot produce a
  success-labelled backup.

### P1 — BUG-KEYWORD-01 — Require an authoritative automatic-keyword baseline

**State:** Open

**Failure path:** automatic-keyword synchronization passes only a
`List<ResultItem>` into `AutomaticKeywordRuleEngine`. In `recordBaseline()`, an
empty list performs no per-video work, leaves `baselineCanComplete` true, and can
therefore call `completeScheduledSyncIfCurrent()`. Other discovery paths have
the same fundamental problem: completeness is not represented independently
from item count.

**Why this is a defect:** an empty result caused by an incomplete/transient
extractor response can be recorded as a completed baseline. A later complete
fetch can then classify old playlist members as newly discovered content when
apply-existing is disabled.

Required result:

- carry extractor completeness/authority separately from the returned list;
- complete baseline state only from a complete authoritative snapshot,
  including an explicitly authoritative-empty result;
- leave incomplete/ambiguous empty fetches retryable without advancing baseline
  state;
- add empty-incomplete -> later-nonempty and authoritative-empty regressions.

### P1 — BUG-METADATA-01 — Prevent stale full-row metadata writes

**State:** Open

**Failure path:** this is not limited to one worker. In
`UpdateMultipleDownloadsDataWorker`, a `DownloadItem` is loaded, metadata
lookup can take substantial time, only the current `status` is reloaded, and the
resulting object is written back through a full-row update. `DownloadWorker`'s
`persistDownloadMetadata()` also enriches an existing `DownloadItem` snapshot
and later calls `dao.updateWithoutUpsert()` after checking only whether the row
is still `Active`. Neither path establishes that unrelated mutable columns still
match the snapshot used for enrichment.

**Why this is a defect:** configuration, scheduling, path, queue metadata, retry
state, or other fields changed concurrently can be replaced by stale values even
though metadata enrichment does not own those fields.

Required result:

- define one metadata-owned column update/merge contract used by all metadata
  writers, or add a row revision/CAS contract that rejects stale snapshots;
- preserve concurrent non-metadata edits instead of copying an old full row over
  them;
- add deterministic concurrency tests for both background batch enrichment and
  download-time enrichment, mutating non-metadata fields while lookup is in
  progress.

### P2 — BUG-KEYWORD-02 — Recompute derived RULE assignments on History Undo

**State:** Open

**Failure path:** record-only History deletion snapshots all keyword assignment
rows before deleting the History item. Undo calls
`HistoryKeywordAssignmentRepository.restoreHistory()`. For snapshot rows whose
source is `RULE`, restore checks only whether a rule with the same numeric ID
currently exists. It does not bind the snapshot to that rule's old revision,
condition, or keyword set.

**Why this is a defect:** if the rule is edited/deleted/recreated during the Undo
window, the same numeric rule ID can represent a different derivation. Restoring
the old RULE rows makes derived keyword state stale relative to the current rule.

Required result:

- restore user-owned/manual assignment state from the snapshot;
- recompute RULE-derived assignments from the current rule definition, or prove
  an immutable matching rule revision before restoring derived rows;
- add Undo tests covering rule edit, deletion/recreation, and changed keyword
  membership while the History row is absent.

### P2 — BUG-METADATA-02 — Validate source identity before applying download metadata

**State:** Open

**Failure path:** `ResultRepository.getSingleMetadataFromUrl()` validates a
fresh extractor result against the requested URL with trusted source identity.
`updateDownloadItem()`, however, can fetch through
`getSingleMetadataFromSource()` and apply that result without the same identity
check; the fresh fallback participating in cache-first lookup has the same gap.
`applyMetadata()` can then change title, author, playlist title, thumbnail,
duration, website, and publication date on the requested download row.

The previous task text incorrectly listed formats and subtitles as fields
modified by this path. `applyMetadata()` does not update those fields in the
checkpoint implementation.

**Why this is a defect:** if an extractor/fallback returns a different media
item, valid-looking metadata from that item can be persisted on the wrong
Download and later flow into History.

Required result:

- validate every fresh metadata candidate against the requested download source
  before applying or combining it with cached data;
- reject mismatched candidates in both fresh-first and cache-first paths;
- continue accepting canonical-equivalent identities and deliberately supported
  provider redirects;
- add accepted-equivalent and unrelated-result regressions for every lookup
  order.

### P2 — BUG-DATE-01 — Preserve extractor failure instead of ambiguous NO_DATE

**State:** Open

**Failure path:** `HistoryDateResolutionEngine.resolve()` catches a non-
cancellation exception from `minimalLookup()` and converts it to `null`, then
tries `compatibilityLookup()`. If compatibility lookup also yields no date
without throwing, the resolver returns `HistoryDateLookupOrigin.NONE`.
`HistoryDateFetchRepository.checkpointSourceGroup()` persists a non-present date
with origin `NONE` as `NO_DATE`. The earlier extractor failure is therefore lost
from the durable outcome.

**Why this is a defect:** `NO_DATE` reads like a successful determination that
no publication date exists, while this path can mean that lookup failed and the
fallback produced ambiguous empty output.

Required result:

- preserve success/completeness/failure state for each lookup attempt instead
  of reducing failed attempts to nullable dates;
- persist `NO_DATE` only after a successful authoritative lookup establishes
  date absence;
- keep extractor failure/ambiguous empty output retryable or explicitly failed;
- add minimal-failure + compatibility-empty/ignored-error regressions.

### P2 — BUG-DATE-02 — Do not mark a date-fetch operation COMPLETED with failed items

**State:** Open

**Failure path:** child ledgers can enter `HistoryDateFetchItemState.FAILED`.
`HistoryDateFetchRepository.finalizeWorkerRun()` nevertheless marks the parent
`COMPLETED` whenever the pending count reaches zero and cancellation was not
requested; it does not check failed-child count. The worker can then emit a
terminal completed notification and return `Result.success()`.

There is a second consequence: child lookup failures that have already been
converted into terminal `FAILED` item outcomes do not escape as coordinator
exceptions. Because the worker still succeeds, WorkManager does not
explicitly retry those failed items even when the underlying lookup failure was
transient.

**Why this is a defect:** parent state, notification state, and retry behavior
can all claim completion while part or all of the requested backfill failed.

Required result:

- derive parent terminal state from the complete child-outcome distribution;
- represent mixed/all-failed terminal outcomes according to an explicit
  partial/failure contract;
- keep retryable child failures unresolved while bounded retry budget remains
  and return `Result.retry()` when that contract requires another attempt;
- make terminal UI/notification counts expose failures;
- add mixed success/failure, all-failed, retryable-failure, and exhausted-retry
  tests.

### P2 — BUG-BACKUP-02 — Use collision-free custom-thumbnail paths during restore

**State:** Open

**Failure path:** custom thumbnails are written before destination History IDs
are allocated. The path is derived from the backup-local ID as
`restored_<oldHistoryId>.<extension>`, and `writeBytes()` overwrites an existing
file at that location. The later `importedHistoryIdMap` cannot repair a file
that was already overwritten.

**Why this is a defect:** two independent backups can legitimately contain
different History records with the same source-local numeric ID. Restoring the
second backup in merge mode can overwrite the thumbnail file still referenced
by a record restored from the first backup.

Required result:

- allocate thumbnail storage using the new History identity or a collision-
  resistant generated token;
- never overwrite a path referenced by another live History row;
- remove newly staged thumbnail artifacts if their History restore fails;
- test same-old-ID independent backups, repeated merge restore, differing
  extensions/content, and reset restore.

### P2 — BUG-BACKUP-05 — Include paused downloads in backup and restore

**State:** Open

**Failure path:** `Paused` is a persistent `DownloadRepository.Status`, is
queried separately from queued rows, and is displayed with active download
state. App-data backup/restore has categories and model fields for queued,
scheduled, cancelled, errored, and saved downloads, but no paused category.
`backupQueuedDownloads()` reads queued/waiting rows and does not implicitly
capture paused rows.

**Why this is a defect:** an all-category backup can report success while
omitting paused jobs, so restoring that backup cannot reconstruct the queue
state the user intentionally preserved.

Required result:

- include paused rows in the versioned backup schema and all-category selection;
- restore them under an explicit contract that does not unexpectedly auto-start
  them;
- preserve their configuration, retry/operation metadata, and safe queue order;
- define compatibility with older backup versions lacking paused data and add
  paused+queued+scheduled round-trip coverage.

### P2 — BUG-BACKUP-06 — Remap or reject every imported numeric reference

**State:** Open

**Failure path:** restore correctly remaps several newly allocated table IDs but
not every persisted reference to them. Youtuber-group members and relations use
`youtuberGroupIdMap`, while `historyVisibleChildYoutuberGroups` is written back
from old backup IDs. Generic settings backup can also contain ID-bearing
preferences. For `LEGACY_OBSERVE_SOURCE` keyword assignments, restore can retain
the old `sourceId` when no Observe Source mapping is available instead of
proving that the live ID represents the same source.

**Why this is a defect:** numeric PKs are database-local. In merge restore an old
ID can point to a different live entity; in reset restore it can point nowhere.
The result is visibility/provenance state bound to the wrong identity.

Required result:

- inventory every ID-bearing backup field/preference and assign a remap or
  validation rule;
- remap Youtuber-group preference IDs through the same destination map used for
  rows/relations;
- require mapped or stable-source-validated Observe Source identity for legacy
  assignments, otherwise drop/quarantine the stale derived attribution;
- add merge/reset collision tests for group/source IDs.

### P2 — BUG-BACKUP-07 — Preserve playlists and playlist groups in app-data backup

**State:** Open

**Failure path:** Room persists `Playlist`, `PlaylistItemCrossRef`,
`PlaylistGroup`, and `PlaylistGroupMember`, but the backup category list,
`RestoreAppDataItem`, serializers, parser, and restore sequence contain no
corresponding playlist/playlist-group payload. History is restored with newly
allocated IDs without recreating those relationship tables.

**Why this is a defect:** an all-category backup cannot reconstruct user-created
playlists, History-to-playlist membership, or playlist grouping even though the
backup reports success.

Required result:

- add playlist rows, History cross-references, playlist groups, and group
  memberships to the versioned backup format/default selection;
- remap both History and playlist IDs before inserting relationship rows;
- define stable merge semantics for same-name or otherwise colliding entities
  rather than falling back to numeric IDs;
- add reset/merge round trips with multi-playlist History membership and
  multi-group playlist membership.

### P2 — BUG-BACKUP-08 — Restore every supported SharedPreferences value type

**State:** Open

**Failure path:** `BackupSettingsUtil.backupSettings()` serializes the values it
backs up from `SharedPreferences.all` and records each runtime type; it does
explicitly exclude keys such as `app_language`, so the previous wording that it
backs up literally every preference was too broad. Among the included values,
`Long` and `Float` are serialized successfully. `restoreData()` handles
`String`, `Boolean`, `Int`, and string-set values but has no corresponding
`Long`/`Float` restore branch, so those values are silently not applied.

The checkpoint uses real missing-type values: player playback-position cache
entries are stored with `putLong`, and subtitle text size, hold speed, and speed
presets use `putFloat`.

**Why this is a defect:** reset restore clears preferences and cannot reconstruct
those values; merge restore can silently keep destination-local values instead
of applying the backup, while both operations can still report success.

Required result:

- restore `Long` and `Float` through their matching SharedPreferences editor
  methods and reject/diagnose unknown serialized types;
- explicitly decide whether transient ID-keyed values such as playback-position
  cache belong in portable settings backup; if retained, remap/validate their
  referenced History identity separately;
- preserve all supported value types losslessly and version the encoding if the
  schema changes;
- add String/Boolean/Int/Long/Float/StringSet reset+merge round trips and
  malformed/unknown-type coverage.

### P2 — BUG-DUPLICATE-01 — Canonicalize media identity for config duplicate checks

**State:** Open

**Failure path:** `DownloadConfigurationDuplicatePolicy` includes the raw URL in
`RequestConfiguration` equality. Equivalent YouTube forms such as youtu.be,
watch, mobile, or music URLs can therefore represent the same media but compare
as different configurations. The queue/history duplicate path also constructs
canonical-equivalent History candidates but performs command/config matching
against the exact-URL History list in the relevant branch.

**Why this is a defect:** URL spelling becomes part of duplicate identity, so a
canonical-equivalent request can bypass duplicate prevention and create another
download/storage copy despite otherwise identical request settings.

Required result:

- separate canonical media identity from request-changing configuration fields;
- treat supported equivalent URLs for one media ID as the same source while
  preserving distinct media IDs;
- use canonical-equivalent History candidates consistently for config duplicate
  checks;
- test active queue and History against youtu.be/watch/mobile/music variants.

### P2 — BUG-CLEANUP-01 — Make automatic leftover cleanup actually recurring

**State:** Open

**Failure path:** selecting daily/weekly/monthly creates one delayed
`OneTimeWorkRequest<CleanUpLeftoverDownloads>`. The worker performs cleanup and
returns success without scheduling another run. Enabling/changing the setting
also uses `System.currentTimeMillis().toString()` as the unique-work name, so a
new cadence does not replace the previous logical cleanup schedule.

**Why this is a defect:** the UI promises a cadence but execution stops after one
run, and repeated preference changes can leave multiple delayed cleanup requests
instead of one current schedule.

Required result:

- use a recurring WorkManager contract or explicitly schedule the next one-time
  execution after each run;
- use one stable unique-work identity and replace/cancel it when cadence changes
  or cleanup is disabled;
- test repeated cadence execution, preference changes, and restart recovery with
  exactly one active logical schedule.

### P2 — BUG-CLEANUP-02 — Prevent leftover cleanup from deleting live download temp files

**State:** Open

**Failure path:** `CleanUpLeftoverDownloads` reads
`DownloadRepository.getActiveDownloadsCount()` once and, when that snapshot is
zero, immediately calls `AppCacheManager.delete(DOWNLOAD_TEMP)`. The count
covers `Active` and `PostProcessing`, but there is no shared ownership barrier
between this cleanup worker and `DownloadWorker`. A queued download can be
selected concurrently after the zero-count snapshot: `DownloadWorker` marks the
row `Active` and uses `File(FileUtil.getCachePath(context), downloadItem.id)` as
its live temp directory. `AppCacheManager` then enumerates and deletes entries
under that same download-temp root. If the new worker creates or starts writing
its per-download directory before cleanup enumeration/deletion reaches it, the
cleanup treats live artifacts as leftovers and removes them.

**Why this is a defect:** a production-scheduled cleanup can delete temporary
media belonging to a download that became active after the stale count check,
causing extraction/post-processing failure or destroying otherwise recoverable
partial output. The cleanup contract is intended to remove leftovers, not files
owned by live work.

Required result:

- coordinate cleanup and download startup with an ownership/serialization
  barrier so no new live temp owner can appear after cleanup authorization and
  before destructive deletion completes;
- preserve both `Active` and `PostProcessing` temp ownership at the destructive
  boundary rather than relying on one earlier aggregate count;
- if cleanup is made per-directory, prove each directory is unowned immediately
  before deletion under a protocol that prevents ownership from being acquired
  until that deletion commits;
- add a deterministic race where a queued row becomes `Active` after the cleanup
  zero-count check but before temp enumeration/deletion, plus a
  `PostProcessing` ownership regression, proving live temp files are preserved.

### P2 — BUG-CACHE-01 — Do not move live download temp files during cache migration

**State:** Open

**Failure path:** the production Folder settings screen exposes
`move_temporary_files` and its click handler immediately enqueues
`MoveCacheFilesWorker`; unlike clear-cache and default-video-folder migration,
this path does not call `hasActiveDownloads()` or otherwise establish exclusive
cache ownership. `MoveCacheFilesWorker` walks the entire
`FileUtil.getCachePath(context)` tree and moves every file it encounters into
public `Downloads/YTDLnisx/CACHE_IMPORT`. `DownloadWorker` concurrently uses a
per-download child of that exact cache root as its live yt-dlp/post-processing
temp directory. A user can therefore start cache migration while a download is
active and the migration can move files out from underneath the running worker.

**Why this is a defect:** a maintenance action can steal live temp artifacts
from an in-progress download, causing extraction/post-processing failure and
moving partial/intermediate media into a user-visible recovery directory as if
it were leftover cache. The worker reports success after traversing the cache
without proving that the files were unowned.

Required result:

- gate cache migration on an authoritative live-work ownership contract that
  covers `Active` and `PostProcessing` downloads and prevents new cache owners
  from starting until migration completes;
- preferably migrate only explicitly unowned per-download cache directories
  instead of walking the whole shared cache tree;
- preserve unrelated/nested cache categories according to the same ownership
  policy used by `AppCacheManager` rather than assuming every entry is movable;
- add deterministic active-download and post-processing races proving live temp
  files are never moved, plus an idle-leftover regression proving intended cache
  recovery still works.

### P2 — BUG-LOCALADD-01 — Do not treat a bare filename stem as local-file identity

**State:** Open

**Failure path:** `LocalAddWorker` first checks stronger tree/document/path
identities but also builds `existingBaseNames` from History and silently skips a
candidate whose extensionless basename is already present. After an item is
actually accepted into History, its basename is added to this set, so a later
distinct same-name file in the same batch can also be skipped.

The previous wording was too absolute: two same-name files selected in one batch
are **not always** collapsed by this mechanism. If the earlier candidate remains
unresolved/pending and is not accepted into History, its basename is not
necessarily added to `existingBaseNames`.

**Why this is a defect:** basename alone is not file identity. Distinct files in
different directories/providers can legitimately share a name and can be
silently omitted without a conflict prompt.

Required result:

- use persisted tree/document/URI identity as the primary duplicate key;
- never discard a distinct file solely because another History or already-
  accepted batch item has the same basename;
- if heuristic duplicate detection is desired, require stronger evidence such
  as size/duration/hash and surface ambiguity to the user;
- test same-name files already in History, same-name accepted earlier in one
  batch, and same-name unresolved candidates.

### P2 — BUG-HISTORY-01 — Preserve playlist membership across History delete and Undo

**State:** Open

**Failure path:** `HistoryRepository.deleteRecords()` calls
`playlistDao.deletePlaylistItemsByHistoryIds()` and then
`historyDao.deleteWithIds()` as separate DAO operations; the repository method
itself is not a Room transaction covering both. The record-only single-item Undo
path snapshots the `HistoryItem` and keyword assignments, deletes the record,
and later restores only the History row and assignment snapshot. Playlist
cross-references are neither snapshotted nor restored.

**Why this is a defect:** successful Undo permanently loses playlist membership.
An exception between relationship deletion and History deletion can also leave
the History row present while its playlist relationships are already gone.

Required result:

- snapshot all playlist cross-references required for Undo;
- delete playlist relationships and History rows in one Room transaction;
- restore History, keyword assignments, and playlist membership under one
  consistent restore contract;
- route bulk/file-backed deletion through the same atomic relationship-removal
  primitive;
- test multi-playlist Undo and an injected failure between relationship and
  History mutation.

### P2 — BUG-PLAYER-01 — Serialize playback-position persistence by History item

**State:** Open

**Failure path:** `VideoPlayerActivity.savePlaybackPositionForHistoryId()`
updates in-memory queue/cache state immediately, then launches a new
`lifecycleScope.launch(Dispatchers.IO)` for each
`historyDao.updatePlaybackPosition(historyId, positionMs)`. Playback position is
saved from multiple lifecycle, transition, seek, and completion paths. The DAO
update has no sequence/revision guard, and the independent IO coroutines are not
serialized per History ID.

**Why this is a defect:** database commits can occur out of call order. A newer
completion reset to `0` can be overwritten by an earlier nonzero save that
finishes later; rapid transitions can similarly persist an older position than
the latest state. SharedPreferences cache and Room can then disagree.

Required result:

- serialize durable writes per History ID or attach a monotonically increasing
  revision/timestamp that rejects stale writes;
- ensure completion reset cannot be superseded by an older launched write;
- keep cache and Room under one ordering contract;
- add deterministic delayed-write tests for completion, seek, pause/stop,
  destruction, and queue transitions.

### P2 — BUG-TERMINAL-01 — Do not reclassify completed terminal output as failure

**State:** Open

**Failure path:** the production terminal UI schedules `TerminalDownloadWorker`
through `TerminalViewModel`. After yt-dlp returns successfully, the worker either
already has output in the user-selected destination (`noCache`) or synchronously
moves the app-cache output there with `FileUtil.moveFile()`. The output mutation
is therefore complete before the remaining log/notification cleanup runs. The
same outer `try` then calls `TerminalDao.updateLog()` directly, cancels the
notification, delays, and deletes the terminal row. If one of those post-output
operations throws, the broad outer `catch` handles it as a download failure,
shows the failure path, removes the terminal task, and returns
`Result.failure()` even though the requested output was already committed.

`LogRepository.update()` itself is best-effort, but the direct
`TerminalDao.updateLog()` after output completion is not wrapped and remains a
concrete throwing persistence step on this path.

**Why this is a defect:** non-authoritative bookkeeping after successful output
creation can change the semantic result from success to failure. The user can be
told that a completed terminal download failed and may retry it, producing a
duplicate output while the original file remains in the destination.

Required result:

- establish an authoritative completion boundary once destination output has
  been successfully produced/moved;
- make logging, notification cancellation, and terminal-row cleanup after that
  boundary best-effort or persist their failures separately without flipping the
  completed download outcome;
- preserve a recoverable terminal-row/cleanup state if required bookkeeping
  cannot finish, rather than representing the media transfer itself as failed;
- add fault-injection regressions for terminal-log persistence and post-success
  notification/row cleanup after both direct-destination and cache-move output,
  proving committed output remains a success and does not invite duplicate
  retry.

### P2 — BUG-FORMAT-01 — Do not commit or report partial bulk format refresh as success

**State:** Open

**Failure path:** the production bulk-format flow enters through
`DownloadViewModel.continueUpdatingFormatsOnBackground()`, which moves the
current `Processing` rows to `Saved` and enqueues
`UpdateMultipleDownloadsFormatsWorker`. For each requested row whose
`allFormats` is empty, the worker wraps format extraction, format selection,
`ResultItem` persistence, and `DownloadItem` persistence in one `runCatching`
but ignores the resulting `Result`. It writes the cached Result row first and
the Download row second. An extractor or database exception is therefore
silently swallowed; if the Result write succeeds and the Download write fails,
the two persistent representations can also diverge. The worker still increments
its completed count, continues the batch, returns `Result.success()`, and its
`finally` path calls `showFormatsUpdatedNotification()` for the requested IDs.

**Why this is a defect:** a failed or partially persisted format refresh is
collapsed into the same terminal state and success notification as a completed
refresh. Users can be told that formats were updated while the Saved download
still has missing/stale format data, and a mid-persistence failure can leave the
Result cache disagreeing with the Download row.

Required result:

- preserve a typed per-item success/failure outcome instead of discarding the
  `runCatching` result, and rethrow coroutine cancellation;
- make Result/Download format persistence atomic or define a compensating
  consistency contract so a failed second write cannot leave a success-labelled
  split state;
- represent mixed/all-failed batches explicitly and make WorkManager result and
  user notification reflect those failures rather than always announcing
  success;
- add fault-injection regressions for format extraction, Result-row write,
  Download-row write, cancellation, and mixed-success batches.

### P2 — BUG-TERMINATE-01 — Requeue active work before no-confirmation app termination

**State:** Open

**Failure path:** the navigation-drawer terminate action has two production
paths. While `ask_terminate_app` is true, the confirmation branch loads both
`Active` and `PostProcessing` rows, rewrites them to `Queued`, persists those
changes, and only then calls `exitProcess(0)`. If the user checks the dialog's
"do not show again" option, that preference becomes false. On every later
terminate action the no-confirmation branch calls `finishAndRemoveTask()` and
`exitProcess(0)` immediately without performing the same durable requeue.

`DownloadWorker` normally repairs interrupted `Active`/`PostProcessing` rows in
`cleanupStoppedWorker()`, but that repair lives in the worker's `finally` block
and is conditioned on `isStopped`. A direct process exit is not a durable
shutdown protocol and cannot be relied on to execute coroutine/finally cleanup.
On a subsequent worker start, persisted `Active` rows are explicitly loaded into
`runningYTDLInstances` and counted as already-running work before candidate
selection. After the old process has been killed there is no corresponding live
yt-dlp owner, so such stale `Active` rows can consume concurrency slots and keep
the queue from restarting them; stale `PostProcessing` rows can likewise remain
represented as live state without their previous post-processing owner.

**Why this is a defect:** choosing "do not show again" changes not only the
confirmation UI but the state-preservation semantics of app termination. A
normal user-facing terminate action can leave durable download rows in
`Active`/`PostProcessing` even though their process was killed, producing ghost
activity and potentially blocking queued work after restart.

Required result:

- route both terminate branches through one durable shutdown operation that
  requeues or otherwise terminalizes every app-owned `Active` and
  `PostProcessing` row before process exit;
- do not rely on WorkManager/coroutine cancellation callbacks after
  `exitProcess()` for persistent-state repair;
- on startup or worker acquisition, reconcile stale live-status rows against
  actual worker/process ownership so a prior abrupt process death cannot strand
  the queue;
- add regressions for first confirmed termination, the subsequent
  no-confirmation path, process death between UI shutdown steps, and restart
  with stale `Active`/`PostProcessing` rows.

### P3 — BUG-QUEUE-01 — Keep membership-waiting selections out of queue reorder actions

**State:** Open

**Failure path:** the queued UI displays/selects both `Queued` and
`WaitingForMembership` rows. `QueuedDownloadAdapter` disables drag and per-item
move controls for waiting rows. Multi-select, select-all, and inverted selection
can still include those rows, and contextual Move Up/Move Down passes the full
selected ID set to reorder operations. `DownloadDao` constructs/re-writes queue
order from rows whose status is exactly `Queued`, so selected waiting IDs are
silently excluded from the reorder.

**Why this is a defect:** the UI says the action applies to the visible selected
set while the data layer applies it to a smaller hidden subset, making the
result inconsistent with the user's selection.

Required result:

- disable/hide contextual reorder when selection contains a waiting row, or
  explicitly define and implement ordering that includes waiting rows;
- make direct, mixed, select-all, and inverted selection obey the same rule as
  drag/per-item controls;
- add focused membership-waiting selection/reorder regressions.

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
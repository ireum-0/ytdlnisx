# Improvement Task Status

This file reconciles the older improvement plan with the implementation
audited on 2026-07-30. It is a status record, not permission to implement
additional work.

The active correctness defects below were revalidated against
`checkpoint/pre-baseline-review@73d3836665f5f2e6e232e327eef1d968054d0539`
on 2026-08-20. This defect list intentionally excludes repository settings,
quality-gate/process configuration, and documentation-only drift.

There are **63 active correctness defects** in this checkpoint. The previous
`BUG-BACKUP-09` entry was removed during revalidation because the user-facing
restore parser explicitly resets `CookieItem`, `CommandTemplate`, and
`TemplateShortcut` primary keys to `0L` before calling `restoreData()`. The
previous claim that merge restore passes backed-up primary keys directly into
the repositories therefore does not describe the production restore path.
Defense-in-depth normalization at the `restoreData()` boundary may still be a
hardening improvement, but it is not an active correctness defect at this
checkpoint.

The broader 63-defect registry is intentionally retained here. The separate
correctness-remediation Master Plan governs the F1→F22 execution order and may
use a narrower baseline inventory. Remediation-discovered follow-ups recorded
below do **not** change the 63-defect count unless they are explicitly promoted
into this active registry.

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

## Current correctness-remediation overlay

This overlay records the latest reviewed F1 state without replacing the broader
63-defect registry below.

- Current remediation item: **F1 — `BUG-BACKUP-01`**.
- Authorized review HEAD for the current Finding A review:
  `66c1db3c7315ead73c585b9d18a229a36a275d22`.
- Finding A status: **Partial / NOT CLEAN**.
- The previous P2 where the first SourceMismatch / TypeMismatch terminal
  `dao.update(...)` failure was swallowed is corrected by
  `fa1156be49d8ca6527948d1d7cffe0ad4f98cd18`: the authoritative mismatch now
  survives recovery and an unrecoverable mismatch-persistence failure is not
  silently converted into handled success for that item.
- Current open Finding A blocker: **P2 — when one item suffers both the first
  mismatch terminal-write failure and the mismatch-preserving recovery-write
  failure, the resulting exception can escape that per-item `launch` inside the
  shared worker `coroutineScope`, cancel sibling downloads, and leave an
  unrelated sibling durably `Active`/`PostProcessing` without a live owner.**
- The required correction must either isolate this per-item terminal failure or
  durably clean/requeue affected sibling rows. It must preserve the exact
  authoritative mismatch for the failing item and must not confuse sibling
  failure isolation with user/worker cancellation.
- Add focused worker-level fault coverage with `concurrent_downloads >= 2` where
  item A suffers first-write + recovery-write failure and item B is proven not
  to remain a ghost `Active`/`PostProcessing` row.
- Finding B remains confirmed but **must not start until Finding A closes**:
  source-less `DownloadType.command` History redownload requires stable opaque target
  identity and must never fall back to numeric History ID authority.
- Focused Gradle verification remains **ATTEMPTED, NOT COMPLETED**, not PASS.
- Review closure uses `remediation-review-checklist-v3.md`, including the mandatory
  terminal fault matrix: first terminal DB write failure, linked-ledger failure,
  notification/logging failure, recovery-write failure, process-death window, and
  final worker/result consistency must all be reviewed before P0/P1/P2 CLEAN.

### F1 remediation-discovered follow-up not counted in the 63 active defects

#### REMEDIATION-FOLLOWUP-DOWNLOAD-TERMINAL-RECOVERY-01

**State:** Discovered  
**Severity:** P2 candidate  
**Ownership:** cross-cutting Download terminal repository recovery  
**Current Finding A impact:** pre-existing / non-blocking for the current mismatch fix

This follow-up is **generic terminal recovery**, not only the authoritative
`TargetMissing` branch.

For authoritative `TargetMissing`, the target-deleted terminal repository operation can
fail. The fallback then attempts to preserve the Download as Error. If that fallback
`dao.update(...)` also fails, the persistence exception can be logged and swallowed before
the branch exits, leaving stale running state possible. Even when the fallback Download
Error write succeeds, later reconciliation can reconstruct a still-nonterminal linked
low-quality child as generic `FAILED` instead of preserving the intended
`SKIPPED / HISTORY_TARGET_DELETED` disposition.

For `Authorized + cleanup incomplete/failed`, the normal failure branch similarly relies
on a first terminal Download Error write followed by generic outer recovery. If the first
terminal write fails and that recovery write also fails **without an already-established
SourceMismatch/TypeMismatch carrier**, the recovery failure can still be logged/handled in
a way that permits a durable `Active`/`PostProcessing` row rather than an honest terminal
worker outcome. This structure predates the narrow mismatch fix and is not attributed to
`fa1156be`.

Required eventual result:

- preserve every authoritative terminal disposition through repository/recovery failure;
- do not swallow fallback or recovery terminal persistence failure;
- prevent stale `Active`/`PostProcessing` + handled completion;
- preserve linked child/parent target-deleted semantics during recovery/restart;
- cover `TargetMissing` and `Authorized + cleanup incomplete/failed` with direct
  first-write and recovery-write fault injection.

Existing broader-registry entries already own several other F1-adjacent findings and
must not be duplicated as new active defects here: `BUG-HISTORY-03` owns the post-commit
History reclassification issue (including cancellation after the authoritative History
commit), `BUG-HISTORY-02` owns retained-reference deletion TOCTOU, and
`BUG-LOWQUALITY-01` owns Download/low-quality terminal atomicity (including process-death
loss of the exact mismatch reason). Other F1 P3 and verification follow-ups remain tracked
in the remediation follow-up ledger.

## Active correctness defects

### P0 — BUG-BACKUP-01 — Remap History replacement markers during restore

**State:** Partial / NOT CLEAN

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

**Current remediation status:** the core marker remap, transactional current-target
authorization, strict source/type mismatch handling, exact authorized snapshots, and typed
quality-cleanup policy are implemented through the authorized review HEAD above. Finding A
is still blocked by the sibling-cancellation/ghost-running-row P2 described in the overlay.
Finding B is confirmed next but has not started. Do not mark this item clean until the full
Finding A v3 terminal fault matrix is closed and Finding B is separately
implemented/reviewed.

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

### P1 — BUG-TERMINAL-02 — Separate terminal task identity from Download identity

**State:** Open

**Failure path:** the production terminal UI inserts `TerminalItem` rows into the
independent `terminalDownloads` table, whose primary key is auto-generated.
Normal downloads use a separate auto-generated primary key in the `downloads`
table. `TerminalDownloadWorker` nevertheless uses the bare terminal row ID as
its yt-dlp process ID and foreground/running notification ID. Its progress
notification also builds the Cancel action with
`CancelDownloadNotificationReceiver`, passing that terminal ID as `itemID`.
That receiver interprets `itemID` as a normal Download ID: it calls
`DownloadRepository.cancelByUser(id)`, refreshes any linked low-quality ledger,
destroys the yt-dlp process with the same bare ID, cancels Download
post-processing, cancels the untagged notification, and finally deletes a
Terminal row with that number. `DownloadWorker` independently uses the bare
Download row ID for its own running notification and yt-dlp process identity.

Because the two tables allocate IDs independently, a live Terminal task and an
unrelated normal Download can legitimately have the same numeric ID. In that
case starting/updating the two jobs aliases their yt-dlp/notification identity,
and pressing Cancel on the Terminal notification can durably change the
unrelated Download to `Cancelled`, terminalize its linked low-quality child, and
kill its process/post-processing. Cleanup or notification cancellation from
either path can also remove the other live task's notification. No production
parser or validation layer namespaces or rejects the collision.

**Why this is a defect:** a user action addressed to one Terminal task can mutate
the persistent state and execution of an unrelated normal Download solely
because two independent database primary keys happen to be numerically equal.
This is cross-record ownership corruption rather than a notification-only
cosmetic collision, and can abort active work or falsely terminalize a
low-quality operation.

Required result:

- give Terminal and normal Download execution/process identities disjoint
  namespaces rather than deriving both from an unqualified numeric primary key;
- use distinct tagged/offset notification identities and a Terminal-specific
  cancel action that never calls normal `DownloadRepository.cancelByUser()` or
  Download post-processing cancellation;
- make every destroy/cancel/cleanup path target the same typed owner identity
  that created the process/work/notification;
- add deterministic same-numeric-ID regressions with both jobs live, covering
  terminal start, normal start, progress updates, Terminal Cancel, normal
  Cancel/Pause, cleanup, and a linked low-quality Download, proving neither
  workflow mutates or terminates the other.

### P2 — BUG-OBSERVE-02 — Preserve Observe Source runtime state across configuration edits

**State:** Open

**Failure path:** editing an existing Observe Source enters
`ObserveSourcesBottomSheetDialog` with the current `ObserveSourcesItem`. On Save,
the dialog does not update only the user-editable configuration fields. It builds
a new `ObserveSourcesItem` with the same primary key, explicitly sets
`runCount = 0`, conditionally copies the processed/ignored/retry/observed link
lists, and omits `runHistory`, `runInProgress`, and `currentRunStatus`, so those
fields take their constructor defaults. `ObserveSourcesViewModel.insertUpdate()`
then passes this reconstructed item to `ObserveSourcesRepository.update()`, whose
DAO uses a full-row `@Update`, and reschedules the observation.

`runCount` is not cosmetic. `ObserveSourceWorker.finishRunAndSchedule()` increments
it and stops a source with `endsAfterCount` only when the persisted count reaches
the configured limit. For example, a source configured to stop after 10 runs
with 8 already completed is reset to zero by editing an unrelated field and can
run up to 10 more times. The same edit also silently clears the user-visible
`runHistory`. The explicit Start action separately resets `runCount` to zero,
so ordinary configuration Save is not the only lifecycle transition available
to own restart semantics.

**Why this is a defect:** a normal configuration edit rewrites worker-owned
durable runtime/progress state that the edit does not semantically own. This
changes the source's termination contract and loses recorded run history even
when the user did not request a reset.

Required result:

- separate user-editable Observe Source configuration from worker-owned runtime
  state, using partial updates or a transactional merge against the current row;
- preserve `runCount`, `runHistory`, `runInProgress`, and `currentRunStatus`
  across ordinary edits unless a dedicated reset action explicitly owns them;
- make `resetProcessedLinks` affect only the documented processed/ignored/retry/
  observed membership state, not run-count/history state;
- ensure rescheduling uses the committed merged configuration without resetting
  prior execution progress;
- add regressions for editing name/cadence/template at nonzero `runCount`,
  `endsAfterCount` near its terminal boundary, preserved `runHistory`, explicit
  processed-link reset, and editing while a run is active.

### P2 — BUG-OBSERVE-03 — Recover active Observe Source schedules after enqueue loss

**State:** Open

**Failure path:** creating or editing an Observe Source enters
`ObserveSourcesViewModel.insertUpdate()`. For an existing source it first commits
the reconstructed source row, then `ObserveSourcesRepository.observeTask()`
cancels the current unique/tagged work and enqueues a replacement one-time
`ObserveSourceWorker`. For a new source the row is likewise inserted before its
first work request is enqueued. `observeTask()` discards the `Operation` returned
by `enqueueUniqueWork()`, so asynchronous enqueue failure is not observed; an
edit can additionally remove the previously valid schedule before the
replacement enqueue is durably accepted.

The recurring handoff has the same split boundary. At the end of a normal or
recovered run, `ObserveSourceWorker.finishRunAndSchedule()` first persists the
updated `runHistory`, `runCount`, `runInProgress = false`, current status, and any
STOPPED decision. If the source remains active it then builds a new one-time work
request, calls `enqueueUniqueWork(..., REPLACE, ...)`, ignores its returned
`Operation`, and immediately returns `Result.success()`. An enqueue failure or
process death after the source-state commit but before the successor WorkManager
record is durable therefore leaves the source persisted as `ACTIVE` with no
future execution owner.

Normal app startup does not rebuild this missing schedule. `App.onCreate()`
reconciles automatic-keyword observation coverage, low-quality re-downloads, and
History date-fetch operations, but has no reconciliation that enumerates every
active USER Observe Source and proves a corresponding WorkManager chain exists.
`AutomaticKeywordObservationCoverage.reconcile()` is not such a repair: it uses
USER sources only to decide whether managed discovery coverage is necessary,
and for an already-`ACTIVE` managed source it also does not call `observeTask()`
merely because its WorkManager record is absent. A lost handoff can therefore
remain silent across process/app restart until the user explicitly edits or
restarts the source.

**Why this is a defect:** `ACTIVE` is durable user intent for recurring
observation, while each one-time WorkManager request is only the current carrier
of that intent. The implementation can commit the former while losing the latter
and then report the current worker as successful. Observation can silently stop
forever even though the source still appears active, causing new uploads,
retry-missing processing, synchronization, or automatic-keyword discovery to be
missed. This is distinct from `BUG-OBSERVE-01`, which concerns destructive use of
non-authoritative source snapshots, and from `BUG-OBSERVE-02`, which concerns
runtime state being overwritten by configuration edits.

Required result:

- represent active Observe Source scheduling with a durable generation/outbox or
  equivalent contract and reconcile every active source to exactly one matching
  WorkManager execution after startup/restart;
- observe enqueue completion/failure rather than treating invocation as a
  completed handoff, and make `finishRunAndSchedule()` return retry/failure or a
  durable recoverable scheduling state when the successor was not accepted;
- replace an existing schedule under a protocol that does not cancel the last
  valid carrier before a replacement is durably established, or guarantees
  recovery from that intermediate state;
- bind replacement work to the committed source generation/configuration so
  stale work cannot overwrite or reschedule a newer edit;
- add deterministic initial-save, edit-replacement, end-of-run handoff,
  asynchronous enqueue-failure, and process-death tests, plus startup recovery
  for USER and managed sources and exactly-one-work regressions.

### P2 — BUG-OBSERVE-04 — Prevent stale Observe Source workers from overwriting newer edits

**State:** Open

**Failure path:** `ObserveSourceWorker.runSourceWork()` loads one full
`ObserveSourcesItem` snapshot near the start of a run and keeps mutating that
same object throughout extraction, membership processing, History/download
reconciliation, and final scheduling. Worker progress and completion are
persisted through `ObserveSourcesRepository.update(item)`, whose DAO sink is a
full-row `@Update`; there is no source revision, expected configuration, or
worker-generation predicate on those writes.

The production edit path can race directly with that stale snapshot.
`ObserveSourcesViewModel.insertUpdate()` persists the user's newly reconstructed
source row **before** it calls `observeTask()` to cancel/replace the previous
worker. Consequently an old worker can still be executing when the edit commit
becomes authoritative. If that worker subsequently reaches `updateRunStatus()`
or `finishRunAndSchedule()`, its full-row update writes the pre-edit values back
over the same source ID. Fields owned by the user edit, such as URL/name,
cadence, filters, templates, retry/sync options, termination settings, and other
configuration, can therefore be silently reverted along with runtime fields.
Cancellation of the old WorkManager request after the edit does not close the
race because cancellation is not the commit boundary for the preceding DB edit,
and no DAO CAS rejects a late stale write.

The stale worker can then calculate and enqueue its successor from the reverted
snapshot. A newer replacement worker scheduled by the edit and the stale
worker's later `enqueueUniqueWork(..., REPLACE, ...)` use the same unique work
name, so whichever handoff occurs last can also replace the execution carrier
for the newer configuration. The user can therefore save a visible edit, have
it durably overwritten by an older run, and continue observing under the old
configuration without another explicit action.

**Why this is a defect:** runtime progress ownership and configuration ownership
are not separated. A worker that was authorized under an earlier source snapshot
can overwrite a later authoritative user edit and can reschedule work using that
obsolete configuration. This is a lost-update/data-integrity race distinct from
`BUG-OBSERVE-02`, where the edit itself resets runtime fields, and from
`BUG-OBSERVE-03`, where a valid durable source intent loses its WorkManager
carrier.

Required result:

- add a monotonically increasing source revision/generation and bind each worker
  request to the revision it loaded;
- make worker-owned persistence update only runtime/progress fields, or use a
  revision-checked transaction/CAS that rejects stale workers without rewriting
  user-owned configuration;
- make edits increment the generation before cancelling/replacing work so every
  older worker is durably invalidated at the same authoritative boundary as the
  new configuration;
- prevent a stale worker from enqueueing/replacing a successor after its
  generation has been superseded;
- add deterministic edit-vs-worker races at initial status write, source fetch,
  download/History processing, final full-row update, and successor enqueue,
  proving the edited configuration remains durable and exactly one current-
  generation worker survives.

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

### P2 — BUG-KEYWORD-03 — Recover queued automatic-keyword sync after enqueue failure

**State:** Open

**Failure path:** the production automatic-keyword editor calls
`AutomaticKeywordRuleRepository.save()`. The repository first commits the rule
revision, keyword rows, and affected existing RULE assignments in a Room
transaction. When the enabled rule needs an initial/baseline/apply-existing
sync, it then persists `manualSyncStatus = QUEUED` and only afterward calls
`AutomaticKeywordRuleScheduler.enqueue()`. The user-facing Sync Now path has the
same split boundary: `requestApplyExistingSync()` first increments the durable
rule revision, sets `pendingApplyToExisting = 1`, and stores
`manualSyncStatus = QUEUED`, then `syncNow()` calls the scheduler.

`AutomaticKeywordRuleScheduler.enqueue()` calls
`WorkManager.enqueueUniqueWork(...)` and discards the returned `Operation`.
WorkManager exposes that `Operation` specifically so callers can observe when
enqueue has completed; an enqueue failure reported through it therefore does not
become a repository failure here. A process death between the rule/QUEUED commit
and durable WorkManager insertion creates the same state. On the next app start,
startup reconciliation restores automatic-keyword **observation coverage**,
low-quality re-download state, and History date-fetch state, but it does not scan
rules whose `manualSyncStatus` is `QUEUED` or whose
`pendingApplyToExisting` flag still requires a manual/baseline sync.
`AutomaticKeywordObservationCoverage.reconcile()` only creates, removes, or
restarts discovery-only Observe Source rows; it never re-enqueues
`AutomaticKeywordRuleSyncWorker` for orphaned rule-sync intent.

The result can therefore remain durably `QUEUED` with no matching WorkManager
work. A new rule may never establish its baseline, and an Apply Existing request
may never apply the rule to existing History until some later explicit edit,
toggle, or Sync Now action happens to enqueue fresh work. Because the enqueue
`Operation` is ignored, the normal editor can also dismiss after a save whose
rule transaction committed even though the associated sync enqueue later fails.

**Why this is a defect:** the database state is the durable source of truth for
an explicitly requested synchronization, but its queued/pending intent can
outlive the only executor record capable of carrying it out. The UI can therefore
show a synchronization as queued indefinitely while the requested baseline or
existing-History mutation never occurs. This is distinct from
`BUG-KEYWORD-01`, which concerns whether an executed fetch is authoritative, and
from `BUG-KEYWORD-02`, which concerns restoring stale derived assignments on
History Undo.

Required result:

- represent rule-sync intent with a durable generation/outbox or equivalent
  recoverable contract and reconcile every nonterminal queued/pending request to
  exactly one WorkManager execution after restart;
- observe enqueue completion/failure, or otherwise rely on durable
  reconciliation, so a rule save or Sync Now action is not reported as fully
  queued when no work was durably accepted;
- if the rule mutation has already committed but scheduling fails, preserve that
  distinction instead of implying that the rule save itself was uncommitted;
- bind worker execution to the intended rule revision/generation so stale
  replaced work cannot consume or clear a newer sync request;
- add deterministic enqueue-failure and process-death tests after the rule or
  `requestApplyExistingSync()` QUEUED commit but before WorkManager persistence,
  plus startup recovery, exactly-once replacement, revision-race, new-rule,
  edited-rule, and Apply Existing regressions.

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
file at that location. The later `importedHistoryIdMap` cannot repair a file that
was already overwritten.

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

### P2 — BUG-DUPLICATE-02 — Match download-archive entries by exact media identity

**State:** Open

**Failure path:** when duplicate prevention is set to `download_archive`,
`DownloadViewModel.detectAndMarkDuplicates()` reads each yt-dlp archive line,
keeps only the second whitespace-delimited token, and then decides that a queued
item is already downloaded when `item.url.contains(archiveId)` is true. This
both discards the archive entry's extractor/source component and uses substring
containment instead of an exact media-identity comparison. No upstream queue
normalization repairs that loss: the normal production queue path reaches this
branch with the request URL, and a match calls `markDuplicate()`, persists the
Download row as `Duplicate`, and removes it from the set passed to the download
worker. An archive entry such as `youtube abc123` can therefore block an
unrelated request whose path or query merely contains `abc123`, and the same ID
string from a different extractor can also collide.

**Why this is a defect:** the archive is intended to prevent re-downloading the
same archived media, but the current comparison can classify unrelated media as
the same item and suppress a requested download. The decision is persisted and
surfaced as a duplicate even though no authoritative source/media identity
matched.

Required result:

- parse archive entries as source/extractor plus exact media ID according to the
  yt-dlp archive identity contract instead of reducing them to bare substrings;
- resolve the queued request to an authoritative canonical media identity before
  comparing it with archive entries, while preserving deliberately supported
  equivalent URL forms;
- never treat an archive ID appearing only as a prefix, suffix, path fragment,
  or query substring of another request as proof of duplication;
- add queue-path regressions for an exact archived item, one ID contained in
  another, an unrelated query/path containing an archived ID, the same bare ID
  under different extractors, and canonical-equivalent YouTube URLs.

### P2 — BUG-DUPLICATE-03 — Preserve SAF authority for custom download-archive storage

**State:** Open

**Failure path:** the production Download settings screen lets the user choose a
custom download-archive folder with `ACTION_OPEN_DOCUMENT_TREE`, takes a
persistable read/write URI grant, and stores the returned `content://` tree URI
in `download_archive_path`. `FileUtil.getDownloadArchivePath()` does not keep
that provider-backed authority. It calls `formatPath()` on the stored tree URI,
turning it into a raw `/storage/.../` path, appends `download_archive.txt`, and
returns that raw path. `DownloadViewModel.detectAndMarkDuplicates()` then opens
the path with `java.io.File`; any read failure is swallowed into an empty archive.
`YTDLPUtil` independently passes the same raw path to yt-dlp through
`--download-archive`.

The app targets Android 36 and does not request broad all-files access. On
scoped-storage Android, a successful SAF tree grant authorizes provider/document
access; converting that URI to a raw shared-storage pathname does not transfer
the grant to direct `File` or native yt-dlp access. No production validation
proves that the derived raw archive file is readable and writable before the
setting is accepted or a queued download uses it.

**Why this is a defect:** a normal user can successfully choose and authorize a
custom archive folder while the duplicate precheck silently behaves as if the
archive were empty and yt-dlp receives a path it may not be able to read or
update. Duplicate prevention can therefore stop working without an honest
failure signal, and archive-enabled downloads can fail or stop recording future
archive entries despite the UI showing an authorized custom folder. This is
distinct from `BUG-DUPLICATE-02`, which concerns media-identity comparison after
archive content has been read successfully.

Required result:

- retain custom archive storage under a provider-aware URI contract, or keep an
  app-owned filesystem archive and synchronize it to/from the selected SAF tree
  through `ContentResolver`/`DocumentFile` under an explicit commit contract;
- never collapse archive access failure into an authoritative empty archive;
- pass yt-dlp only a filesystem archive path the app/native process can actually
  access, and surface/safely recover any failure to synchronize the authoritative
  archive state;
- validate read/write authority when configuring and before using a custom
  archive, while preserving the existing app-owned default path behavior;
- add scoped-storage regressions for a custom shared-storage tree, revoked grant,
  read-only/unwritable provider state, an exact duplicate already in the archive,
  archive-write failure after a successful download, and the default app-owned
  archive path.

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

### P2 — BUG-CACHE-02 — Preserve SAF authority for custom download cache storage

**State:** Open

**Failure path:** the production Folder settings screen lets the user choose the
custom download cache directory through `ACTION_OPEN_DOCUMENT_TREE`, takes a
persistable read/write URI grant, and stores that `content://` tree URI in
`cache_path`. The setting remains available on scoped-storage devices even when
broad all-files access is unavailable. `FileUtil.getCachePath()` then discards
that provider-backed identity by passing the stored URI through `formatPath()`,
which converts external-storage tree/document URIs into raw `/storage/.../`
paths.

Normal download execution consumes that converted path directly. After a queued
row is claimed, `DownloadWorker` constructs
`File(FileUtil.getCachePath(context), downloadItem.id.toString())` as the
operation's temporary directory. Before yt-dlp runs,
`executeYtdlpPhase()` calls `resetYtdlpTempDirectory()`, which again obtains the
raw cache root, validates the child with `canonicalFile`, recursively deletes an
old directory if present, and recreates it with `java.io.File.mkdirs()`. Later
retry, staged-quality, hard-sub, info-JSON, and output-recovery paths use the
same filesystem cache identity. There is no `DocumentFile`/`ContentResolver`
implementation for this live yt-dlp temp tree and no validation proving that the
raw path is directly writable after the SAF-only grant.

The app targets Android 36. On scoped-storage Android, the first authoritative
observation is only that the selected tree is writable through the document
provider; converting that tree URI to a filesystem-looking path does not transfer
that authority to `File` or the native yt-dlp process. A user can therefore
successfully authorize and persist a custom cache folder, then have the next
ordinary download fail while creating or using its temporary directory even
though the settings UI accepted the location.

**Why this is a defect:** a normal supported cache-directory selection loses the
very authority that made the selection writable before the core download path
uses it. This can make ordinary downloads, retries, staged quality checks, or
hard-sub work fail solely because a provider-authorized cache tree was
reinterpreted as directly writable raw storage. It is distinct from
`BUG-CACHE-01`, which concerns moving live cache files during maintenance, from
`BUG-DUPLICATE-03`, which concerns custom download-archive storage, and from
`BUG-TERMINAL-03`, which concerns Terminal destination output.

Required result:

- keep cache storage under a typed authority contract: either restrict live
  yt-dlp cache roots to app-owned/directly writable filesystem locations, or add
  an explicit provider-backed staging design that never assumes a SAF grant is
  raw-path authority;
- validate the exact cache mode at configuration time and immediately before a
  worker acquires it; reject or safely migrate an inaccessible custom cache
  rather than accepting the tree and failing after the Download is claimed;
- ensure retry, hard-sub, staged-quality, info-JSON, cleanup, and cache-migration
  code all use the same authoritative cache abstraction and cannot independently
  re-derive an unauthorized raw path;
- preserve the current app-owned default cache as the safe fallback and define
  recovery for an already-stored custom tree whose grant is revoked or whose raw
  path is inaccessible;
- add scoped-storage regressions for primary and non-primary SAF trees, revoked
  grants, the app-owned default cache, any deliberately supported directly
  writable raw cache, normal download, retry, staged-quality/hard-sub, and cache
  maintenance, proving an accepted cache location remains usable by the actual
  execution path.

### P2 — BUG-MOVE-01 — Preserve uncopied source files after partial SAF fallback

**State:** Open

**Failure path:** normal downloads and Terminal cache-output publication both use
`FileUtil.moveFile()` to move an app-owned source directory into the selected
destination. For a provider-backed destination, `moveFile()` first tries
`DocumentFile.copyFolderTo()`. If that operation reports failure, its `onFailed`
callback falls back to walking every source file and calling
`moveFileInputStream()` individually. A per-file `createDocument()` or
`openOutputStream()` failure makes `moveFileInputStream()` return `null`; the
fallback simply skips that file and continues. It tracks only whether **any**
file was copied. After the loop it unconditionally calls
`sourceDir.deleteRecursively()` and reports the fallback as recovered whenever
`copiedAny` is true.

With a two-file output, for example, file A can copy successfully while file B
fails to create/open at the provider. A makes `copiedAny = true`; B is silently
skipped; then the entire source directory, including B's only recoverable cache
copy, is deleted. Because the fallback returns success, `hasMoveFailure` is not
set and `fileList` contains only the successfully copied subset. `moveFile()` can
therefore return that subset as a successful move. `DownloadWorker` can accept
it as `finalPaths` and continue toward History/terminal completion, while
`TerminalDownloadWorker` can likewise continue as though cache publication
completed.

**Why this is a defect:** a provider-specific partial copy failure is converted
into successful publication and destructive cleanup. Files that were never
published are deleted from the only recoverable source copy, so a normal SAF
output move can silently lose media or sidecars while downstream state describes
the move as successful. This is distinct from `BUG-TERMINAL-03`, which concerns
losing SAF authority before Terminal execution, and from `BUG-CACHE-01`, which
concerns maintenance code moving files owned by a live download.

Required result:

- make fallback publication outcome authoritative per source file and do not
  delete a source file until its corresponding destination copy is durably
  verified;
- never delete the whole source directory when any eligible source file failed
  or remained unverified; preserve those files for retry/recovery;
- surface a partial move as an explicit failed/partial outcome rather than a
  success containing only the copied subset;
- make DownloadWorker and Terminal commit only outputs proven published under
  that move contract and preserve recoverable cache otherwise;
- add deterministic SAF regressions where `copyFolderTo()` fails and the fallback
  has all files succeed, first-succeeds/second-fails, first-fails/second-succeeds,
  `createDocument()` failure, and `openOutputStream()` failure, proving uncopied
  files remain recoverable and no success-labelled partial publication occurs.

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

### P2 — BUG-HISTORY-03 — Do not reclassify a committed History write as download failure

**State:** Open

**Failure path:** after yt-dlp output has been produced and validated,
`DownloadWorker` enters its History stage. For an ordinary non-incognito
completion it first calls `HistoryKeywordAssignmentRepository.insertHistory()`.
That method commits the new History row together with manual/RULE keyword
projection in its own Room transaction and returns the newly allocated History
ID. `DownloadWorker` then performs a separate
`AutomaticKeywordRuleEngine.applyToHistory()` call for legacy Observe Source
keywords/current rule assignments. Replacement downloads have the same ordering:
the authorized History replacement commits first, then `applyToHistory()` runs.

If that later derived-keyword call throws, the surrounding History `catch`
treats the whole stage as `HISTORY_WRITE_FAILED`, sets `preserveQueueRecord =
true`, and moves the Download row to `Error`. The already committed History
mutation is not rolled back. For a new download, the media and History row can
therefore be present while the queue still advertises a failed job; for a
replacement, the History row may already point at the replacement output while
the old-media cleanup has not yet run. A user retry can then repeat work against
a state that already crossed the authoritative History commit boundary.

A `CancellationException` observed after that same commit boundary is another
instance of the same barrier defect: the History catch rethrows cancellation,
so worker execution can be exposed as canceled even though the authoritative
History insert/replacement already committed.

**Why this is a defect:** a non-authoritative derived-keyword failure after the
History commit changes the semantic result from completed media/History
persistence to download failure. Cancellation after the same boundary can
likewise expose already-committed History mutation as if the operation never
completed. This violates the post-commit barrier and can invite duplicate
redownloads or repeated History replacement while durable success state already
exists.

Required result:

- define the History insert/replacement commit as an authoritative completion
  boundary for the media-to-History mutation;
- make follow-up derived keyword/provenance assignment part of the same atomic
  transaction when it must gate completion, or persist/report its failure
  separately without reclassifying the committed download as failed;
- define cancellation semantics after the authoritative History commit so a
  late cancellation does not erase or misrepresent the committed mutation;
- ensure replacement old-media cleanup and terminal queue/ledger state use an
  explicit recovery contract if post-commit enrichment fails;
- add fault-injection regressions for legacy Observe Source assignment, RULE
  assignment, and cancellation after both new-History insert and authorized
  replacement, proving a committed History mutation is never exposed as an
  uncommitted/retryable result.

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

### P2 — BUG-TERMINAL-03 — Preserve SAF authority for Terminal output-folder selection

**State:** Open

**Failure path:** the production Terminal screen exposes a Folder action backed
by `ACTION_OPEN_DOCUMENT_TREE`. After the user selects a tree, the activity takes
a persistable read/write URI grant, but the selected `content://` tree is not
kept as the authority used by the command. `TerminalFragment` immediately passes
it through `FileUtil.formatPath()` and inserts the resulting raw
`/storage/.../` pathname into the command text. The configured Terminal output
path has a second production route to the same loss of authority:
`TerminalCommandPlanFactory` reads `command_path`, calls
`FileUtil.canWriteToDestination()` on the original value, and for a writable SAF
tree that check can succeed through `DocumentFile`/persisted URI permission.
`TerminalCommandPlanner` then chooses direct output and passes
`environment.formattedDownloadLocation` — the raw path produced by
`FileUtil.formatPath()` — to yt-dlp as `-P`.

A user-entered command containing `-P ` bypasses even that destination-writable
decision: `TerminalCommandPlanner.create()` treats the presence of `-P ` in the
sanitized config as proof that output is direct and sets `usesAppCache = false`
without validating whether the path is writable by the native yt-dlp process.
`TerminalDownloadWorker` consequently skips the provider-aware
`FileUtil.moveFile()` stage whenever this direct-output decision is made and lets
yt-dlp write to the raw path itself.

The app targets Android 36 and does not have broad all-files access. A SAF grant
authorizes access through the document provider; converting the tree URI to a
filesystem-looking path does not grant equivalent raw/native access under scoped
storage. The first authoritative observation — that the app can write the SAF
tree through the provider — is therefore discarded before the actual yt-dlp
filesystem mutation. No later validation proves that the raw path is writable by
yt-dlp.

**Why this is a defect:** the Terminal UI can successfully present and persist a
folder authorization that the execution path cannot actually use. A command can
be routed away from the safe app-cache + provider-aware move path precisely
because provider authority was observed as writable, then fail when yt-dlp is
asked to use an unauthorized raw path. This is a normal production path on
scoped-storage devices, not a malformed-command-only edge case, and it is
distinct from `BUG-DUPLICATE-03`, which concerns custom download-archive storage.

Required result:

- retain SAF destinations as provider-backed identities through Terminal command
  planning and stage output in app-owned storage before moving/copying it through
  `ContentResolver`/`DocumentFile` when raw filesystem authority is unavailable;
- permit direct yt-dlp `-P` output only after proving the exact raw destination is
  writable by the native execution path, not merely that the corresponding SAF
  tree is writable through the app process;
- do not translate a successful SAF grant into raw-path authority, and make the
  Terminal Folder action insert/retain a typed destination rather than an
  authority-losing pathname;
- define explicit handling for user-supplied `-P`/`--paths` destinations that
  cannot be proven writable instead of unconditionally disabling cache staging;
- add Android scoped-storage regressions for primary and non-primary SAF trees,
  cache enabled/disabled, a writable provider with raw-path denial, revoked
  grants, an actually writable app/raw destination, and explicit `-P`, proving
  provider-authorized destinations either complete through staged movement or
  fail before yt-dlp begins with an actionable error.

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

### P2 — BUG-FORMAT-02 — Do not let stale format-update notifications overwrite live download ownership

**State:** Open

**Failure path:** continuing a multi-download format refresh in the background
first moves the current `Processing` rows to durable `Saved` rows and enqueues
`UpdateMultipleDownloadsFormatsWorker`. When that worker finishes, its
`showFormatsUpdatedNotification()` PendingIntent deep-links to `HomeFragment`
with the numeric Download IDs and `showDownloadsWithUpdatedFormats = true`.
Those IDs are retained in the notification with no status, generation, or
execution-owner snapshot and the notification can be opened later.

Before the notification is tapped, the user can normally open one of those Saved
rows and queue it. `DownloadWorker.claimDownloadForWorker()` can then
transition that same ID to `Active` and install a fresh `executionId`. Tapping
the older format-update notification afterward reaches `HomeFragment.onResume()`,
which calls `turnDownloadItemsToProcessingDownloads(ids, deleteExisting = true)`.
That method deletes any current Processing session, reloads each row only by
numeric ID, retains the existing ID when `deleteExisting` is true, unconditionally
sets `status = Processing`, and persists the full row through
`DownloadRepository.update()`. It has no expected `Saved` status or execution-
ownership predicate, so it can rewrite the live `Active`/`PostProcessing` row
as `Processing` while the original worker still owns and executes the same
attempt.

The deep link immediately opens `DownloadMultipleBottomSheetDialog`. Its normal
Download action queues the Processing rows, and the DAO path clears execution
ownership when moving them to `Queued`. A second worker can therefore claim the
same Download ID while the original execution is still downloading, moving, or
post-processing. Even before that second queue action, the first worker's owner-
guarded `Active`/`PostProcessing` writes can fail because the stale notification
already changed the durable status out from under it. No notification parser or
Home navigation validation rejects IDs whose state has advanced since the
background-format operation completed.

**Why this is a defect:** a completion notification is only a stale navigation
hint, but it is treated as authority to reclassify current durable Download
state. A normal Saved → Queued → Active progression can therefore have its live
execution ownership revoked by tapping an older notification, allowing duplicate
execution, conflicting output/terminal commits, or a false failure of the
original worker. This is distinct from `BUG-FORMAT-01`, which concerns the
format worker's own persistence/result semantics, and from scheduler/pause races
whose stale commands enter through different state transitions.

Required result:

- treat format-update notification IDs as navigation hints only and revalidate
  each row against the expected post-refresh state before any mutation;
- allow notification-driven reconfiguration only for rows still durably owned by
  the completed format-refresh flow (normally `Saved`) and never rewrite
  `Queued`, `Active`, `PostProcessing`, `Paused`, `Cancelled`, `Error`, or an
  unrelated current `Processing` session;
- preserve `executionId` ownership by using an expected-state/CAS or generation
  token that a later queue/worker transition invalidates;
- do not delete an unrelated Processing configuration session merely because an
  older format-update notification was opened; scope any replacement to the
  notification-owned rows;
- add deterministic regressions for Saved → Active before notification tap,
  Saved → Queued, already-PostProcessing, unrelated Processing-session presence,
  normal still-Saved reopening, and pressing Download after a stale notification,
  proving no second execution owner can be created.

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

### P2 — BUG-SCHEDULER-01 — Keep download scheduling recurrent and correct across midnight

**State:** Open

**Failure path:** enabling the download scheduler or adding/requeuing work outside
the allowed window calls `AlarmScheduler.schedule()`. That method uses
`AlarmManager.setExactAndAllowWhileIdle()` to arm one start alarm and one end
alarm. Both are one-shot exact alarms. `ScheduleAlarmReceiver` only enqueues a
`DownloadWorker`, and `CancelScheduleAlarmReceiver` only enqueues
`CancelScheduledDownloadWorker`; neither receiver re-arms the next day's start
or end alarm. If queued work survives the first end boundary, the cancel worker
returns active rows to `Queued`, but no next-day start alarm is guaranteed until
another user/queue action happens to call `schedule()` again.

The same scheduler also misclassifies overnight windows. For a window such as
23:00→05:00, `isDuringTheScheduledTime()` converts the end hour to `29` but
leaves a post-midnight `currentHour` such as `1` unchanged, so `1 in 23..29` is
false. `DownloadWorker` uses that result to cancel itself when the scheduler is
enabled and there is no individually timed item or priority work;
`DownloadViewModel` and Observe Source requeue paths likewise treat the same
post-midnight period as outside the window and schedule an alarm instead of
starting eligible queued work.

**Why this is a defect:** a persistent scheduler setting does not reliably
represent a persistent daily execution window. Downloads can stop after the
first scheduled cycle and remain queued on later days, while valid work during
the post-midnight half of an overnight window can be incorrectly suppressed.
The failure is production-reachable through the normal scheduler setting and
queue/Observe Source paths without malformed state or external corruption.

Required result:

- represent daily time-window membership in a form that correctly handles both
  same-day and overnight intervals, including exact start/end boundary rules;
- re-arm the next start/end pair after each scheduled transition, or use another
  persistent scheduling contract that survives repeated daily cycles without
  requiring a later queue/settings mutation;
- ensure settings changes replace/cancel the prior logical schedule rather than
  leaving stale boundaries, and define/recover the schedule after process/device
  restart where platform alarms are no longer present;
- make `DownloadWorker`, queue insertion/requeue, and Observe Source dispatch use
  the same authoritative window decision;
- add same-day and 23:00→05:00 boundary tests plus multi-day, no-new-user-action,
  restart/re-arm, and queued-work-survives-end regressions.

### P2 — BUG-SCHEDULER-02 — Preserve live execution ownership when forcing scheduled items to run now

**State:** Open

**Failure path:** the production Scheduled Downloads screen offers per-item and
multi-selection “Download now” actions. Both retain the selected numeric
Download IDs and call
`DownloadViewModel.resetScheduleTimeForItemsAndStartDownload()`, which directly
executes `DownloadDao.resetScheduleTimeForItems(ids)`. That DAO update has no
expected-status or execution-owner predicate: for every matching ID it sets
`downloadStartTime = 0`, `status = 'Queued'`, and `executionId = ''`.

A scheduled row can independently become eligible at its configured time and be
claimed by `DownloadWorker`, whose claim changes the same row from `Scheduled`
to `Active` and installs a fresh `executionId`. If that claim occurs after the
Scheduled UI selected the row but before the user action reaches
`resetScheduleTimeForItems()`, the unguarded update rewrites the live `Active`
row back to `Queued` and erases its execution token while the original worker
continues running. The view model then starts the download worker again; the
queued row is eligible for a fresh claim and a second execution owner can start.
The all-Scheduled variant does not have this exact defect because
`resetScheduleTimeForAllScheduledItems()` requires `status = 'Scheduled'`, and
`rescheduleQueuedOrScheduled()` likewise has an expected-status predicate.

**Why this is a defect:** a stale UI selection can revoke durable ownership from
a genuinely running download without cancelling or synchronizing with that
owner. The database then advertises the same request as queueable while the old
worker may still be downloading, moving, or post-processing files, allowing
duplicate execution, conflicting output, and competing terminal writes.

Required result:

- make per-ID “Download now” an expected-state transition that changes only
  rows still durably `Scheduled` (or another explicitly permitted non-running
  state) and never clears the `executionId` of `Active`/`PostProcessing` work;
- use the same authoritative ownership/status contract for single-item and
  multi-selection actions as the already status-guarded all-Scheduled path;
- if the target has already been claimed, treat the action as already running
  rather than requeueing it;
- keep worker acquisition and UI status transitions under a CAS/transactional
  contract so no stale selection can manufacture a second owner;
- add deterministic races where a selected Scheduled row is claimed immediately
  before the per-item and multi-item “Download now” write, plus normal still-
  Scheduled and already-PostProcessing regressions.

### P2 — BUG-SCHEDULER-03 — Re-arm individually scheduled downloads after each exact alarm

**State:** Open

**Failure path:** the production download-card Schedule action persists
`Processing` rows as `Scheduled` with their selected `downloadStartTime` and then
runs the normal queue path. `DownloadRepository.startDownloadWorker()` merges
those rows with every already-Scheduled row, groups future start times, and,
when the user-facing `use_alarm_for_scheduling` setting is enabled, does not
create one delayed WorkManager request per group. Instead it calls
`AlarmScheduler.scheduleAt(futureScheduleGroups.keys.min())`, so AlarmManager
represents only the earliest future start time. `scheduleAt()` itself stores a
single one-shot exact alarm using the same request-code-1 PendingIntent.

When that alarm fires, `ScheduleAlarmReceiver` only enqueues an immediate
`DownloadWorker`; it does not inspect remaining future Scheduled rows or arm a
successor alarm. `DownloadWorker` captures an eligibility cutoff near its own
start and observes only rows whose `downloadStartTime` is at or before that
cutoff, so a second scheduled group at a later time is not kept alive by the
first worker. Once the due group and any current running work are gone, the
worker can cancel itself. There is no other production caller that re-arms
`scheduleAt()` after the alarm fires. With scheduled groups at `T1 < T2` and no
later user/queue mutation, `T1` can run normally while `T2` remains durably
`Scheduled` after its requested time with no execution carrier.

**Why this is a defect:** enabling the explicit AlarmManager scheduling option
changes multiple independently scheduled downloads into a chain whose only
carrier is the first one-shot alarm, but no handoff to the next start time
exists. A normal pair of user-scheduled downloads at different times can
therefore silently lose the later execution even though its row still records
the requested schedule. This is distinct from `BUG-SCHEDULER-01`, which covers
the recurring daily scheduler window, and from `BUG-SCHEDULER-02`, which covers
stale “Download now” ownership races.

Required result:

- after each individual exact alarm fires, atomically/reliably discover and arm
  the next future Scheduled start time, or represent all pending future groups
  with independently durable alarm/work identities;
- keep one authoritative earliest-alarm contract when rows are added,
  rescheduled, deleted, or forced to run now, replacing the current alarm only
  when the next due time actually changes;
- recover the next individual schedule after process/device restart or lost
  platform alarm without requiring another user mutation;
- ensure a worker launched for `T1` cannot be treated as the carrier for `T2`
  when its eligibility cutoff excludes `T2`;
- add deterministic two- and three-time-group regressions with AlarmManager
  enabled, no intervening user action, cancellation/deletion of the earliest
  group, rescheduling, process/device restart, and the WorkManager-delayed mode
  as a non-regression control.

### P2 — BUG-HARDSUB-01 — Preserve ambiguous subtitle lookup failures instead of excluding scan candidates

**State:** Open

**Failure path:** `HardSubScanWorker` scans History rows whose
`hardSubScanRemoved = 0` and `hardSubDone = 0`. For each candidate it calls
`ResultRepository.getResultsFromSource(..., singleItem = true)`, takes
`firstOrNull()?.availableSubtitles`, and collapses a missing result to
`emptyList()`. A thrown lookup exception is treated as a failure and left
retryable, but a non-throwing empty result is treated exactly like an
authoritative result with no requested subtitle language: the worker persists
`hardSubScanRemoved = true, hardSubDone = false` and moves on.

That empty result is not authoritative. The YouTube-video path falls back to
`YTDLPUtil.getFromYTDL()`, whose metadata request applies `--ignore-errors` and
can therefore complete without throwing while producing no parsed `ResultItem`;
`getYoutubeVideo()` returns that empty list to the scan. The worker's rescan
reset does not repair these rows: `resetHardSubDoneForRescan()` only resets rows
where `hardSubDone = 1`, while the ambiguous-empty branch stores
`hardSubDone = 0`. The automatic candidate query consequently excludes the row
on subsequent scans unless a separate user action explicitly changes its scan
state.

**Why this is a defect:** a transient extractor/authentication/item failure can
be converted into durable evidence that the History item is not a hard-sub scan
target. Unlike the exception path, the ambiguous-empty path is not counted as a
failed lookup and does not participate in the worker's bounded retry behavior,
so eligible media can be silently omitted from future automatic hard-sub scans.

Required result:

- carry an explicit lookup success/completeness outcome instead of reducing an
  absent `ResultItem` to an empty subtitle list;
- set `hardSubScanRemoved = true` only after an authoritative successful lookup
  establishes that no requested manual subtitle exists (or another deliberate
  exclusion rule applies);
- keep empty/ignored-error/ambiguous lookups retryable under the same bounded
  policy as thrown lookup failures and preserve the candidate when retries are
  exhausted;
- distinguish a successfully fetched item with an authoritative empty subtitle
  set from a fetch that produced no item at all;
- add ignored-error empty-result, thrown-failure, authoritative-no-subtitle,
  requested-subtitle, retry-exhaustion, and subsequent-rescan regressions.

### P2 — BUG-HARDSUB-02 — Preserve hard-sub guarantees across History re-download replacement

**State:** Open

**Failure path:** a normal History re-download is prepared with
`DownloadViewModel.createDownloadItemFromHistory()` and later revalidated by
`hydrateHistoryRedownloadBeforeQueue()`. For a video whose existing History row
has `hardSubDone = true`, hydration deliberately sets `requiresHardSubLookup =
true`, because replacing that row should not silently discard its established
hard-sub state. The subtitle lookup nevertheless takes
`firstOrNull()?.availableSubtitles.orEmpty()`. A thrown lookup exception blocks
queueing when `requiresHardSubLookup` is true, but a non-throwing missing
`ResultItem` is collapsed to an empty subtitle list and is treated as a valid
lookup. This is production-reachable because the yt-dlp metadata path uses
`--ignore-errors` and can return normally with no parsed item.

If the global/current re-download configuration did not already request subtitle
burn-in, that ambiguous empty result leaves `videoPreferences.embedSubs = false`
and the re-download proceeds without burning subtitles. `DownloadWorker` then
uses the regular `HistoryRedownloadMarker` to authorize replacement of the same
History row. When `hardSubBurned` is false, the replacement factory does not set
`hardSubDone`/`hardSubScanRemoved` from the newly produced media; instead it
copies `previous.hardSubDone` and `previous.hardSubScanRemoved`. After the
History replacement commits, `deleteReplacedHistoryMedia()` deletes the previous
media. The new file can therefore be unburned while the durable History row still
claims `hardSubDone = true` and may remain excluded from future hard-sub scans.

**Why this is a defect:** a non-authoritative metadata-empty result can erase an
already-established media property during a destructive replacement while the
persistent state falsely says that property survived. The user can lose the old
hard-subbed file, receive an unburned replacement, and have automatic recovery
skip it because the stale `hardSubDone` flag was inherited. This is distinct
from `BUG-HARDSUB-01`, which concerns excluding scan candidates before any
History replacement occurs.

Required result:

- carry an explicit authoritative subtitle-lookup outcome through History
  re-download preparation, distinguishing a successful empty subtitle set from
  an ignored-error/absent-item result;
- when the target History row has `hardSubDone = true` or the current request
  otherwise requires hard-sub verification, keep ambiguous/failed lookup
  retryable or block replacement rather than treating it as “no subtitles”;
- derive replacement `hardSubDone` and `hardSubScanRemoved` from the newly
  produced media/current burn result, never by inheriting a prior file's hard-sub
  claim without proof that the replacement satisfies it;
- preserve the previous History media until the replacement has authoritatively
  re-established the required hard-sub property, and fail safely if it cannot;
- add direct Download-now and multi-Processing queue regressions for ignored-error
  empty lookup, thrown lookup failure, authoritative empty subtitles, requested
  subtitle discovery, prior `hardSubDone = true` with global embed disabled, and
  successful burn-in, proving old media is not deleted and flags are not falsely
  retained when the guarantee is not re-established.

### P2 — BUG-HARDSUB-03 — Replace split-stream merge output without a delete-before-rename loss window

**State:** Open

**Failure path:** `DownloadWorker.burnSubtitlesInPlace()` prepares media for
hard-sub burn-in by calling `mergeSeparatedVideoAudioIfNeeded()`. When yt-dlp has
produced a video-only stream plus a matching audio-only stream and the current
parent directory can create sibling output, that helper calls
`mergeVideoAudioPairInDirectory()`. ffmpeg first writes a non-empty
`<video>.muxed.<ext>` candidate. The publish step then deletes the original video
**before** renaming the muxed candidate onto the original pathname. If
`renameTo(primaryVideo)` returns false, the failure path deletes `mergedTemp`
and returns `null`; its caller converts that into an IOException.

A filesystem/provider/storage failure can therefore occur after the original
video was successfully deleted but before the rename committed. In the explicit
rename-failure path the code then deletes the only completed merged video as
well, leaving neither the original video-only stream nor the merged candidate;
the audio-only stream remains but cannot replace the lost video. A process death
between original deletion and rename creates the same non-atomic publication
window. This path is production-reachable whenever hard-sub processing receives
split video/audio outputs; it occurs before subtitle burn-in and can run on the
app cache or on a directly writable output directory.

**Why this is a defect:** a helper whose job is to improve the current download's
media layout uses destructive delete-before-publish ordering. A late rename or
process failure can turn an otherwise completed yt-dlp video stream plus a
successfully generated mux candidate into irreversible loss of the video output,
rather than preserving either the prior stream or the candidate for recovery.
This is distinct from `BUG-HARDSUB-02`, which concerns preserving an existing
History hard-sub guarantee across replacement, and from `BUG-MOVE-01`, which
concerns partial SAF destination publication.

Required result:

- publish the muxed candidate with a replacement protocol that never removes the
  last valid video copy before the replacement is durably established;
- preserve the original video or the completed mux candidate on rename/publish
  failure and expose a recoverable failure rather than deleting both;
- make process-death recovery able to distinguish and safely resume/clean an
  interrupted split-stream merge without losing completed media;
- delete the companion audio-only stream only after the replacement video is
  authoritatively committed and validated;
- add deterministic same-directory rename failure, process death between old-file
  removal and replacement, cache-directory, direct-output, successful merge, and
  already-audio-containing-video regressions proving at least one valid video
  copy always survives a failed publish.

### P2 — BUG-CANCEL-01 — Persist cancel/requeue intent before terminating live work

**State:** Open

**Failure path:** the per-download notification Cancel action is wired directly
to `CancelDownloadNotificationReceiver`. The receiver attempts
`DownloadRepository.cancelByUser(id)` inside an inner `runCatching`, discards
that failure, and then unconditionally destroys the yt-dlp processes, cancels
post-processing, and removes the download notification. `cancelByUser()` is the
authoritative Room transaction that changes the Download row to `Cancelled` and
terminalizes any linked low-quality ledger children. If that transaction throws,
the process is still killed while the durable row can remain `Active` or
`PostProcessing` and the ledger can remain nonterminal.

The scheduler end-boundary path has the inverse ordering problem in
`CancelScheduledDownloadWorker`: it cancels download work and destroys each
running yt-dlp process before rewriting that row to `Queued`. A throwing DAO
update after process destruction can therefore leave the dead job persisted as
`Active`. `DownloadWorker` does not make these paths safe after the fact: its
canceled-exception branch treats `YoutubeDL.CanceledException` itself as a
canceled outcome even when the latest DB status is still live, and stopped-worker
requeue cleanup is best-effort rather than a durable prerequisite for either
external cancellation path.

**Why this is a defect:** both production cancellation entry points can cross the
irreversible process/post-processing termination boundary before the intended
terminal/requeue state is durably established. A transient Room failure can
therefore leave ghost `Active`/`PostProcessing` rows with no live owner, stale
low-quality operation state, or queue slots that appear occupied after restart.
The user-visible notification path is especially problematic because the
persistence failure is explicitly swallowed and the action still appears to
have completed.

Required result:

- make user cancellation atomically persist the Download terminal state and
  linked-ledger transition before any yt-dlp/post-processing termination, and do
  not continue with destructive cancellation if that authoritative transaction
  fails;
- make scheduler stop persist or reserve an authoritative `Queued` transition
  before terminating the corresponding process, under an ownership protocol
  that prevents the live worker from committing conflicting state;
- preserve typed persistence failure instead of swallowing it, and keep the
  notification/action retryable when durable cancellation could not commit;
- make worker canceled-exception handling reconcile against authoritative DB
  state rather than treating process cancellation alone as proof that the
  requested persistent transition succeeded;
- add fault-injection regressions for `cancelByUser()`, linked-ledger update, and
  scheduler requeue writes, plus restart tests proving no killed job remains as
  ghost `Active`/`PostProcessing` work.

### P2 — BUG-CANCEL-02 — Make persisted cancellation authoritative at the success commit boundary

**State:** Open

**Failure path:** during a normal `DownloadWorker` run, the last explicit
`shouldStopForUserRequest()` status check occurs before the final success/History
stage. Once that check has observed the row as worker-owned, the worker can
continue through final output handling and into the History insert/replacement
without another authoritative Download-status check at the commit boundary.
Meanwhile the production notification Cancel action can successfully commit
`DownloadRepository.cancelByUser(id)`, which changes the Download row to
`Cancelled` and terminalizes a linked low-quality child, before it destroys the
yt-dlp/post-processing process. If cancellation lands after the worker's last
status check, yt-dlp may already have exited and the stale worker can still
commit a new History row or an authorized History replacement after the durable
`Cancelled` state was established.

The terminal success repository call does not close this race.
`DownloadRepository.completeAndDelete()` updates a linked low-quality child only
when that child is still nonterminal, then deletes the Download row regardless
of its current Download status. A cancellation-terminalized child is therefore
left cancelled while the stale success path can still delete the already
`Cancelled` Download. In a History replacement flow the same stale continuation
can also reach replacement cleanup after cancellation, including deletion of the
previous media under the otherwise-authorized replacement contract. Process
termination after the cancellation transaction cannot prevent this when the
worker is already in database/filesystem post-processing rather than an active
yt-dlp call.

**Why this is a defect:** a successful cancellation transaction is the first
authoritative durable observation of the user's terminal intent, but a worker
that passed an earlier status snapshot can subsequently commit success-side
History/file mutations and erase the cancelled Download row. The result can be
a user-cancelled operation with newly committed History/output, or a cancelled
replacement that still mutates the History target and cleans up previous media.
This is a distinct ownership/TOCTOU defect from `BUG-CANCEL-01`, which covers
failure to durably persist cancellation before process termination, and from
`BUG-HISTORY-03`, which covers cancellation observed only after an authoritative
History commit.

Required result:

- make final History insert/replacement and terminal Download success conditional
  on authoritative worker ownership that is validated at the same commit
  boundary, using a transaction/CAS, ownership token, or generation that a
  committed cancellation invalidates;
- once `Cancelled`/`Paused` is durably established, prevent a stale worker from
  inserting/replacing History, deleting the Download row as success, or
  performing replacement old-media cleanup;
- make `completeAndDelete()` and the target-deleted success variant reject a
  Download whose current durable status no longer belongs to the worker, and
  treat an already-terminal linked cancellation as a veto rather than merely
  skipping the child-state update;
- define an explicit cancelled/partial-output recovery contract when media
  output already exists but cancellation wins before History commit, preserving
  recoverable output without misrepresenting the cancelled request as success;
- add deterministic ordinary-download, History-replacement, and linked
  low-quality races where cancellation commits between the last status check and
  History/terminal success commit, including cancellation after yt-dlp exit, and
  prove no post-cancel History replacement, previous-media cleanup, or success
  deletion can occur.

### P2 — BUG-PAUSE-01 — Do not expose a failed pause as resumable state

**State:** Open

**Failure path:** the production Pause action enters
`PauseDownloadNotificationReceiver`. It loads the active Download, changes the
in-memory item status to `Paused`, and calls `downloadDao.update(item)` before
cancelling the running notification or destroying yt-dlp/post-processing. If
that authoritative Room write throws, control skips every process-termination
step and the live worker can continue with its row still durably `Active` or
`PostProcessing`. The coroutine's `finally` nevertheless always switches to the
main dispatcher and calls `NotificationUtil.createResumeDownload()`, so the UI
advertises a Resume action even though Pause never committed and the original
worker still owns live work.

That misleading action is not harmless. `ResumeActivity` calls
`DownloadViewModel.reQueueDownloadItemsAndWait()`, whose DAO transition rewrites
the selected row to `Queued` without requiring the expected current status to be
`Paused`. If the user taps the erroneous Resume notification after the failed
pause write, it can therefore change the still-live worker's `Active` or
`PostProcessing` row to `Queued` and start the normal download worker again.
Download-worker candidate ownership is reconstructed from durable live statuses,
so the original live owner can disappear from the next DB ownership snapshot
while it is still executing.

**Why this is a defect:** a failed authoritative pause write is reinterpreted by
a `finally` side effect as successful Pause, and the follow-up Resume path can
then remove durable ownership from a genuinely running job. Besides false UI
state, this creates a production-reachable path to duplicate/restarted work,
conflicting terminal commits, or duplicate output while the original worker is
still alive.

Required result:

- publish the Resume notification only after the `Paused` transition has
  durably committed and the live worker/process has been brought under the
  corresponding paused ownership contract;
- if pause persistence fails, preserve the running notification/state and report
  or retain a retryable pause failure instead of executing success-only UI side
  effects from `finally`;
- make Resume an expected-state transition that can change only a durably
  `Paused` row to `Queued`; it must not rewrite `Active`, `PostProcessing`,
  `Cancelled`, `Error`, or another terminal/owned state;
- coordinate pause/resume with worker acquisition so a row cannot become queued
  while an earlier worker still owns its process/post-processing work;
- add fault-injection coverage for the pause Room write, notification creation,
  and a Resume click after failed persistence, plus a deterministic concurrent
  worker-selection test proving no duplicate owner can be created.

### P2 — BUG-LOWQUALITY-01 — Persist download failure and low-quality ledger failure atomically

**State:** Open

**Failure path:** the low-quality re-download flow creates a normal queued
`DownloadItem` and links its generated Download ID to a nonterminal
`LowQualityRedownloadItem`. When that production download later fails,
`DownloadWorker` first changes the Download row to `Error`, stores the selected
`lastIssueCode`/`lastIssueStage`, and commits that row with `dao.update()`.
Only afterward does it call `LowQualityRedownloadLedger.transition(...,
FAILED, ...)`. The ledger call is wrapped in `runCatching` and its failure is
discarded. The same split ordering is present in multiple DownloadWorker error
branches.

`LowQualityRedownloadLedger.transition()` is not a notification-only follow-up:
it calls `LowQualityRedownloadRepository.markDownloadState()`, whose Room
transaction terminalizes the linked child and can finalize the parent operation.
A process death after the Download-row commit, or a Room exception in that
second transaction, therefore leaves durable `Download = Error` while the
linked low-quality child and operation can remain nonterminal. The surrounding
worker path does not reinterpret the swallowed ledger failure as retryable or
force immediate reconciliation. Startup `LowQualityRedownloadManager.reconcile()`
can repair the mismatch later by deriving `FAILED` from the Error row, but until
that separate recovery path runs the current operation can remain falsely active
and its progress/notification state is stale.

For SourceMismatch/TypeMismatch specifically, that restart reconciliation can repair the
child to generic `FAILED`, but it does not reconstruct the exact authoritative mismatch
reason from the Download's persisted issue code/stage. A process death between the Download
Error commit and the linked-ledger transition can therefore lose the exact mismatch reason
even when later reconciliation repairs liveness.

**Why this is a defect:** the Download failure and the operation ledger describe
one authoritative terminal event but are committed in two failure-separated
transactions, and the second failure is explicitly suppressed. A normal
Download failure can therefore be durably visible while the owning low-quality
operation still claims unfinished work, blocking/falsifying operation state
until a later reconciliation happens to run. Crash recovery can also preserve only a
generic terminal child state while losing the authoritative failure reason.

Required result:

- persist `Download = Error`, `lastIssueCode`/`lastIssueStage`, the linked child
  `FAILED` state/reason, and any resulting parent terminal state under one Room
  transaction or another atomic repository contract;
- do not place logging, notification, cleanup, or other throwing side effects
  between determination of the authoritative failure and that durable terminal
  commit;
- if the combined terminal commit cannot complete, preserve a typed retryable
  persistence failure instead of swallowing the ledger half of the failure;
- keep startup reconciliation as crash recovery, not as the normal mechanism
  required to make a just-failed operation internally consistent, and preserve the exact
  authoritative reason when reconciliation is required;
- add fault-injection/process-death regressions after failure classification,
  after the Download error write, and during child/parent ledger finalization,
  proving no observable committed state contains `Download = Error` with a
  nonterminal linked low-quality child and no repaired child loses the exact authoritative
  terminal reason.

### P2 — BUG-LOWQUALITY-02 — Preserve low-quality ledger ownership across Error reconfiguration

**State:** Open

**Failure path:** every low-quality re-download child is linked to its concrete
Download row by `LowQualityRedownloadItem.downloadId`. If that Download reaches
`Error`, it appears in the normal Errored Downloads UI. In the production bulk
Redownload-with-card path, `turnDownloadItemsToProcessingDownloads()` loads the
Error row, clears `item.id` to `0`, and inserts a new `Processing` clone after
preparing reconfigured retry metadata. No ledger row is rebound to the newly
allocated Download ID. When the user presses Download,
`DownloadMultipleBottomSheetDialog` first calls
`deleteAllWithID(currentDownloadIDs)` on the original Error IDs.
`DownloadRepository.deleteKnownUserRemoval()` then transactionally changes any
linked nonterminal low-quality child to `CANCELLED` with
`REASON_USER_REMOVED`, may finalize the parent operation, and deletes the old
Download. The Processing clone is subsequently queued under its different ID.
All normal completion/failure ledger transitions look up ownership by Download
ID, so that retry has no child link to update even though it is a continuation
of the low-quality re-download attempt.

**Why this is a defect:** a normal retry/reconfiguration action changes durable
operation ownership into an apparent user removal. The low-quality operation can
finish as cancelled/skipped while its replacement download is still queued or
later succeeds, and the retried work's eventual success/failure is no longer
represented by the operation that created it. Copying `operationId` on the
Download does not repair this because ledger transitions are keyed through the
child's `downloadId`.

Required result:

- preserve or atomically transfer the low-quality child ownership when an Error
  download is reconfigured into a replacement Download ID, or reconfigure the
  existing Download in place when that is safe;
- do not run the original row through generic `USER_REMOVED` terminalization
  when the user is semantically retrying the same low-quality child;
- bind the replacement Download ID and retry metadata to the existing child
  before the old Download can be deleted, under one transaction/ownership
  protocol that cannot leave both IDs unowned or concurrently owned;
- ensure completion, failure, save-for-later, cancellation, and startup
  reconciliation all observe the transferred ownership consistently;
- add production-path regressions for low-quality Error -> bulk reconfigure ->
  queue -> success/failure, cancellation before queueing, and a fault between
  replacement insertion, ownership transfer, and old-row deletion.

### P2 — BUG-LOWQUALITY-03 — Preserve low-quality ledger semantics across same-settings retry

**State:** Open

**Failure path:** a normal low-quality re-download child is linked to its
concrete Download row by `LowQualityRedownloadItem.downloadId`. When that
Download fails, `DownloadWorker` can successfully persist the Download as
`Error` and transition the linked child to `FAILED`; `markDownloadState()` then
runs `finalizeIfReadyLocked()`, so once the selected children are terminal the
parent operation can itself become terminal `FAILED` or `PARTIAL_FAILURE`.
The Error Download row remains available in the normal Errored Downloads UI.

Pressing the single-card Download action calls
`DownloadViewModel.retryFailedDownload()`. For an issue that permits a
same-settings retry, the view model applies new retry metadata to the **same
Download ID**, changes that row from `Error` to `Queued`, and starts the normal
worker. It does not reopen the linked terminal child, create a new child/attempt,
or move an already-terminal low-quality parent back to a running state. The
worker can therefore execute a new attempt while the durable ledger still says
that this child — and potentially its parent operation — has already failed.

If the retry succeeds, `DownloadRepository.completeAndDelete()` looks up the
linked child but changes it to `SUCCEEDED` only when the current child is
nonterminal. A child already terminalized as `FAILED` by the first attempt is
skipped, after which the Download row is deleted as a successful completion.
Startup reconciliation cannot repair the discrepancy: `reconcileLinkedDownloads()`
explicitly filters to linked children whose state is nonterminal, and
`finalizeIfReadyLocked()` preserves an already-terminal operation. The final
durable state can therefore remain child=`FAILED` and parent=`FAILED` or
`PARTIAL_FAILURE` even though the same low-quality Download retry later
succeeded and its Download row was removed as success.

**Why this is a defect:** the generic retry contract recognizes a newer attempt
on the Download row, but the owning low-quality ledger treats the first attempt's
terminal failure as permanently authoritative. A user can successfully repair
the media through the normal supported same-settings retry while the operation
that created and tracks that repair continues to report failure, with no restart
reconciliation path capable of correcting it. This is distinct from
`BUG-LOWQUALITY-02`, which loses ownership while creating a different Download
ID through bulk reconfiguration; this path reuses the original Download ID and
enters through the ordinary single-item `SAME_SETTINGS` retry.

Required result:

- represent retry attempts explicitly in the low-quality ledger or atomically
  reopen/rebind the existing child and parent when an allowed retry is queued;
- make the low-quality retry transition and Download `Error -> Queued` transition
  one ownership transaction so a terminal child cannot remain `FAILED` while
  the same logical child is running again;
- on retry success, failure, cancellation, or save-for-later, commit the newer
  attempt outcome into the owning child/parent and do not let
  `completeAndDelete()` silently skip an older terminal child that owns the
  current retry;
- make startup reconciliation generation-aware so an old terminal attempt cannot
  remain authoritative for a newer durable Download retry;
- add production-path regressions for low-quality Error -> single-card
  `SAME_SETTINGS` -> success/failure, mixed parent outcomes, cancellation,
  process restart, and retry-attempt limits, with bulk `RECONFIGURED` ownership
  transfer retained as separate coverage.

### P2 — BUG-RETRY-01 — Require actual reconfiguration before bypassing same-settings retry policy

**State:** Open

**Failure path:** the normal Error-card retry path calls
`DownloadViewModel.retryFailedDownload()`, which classifies the request as
`SAME_SETTINGS` and correctly requires `lastIssueCode.supportsSameSettingsRetry()`.
The user-facing bulk Redownload path takes a different production route when the
download card is enabled. `ErroredDownloadsFragment` passes the selected Error
IDs to `turnDownloadItemsToProcessingDownloads()`. Before the user changes any
setting, that method calls `prepareRetryMetadata(..., RECONFIGURED,
settingsConfirmed = true)`, applies the returned retry metadata, clears the row
ID to create a Processing clone, and inserts it. `DownloadRetryPolicy` does not
require `issueRetryable` for `RECONFIGURED`, so an issue deliberately blocked
from same-settings retry can pass this branch. In
`DownloadMultipleBottomSheetDialog`, pressing Download without changing any
configuration deletes the original Error rows and calls
`queueProcessingDownloads()`. The clones are already `Processing` with
`retryStrategy = RECONFIGURED`, so `queueDownloads()` no longer performs the
Error-state retry-policy check and queues the unchanged request.

**Why this is a defect:** merely entering and confirming the reconfiguration UI
is treated as proof that the request was actually reconfigured. A deterministic
failure that the retry policy intentionally refuses to repeat with identical
settings can therefore be retried unchanged, while the durable retry metadata
claims a semantically different `RECONFIGURED` attempt. This also consumes the
one reconfigured-attempt allowance and deletes the original Error row without
preserving evidence that any configuration changed.

Required result:

- capture an authoritative retry-relevant configuration snapshot for the failed
  request and compare it with the configuration being queued;
- classify the attempt as `RECONFIGURED` only when a retry-relevant setting has
  materially changed; otherwise route it through `SAME_SETTINGS` policy and
  preserve `NOT_RETRYABLE`/attempt-limit blocking;
- do not set `settingsConfirmed = true` merely because the configuration UI was
  opened or its unchanged contents were submitted;
- keep single-item, multi-item, schedule, save-for-later, and card-disabled
  retry entry points under the same semantic retry contract;
- add regressions for a non-retryable Error submitted unchanged through the
  multi-card flow, a genuinely changed configuration, unchanged retryable
  failures, attempt limits, and cancellation/dismissal before queueing.

### P2 — BUG-UPDATER-01 — Preserve custom yt-dlp update failure instead of reporting success

**State:** Open

**Failure path:** the production Updating settings screen lets the user create
and select an arbitrary custom yt-dlp source string. Selecting that source stores
it in `ytdlp_source` and immediately calls `UpdateUtil.updateYoutubeDL(source)`;
startup auto-update later calls the same method with the stored source. For any
source other than `stable`, `nightly`, or `master`, `updateYoutubeDL()` executes
yt-dlp with `--update-to <source>@latest` and inspects the final nonblank output
line. If that line contains `ERROR`, the function constructs an
`YTDLPUpdateResponse(ERROR, out)` but discards that value because there is no
`return` or `else`. Control then reaches a second independent `if`: unless the
same line also contains `yt-dlp is up to date`, its `else` returns
`YTDLPUpdateResponse(DONE, out)`. The settings UI therefore takes the success
branch and refreshes the displayed version, while startup auto-update can show a
success Snackbar for the same failed update.

**Why this is a defect:** an authoritative updater failure is observed and then
lost by control-flow fallthrough, so a real production update failure is
reinterpreted as success. The user can reasonably believe a requested runtime
update was applied when the installed yt-dlp binary was not updated, obscuring
the failure and any remediation needed for subsequent downloads.

Required result:

- make updater output classification mutually exclusive and return the typed
  `ERROR` result immediately when authoritative error output is detected;
- prefer the updater process/exit-status contract over fragile final-line text
  matching where the library exposes it, while preserving explicit
  `ALREADY_UP_TO_DATE` and `DONE` states;
- ensure manual settings updates, source changes, startup auto-update, and any
  WorkManager updater path propagate the same typed failure semantics and never
  display success for a failed update;
- add regressions for custom-source error output, already-up-to-date output,
  successful output, thrown execution failure, and an error line containing
  unrelated success-like text.

### P2 — BUG-PLAYLIST-01 — Make playlist and playlist-group deletion atomic

**State:** Open

**Failure path:** the production History playlist UI confirms single- or
multi-playlist deletion and calls `PlaylistViewModel.deletePlaylist()`, which
launches `PlaylistRepository.deletePlaylist()`. That repository performs
`playlistDao.deletePlaylistItemsByPlaylistId()`,
`playlistDao.deletePlaylist()`, and
`playlistGroupDao.deleteMembersByPlaylist()` as three independent DAO mutations
without a Room transaction. `PlaylistGroupMember` has no foreign-key cascade
tying its `playlistId` to `playlists`, so the final cleanup is not repaired
automatically.

If playlist-item deletion commits and the subsequent playlist-row delete throws,
the playlist remains but its History membership has already been lost. If the
playlist row is deleted and the final group-membership cleanup throws, stale
`playlist_group_members` rows remain for a playlist that no longer exists. The
production playlist-group Delete action has the same split boundary in
`HistoryFragment`: for each selected group it calls `deleteMembersByGroup()` and
then `deleteGroup()` separately, so a failure after the membership delete can
leave the group present but emptied. The UI does not expose a recoverable
partial-deletion state; selection/filter UI is closed or reset after the command
is issued.

**Why this is a defect:** one user-facing destructive action mutates one logical
playlist/group relationship graph, but the durable state can commit only a
prefix of that mutation. A transient Room failure or process death between
statements can silently erase memberships, leave orphan group links, or leave an
existing group/playlist with relationships already removed. This is separate
from `BUG-HISTORY-01`, which covers deleting History rows and Undo, and from
`BUG-BACKUP-07`, which covers backup/restore omission.

Required result:

- delete a playlist, its `PlaylistItemCrossRef` rows, and every
  `PlaylistGroupMember` reference in one Room transaction, or enforce equivalent
  foreign-key/cascade semantics under one authoritative repository operation;
- delete each playlist group and its member rows atomically, and define whether
  multi-selection deletion is all-or-nothing or returns an explicit per-item
  partial result rather than silently mixing success and failure;
- surface persistence failure without closing/resetting the UI as though the
  relationship graph definitely committed;
- add fault-injection/process-death regressions after playlist-item deletion,
  after playlist-row deletion, and after group-member deletion, plus normal
  single/multi playlist and playlist-group deletion tests proving no surviving
  object loses membership and no orphan relation survives.

### P2 — BUG-QUEUE-02 — Preserve worker ownership when moving a queued item to Saved

**State:** Open

**Failure path:** the production Queued Downloads screen loads a `DownloadItem`
and opens `UiUtil.showDownloadItemDetailsCard()`. Its long-click Download action
uses that earlier UI snapshot to enter the reconfiguration/save flow: it changes
the snapshot status to `Saved`, calls
`DownloadViewModel.updateToStatus(id, Saved)`, and then opens the normal download
configuration sheet. The selected row is not reserved while that details card is
open. `DownloadWorker` can independently claim the still-Queued row in the
meantime through `claimDownloadForWorker()`, changing it to `Active` and
installing a fresh `executionId`.

The stale UI action does reload the row indirectly, but the repository does not
use that newer state as a veto. `updateToStatus(..., Saved)` delegates to
`DownloadRepository.moveToSaved()`, whose Room transaction reads the current row
and then unconditionally calls `downloadDao.setStatus(id, 'Saved')`. The DAO
update has no expected `Queued` status or execution-owner predicate and does not
clear or coordinate the active execution token. The same transaction calls
`markLinkedDownloadSaved()`, which can terminalize an attached low-quality child
as `SKIPPED / SAVED_FOR_LATER` and finalize its parent operation even though the
claimed Download worker is still executing.

`DownloadWorker` does not treat `Saved` as a user-stop state; its ordinary stop
check covers `Paused` and `Cancelled`. The worker can therefore continue doing
network/filesystem work until a later owner-and-running-state guarded write fails,
or can already have produced output before that conflict is observed. The
configuration sheet opened by the stale action can subsequently mutate or queue
the same durable row again, creating further competing state transitions while
the original execution still exists.

**Why this is a defect:** a navigation action that was authorized only by an old
Queued UI snapshot can overwrite a newer authoritative worker claim and can
simultaneously tell the low-quality ledger that the work was intentionally
skipped. Durable Download state, ledger state, and the real live process can
therefore disagree, with possible duplicate/reconfigured execution, orphaned
output, false low-quality completion, or a spurious failure of the original
worker. This is distinct from `BUG-FORMAT-02`, whose stale authority comes from a
completed format-notification deep link, and from scheduler/pause defects whose
state transitions enter through different commands.

Required result:

- make Queued -> Saved an expected-state transition that succeeds only while the
  row is still in the UI-authorized non-running state and has no current worker
  ownership; if it has already become `Active`/`PostProcessing`, report it as
  already running instead of rewriting its status;
- bind the details/reconfiguration action to an expected status/generation or
  re-read and validate current ownership immediately before the mutation;
- terminalize a linked low-quality child as `SKIPPED / SAVED_FOR_LATER` only in
  the same transaction that successfully commits the authorized non-running
  Download -> Saved transition;
- never allow a stale Saved/reconfiguration UI to clear, replace, or compete with
  an existing `executionId`; coordinate any deliberate stop-and-reconfigure flow
  through the normal durable cancellation/pause ownership protocol first;
- add deterministic Queued-details -> worker-claim -> Save races for ordinary and
  low-quality-linked downloads, plus already-PostProcessing, normal still-Queued,
  and subsequent configuration/queue-action regressions.

### P2 — BUG-GROUP-01 — Make keyword and Youtuber group deletion atomic

**State:** Open

**Failure path:** the production History group-management UI deletes selected
keyword groups and Youtuber groups directly from `HistoryFragment`. For each
keyword group it calls `KeywordGroupDao.deleteMembersByGroup(id)` and then
`deleteGroup(id)` as separate Room statements. For each Youtuber group it calls
`YoutuberGroupDao.deleteMembersByGroup(id)`,
`deleteRelationsByGroup(id)`, and finally `deleteGroup(id)`, again without one
transaction spanning the logical deletion. The entity models do not declare
foreign keys or cascade behavior from `keyword_group_members`,
`youtuber_group_members`, or `youtuber_group_relations` to their group rows, so a
later statement cannot rely on Room/SQLite to repair an earlier committed
prefix.

If keyword membership deletion commits and group deletion then throws or the
process dies, the group survives but is silently emptied. In the Youtuber path,
a failure after membership or relation deletion can leave the group present with
part of its relationship graph already erased; a failure of the final group-row
delete can leave an emptied/disconnected group, while a future reordering of the
statements would also permit orphan relation rows because no FK protects them.
Multi-selection repeats this non-atomic sequence group by group, so earlier
groups can be fully deleted while a later one is only partially mutated. The UI
resets filters/action state immediately after launching the IO coroutine and does
not expose a partial-deletion result or rollback contract.

**Why this is a defect:** deleting one logical group is a destructive mutation of
the group and all relationships that define its contents/hierarchy, but the
production command can durably commit only a prefix. A transient Room failure or
process death can therefore lose keyword memberships, Youtuber memberships, or
parent/child relationships while the affected group itself remains, and the
normal UI gives no indication of which prefix committed. This is distinct from
`BUG-PLAYLIST-01`, which owns playlist and playlist-group relationship deletion,
and from `BUG-BACKUP-06`, which concerns remapping group identities during
restore.

Required result:

- delete each keyword group together with all `KeywordGroupMember` rows in one
  Room transaction or equivalent FK/cascade contract;
- delete each Youtuber group together with every member row and every parent or
  child `YoutuberGroupRelation` in one authoritative transaction;
- define multi-selection deletion as all-or-nothing or return an explicit
  per-group result rather than silently accepting a partially committed batch;
- surface persistence failure without clearing/resetting the management UI as if
  deletion definitely committed, and keep surviving groups' relationship graphs
  intact;
- add fault-injection/process-death regressions after keyword-member deletion,
  after Youtuber-member deletion, after relation deletion, and before group-row
  deletion, plus normal single/multi deletion and parent/child hierarchy tests
  proving no surviving group loses only part of its graph and no orphan relation
  survives.

### P2 — BUG-COOKIE-01 — Make cookie revocation authoritative before yt-dlp reuse

**State:** Open

**Failure path:** the Cookies screen persists cookie records in Room, but yt-dlp
does not read those rows at request time. `CookieViewModel.updateCookiesFile()`
projects the currently enabled rows into the separate app-cache file
`cacheDir/cookies.txt`, and `YTDLPUtil` attaches that existing file through
`--cookies` for metadata/date fetches and ordinary download requests whenever
`use_cookies` is enabled. The runtime file is therefore an independent
credential-bearing execution input whose contents can outlive a Room mutation.

The production Delete All action does not use one ordered repository operation.
`CookiesFragment` calls `cookiesViewModel.deleteAll()`, which launches an IO
coroutine that only runs `cookieDao.deleteAll()`, and then independently executes
a best-effort `File.writeText("")` against `cookies.txt` inside `runCatching`.
The two mutations race and the filesystem failure is discarded. If the Room
delete commits while truncating the cookie file fails, the visible cookie list
becomes empty but the old credential file remains; the next metadata lookup,
date fetch, or normal download can still attach and send cookies that the user
just deleted. A process death in the split window can produce the same state.
The per-cookie Delete and enabled/disabled paths are ordered slightly better but
still call `updateCookiesFile()` as a new fire-and-forget coroutine after the
Room mutation, so process death after the DB commit can likewise leave a stale
runtime credential projection until some later refresh. `MainActivity` rebuilds
the file on creation, but that eventual repair does not protect requests issued
before recreation and cannot make the deletion boundary atomic.

The inverse split is also observable: the file can be cleared before the Room
Delete All coroutine commits. If the DB mutation then fails or the process dies,
Room still contains enabled cookies while the current runtime file is empty;
a later `MainActivity` recreation regenerates those credentials from the rows.
No generation, pending-revocation state, or extractor preflight proves that the
file being attached corresponds to the latest committed cookie state.

**Why this is a defect:** deleting or disabling authentication cookies is a
user-visible revocation command, but the durable UI state and the actual
credential source consumed by yt-dlp do not share a commit boundary. A normal
filesystem/Room failure or abrupt process death can therefore either keep using
credentials after the UI says they were deleted or later resurrect credentials
from rows after the runtime file was cleared. This is not merely stale display
state: subsequent network requests can be authenticated under a credential set
that contradicts the user's last completed cookie action.

Required result:

- define one authoritative cookie-state generation and commit deletion/enable
  changes together with a recoverable runtime-projection state before reporting
  the revocation as complete;
- never swallow failure to clear/rebuild the credential file after a committed
  revocation; if projection cannot be made current, prevent `--cookies` from
  being attached and surface/retry the projection failure rather than using a
  stale file;
- make Delete All, single Delete, enable/disable, import/update, startup rebuild,
  metadata/date fetches, Terminal authentication, and ordinary downloads use the
  same current-generation projection contract;
- ensure process restart reconciles any pending projection/revocation before an
  extractor can consume cookies, without resurrecting a user-completed delete;
- add deterministic Room failure, cookie-file write/truncate failure, process-
  death-between-boundaries, immediate post-delete metadata/download, disabled-
  cookie, Delete All, single Delete, and restart regressions proving yt-dlp can
  never consume credentials older than the authoritative cookie state.

### P3 — BUG-LOCALADD-02 — Persist local-add session input before durable worker enqueue

**State:** Open

**Failure path:** the History local-add flow expands the selected URIs, serializes
them through `LocalAddStorage.saveEntries()`, and immediately enqueues a
`LocalAddWorker` whose only durable input is the generated session ID.
`saveEntries()` stores the actual URI list with `SharedPreferences.Editor.apply()`,
which updates process memory immediately but commits the file asynchronously.
The WorkManager request is persisted independently. If the process dies after
the WorkManager request becomes durable but before the SharedPreferences disk
write completes, a later worker process can retain the session ID while
`LocalAddStorage.loadEntries()` observes no entry payload and returns
`emptyList()`. `LocalAddWorker` treats an empty list as normal completion, clears
the progress snapshot, and returns `Result.success()` rather than preserving the
missing-session distinction.

**Why this is a defect:** the durable work record can outlive the non-durable
payload that gives it meaning. An abrupt process death in this window silently
turns a user-selected local import into a successful no-op, so WorkManager will
not retry and the user receives no indication that the selected files were
never processed.

Required result:

- persist local-add request payload and worker ownership under one durable
  ordering contract before enqueue can survive process death;
- do not use asynchronous SharedPreferences persistence as the sole backing
  store for input required by durable WorkManager work; use durable database/file
  state or otherwise prove the payload commit completed before enqueue;
- distinguish an intentionally empty request from a missing/corrupt session and
  return a retryable or explicit failure outcome instead of `Result.success()`;
- add a deterministic process-death regression between payload persistence and
  WorkManager execution, plus missing-session, corrupt-session, and normal
  local-add completion coverage.

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

### P3 — BUG-METADATA-03 — Do not report failed Saved-item metadata enrichment as success

**State:** Open

**Failure path:** the production download card's Save for Later action calls
`DownloadViewModel.putToSaved()`, which first persists the Download row as
`Saved`. If title, author, or thumbnail is blank, the view model then enqueues a
one-time `UpdateMultipleDownloadsDataWorker` for that durable row. The worker
runs each requested ID through `MetadataBatchProcessor`. Non-cancellation
exceptions while loading, checking, extracting, or persisting one item are
caught inside the processor and reduced to `MetadataBatchResult.failed`; the
worker only logs that count and still returns `Result.success()`.

There is also a non-throwing loss path. `ResultRepository.updateDownloadItem()`
returns `null` when no usable metadata candidate is available. The worker invokes
it with `?.let { ... }`, so that `null` performs no persistence but returns from
the per-item callback normally. `MetadataBatchProcessor` consequently counts the
item as completed, and the worker again returns success. The Saved row can
therefore retain the same missing metadata that caused background enrichment to
be scheduled, while WorkManager records the one-time request as successfully
finished.

**Why this is a defect:** a production-requested repair of visibly incomplete
Saved metadata can become a durable scheduler success even though no repair was
committed. Transient extractor/database failures and ambiguous empty metadata no
longer have a retry signal, and there is no durable failed/pending enrichment
state for recovery after restart. This is distinct from `BUG-METADATA-01`, which
covers stale full-row writes, and `BUG-METADATA-02`, which covers applying
metadata from the wrong source identity.

Required result:

- return an explicit per-item enrichment outcome that distinguishes updated,
  no-longer-needed, retryable lookup failure/ambiguous empty result, and durable
  persistence failure instead of using nullable success semantics;
- make the worker's WorkManager result reflect unresolved requested items under
  a bounded retry/failure contract rather than returning success whenever the
  outer loop completes;
- treat a request as successful only after an authoritative re-read proves the
  row no longer needs enrichment, or the intended metadata mutation commits;
- preserve `CancellationException` as cancellation and avoid retrying already
  completed items unnecessarily;
- add single-item and mixed-batch regressions for empty metadata, extractor
  exception, Download-row persistence failure, cancellation, process restart,
  retry exhaustion, and successful enrichment.

### P3 — BUG-INCOGNITO-01 — Represent mixed multi-download incognito state correctly

**State:** Open

**Failure path:** the production multi-download configuration sheet initializes
its Incognito control by calling `DownloadViewModel.areAllProcessingIncognito()`
for either the current checked subset or, when no subset is checked, every
Processing row. Despite the function name, both branches return `true` whenever
the corresponding DAO count is merely greater than zero. A mixed set containing
one `incognito = true` item and one `incognito = false` item is therefore
reported to the UI as though every selected item were incognito, and the menu
icon is rendered in the fully-enabled state.

The same menu item uses that icon state as the semantic input for the next
mutation. If the icon is fully enabled, tapping it calls
`updateProcessingIncognito(..., false)`, whose DAO path updates every targeted
Processing row. No parser, adapter normalization, or repository merge prevents
mixed persisted values before this check. A user who selects a mixed set and
sees the incorrect all-enabled state can therefore turn Incognito off for the
entire set with one tap, including items whose previous privacy setting was not
represented accurately by the control.

**Why this is a defect:** a Boolean aggregate named and presented as “all” is
implemented as “any”, and that false authoritative UI state is then used to
choose a persistent bulk mutation. This is not only an icon inconsistency: mixed
per-item privacy configuration is collapsed into a different durable state by a
normal production action without an explicit mixed-state decision.

Required result:

- derive Incognito selection state from the complete targeted set, not from
  `count > 0`; represent all-on, all-off, and mixed explicitly or otherwise
  define a deterministic bulk-toggle contract that does not mislabel mixed data;
- make the mutation depend on the authoritative targeted-row state or an
  explicit user command rather than icon alpha;
- preserve unselected Processing rows and make checked-subset versus no-selection
  semantics unambiguous;
- refresh the aggregate after selection membership or row state changes so the
  displayed state cannot become stale before the write;
- add regressions for all-on, all-off, mixed, selected-subset mixed, no-selection
  whole-batch mixed, selection changes, and one-tap persistence outcomes.

### P3 — BUG-RESULT-01 — Make Result reverse ordering atomic and independent of primary keys

**State:** Open

**Failure path:** the production playlist-selection sheet exposes a Reverse action
that calls `ResultViewModel.reverseResults(resultItemIDs)`. The view model takes
the maximum current Result primary key, assigns each existing Result a future
primary key above that maximum in reverse order, immediately returns those not-
yet-committed IDs to the UI, then launches a background coroutine that waits one
second and calls `ResultRepository.updateID()` once per row. The DAO implements
that operation as `UPDATE results SET id = :newID WHERE id = :id`; the sequence is
not wrapped in one Room transaction and no revision or reservation protects the
future IDs.

A normal concurrent Result insertion can occur after the future IDs are computed
but before all of those delayed PK rewrites finish. SQLite can allocate one of
the supposedly reserved future IDs to that new Result, causing a later
`UPDATE ... SET id = <same value>` to fail on the primary-key uniqueness
constraint. Process death or coroutine failure after only a prefix of updates
creates the same persistent partial-reorder state. Meanwhile
`SelectPlaylistItemsDialog` has already replaced `resultItemIDs` with the full
future-ID list and treats any mismatch between that list and the observed Result
rows as loading, suppressing the recycler layout and disabling selection
controls. No parser or repository layer later reconstructs the intended order or
rolls back a partially remapped set.

**Why this is a defect:** a UI-only ordering command mutates database identity in
multiple independent commits and exposes uncommitted IDs as though they were
already authoritative. A normal insertion race or process interruption can leave
persistent Result rows only partly reordered and can strand the selection sheet
waiting for IDs that will never all appear. Result rows are transient compared
with History, so the impact is limited, but the production action still has a
real consistency and recoverability failure.

Required result:

- represent Result display/order with a dedicated ordering field or an in-memory
  ordered ID list rather than rewriting primary keys for presentation;
- if persistent reordering is required, commit the complete reorder atomically
  under one transaction using a collision-free mapping and do not publish new
  identities before commit;
- keep concurrent Result insertion from colliding with the reorder, or serialize
  insertion/reorder under the same authoritative ordering contract;
- on failure, retain the prior committed order and keep the selection UI usable
  instead of waiting on speculative IDs;
- add deterministic concurrent-insert, process-death/exception-between-updates,
  normal reverse, repeated reverse, and selection-after-reverse regressions.

### P3 — BUG-LOCALADD-03 — Persist unresolved local-add session before worker success

**State:** Open

**Failure path:** the production History local-add flow persists the selected URI
list and enqueues `LocalAddWorker`. During the worker run, candidates that cannot
be accepted into History automatically are accumulated in `pending`. When that
list is non-empty, the worker generates a new session ID, calls
`LocalAddStorage.savePending()`, calls `LocalAddStorage.setOpenSession()`, posts a
system notification whose PendingIntent contains the session ID, and then
returns `Result.success()`. Both `savePending()` and `setOpenSession()` use
`SharedPreferences.Editor.apply()`: they update process memory synchronously but
write to disk asynchronously and expose no durable-write failure result.

The notification and WorkManager completion are independent of those preference
writes. A process death or failed asynchronous preference write after the
notification/worker success boundary can therefore leave the notification's
session ID without a durable `local_add_pending_<id>` payload, or can persist the
payload without the open-session pointer that makes it discoverable after a
restart. Tapping the notification does not recover the missing candidate list:
`MainActivity` forwards the session ID, but `HistoryFragment.openPendingLocalAddSession()`
calls `LocalAddStorage.loadPending()` and simply returns when it observes an empty
list. There is no startup reconciliation that enumerates orphan pending-session
keys or recreates a missing payload from the already-successful worker. The
original selected files remain untouched, but the app can silently lose the
user's pending import/resolution work and report the worker as successfully
finished.

**Why this is a defect:** unresolved candidates are the authoritative output of
the first local-add phase, but that output is stored less durably than both its
success result and its user-visible continuation token. A normal abrupt process
death or preference-write failure can therefore turn a completed local-add scan
into a dead notification/no-op or an undiscoverable pending session, with no
retry or failure signal. This is distinct from `BUG-LOCALADD-02`, which covers
losing the **input URI list before the worker starts**; this path loses the
worker's unresolved **output session after processing has completed**.

Required result:

- persist pending local-add candidates and their session/continuation identity in
  durable database or file-backed state before the worker can return success or
  publish a continuation notification;
- make the pending payload and discoverable/open-session ownership one atomic or
  recoverably ordered contract so neither can survive without the other;
- publish the notification only after that durable commit, and make opening a
  missing/corrupt pending session surface a typed recoverable failure rather than
  silently returning;
- reconcile durable unresolved sessions on startup so a lost notification or
  process death cannot orphan already-processed pending work;
- add deterministic asynchronous-write failure and process-death tests after
  candidate classification, after pending-payload persistence, after open-session
  persistence, after notification publication, and immediately before worker
  success, plus normal notification-open and restart-recovery coverage.

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
| `RETRY-01` | Partial | Same-settings retry is guarded, but the bulk card reconfiguration path can classify unchanged Error settings as `RECONFIGURED`; see `BUG-RETRY-01`. |
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
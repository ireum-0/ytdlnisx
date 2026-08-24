# Post-Split Review Findings

This file is the append-only delta for correctness defects confirmed after review/status records moved to `ledger/remediation`.

- Baseline registry: `TASKS.md`
- Baseline registry synchronized from: `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Baseline active defects: **74**
- Delta active defects: **23**
- Effective active defects: **97**

Production truth always comes from the exact reviewed checkpoint SHA. This file records reviewed findings; it is not permission to implement or to modify the checkpoint branch.

## P2

### BUG-SCHEDULER-05 — Treat pre-Android-12 exact-alarm capability as available

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the app supports Android API 24 and above. `DownloadSettingsFragment` allows the daily `use_scheduler` setting to be enabled on API 24–30 and calls `AlarmScheduler.schedule()` because it only requests the Android 12 exact-alarm special access when `SDK_INT >= 31`. Later, when a user queues a Download outside the configured scheduler window, `DownloadViewModel` calls `AlarmScheduler.canSchedule()` before persisting the queued work. `AlarmScheduler.canSchedule()` returns `false` for every API below 31 solely because `AlarmManager.canScheduleExactAlarms()` is unavailable there. `DownloadViewModel` interprets that false result as unavailable exact-alarm authority, disables `use_scheduler`, marks the queue request unsuccessful, and reports the alarm-permission message instead of using the supported pre-Android-12 exact-alarm path.

Android's platform contract makes this a false capability result rather than an unavailable feature: `AlarmManager.canScheduleExactAlarms()` was added in API 31, and the exact-alarm special-access requirement begins with Android 12 / API 31 for apps targeting that level. The supported API 24–30 band therefore must not be represented as incapable merely because the API-31 capability query does not exist.

**Why this is a defect:** a supported OS band can enable the scheduler in settings but then have ordinary queueing outside the window reject the same scheduler configuration and silently turn it off. The failure is deterministic from the SDK-version branch and blocks a user-requested scheduling workflow even though the platform can schedule the exact alarms the implementation uses. This is distinct from `BUG-SCHEDULER-01` (daily recurrence/midnight semantics), `BUG-SCHEDULER-03` (successor alarms for individually scheduled AlarmManager work), and `BUG-SCHEDULER-04` (daily shutdown cancelling future WorkManager carriers).

Required result:

- define exact-alarm capability by platform version: API 24–30 must be treated as available when the `AlarmManager` service exists; API 31+ must use the platform exact-alarm capability/permission contract;
- make Settings, normal queueing, Observe Source, low-quality redownload, and every other caller interpret the capability helper consistently;
- never disable `use_scheduler` or reject a queue action on API 24–30 merely because `canScheduleExactAlarms()` is an API-31 method;
- preserve the existing API-31+ permission-request/failure behavior when exact-alarm access is actually unavailable;
- add API-band tests for at least API 24/30, API 31+ with access, and API 31+ without access, plus a production-path queue regression proving pre-31 scheduler queueing persists work and arms the scheduler rather than returning the permission failure.

### BUG-ABI-01 — Do not publish non-arm64 APKs without the FFmpeg runtime they invoke

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** `app/build.gradle` explicitly enables release ABI outputs for `x86`, `x86_64`, `armeabi-v7a`, and `arm64-v8a` (plus a universal APK). FFmpeg wrapper dependencies are deliberately disabled, so the app-owned runtime is authoritative. The app-owned `jniLibs` tree, however, contains only `arm64-v8a`; that arm64 directory carries `libffmpeg.so`, `libffprobe.so`, the hard-sub executable variants, and their dependent libraries. The bundled runtime installer also derives `bin/<primary ABI>/ffmpeg_payload.zip`, but production assets contain that payload only for arm64; the x86_64 and armeabi-v7a asset directories contain only `yttml`, and there is no x86 asset directory. `App.installBundledFfmpegPayload()` wraps installation in `runCatching`, logs failure, and lets startup continue.

For every yt-dlp execution, `YoutubeDLCompat.resolveValidFfmpegLocation()` accepts FFmpeg only when both executable `nativeLibraryDir/libffmpeg.so` and `libffprobe.so` exist and the installed payload library directory exists. On the published non-arm64 ABI outputs those conditions cannot be established from the checked-in production artifacts, so no `--ffmpeg-location` is supplied. The hard-sub path is even less tolerant: `DownloadWorker.resolveFfmpegRuntime()` has only `nativeLibraryDir/libffmpeg.so` as its executable candidate and, after finding no usable candidate, falls back to that same first candidate anyway. FFmpeg process start therefore fails or media probes return no streams. Ordinary yt-dlp workflows that require FFmpeg post-processing/merging can fail on these APKs, while a normal hard-sub request can reach `burnSubtitlesInPlace()`, burn zero media, and continue to publish an unburned download because only History hard-sub re-downloads convert `burned == false` into a fatal exception.

**Why this is a defect:** the build advertises and version-codes production APKs for ABIs whose required FFmpeg execution authority is absent. This is not merely an unverified compatibility promise: real production callers gate merging/post-processing on those files, hard-sub directly attempts to execute the missing path, installer failure is non-fatal, and retry/restart cannot materialize an asset that is not packaged for that ABI. The same requested download can therefore have materially different correctness semantics solely because the user installed one of the explicitly published ABI variants. This is distinct from `BUG-HARDSUB-01` through `BUG-HARDSUB-03`, which own subtitle lookup, History replacement semantics, and destructive split-stream replacement respectively; none owns runtime availability across published ABIs.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- define the actual supported release ABI set and make generated APK/AAB outputs match it, or package a complete executable FFmpeg/ffprobe runtime plus required payload libraries for every ABI that remains published;
- make runtime resolution return an explicit unavailable outcome rather than an unusable fallback path, and propagate runtime-install failure to every workflow that requires FFmpeg instead of silently continuing;
- preserve normal no-FFmpeg workflows where FFmpeg is genuinely unnecessary, but never report hard-sub or FFmpeg-required post-processing as successfully completed when the runtime is unavailable;
- verify same-settings retry, raw/manual requeue, reconfigure, notification retry/resume, restart/reconcile, and History re-download: none may reinterpret a missing packaged runtime as transient success or silently downgrade a hard-sub request;
- add assembled-artifact/runtime verification for each published ABI and a production `DownloadWorker` regression that exercises an FFmpeg-required video merge and hard-sub request on every supported ABI. Helper-only source tests are insufficient; verification must prove the packaged runtime is executable and wired into the real worker path.

### BUG-HISTORY-04 — Do not identify destructive History duplicates by title alone

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the History overflow action presents a destructive confirmation dialog and, on confirmation, calls `HistoryViewModel.deleteDuplicates()`. That view model asks `HistoryRepository.getDuplicateGroups()` for the targets. `getDuplicateGroups()` loads downloaded History rows, discards only blank titles, groups every remaining row solely by `item.title.trim()`, keeps every group with more than one member, and chooses the oldest row as the retained record. `deleteDuplicates()` then merges keyword assignments from every other row into that retained row and calls `repository.deleteRecords(...)` for all of those other History IDs. `deleteRecords()` removes playlist cross-references and the History rows themselves.

No source URL, extractor/video identity, normalized media key, download path/content identity, type, or immutable provenance is checked before the destructive target set is formed. Two unrelated videos from different sources, channels, playlists, dates, or local imports can legitimately have exactly the same title, so a normal user-visible title collision is enough to authorize deletion of one record as a supposed duplicate. The media file is not deleted by this path, but the History record and its playlist membership are removed and the surviving record receives the removed row's keyword assignments.

**Why this is a defect:** title equality is display metadata, not duplicate identity. The user invokes a command whose semantics are to remove duplicates, but the implementation can destructively collapse distinct media records that merely share a title. The resulting loss is persistent: the removed History identity and playlist membership are deleted, while its file can become orphaned from History. This is separate from download-time duplicate-prevention defects (`BUG-DUPLICATE-*`), which govern archive/source checks before download rather than destructive deduplication of existing History records.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- define duplicate identity from stable media provenance, using a source-specific immutable key where available and a conservative verified fallback for local/file-only records; title equality alone must never authorize destructive deduplication;
- treat ambiguous same-title records as distinct and preserve both History rows, playlist memberships, keyword assignments, and files;
- compute and revalidate the duplicate identity at the destructive boundary so concurrent metadata edits cannot turn a previously displayed candidate set into authority for deleting a different semantic record;
- make assignment merge plus playlist/History mutation one coherent transaction or otherwise provide rollback/convergence so process death or write failure cannot leave a partially merged deduplication;
- preserve retry/re-entry semantics: rerunning duplicate cleanup after a partial failure must not broaden identity from surviving display metadata or delete a row that failed the original identity proof;
- add focused production-path tests with two different URLs sharing the exact same title, same URL with title variation, cross-site same-title items, local items with the same filename/title but different files, playlist memberships, keyword assignments, a metadata-edit race between grouping and deletion, and first-write/process-death fault injection across assignment merge and History deletion.

### BUG-OBSERVE-04 — Make Observe Source stop/delete revoke in-flight worker authority before stale side effects

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the production Observe Sources screen lets the user pause a source with `stopObserving()` or delete one/all sources with `delete()` / `deleteAll()`. Those paths request WorkManager cancellation through `cancelUniqueWork()` / `cancelAllWorkByTag()`, but the returned cancellation `Operation` is ignored and no exact worker-generation/lease is revoked or awaited before the durable source mutation proceeds. `stopObserving()` writes `STOPPED` and then requests cancellation; delete requests cancellation and then deletes the source row. An already-running `ObserveSourceWorker` can have loaded an `ObserveSourcesItem` snapshot before either user action and continue after the request because its subsequent authority checks use that stale in-memory item rather than re-reading the current source row or a revocation generation at side-effect boundaries.

This is correctness-significant in two concrete production branches. First, a stale worker whose snapshot still has `status = ACTIVE` and `syncWithSource = true` can fetch the source and enter the History reconciliation/deletion path after the user has paused or deleted the source; there is no current-source existence/status revalidation immediately before that destructive reconciliation. Second, if a pause races with the worker's normal completion, `finishRunAndSchedule()` writes the stale full `ObserveSourcesItem` back through `repo.update(item)` and then enqueues the next `OBSERVE<id>` work. Because the worker snapshot still carries `ACTIVE`, that write can overwrite the user's durable `STOPPED` state and resurrect recurring observation. For deletion, the stale update does not recreate the missing row, but the already-authorized run can still perform download/discovery/History side effects before its eventual no-op update, and it may enqueue one more successor that only fails later when the row is missing.

**Why this is a defect:** pause/delete are explicit user revocations of future Observe Source authority. The implementation treats best-effort asynchronous WorkManager cancellation as if it were a synchronization barrier, while the worker holds mutable full-row state and destructive/queueing authority independently of the current persisted source. A normal race can therefore continue or even re-enable activity the user explicitly stopped, including destructive `syncWithSource` History mutation. This is distinct from `BUG-OBSERVE-01` (non-authoritative source snapshot completeness), `BUG-OBSERVE-02` (configuration edits resetting worker-owned runtime counters/history), and `BUG-OBSERVE-03` (loss of the successor WorkManager carrier while durable ACTIVE intent remains).

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- give each Observe Source execution a durable generation/lease or equivalent exact authority token and revoke it atomically with STOPPED/delete before user revocation is considered committed;
- make every side-effect boundary that can queue downloads, mutate discovery state, delete/reconcile History, or schedule a successor prove that the source still exists, is ACTIVE, and that the worker still owns the same generation;
- never let a worker's stale full-row completion update overwrite user-owned STOPPED/deleted state; persist only worker-owned runtime fields under expected-generation/status predicates;
- treat WorkManager cancellation as transport cleanup, not as the semantic revocation barrier, and tolerate delayed/failed cancellation without stale side effects;
- for delete, ensure an in-flight worker that loses authority converges without recreating or rescheduling the source and without applying post-revocation History/download/discovery mutations;
- verify the cross-attempt matrix for pause, delete-one, delete-all, manual Search/Run Now, restart/reconcile, and any successor already enqueued before revocation: no path may restore ACTIVE or regain destructive authority without a new explicit user Start;
- add deterministic production-path races that latch a worker after loading the source and before (a) `syncWithSource` deletion, (b) download creation, and (c) `finishRunAndSchedule()`, then pause/delete the source while delaying or failing WorkManager cancellation. Assert no post-revocation side effect occurs, STOPPED cannot be overwritten by stale ACTIVE state, deleted sources are not rescheduled, and restart does not revive revoked authority. Include first-write/recovery-write fault injection around the durable revocation update/delete. Helper-only tests are insufficient.

### BUG-UPDATER-02 — Serialize yt-dlp runtime updates and bind completion to the requested source

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** `MainActivity` can start an automatic yt-dlp update in an independent `SupervisorJob` during app startup, while the Updating settings screen can start another update from the version/update controls or immediately after changing the selected yt-dlp source. Source selection first writes the new `ytdlp_source` preference and then calls `UpdateUtil.updateYoutubeDL(source)`. `UpdateUtil` contains an apparent process-global serialization guard, but the `if (updatingYTDL) { YTDLPUpdateResponse(PROCESSING) }` branch discards the response instead of returning it; every caller therefore continues, sets `updatingYTDL = true`, and mutates the same app-owned yt-dlp runtime. The flag is also never reset, so it does not represent actual ownership or completion.

A concrete ordering is startup update A reading/selecting `stable`, followed by the user changing the source to `nightly` and launching update B. The preference now describes `nightly`, but A and B are both authorized to replace/update the same yt-dlp runtime. If B completes first and A completes later, the final runtime can be the older request's `stable` artifact while the persisted selected source remains `nightly`. Both callers can independently report successful completion or refresh the displayed version; neither completion is conditioned on still owning the current source generation. Reversing completion order produces a different runtime from the same user-visible sequence. Rapid repeated manual updates have the same missing-serialization property, and custom `--update-to` requests share the same runtime mutation domain.

**Why this is a defect:** the selected update source is user-owned persistent configuration, while the installed yt-dlp executable/runtime is its materialized execution state. The implementation permits an obsolete request to commit after a newer source selection and silently make those two states disagree. This is a real ordering-dependent correctness/reliability failure, not defensive hardening: subsequent downloads execute the final installed runtime, while future UI/auto-update logic treats the persisted source as authoritative. There is no source-generation carrier or startup reconciliation that proves the installed runtime corresponds to the current preference. This is distinct from `BUG-UPDATER-01`, which owns custom-source error fallthrough and false success within one update attempt; `BUG-UPDATER-02` concerns concurrent ownership and stale completion across otherwise successful attempts.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- serialize every production yt-dlp runtime mutation under one exact process-wide ownership primitive, covering startup auto-update, settings/manual update, source-change update, and any worker entrypoint that is actually wired;
- bind each update to the source/configuration generation it was authorized for and prevent an older request from committing or reporting current success after a newer source selection supersedes it;
- make the updating/ownership state lifecycle real: acquisition must return/queue explicitly when already owned, and release must occur in `finally` for success, failure, and cancellation rather than leaving a sticky Boolean with no semantic effect;
- after completion, ensure persisted selected source and installed runtime provenance/version describe the same committed request, or record a durable incomplete/mismatch state that a later retry/restart can converge without silently claiming success;
- preserve `BUG-UPDATER-01` failure semantics: serialization must not turn custom updater `ERROR` into success or allow a stale successful attempt to mask a newer failed/current attempt;
- verify the cross-attempt matrix for repeated same-source update, source reconfiguration while an update is running, startup auto-update racing manual update, cancellation/recreation of the settings screen, and process restart. Download retry/requeue paths are not semantic re-entry paths for this updater state and should be marked not applicable rather than assumed safe;
- add deterministic production-path concurrency tests that latch update A after source capture, persist/select source B and run update B, then release A in both completion orders. Assert one exact owner mutates the runtime at a time, stale A cannot overwrite B's committed source/runtime state or report current success, and failure/cancellation releases ownership. Include an executed integration test against the real `UpdateUtil`/runtime updater wiring; helper-only Boolean tests are insufficient.

### BUG-MIGRATION-01 — Publish default-video-folder moves only with durable History reference updates

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the Folder settings action for migrating the default video folder first loads all video `HistoryItem` rows into memory. For each path that still exists directly under the old default video directory, `migrateDefaultVideoFolderInternal()` calls `moveFileToDestination()`. Both supported move branches can make the new destination authoritative before Room is updated: the raw-file branch can rename the file or copy it and then delete the source, while the SAF branch creates/copies the destination and then deletes the source. Only after that destructive filesystem step returns does migration call `historyDao.update(item.copy(downloadPath = updatedPaths))`.

If the process dies or that first History persistence call throws after the file move, the durable History row still names the old path even though the old file has already been removed. No migration journal, old->new mapping, retry carrier, or startup reconciliation is persisted before source deletion. A later rerun loads the stale History row, observes that the old file no longer exists, and skips it, so it cannot deterministically reconstruct the newly chosen raw filename or SAF document URI. The same write also uses the pre-migration full `HistoryItem` snapshot; `HistoryDao.update()` ultimately performs an `@Update` of the whole row (apart from preserving materialized keywords), so a concurrent legitimate History mutation between the initial snapshot and migration commit can be overwritten when migration intends to change only `downloadPath`.

**Why this is a defect:** a user-requested storage migration can durably break the History-to-media reference across an ordinary process-death or Room-write failure window after the application has already removed the authoritative old file. The media may still exist at the destination, but the application has lost its durable identity/location and normal retry cannot converge. Independently, the stale full-row commit can erase newer History state. This is a persistence/publication-order correctness defect, not defensive hardening, and it is distinct from `BUG-MOVE-01` (partial folder-copy cleanup), `BUG-HISTORY-02` (retained-reference deletion TOCTOU), and cache migration defects.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- give each per-file migration a durable intent/mapping or equivalent recoverable carrier before the old file can be removed, and define restart reconciliation for `old missing/new present`, `old present/new present`, and incomplete-copy states;
- publish the new History `downloadPath` only under an expected old-path/row identity predicate and update only migration-owned fields so a stale migration snapshot cannot overwrite concurrent History metadata or replacement state;
- do not irreversibly delete the old file until either the new reference is durably committed or a compensating rollback can restore the old reference/file mapping;
- make raw-file rename, copy fallback, and SAF document migration follow the same commit/recovery contract and clean up duplicate destinations only when ownership is proven;
- on persistence failure, surface a failed/incomplete migration rather than reporting success or leaving an unrecoverable stale reference.

Focused verification requirements:

- fault-inject process death and a throwing first `historyDao.update()` immediately after successful raw rename, raw copy+source-delete, and SAF copy+source-delete; prove restart/rerun recovers an exact usable History reference;
- race a concurrent History metadata edit and a History replacement/reconnect against a latched migration and prove migration cannot revert fields or paths it does not own;
- cover rerun after every partial state, destination-name collision, multiple History rows referencing the same source path, and a normal complete migration;
- verify through the real Folder-settings -> migration -> filesystem -> Room wiring. Helper-only move tests are insufficient.

### BUG-RUNTIME-01 — Stage and verify bundled FFmpeg payload updates before replacing the live runtime

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** on startup, and again from production FFmpeg-resolution paths, `App.ensureRuntimeToolsInstalled()` enters `installBundledFfmpegPayload()`. When the installed payload does not exactly match the current expected revision, the installer deletes the live `noBackupFilesDir/youtubedl-android/packages/ffmpeg` tree first, recreates that same live directory, and extracts the replacement ZIP directly into it. A real upgrade path exists: the bundled-runtime implementation was introduced with revision `arm64-termux-ffmpeg-8.0.1-openssl3-r1`, while the reviewed checkpoint expects `arm64-wrapper-libffmpeg-0.18.1-r12`. Thus an otherwise working older payload can legitimately enter this replacement branch after an application update.

There is no staging directory, atomic publish/rename, rollback copy, or durable install journal before the old runtime is removed. Any filesystem/extraction exception after `deleteRecursively()` therefore destroys the previously working payload and leaves either an empty or partially populated live runtime directory. The top-level `runCatching` logs that failure and returns normally. Required dependency copying is weaker still: `copyRequiredBundledRuntimeDependencies()` catches each missing/copy failure internally, so installation can proceed to write the new `.payload_revision` and log success even when a required dependency was not materialized.

The runtime readers do not repair this publication gap. `YoutubeDLCompat.resolveValidFfmpegLocation()` treats the FFmpeg payload as acceptable when the native FFmpeg/ffprobe executables exist and the payload `usr/lib` path is merely a directory; it does not verify the revision or required shared libraries. `DownloadWorker.resolveFfmpegRuntime()` likewise invokes `ensureRuntimeToolsInstalled()` but then includes the extracted library directory whenever it exists and can return the native FFmpeg executable. A failed or partially successful replacement can therefore remove a previously usable runtime, then either cause subsequent FFmpeg-required downloads/post-processing to fail or expose an incomplete library set as the current runtime until a later reinstall happens to succeed.

**Why this is a defect:** installing an application-bundled runtime is a publication operation over shared execution state. A transient I/O/storage/extraction failure during a normal app-version transition must not irreversibly replace a known-good runtime with a partial one while the installer reports no authoritative failure to its callers. The defect is production-reachable on an ABI that has all required packaged artifacts, so it is distinct from `BUG-ABI-01`, which owns variants where the required runtime is absent from the package altogether. Retry/restart can attempt installation again, but neither path can restore the deleted previously working revision, and no durable state distinguishes a verified current payload from a partial live tree.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- extract and materialize the replacement payload in an attempt-scoped staging directory without mutating the currently verified runtime;
- verify the revision plus every required runtime library/dependency and executable linkage needed by production callers before publication;
- atomically publish the fully verified staged runtime, or use an explicit rollback protocol that preserves the old verified payload until the new one is authoritative;
- make installer failure an explicit result consumed consistently by yt-dlp and hard-sub runtime resolution; a partial directory must never satisfy runtime-availability checks;
- persist enough install generation/provenance to make process-death recovery deterministic and clean only staging artifacts whose ownership is proven;
- keep `BUG-ABI-01` ownership separate: unsupported/missing packaged ABI artifacts remain an availability defect, while this item governs failure-safe replacement of an otherwise available runtime.

Cross-attempt / recovery requirements:

- same-settings retry, manual/raw requeue, reconfigure, and notification retry/resume must either use the last verified runtime or re-enter one serialized verified installation attempt; they must not reinterpret a partial live tree as valid;
- process restart/reconciliation must identify and discard/finish an owned staging attempt without losing the last verified runtime;
- app upgrade across two bundled runtime revisions must preserve old-runtime usability until the new revision is fully verified and published;
- restore is not a semantic re-entry path for this app-bundled runtime and should remain not applicable rather than fabricating runtime provenance from backup data.

Focused verification requirements:

- fault-inject failure immediately after old-revision detection, after staging begins, during ZIP extraction, during each required dependency copy, before revision publication, and at final publish/rename; prove the old verified runtime remains usable or the new runtime is completely verified, never a partial live tree;
- inject process death at the same publication boundaries and prove restart converges without treating an incomplete directory as current;
- add a production-path regression that starts from a valid older payload revision, triggers the real `ensureRuntimeToolsInstalled()` upgrade, then executes an FFmpeg-required yt-dlp request and hard-sub runtime resolution;
- test resolver rejection of missing required libraries even when `usr/lib` exists and native `libffmpeg.so`/`libffprobe.so` are executable. Helper-only tests are insufficient.

### BUG-TERMINAL-06 — Protect live Terminal cache from manual cache cleanup

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** Folder settings exposes a user-facing Clear Cache action. After category selection and confirmation, `deleteCacheCategories()` calls `hasActiveDownloads()` once; that helper snapshots ordinary `Active`/`PostProcessing` Download count plus `terminalDao.getActiveTerminalsCount()`. If both are zero, the coroutine then switches to IO and calls `AppCacheManager.delete(categories)` with no shared execution lease, transaction, or second authority check. `AppCacheManager` treats `TERMINAL_CACHE` as the whole `<download-cache>/TERMINAL` tree and recursively enumerates/deletes every entry under it.

A Terminal task can become authoritative after that zero-active snapshot. The Terminal Run path persists a new `TerminalItem`, enqueues `TerminalDownloadWorker`, and the worker's normal staged-output plan writes yt-dlp output under `<download-cache>/TERMINAL/<taskId>`. If that row/worker starts after `hasActiveDownloads()` returns false but before or during `AppCacheManager.delete(TERMINAL_CACHE)`, the maintenance path can delete files and directories that the live yt-dlp execution owns. The cleaner does not re-read Terminal identity per directory, acquire a Terminal execution lease, cancel the task, or distinguish pre-existing leftovers from a newly created task directory. The worker can then fail extraction/publication because its output disappeared; its exception path deletes the Terminal row and staged directory, so restart has no durable carrier that can restore the destroyed in-flight attempt.

**Why this is a defect:** a maintenance action that is permitted only while Terminal work is idle relies on a stale aggregate count rather than an ownership barrier at the destructive filesystem boundary. A normal supported Terminal execution and Clear Cache action can therefore race so that maintenance destroys files owned by a newer task. This is distinct from `BUG-CLEANUP-02`, which owns the analogous automatic-leftover-cleanup race against ordinary Download temp directories, and from `BUG-TERMINAL-04`, which concerns a Terminal worker itself misclassifying partial publication after `FileUtil.moveFile()`. Here an independent user-facing maintenance path destroys Terminal execution state without owning or revoking that task.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same code is present at the prior reviewed checkpoint, but its Terminal-specific ownership invariant was not represented in `TASKS.md` or the existing delta.

Required result:

- make `TERMINAL_CACHE` cleanup prove per-task non-ownership at the destructive boundary, or serialize cache deletion with Terminal task creation/execution under a shared authority primitive so a new task cannot acquire a directory while cleanup can delete it;
- do not treat one earlier aggregate active-count snapshot as authority to recursively remove later-created task directories;
- preserve task identity through cleanup: if a directory belongs to a persisted/runnable/running Terminal task, skip it unless the exact task is durably cancelled/terminalized before filesystem deletion;
- make cleanup and Terminal startup/retry/restart converge without deleting a live attempt or leaving a persisted task whose only staged output was removed;
- keep ordinary Download cleanup ownership separate from this Terminal-specific task/cache namespace while applying the same ownership-at-mutation invariant.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: the exact Terminal task/directory ownership at deletion time, not the earlier aggregate count;
- first destructive call: the first `File.delete()` reached by `AppCacheManager.delete(TERMINAL_CACHE)`; inject a task start immediately before and during enumeration/deletion and prove the live directory is preserved;
- durable task state/filesystem effect: a running/runnable `TerminalItem` must retain its staged files and execution carrier; cleanup must not force the worker into failure or cause task-row removal through the worker's exception path;
- same-command retry, manual rerun, task cancellation, process restart/reconciliation, and already-running WorkManager execution must each preserve exact task identity and must not reinterpret a cleanup-damaged attempt as successful or safely abandoned;
- ordinary Download same-settings retry/reconfigure/notification retry are not semantic re-entry paths for Terminal cache ownership and should be marked not applicable rather than assumed safe.

Focused verification requirements:

- add a deterministic production-path race that pauses Clear Cache after its final `hasActiveDownloads()` false result, starts a cache-staged Terminal task, creates/writes `<cache>/TERMINAL/<id>`, then resumes `AppCacheManager.delete(TERMINAL_CACHE)` and proves the task directory is not deleted;
- cover a task that becomes runnable but has not created its directory yet, a task already writing output, a task publishing from cache, cancellation during cleanup, restart after the maintenance/worker race, and true stale Terminal directories that should still be removable;
- exercise the actual Folder-settings -> `AppCacheManager` -> `TerminalDownloadWorker` wiring. Helper-only path-policy tests are insufficient.

### BUG-COOKIE-03 — Do not report WebView cookie capture as success before credentials are durably usable

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** when a Home extraction/search failure offers cookie acquisition, `HomeFragment` launches `WebViewActivity` and treats `Activity.RESULT_OK` as proof that credentials are ready: it persistently sets `use_cookies = true` and immediately calls `startSearch()`. The Generate action in `WebViewActivity`, however, calls `cookiesViewModel.getCookiesFromDB(url).getOrNull()?.let { ... }` and then unconditionally returns `RESULT_OK` and finishes. If WebView's cookie database is missing, has no cookies, cannot be opened/read, or another extraction exception occurs, the failed `Result` is collapsed to `null`, the persistence block is skipped, and the caller still receives success.

The success path has a second publication gap. `CookieViewModel.insert()` is awaited, but `updateCookiesFile()` is not an awaited projection operation; it starts a separate `viewModelScope.launch(Dispatchers.IO)` and returns immediately. The `runCatching` in `WebViewActivity` therefore cannot observe runtime cookie-file write failures or completion, and finishing the activity can clear the activity-scoped ViewModel while that projection is still pending. Even an `insert()` exception is caught only to show a Toast and then falls through to the same unconditional `RESULT_OK`. Consequently the Home retry can begin with no credential row, a stale/missing `cookies.txt`, or a projection that is racing the retry, while persistent configuration already claims cookies are enabled.

**Why this is a defect:** the Activity result is a semantic commit barrier for an authentication recovery workflow, but it is emitted before the three required states—authoritative cookie extraction, Room persistence, and runtime cookie-file materialization—are proven usable. A normal extraction/read/write failure or lifecycle race can therefore be reinterpreted as credential acquisition success, persist a false enabled state, and immediately repeat the failed request without the credentials the UI says were captured. This is distinct from `BUG-COOKIE-01`, which owns revocation/projection correctness after persistent cookie state changes, and `BUG-COOKIE-02`, which owns clipboard export result reporting; neither owns the WebView acquisition handoff and caller-side retry contract.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- return `RESULT_OK` only after cookie extraction succeeds, the intended Room cookie record is durably inserted/updated, and the exact runtime cookie projection required by the caller is proven complete and usable;
- make cookie-file projection an awaitable typed operation whose write/callback failures and cancellation reach the acquisition caller rather than being detached behind `viewModelScope.launch`;
- on extraction, Room persistence, or projection failure, preserve a retryable/error state in the WebView flow and do not let Home persist `use_cookies = true` or automatically restart the search as though credentials were ready;
- bind the success handoff to the same requested URL/cookie generation so a stale projection cannot authorize a newer acquisition/retry;
- on restart or later retry, fail closed when configuration says cookies are enabled but no usable persisted/projected credential state exists, rather than treating the preference alone as proof of credential readiness.

Cross-attempt / result-handoff requirements:

- immediate Home retry after capture must observe the exact completed cookie generation before extraction begins;
- repeated acquisition, manual search retry, activity recreation/cancellation, and process restart must preserve failure vs success rather than converting an incomplete attempt into an enabled-cookie state;
- Cookies-screen editing/re-acquisition may repair a failed attempt, but must do so through a new explicit successful projection rather than inheriting the earlier `RESULT_OK`;
- Download same-settings retry, raw requeue, reconfigure, notification retry/resume, and restore are not semantic re-entry paths for this Activity-result acquisition contract unless they independently consume the same cookie-ready state; mark them not applicable rather than assuming safety.

Focused verification requirements:

- add production-path Home -> `WebViewActivity` -> `CookieViewModel` -> runtime-cookie-file tests for no cookies, missing/unreadable WebView SQLite cookie DB, Room insert failure, runtime projection write failure, lifecycle cancellation after Room insert, and complete success;
- latch `updateCookiesFile()` after Room persistence and prove `RESULT_OK`, `use_cookies = true`, and `startSearch()` cannot occur until projection completion is authoritative;
- verify activity recreation and process restart after every incomplete state, proving failed capture cannot survive as an apparently enabled credential configuration;
- exercise the real ActivityResult caller and generated cookie-file consumer. Helper-only cookie parsing tests are insufficient.

### BUG-RESUME-01 — Do not require overlay-window authority for notification Retry/Resume actions

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** both notification re-entry paths use a normal `PendingIntent.getActivity()` targeting `ResumeActivity`: paused-download notifications created by `NotificationUtil.createResumeDownload()` carry the Download ID plus expected `executionId`, while retryable error notifications carry the Download ID plus retry operation/attempt capability. `ResumeActivity.onCreate()` changes its Activity window type before consuming either capability: API 26+ uses `TYPE_APPLICATION_OVERLAY`, and API 24–25 uses `TYPE_SYSTEM_ALERT`. The manifest does not declare `android.permission.SYSTEM_ALERT_WINDOW`, and this flow never requests or verifies overlay special access. Android requires `SYSTEM_ALERT_WINDOW` for `TYPE_APPLICATION_OVERLAY`; on target API 23+ overlay authority also requires explicit user approval, and pre-26 system-window types likewise require special system-window authority. An ordinary supported install therefore cannot rely on that authority. Window attachment can be rejected before `handleIntents()` reaches `retryFailedDownload()` or `resumePausedDownloadAndWait()`.

**Why this is a defect:** Retry and Resume are advertised production notification actions, but their Activity self-imposes an unrelated privileged window type that the application neither declares nor acquires. The exact retry/resume capability is correctly transported to the Activity yet can never reach its authoritative state transition under the normal permission model. Tapping the action again or restarting the app does not create overlay authority, so the notification path remains broken until the user takes a different in-app recovery route. This is distinct from existing pause/resume ownership defects that concern stale or misleading resume capability after state races; this item concerns valid notification re-entry being blocked before semantic authorization is consumed.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- keep notification Retry/Resume on an ordinary application Activity/window path unless a genuine overlay use case is separately designed, declared, requested, and authorized;
- do not make retry/resume correctness depend on `SYSTEM_ALERT_WINDOW` or other unrelated special access;
- preserve the existing exact `executionId` and retry operation/attempt checks when the Activity reaches the state-changing call;
- if Activity/window setup itself fails, do not consume or cancel the user's recovery affordance as though the retry/resume transition occurred;
- apply one consistent supported-version contract across API 24–25 and API 26+ rather than selecting two privileged system-window types.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision/carrier: the notification's expected execution identity or retry operation/attempt capability; current code transports it but encounters the platform window-authority gate first;
- first persistence call: `resumePausedDownloadAndWait()` or `retryFailedDownload()`; in the defect path it is not reached, so no first-write recovery can repair this launch failure;
- durable Download/linked-ledger state and filesystem effect remain unchanged by the failed notification attempt; no replacement WorkManager carrier is created by this path and no new Download outcome is committed;
- repeated tapping of the same notification and process restart repeat the same window-authority failure. Manual in-app retry/reconfigure may provide an independent recovery route but does not make the advertised notification action correct; restore is not a semantic repair for this admission failure;
- no concurrency or sibling-lock ordering is needed to trigger the defect. Exact retry/resume identity may still be valid and immutable; it is simply never consumed.

Focused verification requirements:

- add production-path Activity/PendingIntent coverage on supported API bands, including API 24/25 and API 26+, with no overlay permission granted; tap both a paused-download Resume notification and a retryable Error notification and prove `ResumeActivity` reaches the exact capability-checked state transition without a window-security failure;
- assert the activity remains a normal application window and that the manifest does not need `SYSTEM_ALERT_WINDOW` for this workflow;
- cover stale execution/retry capabilities as negative controls so removing the privileged window type does not weaken the existing expected-identity checks;
- execute the real `NotificationUtil -> PendingIntent -> ResumeActivity -> DownloadViewModel` wiring. Helper-only intent-construction tests are insufficient.

### BUG-LOCALADD-04 — Isolate Local Add entry failures and retain a recoverable batch carrier

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** History Local Add expands the user's selected files/directories into a list of `LocalAddEntryDto`, stores that list under a session ID, and enqueues one `LocalAddWorker`. The worker processes `dedupedEntries.forEach` with a `try/finally` that advances progress but has no per-entry non-cancellation catch. Several entry-local operations can throw, including provider metadata access and Room reads/writes. If entry B throws, the exception escapes `doWork()` rather than being represented as B's outcome or `Result.retry()`.

The batch is not atomic or durably checkpointed per entry. An earlier exact-match entry A may already have been inserted into History, while an earlier unresolved candidate is held only in the in-memory `pending` list until the entire loop completes. When B aborts the loop, that pending output is never published and all later independent entries C...N are never processed. `clearEntries()` is reached only after a successful full loop, so the original input JSON can remain in SharedPreferences, but the failed WorkManager attempt creates no successor and the History UI only treats `RUNNING`/`ENQUEUED` work as active; it has no failed-session re-enqueue/reconcile path. `LocalAddStorage` also has no durable index/open-session carrier for orphaned input sessions. The practical terminal state is therefore a partially applied Local Add request with some History writes committed, some unresolved results lost from the continuation flow, and unrelated selected siblings stranded until the user manually selects them again.

**Why this is a defect:** the selected entries are independent user-authorized additions, but one entry's transient/provider/database failure aborts the whole durable intent after arbitrary earlier side effects have committed and without a durable remainder owner. This is not merely an all-or-nothing batch policy: the implementation already commits individual History rows as it goes, so exceptional termination can expose partial success while silently abandoning later work. The defect is distinct from `BUG-LOCALADD-01` (bare filename identity), `BUG-LOCALADD-02` (input session durability before worker enqueue), and `BUG-LOCALADD-03` (normal worker completion before unresolved-output persistence is durable); none owns sibling failure isolation after the worker has begun processing a persisted batch.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- give each Local Add entry an explicit success/unresolved/failure outcome boundary so a non-cancellation failure for one entry cannot abort unrelated siblings; user/worker cancellation must remain distinguishable and may still stop the whole session;
- durably checkpoint enough session progress and unresolved output before or with per-entry side effects so process death or exceptional exit can recover the exact remaining entries without relying on an unindexed SharedPreferences key;
- preserve already committed History additions idempotently on retry/restart and never duplicate them while resuming the same session;
- surface a complete/partial/failed session result or continuation that tells the UI which entries need attention instead of making a failed WorkRequest disappearance look like completion;
- provide startup/re-entry reconciliation for a persisted Local Add session whose WorkManager carrier terminated before the batch reached a durable terminal state.

Terminal fault / cross-attempt requirements:

- authoritative carrier: the persisted session ID plus exact selected entry identities; per-entry completion/failure must become durable before the session can advance past that entry;
- first durable side effect can be a History insert for an earlier entry; inject a later sibling failure and prove the prior commit remains represented while unresolved and unprocessed siblings retain a durable recovery owner;
- Download state, linked Download ledgers, and filesystem publication are not applicable to this workflow; the relevant durable ledgers are Local Add session progress plus History rows;
- an unhandled entry exception must not be the terminal owner of the entire session. WorkManager retry/replacement or an application reconciliation carrier must preserve exact remaining-work identity;
- same-session retry, manual reselection, process restart/reconcile, cancellation, and provider-access recovery must be idempotent. Reconfigure, notification Download retry/resume, and backup restore are not semantic re-entry paths unless they explicitly consume the same Local Add session.

Focused verification requirements:

- exercise the real `HistoryFragment -> LocalAddStorage -> WorkManager -> LocalAddWorker -> Room` path with at least three entries where A becomes unresolved or is inserted, B throws from provider/Room access, and C is otherwise valid; prove B is isolated/reported, A remains correctly represented, and C is eventually processed;
- fault-inject a History insert/read failure, provider metadata exception, process death between entries, and failure after one committed History row but before pending-output publication;
- restart the app with a partially processed persisted session and prove exact remaining entries are recovered once without duplicate History rows or lost unresolved candidates;
- verify explicit cancellation remains cancellation rather than being reinterpreted as an item failure. Helper-only parsing/storage tests are insufficient.

### BUG-TERMINAL-07 — Revoke Terminal publication authority before deleting a cancelled task

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** a running cache-staged Terminal task exposes a notification Cancel action wired to `CancelTerminalNotificationReceiver`. The receiver derives the exact Terminal process identity and calls both yt-dlp destroy helpers, but then calls `WorkManager.cancelUniqueWork(terminalId.toString())` without awaiting the returned cancellation `Operation`. It immediately cancels the notification and deletes the `TerminalItem` row. `TerminalDownloadWorker`, meanwhile, does not use that row as a generation/revocation check after startup: once `YoutubeDLCompat.execute()` returns, it can enter `FileUtil.moveFile(<cache>/TERMINAL/<id>, destination, keepCache=false)` and publish staged output without proving that the Terminal task still exists or remains authorized.

A concrete race exists at the native-execution/publication boundary. If yt-dlp has already returned (or returns before WorkManager cancellation is delivered) when the user taps Cancel, the receiver can durably delete the only task row while the worker still advances into the cache-to-destination move. WorkManager cancellation is transport cleanup, not a synchronous semantic barrier, and there is no shared Terminal execution lease between receiver deletion and worker publication. The stale worker can therefore create/move destination files after the user-requested cancellation has been committed as task removal. Cancellation arriving during a non-atomic multi-file move can also leave a partial destination while the Terminal row is already gone. The worker's normal and cancellation paths later delete the row again and may return success, but neither can reconstruct or durably represent an output that was published or partially published after cancellation.

**Why this is a defect:** notification Cancel is an explicit revocation of the Terminal attempt, yet the implementation destroys its durable carrier before proving that every actor holding publication authority has stopped. A normal scheduling race can make a cancelled task publish output after revocation, or leave partial filesystem effects with no persistent task identity from which restart/retry can reconcile them. This is distinct from `BUG-TERMINAL-02` (cross-domain ID/process identity), `BUG-TERMINAL-04` (the worker misclassifying its own partial publication as success), `BUG-TERMINAL-05` (persisted task with lost enqueue carrier), and `BUG-TERMINAL-06` (manual cache cleanup deleting files owned by a live task). The same receiver/worker ordering is present at the prior synchronized checkpoint, so this is a pre-existing baseline defect discovered post ledger split rather than a remediation regression.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- represent Terminal cancellation as an exact durable revocation generation/state, or hold a shared per-task execution/publication lease, before the task is considered cancelled and before its durable carrier is removed;
- make the worker prove current task identity/revocation authority immediately before cache publication and any other post-execution side effect that can create user-visible output;
- treat `cancelUniqueWork()` as asynchronous transport cleanup unless its completion is explicitly awaited; do not infer semantic revocation merely from issuing the request;
- do not delete the final durable Terminal carrier until stale publication authority is impossible or until a durable cancelled/tombstone state can reconcile any already-started publication on restart;
- if cancellation races a multi-file move, define complete rollback/retention or an explicit recoverable partial-cancellation result rather than silently abandoning destination effects.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: the exact user cancellation for one Terminal task; current code has no durable expected-generation predicate and expresses the decision primarily by deleting the row;
- first persistence mutation: `terminalDao.delete(terminalId)` in the notification receiver. Inject success, failure, and process death immediately before/after that deletion while WorkManager cancellation is delayed;
- recovery carrier/recovery write: after successful deletion there is none; a correct design must retain an exact cancelled generation/tombstone or keep the row until publication authority is fenced, and recovery-write failure must not restore publication authority;
- durable Download state and linked Download ledgers are not applicable. The relevant durable state is the Terminal task/cancellation carrier; the relevant filesystem effect is staged and destination output;
- final worker/WorkManager outcome must not reinterpret a revoked task as normal success merely because native execution returned first. A cancelled task must converge consistently even when WorkManager reports cancellation after the worker has crossed the native boundary;
- same-command manual rerun must create a new identity/attempt and must not inherit or overwrite the cancelled attempt's publication authority; process restart/reconciliation must detect any cancelled-but-partially-published attempt while exact identity still exists. Ordinary Download same-settings retry, raw requeue, reconfigure, notification retry/resume, and restore are not semantic re-entry paths and should be marked not applicable.

Focused verification requirements:

- add a deterministic production-path race that latches `TerminalDownloadWorker` immediately after `YoutubeDLCompat.execute()` returns but before `FileUtil.moveFile()`, invokes the real notification Cancel receiver, delays WorkManager cancellation delivery, then releases the worker and proves no destination publication occurs after revocation;
- repeat with cancellation delivered during a multi-file move and verify the destination/cache/task state converges to the explicit cancellation contract without an untracked partial publication;
- fault-inject receiver `terminalDao.delete()` and the durable revocation write that replaces it, including process death before/after first persistence and recovery-write failure;
- cover cancel before native start, during native execution, exactly after native completion, during publication, after publication but before terminal cleanup, repeated Cancel delivery, process restart, and a later manual rerun with the same command but a new task identity;
- exercise the actual `NotificationUtil -> CancelTerminalNotificationReceiver -> WorkManager/native process -> TerminalDownloadWorker -> FileUtil -> terminalDao` wiring. Helper-only cancellation tests are insufficient.

### BUG-DATE-03 — Preserve History date-fetch operation carrier across WorkManager enqueue failure

**State:** Open  
**Reviewed checkpoint:** `1387a8fbefcf6c9c6e9af6b02d1248ae764d5498`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** History's user-facing “fetch missing source dates” action calls `HistoryDateFetchViewModel.startOrReconnect()`, which delegates to `HistoryDateFetchManager.startOrReconnect()`. The manager first calls `HistoryDateFetchRepository.createOrReconnect()`. In one Room transaction that method creates a new `HistoryDateFetchOperation` in its nonterminal RUNNING state and inserts the exact PENDING `HistoryDateFetchItem` child snapshots. Only after that durable transaction commits does the manager build a one-time `HistoryDateFetchWorker` and call `WorkManager.enqueueUniqueWork(...)`. The returned WorkManager `Operation` is discarded and `enqueue()` returns `Unit`; the surrounding `SupervisorJob` launch has no typed scheduling result or durable retry handoff.

If WorkManager reports asynchronous enqueue failure after the Room commit, the operation therefore remains durably RUNNING with PENDING children even though no worker carrier was accepted. No worker exists to produce a `Result`, advance the child ledger, or terminalize the parent. The active UI/notification state can remain present in the same process indefinitely. Manual invocation of the same History action can call `startOrReconnect()` again and reuse the operation ID, and cold-start `HistoryDateFetchManager.reconcile()` also enumerates nonterminal operations, but both are repair paths rather than a completed handoff: startup reconciliation calls the same `enqueue()` that discards the returned `Operation`, so another enqueue failure again leaves RUNNING state without a carrier. A process death between the Room commit and the enqueue call has the same initial state and depends on that restart reconciliation.

**Why this is a defect:** RUNNING/PENDING is durable execution intent, not merely UI decoration. The implementation publishes that intent before proving that the external scheduler accepted its execution carrier and has no current-session convergence debt when scheduling fails. An ordinary WorkManager enqueue failure can therefore strand a user-requested operation in a durable nonterminal state that falsely implies active progress and requires an unrelated manual re-entry or later process restart to make another scheduling attempt. This is distinct from `BUG-DATE-01` (extractor failure reduced to `NO_DATE`) and `BUG-DATE-02` (parent completion despite FAILED children). It is also distinct from feature-specific carrier defects `BUG-QUEUE-03`, `BUG-KEYWORD-03`, and `BUG-OBSERVE-03`, whose durable authorities are respectively Download queue rows, automatic-keyword sync intent, and Observe Source recurring intent rather than the History date-fetch operation/child ledger.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. `HistoryDateFetchManager.kt` has the same production blob at the synchronized baseline checkpoint and at the reviewed checkpoint.

Required result:

- make scheduler acceptance part of the date-fetch scheduling commit: await/observe the returned WorkManager `Operation` or otherwise persist an explicit retry/failure debt that prevents a carrierless operation from being represented as ordinary RUNNING progress;
- preserve the exact `operationId` and PENDING child identities across scheduling retry, and keep `ExistingWorkPolicy.KEEP` or an equivalent exact-carrier check so a retry cannot create duplicate concurrent workers when an earlier enqueue actually succeeded;
- when enqueue fails in the current process, surface or durably record the failure and automatically converge through a bounded retry/reconciliation path rather than requiring a new user action or process restart;
- make startup reconciliation observe its own enqueue outcome as well; repeated scheduler failure must not leave the operation indefinitely RUNNING without either a durable retry owner or explicit failed/retryable state;
- race cancellation with initial/recovery enqueue and prove a cancelled operation cannot be resurrected by a late carrier acceptance or subsequent reconciliation;
- preserve `BUG-DATE-01/02` result semantics once a worker does run; fixing carrier durability must not reinterpret lookup failure or failed children as successful date absence/completion.

Terminal fault / cross-attempt requirements:

- authoritative decision/carrier: the exact `HistoryDateFetchOperation.operationId` plus its snapshotted child IDs/source identities; the first authoritative durable write is the transaction that inserts the operation and children;
- first-write failure before that transaction commits must leave no false RUNNING operation. The primary defect is the later cross-domain boundary where that first write succeeds but scheduler acceptance fails;
- durable state after enqueue failure is currently parent RUNNING plus PENDING children, with no Download state, linked Download ledger, filesystem publication, `DownloadOutcome`, or Worker result because no Worker need exist;
- recovery identity is stable: manual `startOrReconnect()` and startup `reconcile()` can reuse the same operation ID. That repair capability must become an automatic/durable convergence protocol and must itself propagate scheduler acceptance failure;
- process death after Room commit but before enqueue, asynchronous first-enqueue failure, repeated recovery-enqueue failure, recovery success, cancellation racing enqueue, and duplicate manual start while a valid unique work already exists must all preserve one exact operation carrier and truthful operation state;
- Download same-settings retry/raw requeue/reconfigure/notification retry-resume and backup restore are not semantic re-entry paths for this operation and should be marked not applicable rather than assumed safe.

Focused verification requirements:

- exercise the real `HistoryFragment -> HistoryDateFetchViewModel -> HistoryDateFetchManager -> HistoryDateFetchRepository/Room -> WorkManager -> HistoryDateFetchWorker` wiring with an enqueue `Operation` that fails asynchronously after the operation/children transaction commits; assert the parent cannot remain ordinary RUNNING with no retry/failure carrier;
- inject process death immediately after operation creation but before WorkManager acceptance, then restart and prove the same exact operation is reconciled once; repeat with the first reconciliation enqueue failing and a later attempt succeeding;
- race user cancellation against a delayed enqueue completion and against startup reconciliation, proving no cancelled operation is revived and no duplicate worker is created;
- include normal successful enqueue, repeated manual Start/Reconnect while unique work exists, worker terminal success/failure, and regressions for `BUG-DATE-01/02` semantics;
- the existing policy-only test that asserts `ExistingWorkPolicy.KEEP` is insufficient. Verification must cover actual Room + WorkManager acceptance/result wiring.

### BUG-COOKIE-04 — Convert Chromium cookie expiry to Netscape/Unix semantics before yt-dlp use

**State:** Open  
**Reviewed checkpoint:** `1387a8fbefcf6c9c6e9af6b02d1248ae764d5498`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the production Home cookie-recovery flow launches `WebViewActivity`; Generate calls `CookieViewModel.getCookiesFromDB()`, which opens WebView's Chromium `Cookies` SQLite database and reads the `expires_utc` INTEGER directly with `getLong()`. That raw value is copied into `WebViewActivity.CookieItem.expiry`, and `CookieItem.toNetscapeFormat()` writes it unchanged as the fifth field of each generated Netscape cookie line. The generated content is persisted in the app cookie row and projected to `cookies.txt`; when `use_cookies` is enabled, `YTDLPUtil` supplies that file to yt-dlp through `--cookies` for authenticated metadata requests, and the same runtime projection is shared by other cookie-enabled yt-dlp paths.

The two formats do not use the same timestamp representation. Chromium's cookie store persists `expires_utc` through `sql::Statement::BindTime()`, which serializes `base::Time` as **microseconds since the Windows epoch (1601-01-01)**; `ColumnTime()` reverses the same representation. The Netscape cookie-file contract consumed by curl/yt-dlp uses the expiry field as **seconds since the Unix epoch (1970-01-01), or 0 for a session cookie**. The application performs neither the epoch subtraction nor the microseconds-to-seconds conversion. A normal persistent WebView cookie therefore becomes a syntactically valid but enormously future-dated Netscape cookie instead of retaining its actual expiry.

No later boundary repairs the semantic conversion. `CookieViewModel.updateCookiesFile()` copies the already generated line content, yt-dlp accepts Netscape-format cookie files, and restart rebuilds the same malformed expiry from the app's persisted cookie content. Once the real browser expiry passes, the generated jar can continue treating the credential as unexpired and attach it to requests until another server-side mechanism rejects or rotates it, while a fresh WebView acquisition repeats the same conversion error for every persistent cookie.

**Why this is a defect:** cookie expiration is part of authentication authority, not display metadata. The WebView cookie database and Netscape file both carry a numeric field named as an expiry, but raw numeric equality does not preserve its unit or epoch. The application can continue presenting/sending a cookie after the client-side expiration that the captured browser state established, producing stale-authentication failures and potentially retaining credential use beyond the intended browser lifetime. This is distinct from `BUG-COOKIE-01`, which owns ordering/generation between Room cookie state and the runtime file, `BUG-COOKIE-02`, which owns clipboard export result reporting, and `BUG-COOKIE-03`, which owns acquisition success before extraction/persistence/projection completes; none owns representation conversion of an otherwise successfully captured cookie.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same `CookieViewModel`/`WebViewActivity.CookieItem` conversion path is present in the synchronized baseline checkpoint.

Required result:

- convert Chromium `expires_utc` from its documented Windows-epoch microsecond representation to Netscape/Unix seconds before serializing a persistent cookie, while preserving the correct session-cookie `0` semantics;
- treat timestamp unit/epoch conversion as part of the capture contract and reject or diagnose values that cannot be converted safely instead of emitting a syntactically valid but semantically different cookie line;
- preserve the converted expiry through Room persistence, runtime `cookies.txt` projection, export, restart reconstruction, and every yt-dlp consumer; do not let later projection/retry paths reintroduce the raw Chromium value;
- keep `BUG-COOKIE-01/03` generation/publication fixes separate: a fully synchronized and durably published cookie file is still incorrect if its expiry semantics were converted wrongly;
- define migration/reacquisition behavior for already stored WebView-generated cookie rows whose content contains raw Chromium expiry values, without rewriting manually imported Netscape cookie files that already use Unix seconds.

Cross-attempt / recovery requirements:

- immediate Home retry after successful capture, ordinary cookie-enabled metadata/download execution, Terminal cookie use where applicable, and process restart must all consume the same correctly converted expiration semantics;
- repeated WebView acquisition must not reproduce the raw timestamp representation, while manual import of a valid Netscape cookie file must remain unchanged;
- after the true captured expiry time passes, no retry/requeue/reconfigure path may regain authority from the stale generated line merely because its raw Chromium integer is numerically far in the future;
- delete/disable/revocation and projection-rebuild behavior remain governed by `BUG-COOKIE-01`, but those paths must preserve the converted representation when the cookie is still enabled.

Focused verification requirements:

- add a production-path WebView SQLite -> `CookieViewModel.getCookiesFromDB()` -> `CookieItem.toNetscapeFormat()` -> Room -> `cookies.txt` -> yt-dlp test with a known persistent cookie whose Chromium `expires_utc` corresponds to a concrete Unix expiration; assert the generated fifth field equals Unix seconds, not the raw database integer;
- include session expiry, already-expired, near-future, far-future within supported cookie limits, malformed/overflow values, and process-restart reconstruction;
- verify a cookie is no longer eligible after its converted expiration, while a manually imported Netscape cookie with the same Unix expiry remains unchanged;
- exercise the real generated cookie-file consumer rather than only a timestamp helper. No such direct production-wiring verification was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P3

### BUG-TEMPLATE-02 — Validate Command Template URL regexes before they can abort download configuration

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the Command Template creation/edit sheet accepts arbitrary URL-regex text, converts every chip to a string, and persists the resulting `CommandTemplate.urlRegex` without compiling or validating it. Clipboard import likewise deserializes templates and inserts them directly. A normal user can therefore persist an invalid Kotlin regex such as `[` and that malformed matcher survives process restart in Room.

The same persisted field has inconsistent production consumers. Data-fetching templates use `safeRegexMatches(...)`, and ordinary audio/video extra-command selection wraps `Regex(pattern).containsMatchIn(url)` in `runCatching` and treats invalid syntax as non-matching. But `DownloadViewModel.getFormat()` selects a preferred Command template with a raw `Regex(u).containsMatchIn(url)`, and `createDownloadItemFromHistory()` applies audio/video extra commands with the same raw compilation. The Command download card calls `createDownloadItemFromResult(..., DownloadType.command)` before its later UI `try/catch`, so one malformed preferred template can throw `PatternSyntaxException` before the `DownloadItem` exists and prevent that card from initializing. The compatible History re-download path in `VideoPlayerActivity` calls `createDownloadItemFromHistory()` inside `runCatching`; malformed regex therefore turns that user-requested re-download into a failed action instead of merely skipping the invalid template.

**Why this is a defect:** URL regex is user-controlled persistent configuration, not an internal invariant. The UI and import path accept malformed syntax, and sibling consumers already demonstrate the intended safe behavior by treating invalid matchers as non-matches. A single durable malformed matcher can nevertheless abort otherwise valid download-configuration requests every time they traverse the unsafe consumers, including after restart, until the user discovers and edits/deletes the template. This is a correctness/reliability failure rather than defensive hardening. It is distinct from `BUG-TEMPLATE-01`, which owns asynchronous clipboard-export success reporting and does not cover matcher validation or download configuration.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- validate every URL regex before a Command Template can be inserted or updated through the UI or import path, with an actionable per-pattern error that leaves the prior durable template unchanged on edit failure;
- centralize matcher evaluation so every production consumer has the same typed invalid-pattern semantics; malformed persisted legacy/imported data must be rejected or treated as a non-match without aborting an independent download request;
- keep a malformed preferred template from preventing fallback to another applicable preferred/default Command template, and keep malformed audio/video template filters from aborting History re-download construction;
- preserve exact template identity on repair/reconfiguration and do not silently reinterpret invalid syntax as a successful match;
- on restart, existing malformed rows must remain recoverable/editable and must not poison unrelated command/download configuration before the user repairs them.

Terminal fault / cross-attempt requirements:

- authoritative observation: template URL-regex syntax is first authoritative when the template is accepted for persistence, and must be revalidated at consumption for legacy/imported rows;
- first persistence call is the template `insert`/`update`; invalid new input must not commit, while an invalid edit must not partially replace a previously valid row. No Download row, linked download ledger, filesystem output, or `DownloadOutcome` exists in the primary failure because raw regex compilation throws during configuration before queue persistence;
- helper throwable window: `Regex(u)` can throw `PatternSyntaxException`; the exception must be converted into template-invalid/non-match semantics before it reaches Command-card or History-re-download outer control flow;
- same-settings retry, manual/raw requeue, notification retry/resume, and restore are not primary re-entry paths when no Download row was created. Reopening Command configuration, History re-download, template edit/import, and process restart are the relevant cross-attempt paths; each must either reject/skip the same invalid matcher deterministically or succeed after explicit repair rather than repeatedly aborting;
- there is no required lock-order or sibling-concurrency interleaving to trigger the defect; the persisted malformed value alone is sufficient.

Focused verification requirements:

- add production-path tests that create and edit a preferred Command Template with malformed regex through the actual sheet, and import one through the clipboard path; assert invalid syntax cannot become newly authoritative without an explicit error;
- seed a legacy malformed persisted template and exercise the real Home/Share/Command-card `createDownloadItemFromResult(..., command)` path, proving the bad matcher is skipped/reported and a valid fallback template still initializes the request;
- exercise `VideoPlayerActivity -> createDownloadItemFromHistory()` with malformed audio/video extra-command regex and prove compatible re-download construction continues without that template;
- include valid regex, empty regex, multiple regexes with one malformed member, process restart, and edit-to-repair cases. Helper-only regex tests are insufficient; verification must cover Room persistence plus the actual consumer wiring.

### BUG-BACKUP-10 — Propagate SharedPreferences commit failure from app-data restore

**State:** Open  
**Reviewed checkpoint:** `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the app-data restore UI can invoke `SettingsViewModel.restoreData()` for a settings-only merge restore or for a broader restore containing settings. `restoreData()` applies the selected serialized preferences with AndroidX `SharedPreferences.edit(commit = true)`. That extension invokes `Editor.commit()` synchronously but returns `Unit`, discarding the Boolean that is the platform's durable-write success result. `restoreData()` wraps the whole operation in `runCatching` and finally returns only `result.isSuccess`, so a `commit()` that returns `false` without throwing is indistinguishable from a successful preference persistence. The same ignored Boolean pattern is used for restored visible-keyword/youtuber preference sets later in the method.

A settings-only merge restore can therefore return `true` even though the requested preferences were not durably written. Because SharedPreferences first updates the process-local map and then attempts disk persistence, the restored values can appear effective in the current process and disappear after process restart, while the UI has already reported restore success. In a broader merge restore, Room categories may commit while the settings portion silently fails, yielding a mixed durable state despite a successful overall result. For reset restore, the same mechanism compounds the separately owned destructive-partial-restore problem in `BUG-BACKUP-03`; this item owns the ignored persistence-result/false-success path, especially where no destructive reset is involved.

**Why this is a defect:** the restore contract is durable state reconstruction, and `Editor.commit()` explicitly supplies the success/failure result for that persistence boundary. Discarding that result converts a real storage write failure into success and provides no durable recovery carrier or restart indication that settings were not restored. This is distinct from `BUG-BACKUP-08`, which covers supported preference value types that are never restored at all, and from `BUG-BACKUP-03`, which covers non-atomic destructive reset restore across categories.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

Required result:

- use a persistence API/result that propagates `Editor.commit() == false` as restore failure for every preference group whose durable reconstruction is part of the selected backup;
- do not return restore success until all selected SharedPreferences writes required by that restore are durably acknowledged, and identify which preference stage failed rather than collapsing it into success;
- for multi-category merge/reset restore, integrate preference persistence with the broader restore commit/recovery plan so Room categories and settings cannot be presented as one successful restore when one durable domain failed;
- preserve `BUG-BACKUP-08` type semantics and `BUG-BACKUP-03` reset atomicity ownership rather than treating a successful Boolean commit as proof those separate invariants are closed;
- make retry/re-entry idempotent: repeating the same restore after a failed preference commit must either durably apply the same selected settings once or report failure again, while process restart must never be the first point at which a previously reported success is revealed to have lost the restored preferences.

Terminal fault / cross-attempt requirements:

- authoritative decision: the selected backup settings and restore mode accepted by the restore flow;
- first persistence call for a settings-only restore: `SharedPreferences.Editor.commit()` reached through AndroidX `edit(commit = true)`; inject a `false` return with no exception and require the restore result to be failure;
- recovery carrier: current code has none because the false result is discarded; a corrected flow must retain enough restore/session context for explicit retry or otherwise fail without claiming durable completion;
- durable Download/History/linked-ledger and filesystem effects are not applicable to settings-only merge restore. In a mixed restore, independently committed Room categories must not hide preference persistence failure, and reset-specific destructive rollback remains owned by `BUG-BACKUP-03`;
- final outcome: `restoreData()` must not return `true` when any required preference commit returns false. There is no WorkManager result in the settings-only path;
- relevant cross-attempt paths are same-backup retry, merge-vs-reset re-entry, process restart, and restore of a broader category selection. Download same-settings retry, raw requeue, reconfigure, notification retry/resume, and ordinary startup reconciliation are not semantic repair paths for this restore session and should be marked not applicable.

Focused verification requirements:

- exercise the actual restore UI/parser -> `SettingsViewModel.restoreData()` -> default SharedPreferences wiring with a test double/storage fault that makes `Editor.commit()` return false without throwing; assert settings-only merge restore reports failure;
- repeat with one successful and one failed restored preference group, and with a mixed settings + Room-category restore, proving the outer `runCatching` cannot reinterpret false durable persistence as success;
- verify current-process reads versus process-restart reads so a failed disk commit cannot masquerade as successful restore merely because the in-memory SharedPreferences map changed;
- include normal successful commit, repeated retry after failure, reset mode as a regression against `BUG-BACKUP-03`, and `Long`/`Float` handling as a regression against `BUG-BACKUP-08`. Helper-only serialization tests are insufficient.

## P2 — continued

### BUG-COOKIE-05 — Preserve host-only cookie scope when converting Chromium cookies to Netscape

**State:** Open  
**Reviewed checkpoint:** `b60ef3deae3d8eec0505a01b955af05abb75e949`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the production Cookies screen and Home authentication-recovery flow can launch `WebViewActivity` for an arbitrary HTTP(S) login/source URL. Generate calls `CookieViewModel.getCookiesFromDB()`, which opens WebView's Chromium `Cookies` SQLite database and reads each `host_key`. Chromium represents a host-only cookie with a domain string that does **not** begin with `.` and a domain cookie with one that does. The converter does not preserve that distinction: for every `host_key` without a leading dot it prepends one (`.$hostKey`), then constructs `WebViewActivity.CookieItem` without specifying `includeSubdomains`, whose default is `true`. `CookieItem.toNetscapeFormat()` writes that Boolean as the second Netscape field. The result is that every captured host-only cookie is serialized as a leading-dot domain plus `TRUE`, i.e. a cookie eligible for subdomains.

The generated lines are persisted in Room and copied into the shared runtime `cookies.txt`. When `use_cookies` is enabled, `YTDLPUtil.applyDefaultOptionsForFetchingData()` attaches that jar through `--cookies`; the cookie jar is not scoped to only the URL that originally created the row. A host-only credential captured for `example.com` can therefore become eligible for a later request to `sub.example.com`, including extractor redirects/subrequests or a later user request on that subdomain, even though the browser cookie authority required an exact host match. No later projection or restart path restores the lost host-only bit because the broadened Netscape line is the durable app-owned representation.

**Why this is a defect:** host-only versus domain scope is part of credential authority. Chromium explicitly distinguishes a host-only cookie from one that may be sent to subdomains, and the Netscape jar has an explicit include-subdomains field for the same semantic distinction. Broadening that scope changes which network origins can receive authentication state, can produce incorrect authentication behavior, and can disclose a credential to a subdomain that the captured browser cookie would not authorize. This is separate from `BUG-COOKIE-01` (Room/runtime projection ordering), `BUG-COOKIE-03` (capture success publication), and `BUG-COOKIE-04` (expiry epoch/unit conversion): even a fully synchronized, correctly timed cookie is still semantically wrong if its domain authority is widened.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same host normalization and `includeSubdomains = true` default are present in the synchronized baseline conversion path.

Required result:

- preserve Chromium host-only/domain-cookie identity when converting each row: a host-only `host_key` must remain exact-host in the Netscape representation with `includeSubdomains = FALSE`, while a domain cookie must preserve its domain/subdomain authority rather than being inferred from a rewritten display string;
- carry that scope explicitly through the app model, Room content, runtime `cookies.txt`, export, restart reconstruction, and every yt-dlp/Terminal cookie consumer;
- do not rewrite already-valid manually imported Netscape cookies under Chromium conversion rules; generated WebView rows and imported Netscape rows must retain their respective source semantics;
- define migration/reacquisition behavior for already stored WebView-generated lines whose host-only cookies were broadened, without silently broadening them again during projection or retry;
- keep the `BUG-COOKIE-01/03/04` fixes orthogonal: synchronization, successful publication, and expiry conversion do not close domain-scope correctness.

Cross-attempt / recovery requirements:

- immediate Home retry, ordinary metadata/download execution, Terminal cookie use where applicable, repeated WebView acquisition, and process restart must all preserve the same exact host-only/domain authority;
- a later request for a subdomain must not gain a host-only credential merely because the cookie survived in the shared jar, while a genuine Chromium domain cookie must continue to work on authorized subdomains;
- delete/disable/revocation remains governed by `BUG-COOKIE-01`, but every projection produced while the row is enabled must preserve its correct scope;
- manual import/export round trips must not reinterpret `FALSE` as `TRUE` or inject a leading dot that changes host authority.

Terminal fault / candidate-rejection notes:

- there is no Download terminal-state write required to trigger this defect; the authoritative decision is the cookie's domain scope at the Chromium-to-Netscape representation boundary, and the material side effect is which HTTP origins become eligible to receive the credential;
- first persistence failure, linked Download ledger, filesystem output publication, `DownloadOutcome`, and WorkManager terminal result are not applicable to the primary scope-widening path. The generated Room/file representation is itself the durable semantic carrier;
- this candidate is not rejected merely because modern `Set-Cookie` syntax ignores historical leading-dot spelling: the consumer here is a Netscape cookie jar with an explicit include-subdomains Boolean, and the code writes `TRUE` for host-only Chromium rows. Nor does an immediate retry to the same host close the invariant, because the shared jar remains enabled for later extractor/subdomain requests without a scope-repair barrier.

Focused verification requirements:

- add a production-path WebView SQLite -> `CookieViewModel.getCookiesFromDB()` -> `CookieItem.toNetscapeFormat()` -> Room -> `cookies.txt` -> yt-dlp cookie-consumer test with two concrete Chromium rows: host-only `example.com` and domain `.example.com`; assert the host-only row is serialized/consumed as exact-host only and the domain row remains subdomain-eligible;
- include requests to `example.com`, `sub.example.com`, sibling unrelated domains, secure/path combinations, session and persistent cookies, process restart, and multiple enabled cookie rows;
- verify manual Netscape import containing both `FALSE` and `TRUE` scope values is preserved unchanged by later projection/export;
- exercise the real generated cookie-file consumer. No production-path execution or network-cookie wiring test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

### BUG-PLAYER-02 — Do not overwrite newer History thumbnail state with stale playback-cache localization

**State:** Open  
**Reviewed checkpoint:** `e4a47f1cd4990a17a40258afb0f179e027868deb`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** ordinary VideoPlayer artwork/session-metadata refresh reaches `loadPlaybackThumbnail(item)`. From its in-memory `HistoryItem` snapshot it chooses a remote `customThumb`/`thumb` source A, then `ensureLocalThumbForPlayback()` performs the network Picasso fetch, bitmap decode/scale, and app-owned `filesDir/thumb_cache/history_<id>_thumb.jpg` write before entering `HistoryReferenceMutationCoordinator`. While that external work is in flight, a concurrent authorized History replacement or another legitimate thumbnail writer can acquire the same coordinator and commit a newer thumbnail source B. The playback localizer later acquires the coordinator and does re-read `historyDao.getNullableItem(item.id)`, but it never compares that current row's `customThumb`/`thumb` or any source revision with A. It unconditionally calls `historyDao.updateThumbById(item.id, outFile.absolutePath)` and publishes `current.copy(thumb = outFile.absolutePath)` into the playback queue. The stale cache derived from A can therefore overwrite the newer durable B after B has already committed.

The ordering is correctness-significant. If localization commits first and replacement commits second, the newer replacement wins. If replacement commits while the old remote fetch is running and localization commits second, the stale old artwork becomes the durable History thumbnail. The scalar DAO update avoids unrelated full-row overwrite, but scalar ownership alone does not make a value current when that value was derived from a revocable old source outside the synchronization boundary. The generated cache file is persistent app data, and after the stale write a restart reads that local path; because it is no longer remote, ordinary playback does not automatically refetch or recover B.

**Why this is a defect:** background artwork localization is a cache/materialization side effect, not user authority to replace a newer History thumbnail. A normal playback refresh can silently erase a successfully committed replacement/editor thumbnail solely because an older network fetch finished later. This is persistent metadata corruption/lost-update behavior rather than an optimization issue. It is distinct from `BUG-PLAYER-01` (playback-position write ordering), `BUG-METADATA-01` (stale full-row DownloadItem metadata enrichment), and `BUG-BACKUP-02` (custom-thumbnail restore path collisions); none owns stale provenance publication by the VideoPlayer thumbnail localizer.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same `loadPlaybackThumbnail()` / `ensureLocalThumbForPlayback()` source-capture and unconditional `updateThumbById()` sequence is present at the prior reviewed checkpoint `b60ef3deae3d8eec0505a01b955af05abb75e949`; the current checkpoint commit changes the explicit VideoPlayer metadata editor, not this localization sequence.

Required result:

- bind every playback-thumbnail localization attempt to the exact source identity/revision from which the bitmap was derived;
- after external/network/file work, re-enter `HistoryReferenceMutationCoordinator` and update `thumb` only under an expected-current-source predicate proving the target still exists and the preferred thumbnail source has not been replaced or superseded by a newer `customThumb`/`thumb` state;
- if source authority changed, discard the stale publication, keep the newer durable metadata, and clean up the attempt-owned cache artifact when it is no longer referenced;
- retain field-owned/scalar mutation for the successful current-source case so localization never overwrites unrelated History metadata;
- make repeated playback and process restart converge on the currently authoritative thumbnail rather than turning a stale cache path into permanent source identity.

Concurrency / cross-attempt / fault requirements:

- actual ordering is `VideoPlayer remote fetch -> cache-file write -> HistoryReferenceMutationCoordinator -> scalar Room update`, while authorized History replacement uses `HistoryReferenceMutationCoordinator -> Room transaction`; no AB/BA lock inversion is required, but a late derived-value commit can cross a newer serialized mutation;
- first persistence call for localization is `historyDao.updateThumbById()`. A failed first write currently leaves the newer database value intact but can orphan the generated cache file; a successful stale write is the primary defect and has no recovery carrier identifying the source it displaced;
- process restart after a stale successful write preserves the stale local path. Repeated playback then sees a local source and does not restore the superseded remote/replacement source. A later explicit replacement/editor action may repair the row but is not an automatic convergence guarantee;
- same-settings Download retry, raw/manual requeue, reconfigure, notification retry/resume, and backup restore are not semantic recovery paths for this playback-cache attempt and should be marked not applicable. History replacement, explicit thumbnail edit, repeat playback, target deletion, and restart are the relevant re-entry paths;
- Download terminal state, linked Download ledgers, `DownloadOutcome`, and WorkManager result are not part of the primary localization path. The durable terminal semantic effect is the History thumbnail field plus its cache file reference.

Focused verification requirements:

- add a deterministic production-path race that latches the remote thumbnail fetch after source A is captured, commits an authorized History replacement/editor change to source B through the real `HistoryReferenceMutationCoordinator`/Room path, releases the old fetch, and proves B remains durable and the stale A-derived cache cannot publish;
- execute both completion orders, plus target deletion, unchanged-source localization, customThumb-over-thumb precedence changes, Room update failure, and stale-attempt cache cleanup;
- restart after the replacement-before-localizer ordering and prove the authoritative B source remains recoverable/current rather than a stale local A path;
- exercise actual `VideoPlayerActivity -> loadPlaybackThumbnail/ensureLocalThumbForPlayback -> HistoryDao` wiring and a real competing History writer. No such production-path race test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

### BUG-COOKIE-06 — Await WebView cookie clearance before starting a fresh authentication session

**State:** Open  
**Reviewed checkpoint:** `e4a47f1cd4990a17a40258afb0f179e027868deb`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** Home authentication recovery can launch `WebViewActivity` for a fresh credential acquisition. On first creation the activity obtains the process-global `CookieManager`, calls `removeAllCookies(null)`, immediately calls `flush()`, and then creates the WebView; `AndroidView.update` can call `loadUrl(url)` immediately. Android documents `removeAllCookies(ValueCallback)` as asynchronous and states that the callback is invoked once removal is complete. Passing `null` explicitly discards that completion barrier. `flush()` has a different contract—persisting cookies currently accessible through `getCookie()`—and does not establish that an outstanding asynchronous removal finished before the first network request.

A previous WebView session can therefore still be present when the fresh login URL begins loading. The old credential may affect redirects/login state or be sent by that initial request before deletion completes. The later Generate action calls `CookieViewModel.getCookiesFromDB()`, which flushes and opens the Chromium `Cookies` SQLite database, queries all cookie rows, and serializes them into the app-owned Room cookie record/runtime jar. If the stale session influenced the page or survived until capture, that old authority can be durably promoted as the supposedly new acquisition. `PoTokenWebViewLoginActivity` has the same ordering for its explicit `noAuth` mode: it calls `removeAllCookies(null)` plus `flush()` and immediately loads the WebView, so a user-requested unauthenticated token-generation flow can begin under residual authenticated cookie state.

**Why this is a defect:** clearing prior cookies is the isolation/revocation boundary that distinguishes a fresh/no-auth WebView session from the previous browser credential state. The implementation starts the authority-bearing network operation before that asynchronous reset is proven complete. The result can be incorrect authentication identity or persistence of credentials from the wrong session, not merely a privacy hardening concern. This is distinct from `BUG-COOKIE-01` (Room/runtime projection ordering and revocation), `BUG-COOKIE-03` (reporting capture success before extraction/persistence/projection completes), `BUG-COOKIE-04` (expiry representation), and `BUG-COOKIE-05` (domain scope): even a perfectly awaited and semantically correct later projection is wrong if the WebView session itself began with stale credentials.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same `removeAllCookies(null)`-then-load ordering is present in the baseline WebView paths and predates the reviewed checkpoint.

Required result:

- treat cookie-clear completion as an authorization barrier for every flow that promises a fresh or unauthenticated WebView session; do not load the target URL until `removeAllCookies` completion for that exact reset attempt is observed;
- define explicit behavior when cookie clearing cannot complete or the Activity is cancelled/recreated while reset is in flight: do not silently proceed with the old session, and do not reuse a stale completion from a superseded Activity/session generation;
- apply the same barrier to `WebViewActivity` first-load isolation and `PoTokenWebViewLoginActivity` `noAuth` mode, while preserving intentional authenticated flows that are supposed to retain current WebView state;
- do not treat `CookieManager.flush()` as a substitute for asynchronous removal completion; use the API's completion signal or an equivalent proven barrier before first request;
- after successful isolation, preserve the existing cookie acquisition/projection fixes independently: a clean session still must satisfy `BUG-COOKIE-03/04/05` semantics before its captured credentials become durable or consumable.

Cross-attempt / candidate-rejection requirements:

- immediate first load, redirects, Generate/capture, Activity recreation, repeated acquisition, process restart, and PoToken `noAuth` re-entry must all remain bound to the exact completed reset generation before any authority-bearing request starts;
- same-settings Download retry, raw/manual requeue, reconfigure, notification retry/resume, and restore are not semantic recovery paths for an incomplete WebView reset and should be marked not applicable unless they explicitly launch a new isolated acquisition;
- this candidate is not rejected because `flush()` follows `removeAllCookies`: Android's documented completion contract for removal is the asynchronous callback, while `flush()` only guarantees persistence of currently accessible cookies. Nor is a later successful capture a repair barrier, because the first WebView request and resulting session state may already have been authorized by stale cookies;
- no lock inversion is required. The ordering defect is `async reset requested -> first WebView load/request -> reset completion`, and correctness requires completion to move before first use.

Terminal fault / persistence notes:

- authoritative decision is the fresh/no-auth session reset request; its completion is the first required semantic barrier. There is no Download row, linked Download ledger, `DownloadOutcome`, or WorkManager result in the primary failure path;
- before successful capture the material side effect is WebView/network authentication state. If Generate later persists cookies, the first durable app-owned write is the Room cookie insert/update, followed by runtime `cookies.txt` projection; those writes can make wrong-session credentials survive restart;
- cancellation or process death before reset completion must not be interpreted as a successfully isolated session. A later explicit acquisition may repair the state only by issuing and completing a new reset before first use.

Focused verification requirements:

- add a production-path test with a pre-seeded WebView cookie for the target host, latch `removeAllCookies` completion, launch real `WebViewActivity`, and prove the initial WebView request cannot begin or carry the old cookie until the reset callback fires;
- repeat through `PoTokenWebViewLoginActivity` with `noAuth=true` and prove token generation cannot observe/send the prior authenticated session before reset completion;
- cover no prior cookies, prior host/session cookies, delayed callback, Activity recreation/cancellation during reset, repeated launches, reset failure/abandonment, redirects, and process restart;
- after releasing the reset, exercise the real `WebView -> CookieViewModel.getCookiesFromDB() -> Room -> cookies.txt` path as a control and assert only credentials established after the completed reset can become current. No production-path WebView/network test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

### BUG-COOKIE-07 — Preserve partitioned-cookie identity when exporting WebView cookies to yt-dlp

**State:** Open  
**Reviewed checkpoint:** `e4a47f1cd4990a17a40258afb0f179e027868deb`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the production WebView credential flow enables normal and third-party cookies, and modern Android WebView supports cookies carrying the `Partitioned` attribute. Chromium's persistent cookie schema makes the top-level partition part of cookie identity: `top_frame_site_key` stores the scheme/site of the topmost frame and is deserialized into the cookie partition key; current uniqueness also distinguishes partition context from the ordinary host/name/path tuple. `CookieViewModel.getCookiesFromDB()`, however, queries the WebView `Cookies` SQLite table using a projection that contains only `host_key`, `expires_utc`, `path`, `name`, `value`, and `is_secure`. It neither reads nor filters any partition identity and queries the whole table without a partition-aware predicate.

Each returned row is immediately converted to `WebViewActivity.CookieItem` and then to the seven-field Netscape representation. That carrier has domain/include-subdomains/path/secure/expiry/name/value but no top-level partition dimension. The semantic loss occurs before Room persistence: a WebView cookie that is valid only in top-level partition A becomes an ordinary shared Netscape cookie, and two Chromium rows that legitimately have the same host/name/path but different partition keys are flattened into indistinguishable destination identities. Because the SQLite query has no ordering or collision-resolution rule for that lost identity, whichever duplicate value the downstream Netscape/Python cookie jar retains can depend on row/load order rather than the WebView partition that actually authorized the request.

The flattened content is then persisted in the app cookie row, projected to the shared runtime `cookies.txt`, and supplied to yt-dlp through `--cookies` whenever `use_cookies` is enabled. Netscape cookie files do not encode CHIPS/top-level partition keys, so no later projection, retry, or process restart can reconstruct the discarded authority. A partitioned credential can therefore be sent outside the top-level context that WebView would require, or a same-name cookie from another partition can replace/be selected instead of the credential belonging to the intended session.

**Why this is a defect:** cookie partition is part of credential identity and authorization context, not optional display metadata. Android documents that `Partitioned` cookies are returned only for the top-level partition of the URL, and Chromium persists that partition in the cookie's unique identity. Silently dropping the dimension when adapting to a format that cannot represent it broadens authority and can conflate distinct credentials. This is separate from `BUG-COOKIE-04` (expiry representation), `BUG-COOKIE-05` (host-only/domain scope), and `BUG-COOKIE-06` (fresh-session reset ordering): even a correctly isolated, correctly timed, host-scope-preserving capture is still semantically wrong if partition identity is erased.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The reviewed conversion path predates the current checkpoint; the newly considered platform/storage dimension is what exposes the latent defect.

Required result:

- treat partition identity as part of the authoritative source cookie key during WebView capture and never silently serialize a partitioned Chromium row as an unpartitioned shared Netscape cookie;
- because the Netscape format consumed by `--cookies` has no partition-key field, either exclude unsupported partitioned rows from the global jar with an explicit/actionable diagnostic, or introduce a request/context-aware materialization path that selects only an exact proven top-level partition without broadening it; do not invent a flattened equivalent;
- prevent two cookies that differ only by Chromium partition identity from colliding into one effective Netscape cookie value, and preserve ordinary unpartitioned cookies unchanged;
- keep manually imported Netscape cookies under their existing unpartitioned source semantics; do not fabricate partition provenance for them;
- for already persisted WebView-generated rows whose partition provenance was discarded, require safe reacquisition or another explicit migration policy rather than pretending the missing partition can be reconstructed from the flattened line.

Cross-attempt / candidate-rejection requirements:

- immediate Home retry, ordinary cookie-enabled metadata/download execution, repeated WebView acquisition, and process restart must never turn a partition-specific WebView credential into process-wide unpartitioned authority; reacquisition must preserve or deliberately reject the same partition semantics each time;
- a same-host/name/path pair from top-level partitions A and B must remain distinguishable at the source boundary and must not become completion-order/SQLite-order dependent in the runtime jar;
- Download same-settings retry, raw/manual requeue, reconfigure, notification retry/resume, and restore do not repair erased partition provenance; if they consume the shared jar, they must not regain broader authority from a flattened generated row;
- this candidate is not rejected because yt-dlp accepts Netscape cookies or because its own generic browser-cookie path may also flatten unsupported browser context. The adapter is responsible for not converting a source credential with narrower contextual authority into a broader/different identity. The target format's inability to encode a source identity dimension is a reason to reject/contextualize that row, not proof that the dimension is semantically irrelevant;
- no race is required. A single partitioned cookie is already scope-broadened by conversion; multiple same-key rows in different partitions additionally create a concrete credential-collision/selection failure.

Terminal fault / persistence notes:

- authoritative observation is the Chromium/WebView cookie row including its partition key. The carrier-creation gap occurs when `CookieViewModel` projects that row into fields that omit partition identity;
- the primary failure precedes the first app-owned persistence call. If the flattened content is then inserted/updated in Room and projected to `cookies.txt`, those writes durably preserve the wrong semantic identity across restart; failures of those writes remain separately owned by the existing cookie publication defects;
- there is no required Download terminal state, linked Download ledger, filesystem media publication, `DownloadOutcome`, or WorkManager result in the primary path. The material side effect is which credential instance and scope the HTTP consumer can select/send;
- first-write fault injection therefore cannot close this defect: even a perfectly successful durable write is wrong when the carrier has already discarded a source identity dimension.

Focused verification requirements:

- on a WebView version with partitioned cookies enabled, exercise the real WebView/Chromium `Cookies` store -> `CookieViewModel.getCookiesFromDB()` -> Room -> runtime `cookies.txt` -> yt-dlp cookie consumer with an unpartitioned control plus partitioned cookies;
- seed or obtain two partitioned cookies with the same embedded host/name/path and different values under two distinct top-level sites, then prove capture/materialization never merges, arbitrarily selects, or sends one outside its authorized top-level partition;
- cover a single partitioned cookie, mixed partitioned/unpartitioned rows, redirects/third-party cookie creation, repeated capture, process restart, and manual Netscape import as a non-partitioned control;
- verify any chosen policy (skip with diagnostic or context-aware materialization) through real WebView/network consumer wiring. No production-path CHIPS/WebView integration test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P3 — continued

### BUG-CACHE-04 — Handle cache-migration WorkManager enqueue failure without an empty-observer crash

**State:** Open  
**Reviewed checkpoint:** `e4a47f1cd4990a17a40258afb0f179e027868deb`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** Folder settings exposes `move_temporary_files`. Its click handler creates a tagged `MoveCacheFilesWorker`, calls `beginUniqueWork(...).enqueue()`, discards the returned WorkManager `Operation`, and immediately installs a `getWorkInfosByTagLiveData("cacheFiles")` observer. The observer checks `if (list == null)` and then evaluates `if (list.first() == null)`, which dereferences the collection before proving that any `WorkInfo` exists. WorkManager's enqueue API reports asynchronous scheduler/database acceptance through the returned `Operation`; if the first enqueue fails before a tagged WorkSpec is durably accepted, the tag query is allowed to have zero matches. In that state the observer receives an empty `List<WorkInfo>` and `list.first()` throws `NoSuchElementException` on the UI callback instead of preserving the scheduler failure as a failed/not-started migration result.

The broader hypothesis that an ordinary successful first enqueue races a too-early empty query is not required for this defect and was rejected during review: WorkManager serializes enqueue database work and its Room-backed query execution such that a successfully accepted first WorkSpec normally becomes queryable before the later tag observation can be authoritative. The concrete fault path is the scheduler-acceptance failure itself. The app never observes that failure channel, uses a shared historical tag rather than the exact new WorkRequest ID as its completion carrier, and has no outer catch around the empty-list dereference. No migration worker runs and no cache file is moved, but the user action terminates as an uncaught UI exception instead of an actionable scheduling failure.

**Why this is a defect:** enqueue failure is an explicit supported failure result of the scheduler handoff, not an impossible programmer state. A maintenance action must either establish an accepted execution carrier or report that it did not start. Converting a valid zero-carrier state into an uncaught exception makes the cache-migration command unreliable under the exact first-write/scheduler fault that Review Checklist v4 requires reviewers to inject. This is distinct from `BUG-CACHE-01` (moving cache entries owned by live downloads), `BUG-CACHE-02` (losing SAF authority for the configured cache root), and `BUG-CACHE-03` (API 24–25 `renameTo()` failure reported as worker success); those defects assume or concern a worker that reached its filesystem logic, whereas this item owns failure before a WorkManager carrier is accepted.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same unchecked enqueue plus `list.first()` observer is present at synchronized checkpoint `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15` and at the reviewed checkpoint.

Required result:

- retain and observe/await the exact `Operation` returned by the cache-migration enqueue, and treat unsuccessful scheduler acceptance as an explicit failed/not-started UI outcome rather than assuming a WorkInfo carrier exists;
- bind progress/completion observation to the exact `WorkRequest.id` (or another exact attempt identity) instead of a shared historical tag, so older tagged work cannot stand in for the current request and zero-current-carrier state is representable;
- make an empty WorkInfo observation a normal state with explicit semantics (`firstOrNull()` or an exact-ID nullable result), never a throwable condition;
- do not report, refresh, or otherwise imply migration completion until the exact accepted attempt reaches the required terminal WorkInfo state; preserve `BUG-CACHE-01/03` worker/filesystem result semantics once a worker actually runs;
- on lifecycle recreation or a user retry after scheduling failure, either reconnect to a proven accepted exact WorkRequest or create a clearly new attempt; do not inherit success/failure from unrelated older `cacheFiles` work.

Terminal fault / cross-attempt requirements:

- authoritative decision/carrier: the user's cache-migration request plus the exact newly built WorkRequest ID; current code creates that identity but discards the enqueue `Operation` and observes only the broad `cacheFiles` tag;
- first persistence boundary: WorkManager's internal enqueue/database acceptance. Inject asynchronous `Operation` failure before a tagged WorkSpec is accepted; the durable app state has no migration ledger and the correct filesystem effect is none;
- recovery carrier/recovery write: current code has none for an unaccepted request. A later explicit button press creates a new WorkRequest/unique name and is a new attempt, not automatic recovery of the failed one;
- durable Download state, linked Download ledgers, `DownloadOutcome`, and media filesystem publication are not applicable because the worker never starts. The current final application outcome can instead be an uncaught `NoSuchElementException` from the LiveData observer while WorkManager reports enqueue failure;
- relevant re-entry paths are same-screen retry, Fragment/activity recreation, process restart, a previously completed tagged migration, and a new explicit migration request. Download same-settings retry/raw requeue/reconfigure/notification retry-resume and backup restore are not semantic re-entry paths and should be marked not applicable;
- the broader successful-enqueue timing candidate is rejected unless concrete WorkManager ordering evidence allows a zero-row observation after successful acceptance; this defect remains scoped to proven scheduler failure/zero-carrier handling.

Focused verification requirements:

- exercise the real Folder-settings click -> `WorkContinuation.enqueue()` -> WorkManager operation/result -> WorkInfo observation path with scheduler/database enqueue forced to fail asynchronously before the new WorkSpec is accepted; assert the UI does not crash, reports not-started/failure, and no filesystem migration occurs;
- exercise an explicit empty WorkInfo result as a control and prove it is handled without `first()`/index exceptions;
- cover normal successful enqueue and completion, worker failure as a regression for `BUG-CACHE-03`, lifecycle recreation while enqueue is pending, a prior completed work item sharing the legacy tag, process restart, and a later explicit retry with a distinct exact WorkRequest identity;
- verification must cover the actual Fragment + WorkManager wiring. No executed production-path test was run in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P2 — continued

### BUG-COOKIE-08 — Resolve overlapping enabled cookie identities without stale-precedence reversal

**State:** Open  
**Reviewed checkpoint:** `a68ee59c97619f469915895490bea8bc0956c22b`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the production Cookies screen permits multiple persisted cookie records to remain enabled at once. Clipboard import is a concrete ordinary path: every import creates a new enabled `CookieItem` with a timestamped description, so importing a refreshed Netscape jar does not replace an older enabled import. WebView capture can likewise coexist with older enabled cookie records. `CookieDao.getAllEnabledCookies()` orders these records by `id DESC`, placing the newest record first. `CookieViewModel.updateCookiesFile()` then concatenates each enabled record's raw Netscape content into one shared `cookies.txt`; its `cookieTXT.contains(line)` check removes only byte-identical lines, so two lines for the same cookie identity with different values both survive.

That file order reverses the intended credential freshness at the actual consumer. Netscape/Mozilla cookie jars key an ordinary cookie by domain, path, and name; loading a later line with the same key replaces the earlier cookie value. Thus if older row A contains `.example.com / sid=OLD` and a later explicit refresh/import creates newer row B with `.example.com / sid=NEW`, Room returns B then A, the generated file contains NEW before OLD, and the downstream jar ends with `sid=OLD`. The app then hands that exact file to yt-dlp through `--cookies`. A successful user refresh can therefore leave authenticated metadata/download requests using the stale credential rather than the newly acquired one.

The state is durable and self-reproducing. Both Room rows remain enabled across restart, and every later projection repeats the same descending-row/last-line-wins inversion. Same-settings download retry, raw/manual requeue, reconfigure, notification retry/resume, ordinary metadata retry, and process restart can all consume the same stale effective cookie while `use_cookies` remains enabled. Explicitly disabling/deleting the old record can repair the jar, but that manual cleanup is not an automatic semantic barrier for the supported multi-enabled-row state.

**Why this is a defect:** the application intentionally merges multiple enabled credential sources into one keyed runtime jar, but it has no semantic conflict-resolution rule for overlapping cookie identities. Its accidental combination of newest-first Room ordering and downstream last-write-wins parsing deterministically gives older credentials authority over a newer explicit acquisition. This can break authentication after a user refresh and can preserve revoked/rotated session values until the older row is manually disabled. It is distinct from `BUG-COOKIE-01`, which owns whether Room mutations are faithfully/durably projected at all; even a perfectly synchronized projection is wrong here. It is also distinct from `BUG-COOKIE-07`, whose collision comes from erasing Chromium partition identity; this failure occurs with ordinary unpartitioned Netscape cookies that already have the same target identity.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same `CookieDao` newest-first ordering and `CookieViewModel.updateCookiesFile()` concatenation behavior are present at the synchronized baseline checkpoint.

Required result:

- parse enabled cookie-record content into semantic Netscape cookie identities before materializing the shared runtime jar rather than resolving conflicts by raw record/line order;
- define an explicit conflict policy for identical effective cookie keys. A newer explicit acquisition/import must either supersede the stale value or surface an actionable conflict; an older enabled record must never silently override the newer credential solely because it is written later in the generated file;
- preserve distinct cookies that share a name but differ in domain or path, and preserve host-only/domain, expiry, secure, and partition-handling semantics governed by the other cookie defects rather than using coarse text matching as identity;
- make delete/disable, repeated import/capture, and process restart rebuild the same deterministic resolved jar from current enabled authority;
- if source-record recency is not sufficient to establish intent for every overlap, store an explicit generation/precedence or require user conflict resolution rather than depending on DAO iteration order and consumer overwrite direction.

Cross-attempt / terminal matrix requirements:

- authoritative observation is the set of enabled persisted cookie records plus the exact semantic cookie key/value each contributes. The carrier-creation gap is the raw concatenation into a single runtime keyspace without conflict resolution;
- the first filesystem persistence call is the write of generated `cookies.txt`. A fully successful write is already semantically wrong in the overlap case; write failure/revocation ordering remains owned by `BUG-COOKIE-01/03` and is not required to trigger this defect;
- there is no Download terminal-state or linked-ledger mutation required for the primary failure. The material effect is the effective credential selected by the yt-dlp HTTP cookie consumer, which can yield authentication failure or use a stale session value;
- relevant re-entry paths are immediate Home retry after acquisition, repeated WebView capture/import, cookie enable/disable/delete, ordinary cookie-enabled metadata/download attempts, same-settings retry, raw/manual requeue, reconfigure, notification retry/resume, and process restart. None may recover by merely regenerating the same reversed jar. Backup restore must preserve any explicit conflict/precedence semantics if enabled cookie records are restored;
- no concurrency interleaving is required. A stable pair of enabled rows is sufficient, although concurrent projection races remain separately owned by existing cookie publication findings.

Candidate-rejection proof:

- this candidate is not rejected because the user left an older cookie record enabled: the production UI explicitly supports multiple enabled records and repeated imports/captures, and `updateCookiesFile()` deliberately merges them into one jar. Once the application combines those sources, overlapping effective keys need defined semantics;
- it is not rejected as `BUG-COOKIE-01`: that defect concerns projection/revocation ordering between Room and the runtime file, while this one persists even when Room reads and filesystem publication both succeed exactly as designed;
- it is not rejected as `BUG-COOKIE-07`: no partitioned-cookie or hidden Chromium identity dimension is needed. Two ordinary valid Netscape rows with exactly the same domain/path/name and different values reproduce the failure;
- direct consumer evidence confirms the overwrite direction: a Mozilla/Netscape cookie jar loaded with NEW first and OLD second for the same domain/path/name retains OLD. Production-path app/yt-dlp integration was not executed, so verification remains `SOURCE-LEVEL ONLY` rather than PASS.

Focused verification requirements:

- exercise the real Cookies UI/import or WebView acquisition -> Room -> `CookieDao.getAllEnabledCookies()` -> `CookieViewModel.updateCookiesFile()` -> yt-dlp `--cookies` consumer path with an older enabled row containing `sid=OLD` and a later explicit refresh row containing the same cookie identity as `sid=NEW`; assert the effective request uses NEW or reports an explicit conflict, never OLD;
- cover same name with different paths/domains (must coexist), identical duplicate lines, secure/session/persistent variants, host-only vs domain controls, generated plus manually imported records, disabled older/newer rows, deletion, repeated projection, and process restart;
- reverse source creation order and include three successive credential rotations to prove precedence is intentional rather than an artifact of DAO/file iteration;
- run the real generated cookie file through the same cookie-loading path yt-dlp uses and then issue an authenticated request or equivalent integration assertion. Helper-only text-order tests are insufficient for PASS.

### BUG-NATIVE-01 — Recover STARTING yt-dlp barriers that never acquired a process group

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** `DownloadWorker` claims a queued Download under a new exact `executionId`, making that execution the durable active owner before yt-dlp starts. `YoutubeDLCompat.execute()` then calls `YtdlpNativeProcessBarrier.prepare(context, processId)` before `ProcessBuilder.start()`. `prepare()` durably writes a non-quiescent marker containing `state=STARTING` and the exact `processId`, but no process-group ID exists yet. If the application process dies after that marker write and before `ProcessBuilder.start()`, no yt-dlp supervisor or child process was ever created, so there is no external process authority left to stop; nevertheless the STARTING marker survives in app storage.

On restart, `App.onCreate()` synchronously configures the durable native barrier and launches `DownloadExecutionRecovery.reconcile()`. The recovery code includes Downloads referenced by durable native markers, sees the old exact execution as native-visible, and calls the normal exact-process cancellation path. `YtdlpNativeProcessBarrier.recover(marker)` cannot recover this state: a non-QUIESCENT marker requires `pgid`, and a STARTING marker written by `prepare()` has none, so `recover()` returns `false`. The exact cancellation therefore fails closed before the stale Active/PostProcessing Download can be requeued or terminalized. Recovery catches the failure and schedules another reconciliation, but the marker is immutable across those attempts; no later retry can manufacture the missing `pgid` for a process that never existed.

The marker also blocks ordinary reuse even if another path later makes the Download look queued. `DownloadWorker` refuses to claim a new execution while `hasAnyRegisteredNativeProcess(downloadId)` is true, and that helper includes unresolved durable yt-dlp markers. Same-settings retry, manual/raw requeue, reconfigure, notification retry/resume, and restart therefore cannot clear the semantic fence merely by publishing a new Download attempt. The result is a durable stale live execution or permanently fenced Download caused by a crash window whose safe state is actually known: process death occurred before any addressable native process identity was published.

There is a neighboring but distinct launch-transition window after the supervisor process starts and before it replaces the marker with `RUNNING` plus `pgid`; that state must remain fail-closed unless exact external-process absence can be proven. The confirmed defect does not require unsafe timeout clearing of all STARTING markers: it requires a crash-consistent protocol in which every persisted non-quiescent state has either sufficient immutable external identity to recover the exact resource or a production-safe proof that no resource could yet exist.

**Why this is a defect:** the newly introduced durable quiescence barrier is meant to prevent a newer Download execution from racing an older native process, but one of its own persisted intermediate states has no possible restart convergence. A normal abrupt process death at a production-reachable persistence boundary can therefore turn safety fencing into permanent loss of liveness: the stale Download cannot be repaired by startup reconciliation and a new exact execution cannot acquire authority. This is not defensive hardening or a test-only concern. It is distinct from `BUG-TERMINATE-01`, which owns the explicit UI terminate path that exits without first requeueing live Download rows; even the new startup recovery intended to repair stale live rows is itself blocked here by an unaddressable durable native marker.

**Ownership / attribution:** remediation regression. `YtdlpNativeProcessBarrier`, the durable native-process marker protocol, and `DownloadExecutionRecovery` were added after the synchronized baseline checkpoint as part of native-process/exceptional-exit remediation; the pinned checkpoint contains the unrecoverable STARTING state.

Required result:

- make the durable yt-dlp barrier a crash-consistent state machine in which every non-quiescent persisted state can converge after process death without weakening exact-owner safety;
- do not publish a reuse-blocking state before there is either a recoverable immutable external-process identity or a separate durable launch-generation/handshake that can prove on restart whether an external process could have been created;
- explicitly handle both the pre-`ProcessBuilder.start()` crash window and the supervisor-start-before-`RUNNING/pgid` publication window; never clear a marker merely by age when a matching live process group could still exist;
- once quiescence is proven, allow `DownloadExecutionRecovery` to requeue/terminalize the stale exact execution and remove the marker so a newer execution can be claimed exactly once;
- preserve current fail-closed behavior for a genuine RUNNING process group whose termination cannot be proven, including exact execution ownership and sibling isolation;
- ensure marker publication/transition failure and recovery-write failure retain one deterministic recovery owner rather than producing an unbounded retry of an impossible state.

Terminal fault / cross-attempt requirements:

- authoritative decision/carrier: the claimed Download `executionId` plus the durable native barrier for `download:<id>:<executionId>`; the current critical sequence writes the Download execution before native launch and then writes the STARTING marker before any OS process identity exists;
- first persistence call in the confirmed crash sequence: durable STARTING marker publication. Inject process death immediately after it and before `ProcessBuilder.start()`; durable Download may remain Active/PostProcessing, linked operation ledgers remain whatever nonterminal state preceded the crash, and the only native artifact required to reproduce is the marker itself;
- recovery carrier: current STARTING marker has exact app execution identity but no `pgid`; recovery-write/progress is impossible because `recover()` returns false, so startup reconciliation throws/reschedules without changing the durable blocker;
- filesystem/media effect: no native media output or live process is required for the confirmed pre-launch window. `DownloadOutcome` has no terminal result, the prior WorkManager execution dies with the process, and restart recovery cannot reach the Download requeue/terminal transition;
- stale Active/PostProcessing possibility: yes, indefinitely. Semantic downgrade is not the primary failure; the defect is permanent loss of recovery/reuse authority despite no remaining native actor;
- cross-attempt matrix: restart/reconcile deterministically repeats the failed barrier recovery; a newer Worker claim is rejected by the unresolved marker; same-settings retry, raw/manual requeue, reconfigure, and notification retry/resume cannot regain execution while the marker remains unresolved. Restore is not a semantic repair path for this no-backup native-process barrier and must not be treated as one;
- sibling isolation must remain intact: failure to recover one STARTING marker must not authorize clearing or killing a sibling execution and must not require weakening the exact process-group fence for valid RUNNING markers.

Candidate-rejection proof:

- this candidate is not rejected as merely `BUG-TERMINATE-01`: the failure does not require the app's explicit terminate action, and the current checkpoint already contains startup stale-execution reconciliation; the concrete blocker is the newly persisted native barrier state that that reconciliation cannot resolve;
- it is not rejected because fail-closed behavior is generally desirable: fail-closed is correct only while privileged external authority may still exist. In the confirmed pre-`ProcessBuilder.start()` crash point no native process has been created, yet the marker has no protocol state that lets restart prove and converge that fact;
- ordinary `ProcessBuilder.start()` IOException is not a repair barrier for abrupt death: the in-process exception path clears the marker, but process death bypasses that catch entirely;
- existing native-quiescence tests do not close the path: they begin with in-memory `Process` objects or already registered process owners and do not exercise durable STARTING-without-pgid plus process restart and Room recovery.

Focused verification requirements:

- exercise the real `DownloadWorker`/Room execution claim -> `YoutubeDLCompat.execute()` -> `YtdlpNativeProcessBarrier.prepare()` -> `DownloadExecutionRecovery` wiring with a deterministic death/failpoint after STARTING is durably written but before `ProcessBuilder.start()`; restart and prove the exact stale execution converges, the marker is removed only after safe proof, and the Download can be claimed exactly once by a newer execution;
- add a separate failpoint after supervisor creation but before `RUNNING/pgid` publication and prove recovery never clears or reuses the resource while the exact supervisor/process group may still be alive;
- cover `ProcessBuilder.start()` IOException as a control, marker-write failure, STARTING-to-RUNNING transition failure, process death immediately before/after process-group identity publication, recovery persistence failure, true RUNNING group termination success/failure, and sibling recovery isolation;
- verify same-settings retry/requeue/reconfigure/notification and restart behavior against the durable barrier rather than only helper-local marker parsing. Existing helper/in-memory process tests are insufficient; no such production-path process-death wiring test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

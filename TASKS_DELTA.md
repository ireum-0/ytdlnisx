# Post-Split Review Findings

This file is the append-only delta for correctness defects confirmed after review/status records moved to `ledger/remediation`.

- Baseline registry: `TASKS.md`
- Baseline registry synchronized from: `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Baseline active defects: **74**
- Delta active defects: **35**
- Effective active defects: **109**

Production truth always comes from the exact reviewed checkpoint SHA. This file records reviewed findings; it is not permission to implement or to modify the checkpoint branch.

## P2

### BUG-SCHEDULER-05 — Treat pre-Android-12 exact-alarm capability as available

**State:** Open  
**Reviewed checkpoint:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the app supports Android API 24 and above. `DownloadSettingsFragment` allows the daily `use_scheduler` setting to be enabled on API 24–30 and calls `AlarmScheduler.schedule()` because it only requests the Android 12 exact-alarm special access when `SDK_INT >= 31`. Later, when a user queues a Download outside the configured scheduler window, `DownloadViewModel` calls `AlarmScheduler.canSchedule()` before persisting the queued work. `AlarmScheduler.canSchedule()` returns `false` for every API below 31 solely because `AlarmManager.canScheduleExactAlarms()` is unavailable there. `DownloadViewModel` interprets that false result as unavailable exact-alarm authority, disables `use_scheduler`, marks the queue request unsuccessful, and reports the alarm-permission message instead of using the supported pre-Android-12 exact-alarm path.

Android's platform contract makes this a false capability result rather than an unavailable feature: `AlarmManager.canScheduleExactAlarms()` was added in API 31, and the exact-alarm special-access requirement begins with Android 12 / API 31 for apps targeting that level. The supported API 24–30 band therefore must not be represented as incapable merely because the API-31 capability query does not exist.

**Why this is a defect:** a supported OS band can enable the scheduler in settings but then have ordinary queueing outside the window reject the same scheduler configuration and silently turn it off. The failure is deterministic from the SDK-version branch and blocks a user-requested scheduling workflow even though the platform can schedule the exact alarms the implementation uses. This is distinct from `BUG-SCHEDULER-01` (daily recurrence/midnight semantics), `BUG-SCHEDULER-03` (successor alarms for individually scheduled AlarmManager work), and `BUG-SCHEDULER-04` (daily shutdown cancelling future WorkManager carriers).

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression.

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

**Why this is a defect:** notification Cancel is an explicit revocation of the Terminal attempt, yet the implementation destroys its durable carrier before proving that every actor holding publication authority has stopped. A normal scheduling race can make a cancelled task publish output after revocation, or leave partial filesystem effects with no persistent task identity from which restart/retry can reconcile them. This is distinct from `BUG-TERMINAL-02` (cross-domain ID/process identity), `BUG-TERMINAL-04` (the worker misclassifying its own partial publication after `FileUtil.moveFile()`), `BUG-TERMINAL-05` (persisted task with lost enqueue carrier), and `BUG-TERMINAL-06` (manual cache cleanup deleting files owned by a live task). The same receiver/worker ordering is present at the prior synchronized checkpoint, so this is a pre-existing baseline defect discovered post ledger split rather than a remediation regression.

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

### BUG-NATIVE-02 — Do not treat a recycled numeric process-group ID as exact native execution identity

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the yt-dlp supervisor launches the real child with `start_new_session=True`, sets `child_pgid = child.pid`, and durably publishes `RUNNING` plus that numeric `pgid` in the marker for `download:<id>:<executionId>`. If the application/supervisor dies after that publication and the original process group subsequently disappears before it can publish `QUIESCENT`, the stale RUNNING marker can outlive the kernel process-group identity it was meant to describe. Linux PIDs/process-group IDs are reusable numeric identifiers. A later app-owned native child can therefore receive the same leader PID and, because it also starts a new session, the same numeric process-group ID.

Startup configures the marker namespace synchronously but launches `DownloadExecutionRecovery.reconcile()` asynchronously. There is no process-wide recovery gate that prevents a different Download/Terminal native execution from starting before an older stale marker is reconciled. If newer sibling B acquires recycled group ID P while stale marker A still records `pgid=P`, A's recovery reaches `YtdlpNativeProcessBarrier.recover()`. That helper does not validate process start time, session-generation nonce, command/process identity, or another immutable anti-reuse attribute; it immediately calls `Os.kill(-P, SIGTERM)`, polls the same numeric group, and can escalate to `SIGKILL`. Because B runs under the same application UID, the signal can succeed against B's live group. Once B is gone, A's recovery can delete A's marker and return success, falsely treating termination of a different execution as proof that A's old native authority was quiesced.

The per-Download execution/side-effect leases do not close this path. They protect A's Room mutation from a newer execution of A's Download ID, but B has a different Download/Terminal identity and therefore does not share A's lease. The durable `processId` string stored beside `pgid` also cannot validate the current kernel group because `recover()` addresses the group solely by the recycled number. A stale recovery can therefore terminate an unrelated sibling request and then erase the only marker that explains why the signal occurred.

**Why this is a defect:** exact native-process recovery is privileged destructive authority. A durable marker may outlive the lifetime of a recyclable kernel identifier, so numeric PGID equality alone is not proof that the currently addressable process group belongs to the execution that created the marker. The concrete failure is cross-sibling termination: an old Download's recovery can kill a newer independent Download/Terminal native process, causing the newer operation to fail/cancel or lose partial native output. This is distinct from `BUG-NATIVE-01`, whose confirmed path is a STARTING marker that has no PGID and permanently fences the same stale Download. Here the marker is RUNNING and has a PGID, but its identity proof can become false and actively destroy another execution.

**Ownership / attribution:** remediation regression. The durable PGID marker/recovery protocol and startup `DownloadExecutionRecovery` were introduced after the synchronized baseline as part of native-process/exceptional-exit remediation.

Required result:

- make durable RUNNING process-group identity robust against kernel identifier reuse across app/process death; never send a recovery signal solely because the current numeric PGID equals the recorded number;
- persist and validate an anti-reuse identity or equivalent exact recovery handshake sufficient to distinguish the original process group/generation from a later group that inherited the same numeric ID, and define safe behavior when the original group is proven gone but that number is occupied by a different execution;
- preserve support for surviving descendants after the Java/app supervisor dies: a correct anti-reuse check must still be able to terminate the exact old group when it genuinely survives, rather than clearing every stale-looking marker optimistically;
- serialize startup/native-launch ordering where necessary so unreconciled stale barriers cannot race new native generations, while retaining exact per-Download/Terminal sibling isolation;
- clear the stale marker only after absence/termination of the exact recorded generation is proven. Discovery of a different current group with the same number must never authorize signalling that group.

Terminal fault / cross-attempt requirements:

- authoritative carrier: A's durable RUNNING marker (`processId`, `pgid`) plus its Download execution ID; the first persistence relevant to this defect is the supervisor's atomic RUNNING-marker replacement after the child group exists;
- inject app/supervisor death after RUNNING publication, let A's group exit without QUIESCENT publication, then create sibling B whose new-session leader reuses A's numeric group ID before startup recovery processes A;
- durable A state may remain Active/PostProcessing or in the exceptional-recovery journal, while B is independently Active/running under its own execution identity. A's recovery must not mutate B's external process authority even though no shared Room row or per-Download lease exists;
- current recovery can return success after killing B and deleting A's marker, so the outer result can falsely appear converged. B can surface a native cancellation/error or incomplete output even though its own identity was valid and never revoked;
- same-settings retry/manual requeue/reconfigure/notification retry of A does not justify signalling B; retries or new attempts of B must remain isolated. Restart/reconcile must remain safe across repeated stale markers and PID/PGID reuse. Restore is not a semantic recovery path for native OS identity;
- sibling matrix must cover Download-vs-Download and Download-vs-Terminal/native compatibility callers that share the same app UID/process namespace.

Candidate-rejection proof:

- do not reject this as low probability: identifier reuse is a normal kernel property, and v4 requires reachable destructive identity failures to be judged by correctness rather than likelihood;
- do not reject it as `BUG-NATIVE-01`: that defect's concrete failure is non-convergence of a pre-identity STARTING state. This candidate reaches a different terminal effect from a nominally addressable RUNNING state—false-positive ownership and termination of an unrelated sibling;
- a successful `kill(-pgid, 0)`/`SIGTERM` is not identity proof; it proves only that a currently signalable group has that numeric ID. The marker has no immutable comparison against that current group;
- per-Download leases do not prove OS-group identity across different Download/Terminal owners, and the asynchronous startup recovery does not prove stale markers finish before new native groups can be created.

Focused verification requirements:

- add a production-path recovery test that creates a durable RUNNING marker for A, simulates A's original group disappearance, reuses the recorded PGID for a live sibling B under the same UID, and invokes the real `DownloadExecutionRecovery -> cancelProcessesForExecution -> YoutubeDLCompat -> YtdlpNativeProcessBarrier.recover()` path; assert B receives no signal and A converges only through exact-generation proof;
- add a control where A's original exact group genuinely survives app-process death and prove recovery still terminates that group and only that group;
- cover SIGTERM success/timeout/SIGKILL escalation, group-leader exit with surviving descendants, marker deletion/write failure, app restart before/after B launch, multiple stale markers, Download-vs-Download and Download-vs-Terminal collisions, and later numeric reuse after a previously completed recovery;
- verification must exercise real OS process identity/reuse semantics or an equivalent instrumented native harness wired through the production recovery path. No such production-path execution was performed in this review, so verification remains `SOURCE-LEVEL ONLY`.

### BUG-DATE-04 — Do not terminalize History date-fetch cancellation before native extractor quiescence is proven

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the user-facing History date-fetch cancellation reaches `HistoryDateFetchManager.cancel()`, which first durably writes `cancelRequested`, requests WorkManager cancellation, and calls `stopExtractor(operationId)`. That helper invokes both yt-dlp destroy paths inside `runCatching`, but discards the Boolean returned by the current `YoutubeDLCompat.destroyProcessById()`. At the reviewed checkpoint that Boolean is a quiescence contract: `false` means exact native termination has not been proven and the durable native marker remains unresolved. The manager nevertheless calls `repository.finishCancellation(operationId)`, terminalizing the operation and all pending child items as `CANCELLED`, and can emit the terminal notification.

The WorkManager-owned cancellation path has an even earlier semantic inversion. `HistoryDateFetchWorker` catches `CancellationException`; when the durable operation says `cancelRequested`, it calls `finishCancellation()` and returns `Result.success()`. Only in `finally` does it call the same `stopExtractor()` helper, again swallowing exceptions and ignoring the quiescence Boolean. A RUNNING yt-dlp descendant group whose TERM/KILL recovery fails can therefore remain alive while the Room operation ledger is already durably terminal and the worker reports successful cancellation.

There is no later owner that repairs this state. `HistoryDateFetchManager.reconcile()` enumerates only nonterminal date-fetch operations, so a terminal `CANCELLED` operation is excluded after restart. `DownloadExecutionRecovery` only enumerates durable native markers whose process IDs use the `download:<id>:<executionId>` namespace; History date-fetch uses `history_date_fetch_process_<operationId>`. Thus an unresolved date-fetch native marker/process is not adopted by the Download recovery protocol. A later manual date-fetch start can create a new operation/process identity while the old native descendant remains unresolved, allowing two generations of the same feature to overlap after the earlier generation has been presented as cancelled.

**Why this is a defect:** cancellation is a terminal semantic commitment that revokes an operation's authority. The reviewed native helper explicitly requires callers not to release execution/resources when quiescence returns false, yet both Date Fetch cancellation paths collapse that non-success into normal terminal completion. This can leave an external process and durable native marker with no matching nonterminal operation/recovery owner, while UI/WorkManager state says cancellation completed. The defect is distinct from `BUG-DATE-03`, which owns loss of the scheduler carrier before a worker starts, and from `BUG-NATIVE-01/02`, which own respectively an unrecoverable STARTING marker and recycled-PGID identity during Download startup recovery. This path requires an ordinary Date Fetch cancellation plus a real native-quiescence failure after the process exists.

**Ownership / attribution:** remediation regression. `HistoryDateFetchManager` and `HistoryDateFetchWorker` predate the synchronized baseline and already ignored the old best-effort process-destroy return, but the reviewed checkpoint replaced that helper contract with durable descendant-quiescence semantics where `false` explicitly means privileged native authority remains unresolved. The Date Fetch cancellation callers were not adapted to that stronger contract and can now bypass the remediation's fail-closed barrier.

Required result:

- make successful date-fetch cancellation conditional on proven quiescence of the exact `history_date_fetch_process_<operationId>` native execution; propagate a `false` quiescence result instead of swallowing it;
- do not transition the operation/child ledger to terminal `CANCELLED`, emit a terminal cancellation notification, or return a successful worker outcome while exact native authority remains unresolved;
- retain a durable nonterminal cancellation/recovery carrier for the exact operation until quiescence is proven, including after process death, and make startup reconciliation own unresolved History date-fetch native markers rather than limiting recovery to Download marker namespaces;
- define recovery-write failure semantics so a failed attempt to record quiescence/cancellation cannot orphan the external process or falsely make the operation reusable;
- preserve exact operation/process identity across repeated cancellation, restart, and later Start/Reconnect; a new generation must not launch while an older cancelled generation still has unresolved native authority unless exact isolation is otherwise proven;
- continue to treat WorkManager cancellation as transport cleanup rather than proof of native process termination, and preserve `BUG-DATE-01/02/03` result/carrier semantics independently.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: durable `requestCancellation(operationId)` for the exact date-fetch operation; first persistence failure must leave the prior nonterminal operation intact and must not emit terminal cancellation;
- native barrier: after cancellation is requested, inject `YoutubeDLCompat.destroyProcessById(processId) == false` for a real RUNNING marker/process. Current code then calls `finishCancellation()` anyway; corrected code must retain a recovery-owned nonterminal cancellation state until the exact process is proven gone;
- recovery carrier / recovery-write failure: current terminalization removes the operation from date-fetch startup reconciliation while the non-download native marker remains. A corrected flow must keep operation identity plus native barrier discoverable across process death, and failure to persist recovery progress must remain fail-closed without losing the owner;
- durable state in the defect path: parent operation and pending child items become `CANCELLED`; no Download row or Download-linked ledger applies; the external yt-dlp process/group and its marker can remain live/unresolved; no media filesystem publication is required for reproduction;
- final outcome: the manager returns from cancellation normally and the Worker cancellation branch can return `Result.success()` even though native quiescence failed. That is a semantic downgrade from unresolved cancellation to successful terminal cancellation;
- same-operation cancellation retry currently finds a terminal operation and cannot restore ownership; manual Start/Reconnect can create a new operation generation; restart skips the terminal old operation; Download retry/requeue/reconfigure/notification retry-resume and restore are not semantic repair paths. Every relevant re-entry must preserve one exact owner for the unresolved native generation and prevent unsafe overlap;
- sibling/concurrency isolation: a newly started date-fetch generation or another native user must not inherit, clear, or be killed through the old generation's marker; quiescence recovery must remain exact and must also respect the identifier-reuse requirements owned by `BUG-NATIVE-02`.

Candidate-rejection proof:

- do not reject this as `BUG-DATE-03`: that item ends before a worker carrier is accepted and leaves a nonterminal RUNNING/PENDING ledger; this defect starts from an accepted/running operation and produces the opposite false terminal state while external authority may remain;
- do not reject it as `BUG-NATIVE-01`: no STARTING-without-PGID crash is required. A normal RUNNING process whose termination cannot be acknowledged is sufficient;
- do not reject it as `BUG-NATIVE-02`: this defect does not require identifier reuse or signalling the wrong sibling; it exists even when the native marker refers to the correct exact group but termination fails;
- do not treat the worker `finally` block as a repair barrier: terminal Room state and possible terminal notification are committed before that attempt, the helper result is still discarded, and failure leaves no date-fetch startup recovery owner;
- do not treat the legacy `YoutubeDL.getInstance().destroyProcessById()` call as proof of quiescence; the current compatibility layer's descendant barrier exists precisely because destroying the Java/supervisor process is not proof that its descendant process group is gone.

Focused verification requirements:

- exercise the real History cancel UI/ViewModel -> `HistoryDateFetchManager` -> Room operation/items -> WorkManager -> `HistoryDateFetchWorker` -> `YoutubeDLCompat` durable marker path with a RUNNING native process whose termination proof is forced to fail; assert operation/items remain recovery-owned and nonterminal, no terminal cancellation notification is emitted, and no successful WorkManager terminal result is produced;
- repeat through the worker `CancellationException` branch and through direct manager cancellation, covering cancellation before native launch, during STARTING, RUNNING with successful quiescence, RUNNING with failed quiescence, and cancellation just after native exit;
- inject process death after `cancelRequested` but before quiescence, after failed quiescence, and immediately before/after the eventual cancellation persistence; restart must recover the exact date operation/process once and prevent a new generation from overlapping unresolved authority;
- inject first `requestCancellation` write failure and recovery/terminal write failure, plus repeated Cancel delivery, manual Start/Reconnect, and startup reconciliation; preserve exact identity and regressions for `BUG-DATE-01/02/03` and `BUG-NATIVE-01/02`;
- verification must exercise actual Room + WorkManager + durable native-barrier wiring. The existing `HistoryDateFetchEnqueuePolicyTest` only asserts `ExistingWorkPolicy.KEEP` and provides no cancellation/quiescence evidence, so this review remains `SOURCE-LEVEL ONLY`.

### BUG-TERMINAL-08 — Do not terminalize successful Terminal work while descendant native authority remains unresolved

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** a normal Terminal task persists its `TerminalItem`, is accepted by WorkManager, and reaches `TerminalDownloadWorker`. The worker invokes `YoutubeDLCompat.execute()` under process identity `terminal:<id>`. At the reviewed checkpoint that helper runs a Python supervisor whose real yt-dlp child is in a separate process group and whose durable marker is the only descendant-quiescence proof. After the yt-dlp child exits, the supervisor calls `stop_group()`. If descendants do not terminate within the TERM/KILL protocol, the supervisor deliberately leaves the marker in non-quiescent `RUNNING` state but still exits with the yt-dlp child's exit code.

For a child exit code of zero, `YoutubeDLCompat.execute()` waits for the supervisor, calls `removeExactProcess(processId, process)`, and then returns a normal `YoutubeDLResponse`. `removeExactProcess()` is fail-closed internally: it refuses to remove the tracked process or clear the durable marker unless `YtdlpNativeProcessBarrier.isQuiescent()` is true. But that unresolved state is not reflected in the execute result. The Terminal caller therefore receives nominal success even though the exact native descendant barrier is still positive.

`TerminalDownloadWorker` then continues through its normal success path. For cache-staged tasks it can call `FileUtil.moveFile(<cache>/TERMINAL/<id>, destination, false)` and publish output while a descendant from the same native generation remains unresolved; it updates logs, cancels the running notification, deletes the `TerminalItem` row, and returns `Result.success()`. The only durable task carrier is therefore removed while the native marker/process can remain live. This is not repaired on restart: `App.onCreate()` starts `DownloadExecutionRecovery`, but `YtdlpNativeProcessBarrier.downloadProcesses()` intentionally enumerates only `download:<id>:<executionId>` markers. A `terminal:<id>` marker is excluded, and there is no Terminal startup reconciliation that adopts it. The task can consequently be durably completed and forgotten while native execution authority still exists.

The same strengthened contract is also ignored by Terminal cleanup/preflight callers. Before a new Terminal execute, and in stopped-worker cleanup, `TerminalDownloadWorker` calls `YoutubeDLCompat.destroyProcessById(processId)` but discards the Boolean whose documented contract says `false` means the caller must not release execution/resources. Those ignored results can compound the orphaning/re-entry behavior, but the primary confirmed path does not need cancellation or a pre-existing marker: a normal zero-exit Terminal execution plus failed descendant quiescence is sufficient.

**Why this is a defect:** the descendant barrier was introduced specifically because root/supervisor exit is not proof that yt-dlp-launched ffmpeg/ffprobe/aria descendants are gone. A normal Terminal attempt nevertheless treats root success as semantic completion, publishes/cleans resources, removes its durable ledger row, and reports WorkManager success while that stronger proof is explicitly still unresolved. This can leave an external native actor with no matching durable Terminal owner, allow post-success resource effects, and make restart unable to reconcile the generation. It is a correctness/reliability defect, not defensive hardening.

This is distinct from `BUG-TERMINAL-07`: no user Cancel, WorkManager-cancellation race, or stale post-revocation worker is required here. It is also distinct from `BUG-NATIVE-01`, whose failure is an unrecoverable pre-PGID STARTING marker, and `BUG-NATIVE-02`, which requires recycled PGID identity and can kill a sibling. `BUG-DATE-04` demonstrates the same strengthened-helper-contract propagation class for date-fetch cancellation, but this finding owns Terminal's **normal-success** path and Terminal marker recovery namespace. `BUG-TERMINAL-01` owns post-success bookkeeping that can reclassify committed output as failure; it does not own false success while privileged native authority remains unresolved.

**Ownership / attribution:** remediation regression. The synchronized baseline checkpoint does not contain `YtdlpNativeProcessBarrier`; the reviewed checkpoint introduced durable descendant-quiescence semantics and a fail-closed helper contract, while unchanged Terminal callers were not adapted to make that barrier part of Terminal completion or recovery.

Required result:

- make Terminal semantic completion conditional on proven quiescence of the exact `terminal:<id>` native generation; a zero exit code from the root/supervisor is insufficient while its durable descendant barrier remains unresolved;
- make `YoutubeDLCompat.execute()` expose a typed completion/quiescence result or require the caller to perform an exact post-execute barrier check before any output publication, terminal notification, task-row deletion, or `Result.success()`;
- retain a durable nonterminal Terminal recovery owner/tombstone for an unresolved generation and make startup reconciliation enumerate/adopt Terminal native markers rather than limiting recovery to the Download namespace;
- propagate `destroyProcessById() == false` from Terminal preflight, cancellation/stopped cleanup, and any other Terminal release path instead of deleting cache/task state or starting/reusing the same identity as though quiescence were proven;
- bind Terminal native executions to an exact attempt/generation identity if a row ID can be reused or rerun, so recovery cannot confuse an old unresolved generation with a later manual run;
- do not publish cache-staged output or free attempt-owned files while a descendant can still be using/mutating them; if output was already created, keep enough durable state to reconcile it after quiescence;
- preserve the separate cancellation-revocation guarantees owned by `BUG-TERMINAL-07` and the exact external-identity guarantees owned by `BUG-NATIVE-01/02`.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: Terminal success requires both native command success and exact descendant quiescence for the same Terminal generation;
- first relevant persistence/terminal mutation after the false-success observation is Terminal log/status persistence followed by `terminalDao.delete(itemId)`. Inject failure/process death before and after each; none may make an unresolved native barrier disappear from recovery ownership or be reinterpreted as completed;
- native barrier fault: force a real RUNNING marker/process group to remain non-quiescent after the yt-dlp child/root returns exit code zero. Current code returns `YoutubeDLResponse` and can continue to publication; corrected code must remain nonterminal/recovery-owned;
- recovery carrier/recovery-write failure: current durable native marker survives, but no Terminal ledger/reconciler owns it after row deletion. A corrected flow must retain exact Terminal generation plus native marker across restart, and failure to persist recovery progress must fail closed without deleting the owner;
- durable Download state and Download-linked ledgers are not applicable. The Terminal row can currently be deleted; optional log state may survive; cache/destination files may already be moved while native authority remains;
- final WorkManager result is currently `Result.success()` on the primary path. That is a semantic downgrade from unresolved external execution to completed Terminal task;
- same-command manual rerun normally creates a new Terminal task identity and does not adopt the old marker; restart ignores the `terminal:` marker; a same-ID/reused identity can hit preflight `destroyProcessById()==false`, which is currently ignored before `prepare()` fails. Download same-settings retry/raw requeue/reconfigure/notification retry-resume and backup restore are not semantic repair paths and should be marked not applicable;
- sibling/concurrency isolation must include an orphaned Terminal descendant coexisting with a later Terminal or Download native generation. Any cleanup/recovery must also satisfy the PGID anti-reuse guarantees owned by `BUG-NATIVE-02`.

Candidate-rejection proof:

- do not reject this as `BUG-TERMINAL-07`: that defect requires explicit cancellation/revocation and a worker that may publish after the durable task is deleted. This path reaches false terminal success with no cancellation at all;
- do not reject it as `BUG-NATIVE-01`: a valid RUNNING marker with a PGID and a completed child is sufficient; no pre-launch STARTING crash is required;
- do not reject it as `BUG-NATIVE-02`: no identifier reuse or wrong-sibling signal is required; the exact original descendant can simply fail to quiesce;
- do not treat `removeExactProcess()` retaining the in-memory entry/marker as a repair barrier. The caller does not observe that retention, deletes the Terminal row, returns success, and application startup has no Terminal-marker adoption path;
- do not treat successful root/supervisor exit as quiescence. The new helper implementation explicitly leaves `RUNNING` when descendant termination cannot be proven, and its public destroy contract documents `false` as fail-closed resource authority;
- do not reject the path because no dedicated Terminal native test exists. Absence of test evidence keeps verification at `SOURCE-LEVEL ONLY`; it does not restore the missing production barrier.

Focused verification requirements:

- exercise the real Terminal UI/Room row -> WorkManager -> `TerminalDownloadWorker` -> `YoutubeDLCompat.execute()` -> durable native barrier -> cache/destination publication path with a child that exits zero while a descendant/process group is forced to remain non-quiescent; assert the task remains recovery-owned/nonterminal, no success notification/result is emitted, and no attempt-owned output is published/released prematurely;
- include a normal zero-exit + QUIESCENT control, nonzero exit, STARTING state, RUNNING termination success/failure, process death after RUNNING publication, and process death after root success but before any Terminal recovery write;
- fault-inject the first write that records unresolved Terminal recovery state and its later recovery/terminal write, proving write failure cannot delete the only Terminal carrier or permit a new generation to overlap;
- restart with a `terminal:<id>` RUNNING marker and prove startup adopts and converges that exact generation once; repeat with a later manual Terminal run and with a Download sibling to prove isolation and `BUG-NATIVE-02` non-regression;
- exercise cancellation as a regression/control for `BUG-TERMINAL-07`, including `destroyProcessById()==false`, while keeping the primary normal-success invariant separate;
- verification must use actual Room + WorkManager + Terminal worker + native barrier wiring. Existing Download native-quiescence tests do not establish Terminal completion/recovery semantics, so this review remains `SOURCE-LEVEL ONLY`.

### BUG-NATIVE-03 — Do not delete a successful Download carrier while its yt-dlp descendant barrier remains unresolved

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** a normal queued Download is claimed under an exact `executionId` and reaches `DownloadWorker.executeYtdlpAttempt()`, which invokes `YoutubeDLCompat.execute()` with process identity `download:<id>:<executionId>`. The compatibility helper's supervisor waits for the yt-dlp root child and then tries to terminate the child's process group. If yt-dlp itself exits with code zero but one of its descendants cannot be proven gone after the TERM/KILL protocol, the supervisor deliberately leaves the durable native marker in `RUNNING`. `removeExactProcess()` correctly refuses to clear that marker or its tracked process entry, but `YoutubeDLCompat.execute()` still returns an ordinary successful `YoutubeDLResponse` to the Download worker.

`executeYtdlpAttempt()` then observes its async execution as completed, so its `finally` branch does **not** call `cancelYtdlpProcess()`; the unresolved descendant state is not converted into a Download failure or recovery debt. The normal Download path can consequently validate/publish the downloaded paths, insert or replace the History row, emit a finished notification, compose a completed `DownloadOutcome`, and call `DownloadRepository.completeAndDelete(id, expectedExecutionId)`. That removes the durable Download row while the exact `download:<id>:<executionId>` marker can still be non-quiescent. The worker-level finalizer retains the process-local `DownloadWorkerProcessOwners` token when `hasNativeProcessRegistryEntry()` remains true, but that in-memory token is not a durable recovery carrier and disappears if the application process exits.

Cold-start recovery does not adopt the resulting orphan marker. `DownloadExecutionRecovery.reconcile()` asks `YtdlpNativeProcessBarrier.downloadProcesses()` for durable `download:` markers, but converts each marker to a candidate only with `downloadDao.getNullableDownloadById(process.downloadId)` and discards it with `mapNotNull` when the Download row has already been deleted. The recovery function contains a `current == null` cleanup branch, but an item that is already absent before candidate construction can never reach that branch. After process death, the unresolved descendant/marker therefore has neither a Download row nor a process-local owner, even though the namespace is nominally included in Download recovery.

**Why this is a defect:** the descendant barrier exists because successful termination of the root/supervisor is not proof that native descendants have stopped mutating resources. A normal successful Download nevertheless publishes durable History/output state and deletes its only durable execution carrier before the stronger quiescence condition is proven. A surviving descendant can continue acting on attempt-owned files after the app reports completion, and after restart the durable marker itself is orphaned because recovery requires the very Download row that terminal success removed. This is a correctness/reliability and recovery-ownership defect, not defensive hardening.

This is distinct from `BUG-TERMINAL-08`, which owns the same strengthened success contract for the `terminal:` namespace and is missed because Terminal markers are excluded from Download recovery altogether. Here the marker uses the supported `download:` namespace, but recovery still loses it because candidate discovery incorrectly requires an existing Download row. It is also distinct from `BUG-NATIVE-01`, which requires a pre-PGID STARTING crash and causes permanent fencing, and `BUG-NATIVE-02`, which requires a recycled PGID and can kill a sibling. No crash before launch, identifier reuse, cancellation, or explicit failure is required for this path: root success plus unresolved descendant quiescence is sufficient.

**Ownership / attribution:** remediation regression. The synchronized baseline did not contain the durable descendant barrier or Download marker reconciliation. The reviewed checkpoint introduced the stronger quiescence contract but allows normal Download completion and row deletion to bypass it, and its new recovery enumerator cannot own markers whose Download row was already removed.

Required result:

- make successful Download completion conditional on exact yt-dlp descendant quiescence for `download:<id>:<executionId>`; root exit code zero and a usable `YoutubeDLResponse` must not by themselves authorize History publication, finished notification, Download-row deletion, or a completed WorkManager result while the native sidecar remains unresolved;
- expose the unresolved sidecar as a typed execute outcome or explicitly verify the exact native barrier before the first semantic publication/terminal write after yt-dlp returns;
- retain a durable Download/recovery tombstone or another exact generation carrier until quiescence is proven, including when the primary Download row would otherwise be removed after success;
- make startup recovery discover **all** unresolved `download:` markers independently of whether a live Download row still exists, then reconcile an orphan marker without inventing a newer Download identity and without weakening the PGID anti-reuse guarantees of `BUG-NATIVE-02`;
- do not release, move, delete, or treat attempt-owned files as immutable final outputs while a descendant from that exact generation can still mutate them; if semantic publication has already occurred, preserve enough durable provenance to converge after quiescence rather than forgetting the execution;
- preserve the normal success path when the marker is proven QUIESCENT, and preserve cancellation/retry ownership rules for still-live Download rows.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: Download success requires both the normal yt-dlp/media validation result and exact descendant quiescence for the same execution generation;
- first authoritative observation after native return is currently a nominal successful `YoutubeDLResponse`; force `root exit = 0` while the durable marker remains `RUNNING` and verify that no later success publication treats the response as sufficient authority;
- first persistence/publication calls after that observation can include History insertion/replacement and later `completeAndDelete()`. Inject first History-write failure as a control: the Download row remains available for ordinary failure/recovery semantics. The primary defect is the successful-write path followed by row deletion without a post-native quiescence barrier;
- recovery carrier/recovery-write failure: before row deletion, the Download row/execution plus marker can identify the generation. After `completeAndDelete()`, only the marker can survive. Current startup enumeration drops it when the row is absent, so no recovery write can progress. A corrected design must retain or reconstruct an exact durable recovery owner without treating marker deletion failure as quiescence;
- durable Download state in the primary defect is **absent** after completion; History and linked low-quality/other ledgers may already reflect success; filesystem output may already be user-visible while an exact descendant remains active; `DownloadOutcome` is completed and the enclosing `DownloadWorker` ultimately returns `Result.success()`;
- stale Active/PostProcessing is not required. The semantic downgrade is the opposite: unresolved external execution is reinterpreted as fully completed and then made undiscoverable by deleting the carrier needed by recovery;
- same-execution retry before terminal success can call `prepareProcessForExecution()` and attempt exact quiescence, so that path is not the primary failure. After normal completion, same-settings/manual/raw requeue or reconfigure normally creates/uses a new Download attempt rather than adopting the deleted generation; notification retry/resume has no failed/paused row to consume; restart drops the orphan marker because no row maps to it; restore is not a semantic repair path. A corrected cross-attempt contract must prevent a newer native generation from overlapping an unresolved successful predecessor and must converge the orphan exact marker first;
- sibling isolation must include a later Download or Terminal generation started after the old row was removed. Recovery of the old marker may never signal or clear a sibling based only on a recycled locator, as required by `BUG-NATIVE-02`.

Candidate-rejection proof:

- do not reject this as `BUG-TERMINAL-08`: that defect's recovery gap is namespace exclusion for `terminal:` markers. This defect is Download-specific and persists despite the marker being in the nominally supported `download:` namespace because `DownloadExecutionRecovery` maps markers through an existing-row lookup before building candidates;
- do not reject it because `removeExactProcess()` retains the in-memory process entry and marker. That retention is not observed by the normal success caller, does not stop History/row terminalization, and its process-local owner disappears on application death;
- do not treat the `current == null` branch in `DownloadExecutionRecovery` as proof of orphan cleanup: the candidate list uses `mapNotNull(getNullableDownloadById)`, so a marker whose row was absent at enumeration time never enters the loop where that branch exists;
- do not reject it as `BUG-NATIVE-01/02`: neither an unaddressable STARTING state nor identifier reuse is needed. A valid RUNNING marker for the exact original generation is enough;
- do not treat successful History publication as a reason to release native authority. The barrier's purpose is specifically to prove descendants are done before the attempt's resources and durable ownership can be abandoned.

Focused verification requirements:

- exercise the real `DownloadWorker`/Room execution claim -> `YoutubeDLCompat.execute()` -> `YtdlpNativeProcessBarrier` -> normal History publication -> `DownloadRepository.completeAndDelete()` path with a root child that exits zero while an exact descendant/process group is forced to remain non-quiescent; assert no completed `DownloadOutcome`, finished notification, row deletion, or `Result.success()` occurs while the marker is RUNNING;
- add a control with root success plus proven QUIESCENT and prove ordinary Download completion still publishes History and deletes the queue row exactly once;
- force process death after root success and after History commit but before/after any corrected quiescence/recovery write; restart must discover the exact `download:` marker even if the primary Download row is absent and must converge it exactly once before newer native work can overlap;
- explicitly seed an orphan `download:<id>:<executionId>` RUNNING marker with no Download row and invoke real startup `DownloadExecutionRecovery`; prove the marker is adopted rather than filtered out, while a recycled-PGID sibling remains untouched as a `BUG-NATIVE-02` regression;
- cover first History-write failure, `completeAndDelete()` failure, marker-clear failure, cancellation after root exit, same-execution retry, later manual requeue, and process restart. Verification must cover actual Room + Worker + native barrier wiring; no such production-path `root success + unresolved sidecar + Download terminalization` test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

### BUG-TERMINAL-09 — Keep Terminal progress-callback failures inside worker control flow

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** Terminal Run persists a `TerminalItem`, WorkManager starts `TerminalDownloadWorker`, and the worker calls `YoutubeDLCompat.execute(..., redirectErrorStream = true, callback = ...)`. The compatibility helper starts a dedicated `ProgressStreamReader` thread to drain the native process output. On each newline that thread invokes the supplied callback directly; its `run()` catches `IOException` from stream reading but does not catch callback exceptions. The Terminal callback performs notification/event work and then enters `runBlocking(Dispatchers.IO)` where it calls `TerminalDao.updateLog()` directly. That Room transaction can throw from the read or write boundary. Such an exception leaves the callback on the reader thread, bypasses `TerminalDownloadWorker`'s coroutine/outer `try/catch`, and terminates the only stdout reader rather than becoming a typed worker failure.

The transport consequence is material, not merely lost progress UI. Terminal passes `redirectErrorStream = true`, so the child process writes both stdout and stderr into the pipe drained by that reader. `YoutubeDLCompat.execute()` joins the reader thread and then waits for the process. If the callback kills the reader while yt-dlp/ffmpeg/aria continues emitting enough output to fill the bounded OS pipe, the native child can block on write while the Java side blocks waiting for process exit. On platforms/configurations where the uncaught reader-thread exception reaches the process uncaught-exception handler, the app process can terminate instead. If output is small enough for the child to exit before pipe pressure stalls it, the callback failure still is not propagated through the helper's return contract, so the worker can continue toward publication/terminal success even though an application side effect failed outside its control flow.

The normal Download path does not close this Terminal invariant. Its progress callback routes fallible side effects through guarded helper code so they do not throw through the pipe-drain thread, while Terminal supplies an independent callback with the direct Room write. The failure therefore remains reachable from ordinary Terminal execution without cancellation, descendant-quiescence failure, PGID reuse, or post-publication bookkeeping.

**Why this is a defect:** progress handling is running on a transport-critical reader thread, not on the worker coroutine. A reachable persistence failure in a nonessential/auxiliary progress side effect can therefore stop consumption of a bounded native-process pipe, hang or crash the attempt, and evade the worker's declared failure/retry/cleanup semantics. This is a substantive liveness/reliability defect rather than defensive hardening. It is distinct from `BUG-TERMINAL-01` (post-output bookkeeping reclassifying committed output), `BUG-TERMINAL-07` (post-cancel publication authority), and `BUG-TERMINAL-08` (normal-success descendant quiescence): this path occurs during active output transport before publication and requires only a throwable progress side effect.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The synchronized baseline contains the same `ProgressStreamReader` callback invocation/catch boundary and the same direct Terminal `dao.updateLog()` call inside that callback.

Required result:

- never allow a fallible application callback invoked from the stdout/stderr drain thread to terminate pipe consumption without an explicit owner-visible failure protocol;
- define whether Terminal progress notification/log persistence failure is degradable or fatal. If degradable, catch/report it while continuing to drain the native pipe. If fatal, communicate the exact failure to the worker, cancel/quiesce the exact native generation, keep draining or otherwise close transport safely, and produce the correct WorkManager/Terminal outcome;
- propagate callback failure under the worker's attempt identity rather than relying on a caller-thread outer catch that cannot observe exceptions from a separately started reader thread;
- preserve cancellation and descendant-quiescence guarantees owned by `BUG-TERMINAL-07/08` and `BUG-NATIVE-*`; callback-failure handling must not release cache/task/native authority before the exact attempt is quiescent;
- apply the same transport-reader invariant to every production callback passed into the compatibility helper, even when a sibling caller currently wraps its own side effects safely.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: the Terminal attempt is already RUNNING and owns its exact native process identity when the callback fault occurs; the progress callback is observational/ledger side-effect code and must not independently seize terminal authority by killing the reader thread;
- first injected persistence fault: make the first `TerminalDao.updateLog()` reached from a real progress line throw after the native process has started. No durable Terminal success/failure transition is committed by that exception itself, and the worker's outer catch is not entered through the current thread boundary;
- recovery carrier: the `TerminalItem` and WorkManager/native attempt may still exist, but there is no durable carrier for “progress callback failed and pipe reader stopped.” If the process stalls, no typed `DownloadOutcome` applies and WorkManager may never reach a terminal result; if the app process dies, restart behavior is governed by the existing Terminal/native recovery gaps rather than by a recorded callback failure;
- filesystem effect: staged output may be partial while the child blocks or the process dies; publication has not yet been authorized in the primary reproduction. A fatal callback policy must quiesce the exact native generation before cache/task cleanup, while a degradable policy must continue draining and leave output semantics unchanged;
- same-command manual rerun is a new Terminal attempt and does not repair the hung/orphaned old reader; cancellation during the callback fault must still reach the exact process; process restart must not reinterpret an abandoned RUNNING task as successful. Ordinary Download reconfigure/raw requeue/notification retry-resume and backup restore are not semantic re-entry paths for this Terminal transport fault and should be marked not applicable;
- sibling isolation: failure of one Terminal callback may not stop a shared reader/process belonging to another task or reuse another task's native identity. No AB/BA lock inversion is required for the primary defect.

Candidate-rejection proof:

- this is not rejected as “logging can fail”: the direct Room call executes on the only thread draining a bounded native stdout pipe, and its exception is outside the worker's catch boundary, so the effect can be native-process liveness loss rather than a missing log line;
- it is not `BUG-TERMINAL-01`: that defect starts after output has been published and concerns later bookkeeping reclassification, whereas this path can stall before native completion/publication;
- it is not `BUG-TERMINAL-08`: no unresolved descendant sidecar or root-success condition is required; an ordinary progress-line database failure suffices;
- the guarded Download progress path is not proof of Terminal safety because each caller supplies its own callback and Terminal's callback retains the direct throwable Room update;
- absence of an executed integration test does not make the path unreachable; it limits verification to `SOURCE-LEVEL ONLY`.

Focused verification requirements:

- exercise the real Terminal UI/Room -> WorkManager -> `TerminalDownloadWorker` -> `YoutubeDLCompat.execute()` wiring with a deterministic fault that makes the first `TerminalDao.updateLog()` from a progress callback throw while a child continues emitting output beyond pipe capacity; prove the attempt neither hangs nor crashes from an uncaught reader-thread exception and reaches the explicitly defined failure/degraded result;
- inject a throwing notification/progress side effect separately, plus a normal control, low-output child, high-output child, and multiple progress lines;
- race callback failure with user cancellation and with native root/descendant exit, proving exact quiescence and task/cache ownership remain correct;
- verify process restart after the fault and a later manual rerun cannot silently adopt or overwrite the failed generation;
- verification must include the actual callback execution thread and bounded pipe-drain behavior plus real Room/WorkManager wiring. A helper-only callback unit test or source existence check is insufficient for PASS.

### BUG-HARDSUB-04 — Re-evaluate hard-sub scan exclusions when eligibility inputs change

**State:** Open  
**Reviewed checkpoint:** `cc1ddaa80b15e0857a7271e28bb7d93ab4c3cf91`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** `HardSubScanWorker` reads the current `subs_lang` preference and queries `HistoryDao.getHardSubScanCandidates()`, which selects only video rows with `hardSubScanRemoved = 0` and `hardSubDone = 0`. Consider a row whose initial scan runs under `subs_lang = "en"`. The metadata fetch can succeed authoritatively and return only `availableSubtitles = ["fr"]`; `SubtitleLanguageMatcher.hasRequestedSubtitle(...)` is therefore false and the worker durably writes `historyDao.updateHardSubScanState(id, removed = true, done = false)`. That exclusion is semantically valid for the observation that just occurred.

The eligibility inputs are mutable, but the durable carrier does not record or invalidate them. `ProcessingSettingsFragment` lets the user change `subs_lang` (for example from `en` to `fr`) and its explicit “Scan now” action merely enqueues another `HardSubScanWorker`. The new worker reads the new `fr` preference, but the row is excluded by `getHardSubScanCandidates()` before any new source observation occurs because `hardSubScanRemoved` is still true. The worker's one-time rescan helper cannot repair it: `resetHardSubDoneForRescan()` resets only rows whose `hardSubDone = 1`, not removed-only rows with `hardSubDone = 0`; `hard_sub_rescan_done_once_v2` is set false after the initial reset and no production writer sets it true again. `HistoryRepository.updateHardSubScanRemoved()` / `HistoryViewModel.setHardSubScanRemoved()` exist, but repository-wide production search finds no UI/setting/reconciliation caller that clears an exclusion after subtitle-language reconfiguration.

The same stale classification can outlive source changes even without local reconfiguration. If a successful first fetch authoritatively shows no requested language and the upstream video later gains that subtitle, later “Scan now” runs still skip the row before fetching source metadata. Process restart preserves the Room flag and has no reconciliation that binds it to a subtitle-language or source-observation generation. Thus a time-scoped fact—“no requested subtitle under configuration/source snapshot A”—is reinterpreted as permanent semantic identity under later configuration/source state B.

**Why this is a defect:** the user-facing hard-sub scan is intended to evaluate current History items against the current requested subtitle languages and source metadata. A previously valid negative observation becomes a permanent exclusion even after ordinary supported configuration changes or later source availability make the row eligible. The user can explicitly request another scan and receive normal completion while a now-eligible item is never re-observed or queued. This is a substantive reliability/correctness defect, not an optimization cache. It is distinct from `BUG-HARDSUB-01`: that finding owns ambiguous/non-authoritative first observations that must not create an exclusion at all. Here the first no-match can be fully authoritative; the defect arises because the resulting negative decision has no dependency identity or invalidation across later attempts.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The pinned `HardSubScanWorker.kt` has the same blob as synchronized baseline `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`, including the same candidate filter, removed-state write, and rescan-reset behavior.

Required result:

- treat `hardSubScanRemoved` as a negative eligibility decision bound to the exact configuration/source observation that produced it, not as permanent media identity;
- invalidate or version removed-only exclusions when `subs_lang` changes and when an explicit rescan is intended to observe current source eligibility, or persist enough configuration/source fingerprinting to prove an exclusion is still current before skipping metadata lookup;
- re-observe authoritative subtitle availability before preserving a prior exclusion across a changed eligibility generation, while keeping already completed `hardSubDone = true` semantics distinct from “not eligible under the previous scan”;
- preserve `BUG-HARDSUB-01` fail-closed behavior: ambiguous/ignored-error/empty lookup results still must not create a negative exclusion, regardless of the new invalidation scheme;
- make restart and repeated manual scans converge on current configuration/source state without silently reusing stale negative authority.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: a successful subtitle metadata observation evaluated against the exact current `subs_lang`; first durable write is `updateHardSubScanState(id, removed = true, done = false)` for an authoritative no-match;
- first-write failure is fail-safe for this invariant because the row remains eligible for a later scan; the confirmed defect is the successful negative write followed by a later change to an input on which that decision depended;
- recovery carrier is only the boolean `hardSubScanRemoved`; it has no language-set/source-observation generation, so no recovery or restart path can decide whether the old authority is still semantically current;
- durable Download state, linked Download ledgers, filesystem publication, `DownloadOutcome`, and native-process authority are not involved until a row becomes a candidate and a replacement Download is queued; the defect prevents that later workflow from being reached;
- same-settings rescan after an unchanged authoritative no-match may legitimately keep the exclusion; subtitle-language reconfigure is unsafe today; manual “Scan now” after reconfigure is unsafe; a later upstream subtitle addition is unsafe; process restart provides no repair. Download raw/manual requeue, notification retry/resume, and restore are not semantic repair paths for this scan-classification state unless they explicitly invalidate/re-evaluate the same exclusion;
- expected-identity mutability is central: both `subs_lang` and the source's subtitle set can change. No concurrency or AB/BA lock interleaving is required for reproduction.

Candidate-rejection proof:

- do not reject this as `BUG-HARDSUB-01`: that defect concerns whether the **first** lookup was authoritative enough to justify `removed = true`; this path remains defective even when the first successful no-match is unquestionably authoritative;
- do not treat `resetHardSubDoneForRescan()` as an invalidation barrier: its SQL predicate only touches `hardSubDone = 1` rows and leaves `hardSubScanRemoved = 1, hardSubDone = 0` unchanged;
- do not treat the existence of `setHardSubScanRemoved(..., false)` helpers as production repair evidence: no production setting-change, scan-now, startup, or reconciliation path calls them for this state;
- do not reject the path because a user changed settings or the upstream source changed. v4 explicitly requires cross-attempt/reconfigure analysis and forbids treating a supported reconfiguration as proof that stale authority may be retained;
- no test source or helper-only test restores the missing production invalidation barrier. No dedicated production-path hard-sub rescan wiring test covering this transition was found, so verification remains `SOURCE-LEVEL ONLY`.

Focused verification requirements:

- exercise the real Processing settings -> `subs_lang` persistence -> `HardSubScanWorker.enqueue()` -> Room History candidate/state path with an authoritative first observation `subs_lang=en`, subtitles `[fr]`; assert `removed=true/done=false`, then change `subs_lang=fr`, invoke “Scan now,” and prove the same row is re-selected, re-observed, and queued for hard-sub replacement;
- add an upstream-change case where the first successful observation has no requested subtitle, the source later gains it with settings unchanged, and an explicit rescan can re-observe and queue the row according to the defined freshness/invalidation policy;
- include unchanged same-settings authoritative no-match as a control, an ambiguous/empty lookup regression for `BUG-HARDSUB-01`, an already-hard-subbed/`hardSubDone=true` control, worker retry/exhaustion, process restart between scans, and repeated reconfiguration;
- verification must cover the real settings + WorkManager worker + Room candidate query + replacement-queue wiring. No such executed production-path test was run in this review, so verification is `SOURCE-LEVEL ONLY`.

## P1 — continued

### BUG-NATIVE-04 — Preserve proven native quiescence across marker cleanup

**State:** Open  
**Reviewed checkpoint:** `6dc57cb11f53d78cce20b499f35282e0de2fd172`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** a normal Download reaches `YoutubeDLCompat.executeWithQuiescence()` under its exact `download:<id>:<executionId>` process identity. The supervisor can complete normally, prove that the exact descendant generation is gone, and persist the durable barrier as `QUIESCENT`. `execute()` then calls `removeExactProcess(processId, process)`. That helper first observes the marker as quiescent, removes the in-memory process owner, and calls `YtdlpNativeProcessBarrier.clear(marker, generationToken)`, whose successful path deletes the marker. Only **after** that cleanup returns does `execute()` construct `ExecutionResult.nativeQuiescent` by calling `YtdlpNativeProcessBarrier.isQuiescent(marker)` again. `isQuiescent()` is defined as `readMarker(marker)?.state == QUIESCENT`, so the expected absence of a successfully cleared marker evaluates to `false`.

The semantic inversion reaches the real Download path directly. `DownloadWorker.executeYtdlpAttempt()` awaits that `ExecutionResult` and throws `NativeProcessQuiescenceException` whenever `nativeQuiescent` is false, before it permits normal History/output publication, finished notification, completed `DownloadOutcome`, or Download-row deletion. The outer worker code deliberately keeps that exception out of generic terminal-success handling and enters the native recovery/requeue cleanup protocol. Thus a fully successful native generation whose quiescence proof was consumed and cleaned up exactly as intended is reclassified as **unresolved native authority**. No race, process death, PGID reuse, descendant leak, or I/O fault is required.

The failure is self-reproducing. Cleanup/recovery can observe that no native marker/process remains and requeue the Download, but a later same-settings attempt executes the same ordering: it writes a new exact marker, reaches QUIESCENT, clears it, then rereads absence as false and rejects normal success again. Manual/raw requeue and reconfiguration do not repair the helper ordering; restart can converge any prior running row but the next successful attempt repeats the same false-negative result. The newly added test source at this checkpoint expects `nativeQuiescent == true` together with `marker.exists() == false`, but there is no executed CI/status evidence for the checkpoint and the production implementation computes those two assertions from mutually inconsistent ordering.

**Why this is a defect:** descendant quiescence is an authoritative safety decision that gates the core Download success path. The remediation consumes a valid positive proof, destroys its carrier as successful cleanup, and then reconstructs the caller-visible result by re-reading the now-destroyed carrier. This deterministically converts a normal successful Download into exceptional recovery/requeue behavior and can prevent ordinary Downloads from reaching their intended terminal success despite no remaining native authority. This is a substantive core liveness/reliability regression, not defensive hardening.

**Ownership / attribution:** remediation regression introduced by the native-generation crash-convergence change present at the reviewed checkpoint. It is distinct from `BUG-NATIVE-01` (unrecoverable pre-identity STARTING state), `BUG-NATIVE-02` (wrong-sibling authority through reusable external identity), and `BUG-NATIVE-03` (the opposite semantic error: treating a genuinely unresolved descendant generation as successful and then losing its carrier).

Required result:

- carry the authoritative quiescence observation forward as part of the exact cleanup/execute result instead of re-querying a marker that successful cleanup is expected to delete;
- make `removeExactProcess()` or an equivalent exact-generation finalizer return a typed result that distinguishes `proven quiescent and cleared`, `proven quiescent but marker cleanup failed`, `still unresolved`, and `owner/generation changed`, without inferring unresolved authority from expected carrier absence;
- preserve fail-closed behavior for a genuinely RUNNING/non-quiescent marker and for generation/ownership mismatch; fixing the false negative must not weaken `BUG-NATIVE-01/02/03` safety requirements;
- define marker-delete/write failure semantics explicitly: successful semantic quiescence must not be lost merely because cleanup fails, while a failed or mismatched cleanup must retain enough exact recovery evidence for restart;
- keep Download terminal publication behind the corrected exact-generation quiescence result and allow the normal success path to publish/delete the Download exactly once when quiescence was proven.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: exact descendant generation reaches durable `QUIESCENT`; the first relevant persistence call is the supervisor's QUIESCENT marker publication. First-write failure there must remain fail-closed and is not needed for the primary defect;
- post-commit barrier: `removeExactProcess()` consumes that positive state and deletes its marker. The defect occurs because caller-visible `nativeQuiescent` is created **after** proof-carrier destruction and re-observes absence as false;
- recovery carrier: after successful marker deletion no native recovery carrier should be required because the generation is already proven gone. Current code nonetheless creates exceptional Download recovery debt from the false result; recovery may requeue the Download but cannot make the next execute attempt succeed because the same helper ordering repeats;
- durable Download state in the primary path remains nonterminal until worker cleanup/requeue; History/final publication and completed `DownloadOutcome` are intentionally withheld. Files produced by the native attempt may exist in attempt-owned staging/cache state, but the normal success publication path is not authorized. The WorkManager attempt exits through exceptional cleanup rather than ordinary success;
- stale Active/PostProcessing is not required for reproduction. If cleanup persistence itself fails, existing exceptional-recovery debt can remain, but that is a secondary first-write/recovery-write fault and not necessary to prove this defect;
- cross-attempt matrix: same-settings retry repeats the false negative; manual/raw requeue repeats it; reconfigure still traverses the same execute finalizer; notification retry/resume is not an independent repair barrier and any new native attempt repeats it; restart/reconcile can repair prior execution bookkeeping but the next successful generation fails the same way; backup restore is not a semantic repair path for native quiescence;
- concurrency/sibling matrix: no interleaving or lock-order inversion is required. The single exact owner follows the wrong local order deterministically; sibling isolation must nevertheless remain intact in the correction.

Candidate-rejection proof:

- this is not `BUG-NATIVE-03`: that defect is `root success + marker still unresolved -> false success`; this defect is `marker proven QUIESCENT + successfully cleared -> false unresolved`. The durable/terminal effect and corrective barrier are opposite;
- it is not `BUG-NATIVE-01` or `BUG-NATIVE-02`: neither an unaddressable STARTING state nor external-identifier reuse is needed;
- expected marker absence after successful `clear()` cannot itself prove `nativeQuiescent=false` because `clear()` is reached only after exact-generation QUIESCENT proof. The positive observation must be preserved through cleanup rather than discarded;
- the newly added test source is not PASS evidence. It asserts both a true quiescence result and an absent marker, but no test execution/status is present for the checkpoint and direct source ordering contradicts that intended assertion.

Focused verification requirements:

- execute the production `YoutubeDLCompat.executeWithQuiescence()` path with a normal zero-exit child whose exact generation reaches QUIESCENT; prove the returned result reports `nativeQuiescent=true` while the marker is successfully removed;
- exercise the real `DownloadWorker -> executeYtdlpAttempt()`/Room path and prove that same control reaches normal History/publication, completed outcome, queue-row deletion, and WorkManager success exactly once rather than exceptional requeue;
- add controls for a genuinely unresolved RUNNING generation (`nativeQuiescent=false`), generation mismatch, marker-clear failure, marker disappearance caused by a competing/stale owner, cancellation, and process death before/after QUIESCENT publication;
- repeat same-settings retry, manual/raw requeue, reconfigure, notification re-entry where applicable, and restart/reconcile to prove no path recreates the false negative or weakens exact-generation recovery;
- the new helper/source tests at this checkpoint remain insufficient until executed and paired with actual DownloadWorker/Room/WorkManager wiring. Verification remains `SOURCE-LEVEL ONLY`.

### BUG-NATIVE-05 — Preserve startup recovery for migrated Downloads with blank execution identity

**State:** Open  
**Reviewed checkpoint:** `6dc57cb11f53d78cce20b499f35282e0de2fd172`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** `Migration(56, 57)` adds `downloads.executionId TEXT NOT NULL DEFAULT ''`, so a Download row that was already durably `Active` or `PostProcessing` before upgrade can legitimately survive migration with a blank execution identity. The current model retains the same blank default. `App.onCreate()` starts `DownloadExecutionRecovery.reconcile()`, whose purpose includes abandoned running rows. In the current crash-convergence implementation, once such a row is selected and no process-local owner or conflicting current generation owns it, recovery unconditionally constructs `YtdlpProcessIdentity.download(current.id, current.executionId)` to inspect/clear its exact native marker. `YtdlpProcessIdentity.download()` calls `require(executionId.isNotBlank())`, so the supported migrated row throws `IllegalArgumentException` before `cleanupStoppedDownloadExecution()` can requeue or otherwise converge it. The reconciliation catch schedules another recovery but leaves the same durable row unchanged, so same-process retry and later process restart repeat the identical exception.

This is not an unsupported/corrupt-state hypothesis. Production code explicitly has a legacy branch in `DownloadWorker.cancelProcessesForExecution()` for `expectedExecutionId.isBlank()`: it documents that legacy rows have no exact execution identity, refuses unsafe cancellation by numeric Download ID alone, and treats absence of any registered native process as the fail-closed safe condition. At the previous checkpoint, startup recovery could reach the legacy cleanup path without first constructing an exact `YtdlpProcessIdentity`; the reviewed crash-convergence change inserted the unconditional strict-identity lookup while repairing native generation recovery.

**Why this is a defect:** a supported schema upgrade can leave an abandoned running Download in a durable state that startup reconciliation is specifically responsible for repairing, yet the new exact-identity helper rejects that state before the first recovery mutation. No live native process or marker is needed to trigger the failure. The row can remain indefinitely stale `Active`/`PostProcessing` with its original WorkManager execution gone, and restart simply re-enters the same require-failure loop. This is a substantive liveness/reliability regression rather than defensive hardening.

**Ownership / attribution:** remediation regression introduced by the native crash-convergence change at the reviewed checkpoint. Existing `BUG-NATIVE-01` through `BUG-NATIVE-04` own STARTING convergence, external-ID reuse, unresolved-success carrier loss, and quiescence-proof destruction respectively; none owns supported legacy Room rows rejected by the new exact-identity constructor.

Required result:

- branch supported legacy blank-execution rows before any call that requires a nonblank `YtdlpProcessIdentity`;
- when no native registry/marker authority is visible for a blank legacy row, converge stale `Active`/`PostProcessing` through the existing legacy-safe cleanup/requeue contract without inventing an execution token;
- if an unknown native process/registry entry is visible for a blank legacy row, remain fail-closed and never signal/cancel by numeric Download ID alone;
- preserve the exact nonblank execution-generation path and all safety requirements owned by `BUG-NATIVE-01` through `BUG-NATIVE-04`;
- make the first actual recovery persistence write and its failure/process-death retry durable under the existing row/recovery carrier so a migrated row cannot fall into another infinite recovery loop.

Terminal fault matrix / cross-attempt requirements:

- authoritative state: a supported migrated Download row with status `Active` or `PostProcessing` and `executionId = ""`; the migration itself preserves that sentinel as durable state;
- first recovery persistence call is never reached in the defect path because strict process-identity construction throws first. Inject failure at the first corrected cleanup/requeue write and prove the same legacy row remains discoverable for retry rather than being silently terminalized;
- recovery carrier is the Download row itself. No durable native marker, filesystem side effect, completed `DownloadOutcome`, or current WorkManager owner is required to reproduce the defect;
- durable Download state can remain stale `Active`/`PostProcessing` indefinitely; linked ledgers/filesystem state remain whatever the abandoned pre-upgrade attempt left, and no new terminal outcome is produced;
- restart/reconcile repeats the same exception today. Same-settings/manual/raw requeue, reconfigure, notification retry/resume, and restore do not populate an exact execution ID for this already-running migrated row and are not automatic repair barriers; a corrected startup path must converge the legacy carrier before a newer exact attempt is allowed to own the Download;
- sibling isolation remains fail-closed: a blank legacy row may not cancel another execution merely because a numeric Download ID collides or some unrelated native generation exists.

Candidate-rejection proof:

- the state is reachable through the checked-in supported migration, not malformed input: v56→57 explicitly adds the non-null column with default `''`, and the current model still declares that default;
- production code explicitly labels blank execution IDs as legacy and defines safe behavior for them in `cancelProcessesForExecution()`, so the new strict helper cannot dismiss them as impossible;
- no existing defect owns this invariant. `BUG-NATIVE-01` concerns a durable STARTING marker with no recoverable external identity; this path needs no marker or native process at all. `BUG-NATIVE-03/04` concern completion-side quiescence semantics, not startup migration compatibility;
- the previous recovery implementation did not unconditionally construct an exact native process identity on this path, establishing that the new failure is introduced by remediation rather than inherited baseline behavior.

Focused verification requirements:

- seed a real v56 database with abandoned `Active` and `PostProcessing` Download rows, run the production migrations to v58, and assert those rows reach startup recovery with blank `executionId`;
- with no native marker/process, invoke the real `App -> DownloadExecutionRecovery -> Room` wiring and prove each row converges through the legacy-safe cleanup/requeue/terminal contract exactly once rather than throwing/rescheduling forever;
- add a control with a blank legacy row plus visible unknown native registry authority and prove recovery stays fail-closed without numeric-ID cancellation;
- add nonblank current-generation controls, queued/non-running blank-row controls, process restart, repeated reconciliation, and failure/process death at the first corrected recovery write;
- verification must use the migration plus actual startup/recovery wiring. No executed migration+Room+startup integration test was found in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P3 — continued

### BUG-COOKIE-09 — Deduplicate projected cookie lines by exact identity, not substring containment

**State:** Open  
**Reviewed checkpoint:** `6dc57cb11f53d78cce20b499f35282e0de2fd172`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the Cookies clipboard-import path accepts a Netscape cookie file, persists each import as a separate enabled `CookieItem`, and immediately rebuilds the shared runtime `cookies.txt`. Enabled rows are returned newest-first. `CookieViewModel.updateCookiesFile()` then iterates every source line and asks `cookieTXT.contains(line)` before appending the source record. `StringBuilder.contains()` is raw substring membership, not exact-line or semantic cookie identity.

Two ordinary valid, distinct Netscape cookies can therefore make one another look duplicated. For example, let the newer enabled import contain `.sub.example.com\tTRUE\t/\tFALSE\t0\tsid\tV` and the older enabled import contain `.example.com\tTRUE\t/\tFALSE\t0\tsid\tV`, with the same standard Netscape header. The newer row is materialized first. The entire older cookie line is then a literal substring of the newer line beginning at `.example.com`, while the shared header line is also already present. Every line of the older record therefore satisfies `cookieTXT.contains(line)`, so its content is never appended at all. The two cookies are not the same destination identity: one is scoped to `.sub.example.com`, while the omitted one is the distinct parent-domain cookie needed for `example.com` and its authorized subdomains.

The omission is then written successfully to the app-owned `cookies.txt` and consumed by cookie-enabled yt-dlp paths. A request to `example.com` can consequently lose an enabled credential that Room still contains, while the narrower `.sub.example.com` credential remains. Rebuilding after process restart deterministically repeats the same substring decision because the Room rows and newest-first ordering survive. No write failure, malformed cookie, concurrency race, partitioned-cookie feature, or same-key conflict is required.

**Why this is a defect:** the runtime cookie file is intended to materialize the current enabled credential set. Raw substring containment is neither Netscape line equality nor semantic cookie identity and can delete a distinct enabled credential from the execution input even when Room state and filesystem publication both succeed. The user-visible enabled state can therefore disagree with the exact credential set sent by yt-dlp, causing repeatable authentication failure or wrong request authority. This is separate from `BUG-COOKIE-08`, which concerns precedence when two sources map to the **same** cookie key with different values; this defect loses a **different** domain/path identity before the downstream cookie parser ever sees it. It is also distinct from `BUG-COOKIE-01`, whose invariant is durability/current-generation ordering of the Room-to-file projection rather than completeness of a successfully written projection.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The same `cookieTXT.contains(line)` merge and clipboard-import path are present at synchronized checkpoint `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`.

Required result:

- parse enabled source content into exact Netscape cookie records and materialize by semantic identity, or at minimum compare complete normalized records/lines rather than arbitrary substring occurrence;
- preserve every distinct domain/path/name identity, including parent-domain and nested-subdomain cookies whose serialized text overlaps;
- integrate exact-duplicate suppression with the explicit same-key conflict/precedence semantics required by `BUG-COOKIE-08`; fixing substring omission must not reintroduce stale-value precedence or broaden host/domain/partition authority owned by the other cookie defects;
- keep malformed/comment handling explicit so header/comment text cannot accidentally suppress a credential record, and make the projection result deterministic across import order, enable/disable/delete, and restart;
- do not report or consume a current cookie projection unless every enabled supported credential is either materialized exactly once under the defined merge policy or explicitly rejected with an actionable result.

Terminal fault / cross-attempt requirements:

- authoritative observation is the set of enabled Room cookie records and each exact Netscape record identity they contribute; carrier creation fails semantically at the raw-substring dedup step before the runtime file is written;
- the first filesystem persistence call can succeed completely and still commit the wrong credential set, so first-write failure is not required to trigger this defect. Projection write/revocation failures remain owned by `BUG-COOKIE-01/03`;
- there is no Download terminal-state or linked-ledger mutation required in the primary path. The material durable carrier is the generated `cookies.txt`; the downstream effect is missing authentication authority for requests whose applicable cookie was omitted;
- same-settings retry, raw/manual requeue, reconfigure, notification retry/resume, ordinary metadata retry, repeated projection, and process restart do not repair the omission because they reuse or regenerate the same deterministic substring-filtered jar. A later explicit disable/delete of the containing row may accidentally expose the omitted row, but that unrelated user mutation is not a recovery barrier;
- no concurrency or lock ordering is required. The expected identity is stable and the failure occurs with two durable enabled rows in one projection pass.

Candidate-rejection proof:

- do not reject as `BUG-COOKIE-08`: that item requires two inputs collapsing to the same semantic cookie key and traces downstream duplicate overwrite precedence. Here the cookie keys are distinct (`.example.com` versus `.sub.example.com`), and the parent record is discarded before consumer parsing;
- do not reject as `BUG-COOKIE-01`: Room and filesystem can be perfectly synchronized and the write can be durable; the materialized contents are still incomplete because of the merge predicate itself;
- do not reject as a malformed-import edge case: both example lines are valid Netscape-style records and repeated clipboard import is a production-supported way to create simultaneously enabled rows;
- manual cleanup of one enabled row is not proof of correctness for the supported multi-enabled-row state, and absence of a production integration test limits verification to `SOURCE-LEVEL ONLY` rather than making the path unreachable.

Focused verification requirements:

- exercise the real Cookies clipboard import -> Room -> `CookieDao.getAllEnabledCookies()` -> `CookieViewModel.updateCookiesFile()` -> yt-dlp `--cookies` consumer path with a newer `.sub.example.com` cookie and older `.example.com` cookie whose remaining fields are identical; assert both distinct credentials survive the runtime projection and apply only to their correct domains;
- reverse creation order, add exact-duplicate controls, same name with different paths, parent/subdomain combinations, comment/header variations, generated plus imported rows, disabled-row controls, deletion, repeated projection, and process restart;
- add a same-key different-value regression for `BUG-COOKIE-08` and host-only/domain/partition controls for `BUG-COOKIE-05/07` so exact-line/identity handling does not weaken those semantics;
- verification must exercise the actual generated file through the downstream cookie-loading path. No production-path execution was performed in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P1 — continued

### BUG-NATIVE-06 — Fence new Download claims on unresolved durable native-marker debt

**State:** Open  
**Reviewed checkpoint:** `0aebdb76b0081a7c05b6a1c5b8d6f33b0682c89c`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the pinned checkpoint deliberately localizes startup-recovery failures by Download ID: `DownloadExecutionRecovery.reconcile()` catches a candidate's quiescence/recovery exception, retains the durable debt, records that Download in `deferredDownloadIds`, installs a retry owner, and allows the broader queue observation to continue. That is safe only if admission for the deferred Download independently fences on every surviving authority carrier. The pre-claim path says exactly that in its comment, but the predicate does not implement it. Before `claimDownloadForWorker()` publishes a new execution token, `DownloadWorker` checks `DownloadWorkerProcessOwners.canClaimNewExecution(id)` plus `hasAnyRegisteredNativeProcess(id)`. `hasAnyRegisteredNativeProcess()` reads only process-local `DownloadWorkerProcessOwners`, `YoutubeDLCompat` process registry, and in-memory post-processing processes; unlike the exact-generation `hasNativeProcessRegistryEntry()` sibling helper, it does **not** consult `YtdlpNativeProcessBarrier.hasUnresolvedDownloadExecution()` or `hasDownloadMarkerDebt()`.

A concrete cross-process path reaches that gap. The user-facing confirmed app-termination path can durably requeue an `Active`/`PostProcessing` Download and clear its `executionId` before exiting the app process. If the old yt-dlp supervisor/descendant generation has a durable non-quiescent `download:<id>:<E1>` marker, process death erases the process-local owner/registry maps while the marker survives. On restart, recovery discovers E1 and attempts exact quiescence. A STARTING transition in the post-spawn/pre-PGID-publication window, a RUNNING generation whose termination cannot be proven, marker/generation recovery failure, or any other fail-closed native recovery fault can leave the marker unresolved and defer that Download. The row can nevertheless remain durably `Queued` from the earlier requeue.

The same `DownloadWorker` then continues observing runnable queued rows. When it reaches that Download, `hasAnyRegisteredNativeProcess(id)` returns false because all of its consulted registries were process-local and were reset by process death. The surviving durable E1 marker is invisible to this gate. The worker therefore generates E2 and successfully changes the row to `Active` with the new token even though startup recovery has just failed to prove E1 quiescent. If E1's external process/group genuinely survives, E1 and E2 can now mutate the same Download's staging/output domain concurrently; even if E1 later becomes recoverable, its cleanup is now racing a newer authorized generation. The per-Download side-effect lease serializes the recovery and claim code sections, but it does not repair the missing predicate after recovery releases the lease with durable debt still present.

**Why this is a defect:** a durable native marker exists specifically to preserve external execution authority after Java/app-process state disappears. The new-claim boundary converts that surviving non-quiescent authority into “no native process” solely because it asks a narrower same-process helper. This violates the fail-closed exact-generation contract and permits overlapping attempts of one Download after restart, with concrete risks of conflicting file writes, cleanup of resources still used by the older generation, and inconsistent terminal/History state. The existing `BUG-NATIVE-01` record even assumed the opposite—that unresolved durable markers participate in `hasAnyRegisteredNativeProcess()` and therefore fence reuse—so this is not a duplicate of its STARTING-state convergence defect; it is a separate admission failure exposed once recovery is allowed to defer one Download without globally aborting the queue.

**Ownership / attribution:** remediation regression / incomplete closure of the native crash-convergence remediation. The same-process-only helper predates the pinned recovery-localization change, but the pinned checkpoint's item-local defer-and-continue behavior makes an unresolved marker and a still-runnable queued row reach the pre-claim gate without a global recovery exception suppressing queue admission.

Required result:

- make pre-claim reuse authorization include every durable native carrier that represents unresolved authority for the Download, including exact or opaque `YtdlpNativeProcessBarrier` marker debt, not only process-local registries;
- when startup or same-process recovery defers a Download because exact quiescence is not proven, exclude that Download from new execution admission until the durable debt is cleared or a stronger exact-generation protocol proves reuse safe;
- keep the per-Download execution/side-effect lease and Room claim CAS, but treat them as ordering mechanisms rather than substitutes for the missing durable-state predicate;
- do not weaken `BUG-NATIVE-01/02/03/04/05` requirements: an unaddressable STARTING state must converge safely, recycled external identifiers must not authorize sibling destruction, successful attempts must retain recovery ownership until quiescent, positive quiescence must survive cleanup, and legacy blank identities must remain compatible;
- ensure every raw/manual requeue, confirmed app-exit requeue, pause/resume, retry/reconfigure, and restart path that can make a row runnable reaches the same durable native-debt fence before a new execution token is published.

Terminal fault / cross-attempt requirements:

- authoritative decision: E1's durable non-quiescent marker is still present after process-local ownership disappeared; that means new native authority for the same Download is forbidden until E1 is exactly quiescent;
- first persistence creating the re-entry state can be the supported `Active/PostProcessing -> Queued` requeue used during app termination, which clears the old `executionId`. If that write fails, this particular queued-admission path does not proceed and the stale running row remains under recovery instead;
- recovery carrier is E1's durable native marker, optionally plus Download recovery journal. Force exact recovery/quiescence to fail; current code keeps the carrier and records item-local deferred recovery, but does not feed that result into queue eligibility or the pre-claim predicate;
- durable Download state after the defect commits is E2=`Active` with a fresh execution token while E1 marker debt remains. No linked ledger transition is required to reproduce the authority split. E1 may still mutate temp/output files while E2 starts another native request for the same Download;
- the previous WorkManager attempt died with the app process; the restarted worker remains live and may proceed normally under E2. There is no immediate typed `DownloadOutcome` required—the substantive defect is concurrent authorization before any later terminal result;
- semantic downgrade/reinterpretation is explicit: “unresolved durable external authority” is reinterpreted as “no registered native process” at claim admission because the helper's carrier set is process-local only;
- same-settings retry, manual/raw requeue, reconfigure, notification resume/retry, confirmed app-exit requeue, restart/reconcile, and any other path that produces a runnable row are unsafe unless they converge/fence the old marker first. Restore is not a semantic recovery path for an app-owned native marker and must not manufacture authority from backup state;
- lock order is not an AB/BA deadlock: recovery and claim both serialize through the per-Download side-effect lease and the process-global claim lock where applicable. The defect occurs **after** failed recovery releases those locks while durable debt remains; the next serialized claimant checks an incomplete authority predicate.

Candidate-rejection proof:

- do not reject this because exact-generation helpers such as `hasNativeProcessRegistryEntry(id, executionId)` consult the durable barrier. The production pre-claim path calls a different helper, `hasAnyRegisteredNativeProcess(id)`, whose implementation omits the barrier entirely;
- do not reject it because startup recovery exists. The pinned checkpoint intentionally catches candidate failures, records the ID as deferred, schedules item-local recovery, and continues rather than making one failed Download a global queue-admission failure. That continuation therefore requires an independent reuse fence for the same ID;
- do not reject it as `BUG-NATIVE-01`: that finding owns inability to converge a STARTING marker with no recoverable process identity. This finding remains reachable with any durable marker whose quiescence attempt is deferred, including a post-spawn STARTING transition or RUNNING termination failure, and its incorrect effect is authorization of E2 rather than merely E1 non-convergence;
- do not reject it as `BUG-NATIVE-02`: no PID/PGID reuse or wrong-sibling signal is required; the original exact E1 process may simply still exist while E2 is started;
- do not reject it as `BUG-NATIVE-03`: no normal successful Download completion or row deletion is required; the row is explicitly runnable and is reused too early;
- do not reject it as `BUG-TERMINATE-01`: app termination is one concrete producer of the queued state, but the violated invariant belongs to the later execution-admission boundary and applies to any supported requeue/re-entry path that leaves old marker debt.

Focused verification requirements:

- exercise the real `DownloadViewModel/MainActivity requeue -> process death -> App/DownloadExecutionRecovery -> DownloadWorker queue admission -> Room claim -> native launch` wiring. Seed or produce a queued Download with an old exact E1 durable marker, clear all process-local registries as restart would, force recovery of E1 to remain unresolved, and assert no E2 `claimDownloadForWorker()` or native launch occurs;
- include a STARTING post-spawn/pre-PGID-publication case, RUNNING quiescence failure, marker/generation recovery-write failure, and an opaque/legacy marker state that must remain fail-closed; prove repeated item-local recovery can run while the row stays fenced;
- add a positive control where E1 is exactly proven quiescent and the durable marker/journal debt is cleared; then and only then may the queued row be claimed once under E2;
- repeat through same-settings retry, manual/raw requeue, reconfigure, notification resume/retry where applicable, explicit app-exit requeue, and cold restart. Assert no route bypasses the same durable debt predicate;
- verify sibling isolation: a deferred marker for Download A must not globally block independent Download B, while A itself remains fenced. This is the intended benefit of item-local recovery without sacrificing per-item exact authority;
- no production Room + WorkManager + process-death/native-barrier integration test for this exact admission gap was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P2 — continued

### BUG-TERMINAL-10 — Keep Terminal setup failures inside worker terminal/recovery control flow

**State:** Open  
**Reviewed checkpoint:** `0aebdb76b0081a7c05b6a1c5b8d6f33b0682c89c`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the production Terminal Run action first persists a `TerminalItem`, then submits a one-time `TerminalDownloadWorker`. In the confirmed path the WorkManager carrier is accepted and the worker has already passed its separately guarded `setForeground()` block. `doWorkInternal()` then builds `config-TERMINAL[<timestamp>].txt` with `File.writeText(terminalPlan.sanitizedConfig)` and calls `terminalPlan.createRequest(configFile)` **before** entering the broad `try/catch/finally` that owns native execution, notification cleanup, Terminal-row deletion, and `FileUtil.deleteConfigFiles(request)`. A real app-cache write failure such as an `IOException` from full/unwritable storage therefore escapes before that semantic error boundary; request construction has the same unowned throwable window.

`TerminalDownloadWorker.doWork()` does not add a compensating catch. Its `finally` calls `cleanupStoppedWorker()` only when `isStopped`; an ordinary setup exception is not a user/WorkManager stop. The exception therefore reaches WorkManager without the worker choosing `Result.retry()` or `Result.failure()`, while the main catch never cancels the running notification or deletes the durable `TerminalItem`, and the request-scoped config cleanup is not reached. `TerminalDao` has no separate task status: every surviving row in `terminalDownloads` is returned as an active Terminal and counted by `getActiveTerminalsCount()`. The Terminal screen's WorkInfo observer may see a terminal failed state and reset its Run/Cancel controls, but it does not delete or otherwise reconcile the stale Room row. There is also no startup reconciler that pairs failed/absent WorkManager state with persisted Terminal rows.

The stale state survives ordinary re-entry. A manual rerun creates a new `TerminalItem`/ID and work request rather than adopting the failed row; process restart reloads the same old row and still has no failed-task reconciliation; the user can repair it only by an explicit unrelated cancel/delete action. The stale row can also keep maintenance paths that rely on `getActiveTerminalsCount()` believing Terminal work is active after its only execution carrier has already failed.

**Why this is a defect:** once the user request is durably represented and an execution carrier has started, every fallible setup operation needed to enter the command attempt must be owned by a terminal/recovery contract. A supported filesystem/setup failure currently bypasses that contract and leaves durable state claiming an active Terminal task with no live attempt or automatic convergence. This is a substantive reliability/state-integrity failure, not defensive hardening.

This is distinct from `BUG-TERMINAL-05`: that defect owns the earlier Room-to-WorkManager handoff where the Terminal row exists but the worker carrier is never durably accepted. Here the carrier was accepted and the worker is already running. It is also distinct from `BUG-TERMINAL-01`, which starts after output has been committed, and `BUG-TERMINAL-09`, which requires a later progress-callback exception on the native transport reader thread. No native process, publication, callback, cancellation, or concurrency interleaving is required here.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The synchronized baseline `TerminalDownloadWorker.kt` contains the same config-file write/request construction before the main `try/catch/finally`.

Required result:

- place all fallible Terminal-attempt setup after durable task/carrier creation under one owner-visible typed error/retry boundary, or individually wrap setup operations with equivalent durable semantics before any exception can escape;
- on config-file creation/write or request-construction failure, produce an explicit WorkManager result and make the exact `TerminalItem` converge to the defined retryable/failed/removed state rather than remaining indefinitely active;
- cancel/update the exact Terminal notification consistently and clean only attempt-owned partial config/cache artifacts whose ownership is proven; preserve `CancellationException`/`isStopped` semantics separately from ordinary setup failure;
- if setup failure is retryable, retain one exact durable retry carrier and prevent a manual/new attempt from being confused with the failed generation. If setup is terminal failure, retire the row honestly without implying that a command ever ran;
- add startup/re-entry reconciliation for any persisted Terminal task whose matching WorkManager attempt terminated exceptionally before the task reached its own terminal write, while preserving the enqueue-loss ownership of `BUG-TERMINAL-05` and the later native/publication invariants of `BUG-TERMINAL-07/08/09`.

Terminal fault matrix / cross-attempt requirements:

- authoritative carrier: the exact persisted `TerminalItem.id` plus an accepted/running WorkManager request. The confirmed fault is after foreground admission but before native-process creation;
- first durable application write, the Terminal-row insert, has already succeeded. Inject the first setup filesystem fault at `configFile.writeText()`: current code performs no recovery/terminal Room write afterward because the exception escapes before the main catch;
- recovery carrier: the Terminal row survives but contains no retry/failure generation or status, while the WorkManager attempt becomes exceptional/terminal. There is no startup owner that consumes this mismatch; recovery-write failure is therefore currently replaced by complete absence of a recovery write;
- durable Download state and Download-linked ledgers are not applicable. No native yt-dlp process or destination media output is needed for reproduction. A partially created app-cache config file may remain because the request-scoped `finally` is not entered;
- final WorkManager semantics are not an explicit application `Result.failure()`/`Result.retry()`; the worker throws. The durable Terminal table nevertheless continues to classify the task as active, creating a false nonterminal application state;
- stale Active possibility: yes, indefinitely at the Terminal-ledger level. A later same-command manual Run creates another task identity and does not repair the old one; manual Cancel/Delete is user intervention rather than automatic convergence; process restart leaves the row stale; Download retry/reconfigure/notification retry and backup restore are not semantic repair paths;
- no AB/BA lock order or sibling race is required. A single worker under ordinary storage/setup failure reproduces the defect.

Candidate-rejection proof:

- do not reject as `BUG-TERMINAL-05`: that item ends at scheduler handoff and has no accepted worker; this path requires a worker that was accepted and entered `doWorkInternal()`/foreground execution;
- do not reject as `BUG-TERMINAL-01`: no destination output has been committed and no post-output bookkeeping is involved;
- do not reject as `BUG-TERMINAL-09`: no native reader/callback thread exists yet; the throwable is on the worker coroutine before `YoutubeDLCompat.execute()`;
- do not treat WorkManager's framework handling of a thrown `CoroutineWorker` exception as an application recovery barrier. It does not delete or terminalize the app's separate Terminal row, and the current UI observer only resets controls for terminal WorkInfo states;
- absence of an integration test does not make the setup operation non-throwing; it keeps verification at `SOURCE-LEVEL ONLY`.

Focused verification requirements:

- exercise the real `TerminalFragment -> TerminalViewModel.insert() -> WorkManager -> TerminalDownloadWorker` path, allow foreground admission, force `configFile.writeText()` to throw, and prove the exact task reaches the defined explicit retry/failure state without a stale active Terminal row or running notification;
- separately fault `terminalPlan.createRequest(configFile)` and any fallible plan/config preparation that remains before the terminal owner catch, plus normal setup as a control;
- inject process death immediately before and after config-file creation and after a partial write, then restart and prove the exact persisted task is either resumed/retried once or terminalized honestly with owned artifacts cleaned;
- cover user/WorkManager cancellation during setup, a manual same-command rerun after setup failure, Fragment recreation, stale-row impact on `getActiveTerminalsCount()`, and `BUG-TERMINAL-05/07/08/09` non-regression controls;
- verification must include actual Room + WorkManager + Terminal worker wiring. No executed production-path setup-fault test was found in this review, so verification remains `SOURCE-LEVEL ONLY`.

## P1 — continued

### BUG-RECONFIGURE-01 — Do not delete surviving existing Downloads when one reconfigure sibling fails

**State:** Open  
**Reviewed checkpoint:** `abc3998d26bd2f17517097cc9ad1231aad10f6ed`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** multiple existing Download rows can be moved from `Processing` back to `Saved` while `UpdateMultipleDownloadsFormatsWorker` refreshes their formats; the worker later posts a “formats updated” notification containing the captured Download IDs. That notification can remain valid after one sibling has independently been deleted. When the user taps it, `HomeFragment.onResume()` calls `turnDownloadItemsToProcessingDownloads(ids, deleteExisting = true)` to edit the existing rows in place. The method first globally clears current Processing drafts, then iterates the captured IDs one by one. For an existing sibling A, `repository.getItemByID(A)` succeeds and `deleteExisting=true` causes the same durable row to be updated from `Saved` to `Processing`. If a later sibling B was deleted after notification creation, `repository.getItemByID(B)` reaches non-null `DownloadDao.getDownloadById(B)` and throws. The enclosing catch then calls `deleteProcessing()`, whose repository implementation deletes every Download currently in `Processing` and the linked History-replacement/low-quality barrier rows for that status. A—an existing user Download that was merely moved temporarily into Processing for reconfiguration—is therefore permanently deleted because unrelated sibling B failed current lookup. Any unrelated Download that legitimately entered Processing before that cleanup can be deleted by the same global status sweep.

The same failure class is reachable from another mid-loop throwable after an earlier existing sibling has committed its in-place status change, such as retry metadata/Room update failure. There is no batch transaction, prior-status snapshot rollback, exact attempt-created-ID cleanup set, or durable reconfiguration carrier. The catch swallows the exception after destructive cleanup and emits only `processingItems=false`; the Home caller can continue to navigation without a typed partial/failure result. Process restart preserves the row deletion, and later manual reconfigure/raw requeue cannot recover a Download row that no longer exists.

**Why this is a defect:** `deleteExisting=true` explicitly means the flow is temporarily staging already durable user-owned Download records for editing, not creating disposable Processing clones. A later sibling's stale identity or persistence failure cannot authorize deletion of a sibling that successfully existed and was only reclassified to Processing. The cleanup derives destructive authority solely from a mutable status shared with genuine drafts, so a normal stale notification ordering causes persistent Download configuration/data loss and can cross sibling boundaries. This is distinct from `BUG-RETRY-01`, which concerns unchanged errored retry configuration being semantically reclassified in the clone-based/default `deleteExisting=false` flow; it does not own destructive cleanup of in-place existing rows after a later sibling failure.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The synchronized baseline checkpoint contains the same `turnDownloadItemsToProcessingDownloads()` mid-loop catch plus global `deleteProcessing()` cleanup behavior.

Required result:

- separate transient attempt-created Processing drafts from pre-existing durable Downloads that are temporarily moved into Processing; cleanup authority must be based on exact provenance/attempt identity, never status alone;
- make multi-item reconfiguration sibling-isolated or transactional: failure/missing identity for B must not delete or discard A after A's earlier in-place transition, and must not sweep unrelated Processing rows;
- when editing existing rows in place, retain the exact prior durable status/configuration identity needed to roll back only the rows mutated by this attempt, or validate the whole batch before the first mutation and then commit coherently under expected-current predicates;
- return a typed complete/partial/failure result to the notification/Home caller and do not navigate as though reconfiguration succeeded after destructive cleanup;
- make stale notification re-entry, retry/reconfigure, manual/raw requeue, process restart, and later format refresh converge without requiring restoration from an unrelated backup and without broad status-based deletion of surviving rows;
- preserve linked History-replacement/low-quality ledger semantics for rows that remain authoritative; a sibling failure must not delete those ledgers merely because the row temporarily carried `Processing`.

Terminal fault / cross-attempt requirements:

- authoritative decision: the user reopens the exact existing Download IDs captured by the completed format-update notification; current existence/expected identity for every row must be proven before destructive status-dependent cleanup can own it;
- first persistence in the concrete failure path: A's successful `Saved -> Processing` in-place Room update. Inject B missing/read failure immediately afterward; current recovery performs global `deleteProcessing()` rather than rollback and deletes A;
- recovery carrier: none. If the destructive cleanup succeeds, A is absent. If that cleanup itself fails, A can remain stranded in `Processing` without a durable record of its prior Saved status or of the failed batch generation;
- linked-ledger state: `DownloadRepository.deleteProcessing()` also removes History replacement barriers and low-quality-redownload ledger rows for Processing status. No filesystem/media publication is required to reproduce the primary defect;
- final application result: the inner coroutine catches and suppresses the failure and emits `processingItems=false`; the caller receives no typed batch failure. WorkManager already completed the earlier format-update task and is not the owner of this re-entry failure. Stale Active/PostProcessing is not required;
- semantic downgrade: a durable existing Download temporarily marked Processing is reinterpreted as a disposable draft solely because another sibling throws;
- cross-attempt matrix: tapping the same stale notification again retains the obsolete ID set; manual reconfigure or raw/manual requeue cannot recover a row already deleted; notification retry/resume is not a repair path; restart preserves absence; restore from an older backup is not semantic recovery and must not be relied upon. A later format refresh may only act on whatever rows survive and cannot reconstruct the lost row;
- concurrency/sibling isolation: a supported deletion/status change of B after notification creation is sufficient; no AB/BA deadlock is required. The batch must tolerate mutable sibling identities without transferring destructive authority to A or unrelated Processing rows.

Candidate-rejection proof:

- this candidate is not rejected because B changed or was deleted after notification creation: stale re-entry identity is explicitly part of the supported cross-attempt surface, and B's mutation grants no authority over sibling A;
- `deleteExisting=true` proves A is an existing durable row being edited in place; it is not equivalent to the transient id=0 clones used by the ordinary clone-based Processing flow, so the global cleanup cannot be justified as disposing only attempt-created drafts;
- this is not `BUG-RETRY-01`: that defect owns retry/reconfigure semantic classification for unchanged Error settings and does not require or cover a later sibling exception or deletion of an already existing Download row;
- the presence of one outer catch does not restore atomicity: its cleanup is the destructive step that violates provenance and sibling isolation, and there is no prior-status rollback or exact created-row set;
- no production test exercising a later-sibling failure after an earlier in-place `deleteExisting=true` commit was found; absence of such a test keeps verification at `SOURCE-LEVEL ONLY` rather than making the path unreachable.

Focused verification requirements:

- exercise the real `UpdateMultipleDownloadsFormatsWorker -> formats-updated notification -> HomeFragment -> DownloadViewModel.turnDownloadItemsToProcessingDownloads(deleteExisting=true) -> DownloadRepository/Room` wiring with two existing rows A and B. Create the notification, delete B before tapping it, then assert A is not deleted or stranded and unrelated Processing rows are untouched;
- repeat with B failing from a DAO/update/retry-preparation fault after A's in-place transition, and inject failure into the first rollback/recovery write of the corrected implementation;
- cover B as first versus later sibling, three-row batches, an unrelated existing Processing row, linked History-replacement/low-quality ledgers, process death after A transitions but before B is read, repeated stale-notification taps, and normal all-present reconfiguration;
- exercise both `deleteExisting=true` and clone-based `deleteExisting=false` as controls so corrected provenance-aware cleanup does not leak temporary clones or delete pre-existing rows;
- verification must cover the actual Home/notification + ViewModel + Room wiring and the persistent state after restart. No such production-path test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

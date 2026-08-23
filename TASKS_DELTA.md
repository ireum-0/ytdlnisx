# Post-Split Review Findings

This file is the append-only delta for correctness defects confirmed after review/status records moved to `ledger/remediation`.

- Baseline registry: `TASKS.md`
- Baseline registry synchronized from: `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Baseline active defects: **74**
- Delta active defects: **12**
- Effective active defects: **86**

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
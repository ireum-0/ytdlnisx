# Post-Split Review Findings

This file is the append-only delta for correctness defects confirmed after review/status records moved to `ledger/remediation`.

- Baseline registry: `TASKS.md`
- Baseline registry synchronized from: `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Baseline active defects: **74**
- Delta active defects: **4**
- Effective active defects: **78**

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

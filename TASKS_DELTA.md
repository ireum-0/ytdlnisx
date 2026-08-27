# Post-Split Review Findings

This file is the append-only delta for correctness defects confirmed after review/status records moved to `ledger/remediation`.

- Baseline registry: `TASKS.md`
- Baseline registry synchronized from: `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Baseline active defects: **74**
- Delta active defects: **41**
- Effective active defects: **115**

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

[... existing content preserved exactly through BUG-CACHE-05 ...]

## P1 — continued

### BUG-LOWQUALITY-01 — Preserve low-quality cancellation intent across first persistence failure

**State:** Open  
**Reviewed checkpoint:** `92113cdaba27922ec129e8db648ae3ff951fc789`  
**Verification:** `SOURCE-LEVEL ONLY`

**Failure path:** the running low-quality re-download UI exposes Stop, which calls `LowQualityRedownloadViewModel.cancel(operationId)` and then `LowQualityRedownloadManager.cancel(operationId)`. The manager's first semantic step is `repository.requestCancellation(operationId)`. Before any cancellation state is durable, that repository method snapshots current linked `Active`/`PostProcessing` Download execution identities, acquires their side-effect leases, revalidates them, and only then executes the Room transaction `requestCancellationAndMarkItems()`. That transaction is the first durable carrier for the user's decision: it sets `operation.cancelRequested = 1`, marks unqueued children `CANCELLED`, marks queued/active/waiting children `CANCELLATION_REQUESTED`, and returns the linked nonterminal Download IDs.

If any pre-transaction linked-row read/lease operation throws, or if the Room transaction itself throws/rolls back before `cancelRequested = 1` commits, `LowQualityRedownloadManager.cancel()` catches the exception and only calls `scheduleCancellationConvergence(operationId)`. The scheduled convergence task re-reads the operation and immediately returns when `cancelRequested` is still false and state is not already `CANCELLED`. Thus the catch path has no durable intent from which to recover. The finally callback still runs, and the public ViewModel API returns no typed indication that the Stop decision was never accepted.

The loss survives process death. Startup `LowQualityRedownloadManager.reconcile()` sees the same operation as ordinary `RUNNING` with `cancelRequested = false`, so it resumes/reconnects normal low-quality execution rather than reconstructing the user's earlier Stop. Linked Download workers/native processes can therefore continue, and later queue/reconcile paths remain authorized exactly as if cancellation had never been requested. A later new manual Stop may succeed, but no same-attempt/restart path preserves the original authoritative decision.

**Why this is a defect:** Stop is an explicit user revocation of a durable multi-Download operation. The implementation can receive that decision, encounter a supported persistence/lease fault before its first durable carrier, and silently reinterpret the result as "continue running." Scheduling recovery is not a recovery barrier when the predicate required by that recovery was the very write that failed. This is a substantive lifecycle/reliability defect: linked downloads and external/native side effects may continue after an acknowledged user Stop, and restart provides no evidence that the cancellation ever existed.

This is distinct from `BUG-ADMISSION-01`, which owns a Download claim that becomes a false-live execution after a successful claim write, and from `BUG-NATIVE-06`, which owns admission of a newer Download execution while old durable native debt remains. Neither owns loss of the low-quality operation's first cancellation-intent persistence. The cancellation/terminalization ordering otherwise uses the new per-Download side-effect leases; the defect occurs before that durable cancellation authority is established.

**Ownership / attribution:** pre-existing baseline defect discovered post ledger split; not a remediation regression. The synchronized baseline `LowQualityRedownloadManager.cancel()` / repository cancellation path already relies on the same first `cancelRequested` persistence and catch-time convergence behavior.

Required result:

- establish a durable exact cancellation-intent carrier before fallible linked-execution inspection/lease acquisition can cause the Stop decision to disappear, or make first-carrier failure an explicit caller-visible failure while retaining/retrying the same semantic decision through a durable owner;
- do not invoke completion/success-like UI callbacks as though cancellation was accepted when no cancellation carrier was durably established; expose an exact accepted/retryable/failed result to the caller;
- make cancellation convergence able to own and retry first-persistence failure rather than requiring `cancelRequested = true` as its admission predicate when that is the failed write being recovered;
- preserve the exact linked Download execution identities and side-effect leases for the later cancellation phase so the fix does not weaken claim/cancel serialization, sibling isolation, or native-process ownership;
- on process death/restart after the Stop decision but before or during the first persistence attempt, recover the same cancellation semantic exactly once or surface an explicit unresolved failure; never resume the operation as ordinary RUNNING merely because the first write failed;
- keep later `completeCancellation()` and child terminalization behind the existing terminal-state checks and do not broaden cancellation to unrelated Download generations.

Terminal fault matrix / cross-attempt requirements:

- authoritative decision: the user Stop for the exact low-quality operation ID;
- first persistence call: `LowQualityRedownloadDao.requestCancellationAndMarkItems()` after linked execution snapshot/lease acquisition. Fault both the pre-carrier lease/read window and the transaction's first write; rollback currently leaves `cancelRequested = false` and child item states unchanged;
- recovery carrier: none after the current first-write failure. `scheduleCancellationConvergence()` is scheduled, but `runCancellationConvergence()` returns immediately because the durable operation does not say cancellation was requested;
- recovery-write failure: a corrected design must retain the same exact cancellation generation/debt if its retry write fails and must not fall back to ordinary RUNNING semantics;
- durable Download state: linked children can remain Queued/Waiting/Active/PostProcessing under their prior execution identities; no cancellation-requested Download publication is guaranteed;
- linked low-quality ledger state: parent remains `RUNNING`, `cancelRequested = false`; children retain pre-cancel states;
- filesystem/native effect: existing linked Download/native work may continue and publish normally because no durable cancellation authority was established;
- final application outcome: manager logs the exception, schedules ineffective convergence, and runs its completion callback; there is no typed cancellation failure. Existing child WorkManager attempts remain unaffected by the lost Stop;
- stale Active/PostProcessing possibility: yes, as ordinary still-authorized work rather than abandoned state;
- semantic downgrade/reinterpretation: explicit Stop is reinterpreted as continue-running after first persistence failure;
- cross-attempt matrix: same-process convergence does nothing without `cancelRequested`; same-settings automatic operation continuation remains allowed; manual/raw Download requeue and reconfigure do not restore the lost operation-level Stop; notification retry/resume is not a cancellation recovery carrier; restart `reconcile()` sees ordinary RUNNING and continues; backup restore is not a semantic repair path; a later **new** manual Stop can create a new cancellation decision but does not preserve the original one.

Candidate-rejection proof:

- do not reject because the manager catch calls `scheduleCancellationConvergence()`: the production convergence function explicitly returns when `cancelRequested` is false, which is exactly the durable state after the first cancellation transaction fails or rolls back;
- do not reject because the first write is transactional: transaction rollback preserves database consistency but also removes the only durable evidence that the user requested cancellation; atomic rollback is not semantic recovery of the external decision;
- do not reject because a later manual Stop can repair the operation. v4 requires the original authoritative decision to survive first-write/restart faults or to fail explicitly; unrelated repeated user action is not a recovery carrier;
- do not merge into `BUG-ADMISSION-01` or `BUG-NATIVE-06`: those defects concern Download execution admission/ownership. This failure exists before linked Download cancellation publication and is owned by the parent low-quality cancellation-intent boundary;
- production search found no independent durable cancellation request/tombstone outside `cancelRequested`/child cancellation states that startup reconciliation could use to reconstruct the lost Stop.

Focused verification requirements:

- exercise the real low-quality UI/ViewModel -> `LowQualityRedownloadManager.cancel()` -> repository -> Room -> linked Download/WorkManager wiring and force failure (a) while reading/acquiring the current linked execution leases before the cancellation transaction, and (b) on the first Room write/transaction of `requestCancellationAndMarkItems()`;
- after each fault, assert the caller receives explicit failure or the exact Stop remains durably recovery-owned, and assert the operation cannot continue as ordinary RUNNING under an apparently completed Stop action;
- kill/restart the app immediately after the failed first persistence attempt and run the real startup `LowQualityRedownloadManager.reconcile()`; prove the same cancellation semantic is retried/converged rather than erased;
- include healthy linked siblings, an Active/PostProcessing linked Download, Queued/Waiting children, repeated Cancel delivery, recovery-write failure, cancellation racing a Download claim, and a normal successful cancellation control;
- verify that child WorkManager/native authority is eventually revoked only after the exact durable cancellation carrier exists, and regress `BUG-ADMISSION-01`/`BUG-NATIVE-06` serialization. No production Room + WorkManager + low-quality first-write fault test was executed in this review, so verification remains `SOURCE-LEVEL ONLY`.

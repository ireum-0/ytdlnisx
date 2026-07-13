# Verification Note: H1. User-configurable cache path can be recursively deleted

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: High
- Fix priority: P0
- Codex review needed: Yes
- Runtime/device test needed: Yes

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/ui/more/settings/FolderSettingsFragment.kt`
- Function: `changePath(p: Preference?, data: Intent?, requestCode: Int)`
- Evidence: When the user selects a folder via the SAF `ActivityResultLauncher`, the URI string is extracted and stored: `editor.putString("cache_path", path).apply()`.

- File: `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`
- Function: `getCachePath(context: Context)`
- Evidence: Reads the `cache_path` preference. If set, it returns the result of `formatPath(preference)`. `formatPath` converts SAF tree URIs into raw filesystem paths (e.g., `/storage/emulated/0/...`).

- File: `app/src/main/java/com/ireum/ytdl/ui/more/settings/DownloadSettingsFragment.kt`
- Function: `cleanup_leftover_downloads` preference listener
- Evidence: Schedules `CleanUpLeftoverDownloads` via `WorkManager` using `OneTimeWorkRequestBuilder` with delays for "daily", "weekly", or "monthly" intervals.

- File: `app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt`
- Function: `doWork()`
- Evidence: Checks `activeDownloadCount`. If zero, it calls `File(FileUtil.getCachePath(context)).deleteRecursively()`.

- File: `app/src/main/java/com/ireum/ytdl/ui/more/settings/FolderSettingsFragment.kt`
- Function: `onCreatePreferences` (inside `clearCache` click listener)
- Evidence: Defines a `clearCacheFolder` function that recursively deletes children of the directory returned by `FileUtil.getCachePath()`.

## 2.5 Preconditions and runtime assumptions

- User action required: User must select a custom cache directory in settings and choose a directory containing important files.
- App permission/access required: App must have file system access to the selected path (e.g., "All files access" on API 30+ or standard storage permissions on older versions).
- Android/API behavior required: The SAF provider must be one that `FileUtil.formatPath` can map to a raw `/storage/` path (confirmed for standard External Storage Provider).
- Background worker/scheduler trigger required: `CleanUpLeftoverDownloads` must be scheduled and triggered by `WorkManager`.
- Provider/FileSystem behavior required: `java.io.File.deleteRecursively()` must have permission to delete the contents.
- External app/action required: N/A.
- Network/native tool behavior required: N/A.
- Unverified assumptions: The exact conversion reliability of `formatPath` for all 3rd party SAF providers.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| `cache_path` is user-configurable to arbitrary SAF paths | Yes | `FolderSettingsFragment.changePath` | Stores the URI string directly in preferences. |
| `FileUtil.getCachePath` returns the raw path of the user selection | Yes | `FileUtil.getCachePath` calls `formatPath` | `formatPath` bridges SAF to `/storage/` paths. |
| `CleanUpLeftoverDownloads` recursively deletes the cache path | Yes | `CleanUpLeftoverDownloads.kt`: `doWork()` | Confirmed `doWork()` implementation. |
| The cleanup can be triggered automatically | Yes | `DownloadSettingsFragment.kt` scheduling logic | Automated background trigger exists. |
| `FolderSettingsFragment` manual clear also deletes contents | Yes | `FolderSettingsFragment.kt`: `clearCacheFolder` | Manual path to the same risk. |

## 4. Corrected interpretation

The app allows users to select a custom "Cache directory" via the Storage Access Framework (SAF). The `FileUtil.formatPath` utility converts this SAF URI into a raw filesystem path (e.g., `/storage/emulated/0/...`). This raw path conversion is specifically reliable for standard SAF providers (like External Storage Provider) that map directly to the file system.

Two separate mechanisms in the app then perform recursive deletion on this path using standard `java.io.File` APIs:
1. `CleanUpLeftoverDownloads`: A background `CoroutineWorker` that deletes the entire directory recursively if no downloads are currently active.
2. The "Clear cache" button in settings manually triggers a recursive deletion of all children within the configured path.

If a user selects a directory containing important data (such as their main `Downloads` folder or a folder shared with another app), and the app has the necessary file permissions, the app will delete unrelated user files during these cleanup operations.

## 5. Risk assessment

- Actual impact: Complete loss of all files in the user-selected directory.
- Trigger: Either the user clicking "Clear cache" or the `CleanUpLeftoverDownloads` worker running automatically in the background.
- User interaction required: Only once to misconfigure the path; deletion can then happen automatically.
- External app trigger possible: No.
- Affected scope: It is limited to the directory the user selected, which can be any directory they have access to via SAF that the app can also reach via the filesystem.
- Severity judgment: High. Automatic recursive deletion of user-selected directories is a significant data loss risk.

## 6. Recommended fix

- Minimal safe fix: In `CleanUpLeftoverDownloads` and `clearCacheFolder`, instead of deleting the root, only delete specific known subdirectories used by the app (e.g., numeric download IDs, `TERMINAL/`, `tmp/`, `infojsons/`). Never call `deleteRecursively()` on the configured cache root itself.
- Better long-term fix: Ensure the cache directory is always an app-managed subfolder (e.g., `ytdlnisx_cache/`) created within the user's selected path. Validate that the directory being deleted is truly owned by the app.
- Avoid: Do not use `deleteRecursively()` on a path that could potentially be a root user folder like `Downloads` or `Documents`.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: Yes.
- Manual test:
    1. Go to **Settings > Folders**.
    2. Change "Cache directory" to a test folder (e.g., `/sdcard/Download/TestCache`).
    3. Place an unrelated file `important_user_file.txt` in that directory.
    4. Create some app-owned temp subdirectories: `tmp/`, `infojsons/`, `TERMINAL/`, and `12345/`.
    5. Ensure no downloads are running.
    6. Click "Clear cache" in Folder settings, or trigger `CleanUpLeftoverDownloads` (via `DownloadSettingsFragment` "Cleanup leftover downloads" setting).
- Expected before fix: The entire folder is cleared. `important_user_file.txt` is deleted.
- Expected after fix: `important_user_file.txt` remains. Only `tmp/`, `infojsons/`, `TERMINAL/`, and `12345/` are deleted.

## 8. Codex handoff summary

- Final verdict: Confirmed
- Strongest evidence: `CleanUpLeftoverDownloads.kt` calling `deleteRecursively()` on `FileUtil.getCachePath()`.
- Weakest/uncertain part: The reliability of `formatPath` across all Android versions/devices for non-standard SAF providers.
- Recommended action: Patch `CleanUpLeftoverDownloads.kt` and `FolderSettingsFragment.kt` to avoid recursive deletion of the root cache path.
- Files Codex should inspect: `CleanUpLeftoverDownloads.kt`, `FileUtil.kt`, `FolderSettingsFragment.kt`.
- Question Codex should answer: How can we ensure the cleanup logic stays synchronized with all temporary folder types the app creates?

# Verification Note: H2. Tree URI history paths can reach whole-directory deletion

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: High
- Fix priority: P0
- Codex review needed: Yes
- Runtime/device test needed: Yes

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`
- Function: `deleteDocumentUri(uri: Uri)`
- Evidence: Attempts to delete the URI using `DocumentFile.fromSingleUri`. If that fails or does not delete the target, it calls `DocumentFile.fromTreeUri(App.instance, uri).delete()`.

- File: `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`
- Function: `deleteFile(path: String)`
- Evidence: Normalizes the path and, if it starts with `content://`, parses it as a `Uri` and passes it to `deleteDocumentUri(uri)`.

- File: `app/src/main/java/com/ireum/ytdl/database/repository/HistoryRepository.kt`
- Function: `delete(item: HistoryItem, deleteFile: Boolean)` and `deleteAllWithIDs(...)`
- Evidence: If `deleteFile` is requested, it calls `FileUtil.deleteFilesWithZeroByteSiblings(item.downloadPath)`.

- File: `app/src/main/java/com/ireum/ytdl/database/viewmodel/SettingsViewModel.kt`
- Function: `restoreData(data: RestoreAppDataItem, ...)`
- Evidence: Iterates through `data.downloads` (list of `HistoryItem`) and inserts them into the database using `historyRepository.insertAndGetId()`. The `downloadPath` field is taken directly from the external `data`.

## 2.5 Preconditions and runtime assumptions

- User action required: User must restore/import a backup containing a Tree URI in a history item's `downloadPath`, then later delete that item with "Delete file" checked.
- App permission/access required: The app must have persistent URI permissions for the Tree URI (e.g., if the user previously selected that directory or its parent for any app feature).
- Android/API behavior required: `DocumentProvider` must support the `delete()` operation on the tree root URI.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: N/A (unless the malicious backup is provided by an external source).
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Tree URI paths can reach deletion code | Yes | `FileUtil.deleteFile` routes `content://` to `deleteDocumentUri`. | Source confirmed. |
| Deletion code can delete an entire SAF tree | Yes | `FileUtil.deleteDocumentUri` calls `fromTreeUri(...).delete()`. | Source confirmed. |
| History deletion triggers this path | Yes | `HistoryRepository` calls deletion on `downloadPath` entries. | Source confirmed. |
| Stored paths can be arbitrary strings | Yes | `SettingsViewModel.restoreData` copies `downloadPath` from JSON. | Source confirmed. |

## 4. Corrected interpretation

The app's file deletion utility (`FileUtil.deleteDocumentUri`) is designed to handle both single documents and document trees by attempting deletion through both `DocumentFile.fromSingleUri` and `DocumentFile.fromTreeUri`. While intended for flexibility, this creates a high-risk path where any component that stores file paths and later allows their deletion (primarily the Download History) can trigger a recursive directory wipe if a Tree URI is stored instead of a single File URI.

The most likely path for this to occur is through the Data Restore feature, which imports history items from a JSON file and inserts them into the database without validating that the `downloadPath` entries are restricted to single file URIs.

## 5. Risk assessment

- Actual impact: An entire directory (e.g., a user's whole "Downloads" or "Backups" folder) can be recursively deleted without warning if its URI was stored in a history item.
- Trigger: A user deleting a history item with the "Delete file" option enabled.
- User interaction required: Yes, to initiate the deletion of the history item.
- External app trigger possible: No, but a malicious backup file could be used to set the trap.
- Affected scope: Any directory for which the app has a valid, persistent SAF Tree permission.
- Severity judgment: High. The result is permanent and significant data loss in user-selected storage.

## 6. Recommended fix

- Minimal safe fix: Remove the `DocumentFile.fromTreeUri(...).delete()` fallback in `FileUtil.deleteDocumentUri`. File deletion should only target single document URIs.
- Better long-term fix: In `SettingsViewModel.restoreData`, validate that `downloadPath` entries are either raw file paths or valid Document URIs (not Tree URIs). In `FileUtil.deleteFile`, explicitly check that a `content://` URI is not a Tree URI before attempting deletion.
- Avoid: Do not use `DocumentFile.fromTreeUri(...).delete()` for general file deletion tasks.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: Yes.
- Manual test:
    1. Create a test folder containing several files.
    2. Obtain its SAF Tree URI (e.g., by selecting it as a backup folder).
    3. Create a JSON backup where a `HistoryItem` has this Tree URI in its `downloadPath`.
    4. Restore this backup in the app.
    5. Delete the history item and check "Delete file".
- Expected before fix: The entire test folder is deleted.
- Expected after fix: The folder remains intact.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `FileUtil.kt:96` calling `tree.delete()` on a URI that could be a Tree root.
- Weakest/uncertain part: None; the logic flow from database to `delete()` is clear.
- Recommended action: Patch `FileUtil.deleteDocumentUri` to remove the `fromTreeUri` deletion path.
- Files Codex should inspect: `FileUtil.kt`, `HistoryRepository.kt`, `SettingsViewModel.kt`.
- Question Codex should answer: Should the app ever be deleting directory trees via URI? (None were found in the current audited scope).

# Verification Note: M1. DownloadWorker starts download jobs outside WorkManager-owned coroutine scope

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: Yes

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt`
- Function: `doWork()`
- Evidence: Line 243 starts a download job using `CoroutineScope(Dispatchers.IO).launch { ... }`. This scope is detached from the worker's own lifecycle.
- Evidence: The class does not override `onStopped()`, meaning it lacks a hook to clean up native processes when WorkManager stops the worker.

## 2.5 Preconditions and runtime assumptions

- User action required: N/A
- App permission/access required: N/A
- Android/API behavior required: WorkManager's `onStopped()` is the standard hook for cleanup.
- Background worker/scheduler trigger required: WorkManager must start the `DownloadWorker`.
- Provider/FileSystem behavior required: N/A
- External app/action required: N/A
- Network/native tool behavior required: `yt-dlp` (python) process must be running.
- Unverified assumptions: N/A

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Downloads start in detached scope | Yes | `DownloadWorker.kt:243` | `CoroutineScope(Dispatchers.IO)` used. |
| Jobs not children of worker coroutine | Yes | `DownloadWorker.kt:243` | Detached `launch`. |
| No onStopped() cleanup | Yes | `DownloadWorker.kt` | Missing override. |

## 4. Corrected interpretation

`DownloadWorker` bypasses structured concurrency by launching each download in a standalone `CoroutineScope`. Consequently, when `WorkManager` cancels the worker (due to user intervention, battery optimization, or loss of connectivity constraints), the underlying coroutines and the native `yt-dlp` processes they manage are not terminated. They continue to consume system resources and bandwidth in the background.

## 5. Risk assessment

- Actual impact: Resource leak and unexpected background data consumption. Active download records in the database remain stuck in "Active" status indefinitely because the worker that should have updated them to "Cancelled" was stopped while the detached job kept running.
- Trigger: Worker cancellation.
- User interaction required: No.
- External app trigger possible: No.
- Affected scope: System resources and app database consistency.
- Severity judgment: Medium. Affects reliability and resource usage rather than immediate data loss.

## 6. Recommended fix

- Minimal safe fix: Use the worker's `CoroutineScope` (e.g., wrap the work in `coroutineScope { ... }` or use `this` inside `doWork`). Override `onStopped()` to terminate all active `yt-dlp` processes using `YoutubeDLCompat.destroyProcessById()`.
- Better long-term fix: Use structured concurrency where `doWork` awaits the completion of its children. Ensure that database status updates are guarded by `isActive` checks.
- Avoid: Launching critical, long-running background tasks in detached scopes without a tracking and cleanup mechanism.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A
- Instrumented/device test: Yes.
- Manual test:
    1. Start a long download.
    2. Cancel the task via the "Queue" UI or stop the app.
    3. Check `adb shell ps` for any remaining `python` or `yt-dlp` processes.
- Expected before fix: Native process continues running.
- Expected after fix: Native process is terminated.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `DownloadWorker.kt:243` using a detached scope.
- Weakest/uncertain part: N/A.
- Recommended action: Switch to structured concurrency and implement `onStopped`.
- Files Codex should inspect: `DownloadWorker.kt`, `YoutubeDLCompat.kt`.
- Question Codex should answer: How can we reliably track all child process IDs to ensure `onStopped` kills them all?

# Verification Note: M2. Foreground-service setup failures are swallowed before long-running work

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: Yes

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/work/WorkManagerExtensions.kt`
- Function: `setForegroundSafely()`
- Evidence: Catches `IllegalStateException` and `InvalidForegroundServiceTypeException` but only logs them, returning nothing to the caller.

- File: `app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt`
- Function: `doWork()`
- Evidence: Line 93 calls `setForegroundSafely()` and immediately proceeds to queue downloads without checking if foreground status was actually established.

## 2.5 Preconditions and runtime assumptions

- User action required: N/A
- App permission/access required: N/A
- Android/API behavior required: Android 12+ or 14+ foreground service launch restrictions.
- Background worker/scheduler trigger required: Worker started from background.
- Provider/FileSystem behavior required: N/A
- External app/action required: N/A
- Network/native tool behavior required: N/A
- Unverified assumptions: N/A

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Failures are swallowed | Yes | `WorkManagerExtensions.kt:33-37` | Exceptions caught and only logged. |
| Worker proceeds regardless | Yes | `DownloadWorker.kt:93` | No success check or branch. |

## 4. Corrected interpretation

The extension function `setForegroundSafely` swallows critical exceptions that indicate the worker has failed to enter the foreground service state. On modern Android versions (API 31+), if a foreground service cannot be started from the background, the worker is not allowed to run as a long-running task. `DownloadWorker` ignores this failure and continues to launch network-heavy processes that will likely be killed by the system shortly after.

## 5. Risk assessment

- Actual impact: Download failure in background. If foreground setup fails, the OS will treat the worker as a standard background job, which is subject to much stricter execution limits and more aggressive killing.
- Trigger: Background worker start on newer Android versions.
- User interaction required: No.
- External app trigger possible: No.
- Affected scope: Reliability of background downloads.
- Severity judgment: Medium. Affects reliability in specific background scenarios.

## 6. Recommended fix

- Minimal safe fix: Modify `setForegroundSafely` to return a `Boolean`. In `DownloadWorker`, if foreground setup fails, return `Result.retry()` or `Result.failure()` to avoid running heavy work in a restricted state.
- Better long-term fix: Use `setForegroundAsync()` and handle the resulting `ListenableFuture`. Ensure all workers calling for foreground state handle the possibility of being denied.
- Avoid: Silent failure logs for operations that determine whether it is safe to proceed with heavy work.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A
- Instrumented/device test: Yes.
- Manual test:
    1. On Android 12+, trigger a download while the app is in the background and not in any exemption state.
    2. Observe if the foreground notification appears.
    3. Check logcat for "Not allowed to set foreground job".
- Expected before fix: Worker logs error and tries to download, potentially getting killed.
- Expected after fix: Worker retries or stops gracefully if foreground is denied.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: Catch blocks in `WorkManagerExtensions.kt`.
- Weakest/uncertain part: N/A.
- Recommended action: Make `setForegroundSafely` return a result and check it.
- Files Codex should inspect: `WorkManagerExtensions.kt`, `DownloadWorker.kt`.
- Question Codex should answer: Should we retry or fail if foreground is denied?

# Verification Note: M3. Terminal downloads can report success before final file move finishes

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/work/TerminalDownloadWorker.kt`
- Function: `doWork()`
- Evidence: Line 110 launches a detached `CoroutineScope(Dispatchers.IO).launch` to call `FileUtil.moveFile`.
- Evidence: Line 129 returns `Result.success()` immediately after the `launch`, before the move is complete.

## 2.5 Preconditions and runtime assumptions

- User action required: N/A
- App permission/access required: N/A
- Android/API behavior required: N/A
- Background worker/scheduler trigger required: N/A
- Provider/FileSystem behavior required: Move operation takes non-zero time.
- External app/action required: N/A
- Network/native tool behavior required: N/A
- Unverified assumptions: N/A

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Reports success early | Yes | `TerminalDownloadWorker.kt:129` | Follows detached launch. |
| Move is detached | Yes | `TerminalDownloadWorker.kt:110` | Uses standalone scope. |
| Exceptions only print/toast | Yes | `TerminalDownloadWorker.kt:123` | Caught inside the detached scope. |

## 4. Corrected interpretation

In `TerminalDownloadWorker`, the final step of moving downloaded files from the internal cache to the user's selected destination is performed in a detached coroutine. The worker returns `Result.success()` to WorkManager immediately after starting this move. If the move fails or the app is killed during the move, WorkManager considers the task successfully completed, and the user receives no error notification, while the files may be partially moved or remain in cache.

## 5. Risk assessment

- Actual impact: Missing or partial data for terminal downloads. The UI will show completion while the file system is still being modified. No retry mechanism if the move fails.
- Trigger: Terminal download with cache enabled (default).
- User interaction required: No.
- External app trigger possible: No.
- Affected scope: Integrity of terminal download outputs.
- Severity judgment: Medium. Leads to silent failures of the final output stage.

## 6. Recommended fix

- Minimal safe fix: Remove the detached `launch` and `suspend` the worker until `FileUtil.moveFile` completes. Return `Result.failure()` if it throws.
- Better long-term fix: Ensure all critical worker steps are part of the worker's structured scope to ensure they are tracked and completed before reporting success.
- Avoid: Using detached scopes for the final, critical side-effects of a worker.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A
- Instrumented/device test: No.
- Manual test:
    1. Run a terminal command that downloads a large file.
    2. Watch the "Terminal" UI/Notification.
    3. As soon as it says "success", immediately check the destination folder.
- Expected before fix: Notification disappears while the file is still being moved (or move might fail silently).
- Expected after fix: Notification stays until the file is fully moved.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `TerminalDownloadWorker.kt:110` and `129`.
- Weakest/uncertain part: N/A.
- Recommended action: Await the move inside `doWork`.
- Files Codex should inspect: `TerminalDownloadWorker.kt`.
- Question Codex should answer: Why was a detached scope used here?

# Verification Note: M4. Worker metadata refresh can overwrite user cancel/pause status

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt`
- Function: `doWork()`
- Evidence: Line 354 calls `resultRepo.updateDownloadItem(downloadItem)?.apply { dao.updateWithoutUpsert(this) }` after the download completes.
- Evidence: `downloadItem.status` was set to `Active` at line 249 and is not refreshed from the database before this final update.

## 2.5 Preconditions and runtime assumptions

- User action required: User pauses or cancels a download while it is nearing completion.
- App permission/access required: N/A
- Android/API behavior required: N/A
- Background worker/scheduler trigger required: N/A
- Provider/FileSystem behavior required: N/A
- External app/action required: N/A
- Network/native tool behavior required: N/A
- Unverified assumptions: N/A

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Second refresh overwrites status | Yes | `DownloadWorker.kt:354` | No status check before final update. |
| Status set to Active earlier | Yes | `DownloadWorker.kt:249` | Item object held in memory. |

## 4. Corrected interpretation

`DownloadWorker` performs a post-download metadata update using an in-memory `DownloadItem` object whose `status` field was set to `Active` at the start of the process. If a user manually pauses or cancels the download while `yt-dlp` is running, the database status changes. However, the worker's final update (intended only to refresh title/duration/thumbnail) overwrites the entire row, reverting the status to `Active` and effectively ignoring the user's intervention.

## 5. Risk assessment

- Actual impact: Race condition where manual cancel/pause is reverted. This leads to inconsistent UI states and downloads that may unexpectedly resume or remain "active" when they should be stopped.
- Trigger: User action during download finalization.
- User interaction required: Yes.
- External app trigger possible: No.
- Affected scope: Download queue state consistency.
- Severity judgment: Medium. Affects core UI state and state machine correctness.

## 6. Recommended fix

- Minimal safe fix: In the final metadata update, use a targeted DAO query that only updates specific metadata columns (title, duration, etc.) without touching the `status` column. Alternatively, re-verify `checkStatus(id) == Active` immediately before the update.
- Better long-term fix: Use partial updates in Room DAOs for metadata refreshes to avoid status regressions.
- Avoid: Full-row updates on entities whose status can be changed by concurrent UI actions.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A
- Instrumented/device test: No.
- Manual test:
    1. Start a download.
    2. When it is almost finished (e.g. 99%), quickly click "Cancel" or "Pause".
    3. Observe the queue state once the worker finishes.
- Expected before fix: Download might stay in "Active" state or finish despite being paused.
- Expected after fix: Status remains "Cancelled" or "Paused".

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: Lack of status check at `DownloadWorker.kt:354`.
- Weakest/uncertain part: N/A.
- Recommended action: Use a partial update for metadata.
- Files Codex should inspect: `DownloadWorker.kt`, `DownloadDao.kt`, `ResultRepository.kt`.
- Question Codex should answer: Should `updateDownloadItem` be status-agnostic?

# Verification Note: M5. Scheduler alarm cancellation uses the wrong PendingIntent type

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: No
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt`
- Function: `schedule()`
- Evidence: Uses `PendingIntent.getBroadcast(...)` to register alarms for `ScheduleAlarmReceiver`.

- File: `app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt`
- Function: `cancel()`
- Evidence: Line 81 uses `PendingIntent.getService(...)` to attempt to find and cancel those same alarms.

## 2.5 Preconditions and runtime assumptions

- User action required: User disables scheduling or changes schedule times.
- App permission/access required: N/A
- Android/API behavior required: `PendingIntent` matching rules require same operation type (Broadcast vs Service).
- Background worker/scheduler trigger required: N/A
- Provider/FileSystem behavior required: N/A
- External app/action required: N/A
- Network/native tool behavior required: N/A
- Unverified assumptions: N/A

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Created as Broadcast | Yes | `AlarmScheduler.kt:47`, `72` | `getBroadcast` used. |
| Cancelled as Service | Yes | `AlarmScheduler.kt:81`, `85` | `getService` used. |
| Cancellation fails | Yes | Identity rules | Mismatching PI type leads to no-match. |

## 4. Corrected interpretation

In `AlarmScheduler`, the `cancel()` function fails to actually cancel the scheduled alarms because it looks them up using `PendingIntent.getService()`, whereas they were originally created using `PendingIntent.getBroadcast()`. Under Android's `PendingIntent` identity rules, these are considered different objects. As a result, the old alarms remain active in the system's alarm manager.

## 5. Risk assessment

- Actual impact: Stale alarms. If a user changes their scheduled download time or disables the feature, the original alarms will still fire at the old times, potentially starting downloads unexpectedly.
- Trigger: Disabling or modifying the schedule.
- User interaction required: Yes (to trigger the change).
- External app trigger possible: No.
- Affected scope: Download scheduler accuracy.
- Severity judgment: Medium. Affects the correctness of the scheduling feature.

## 6. Recommended fix

- Minimal safe fix: In `AlarmScheduler.cancel()`, change the `PendingIntent` retrieval calls to use `PendingIntent.getBroadcast()` instead of `getService()`. Ensure all arguments (context, request code, intent, and flags) match those used in `schedule()`.
- Better long-term fix: Use a shared private helper function to generate the `PendingIntent` for both scheduling and cancellation to ensure they never go out of sync.
- Avoid: Using different `PendingIntent` types for the same logical alarm.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A
- Instrumented/device test: No.
- Manual test:
    1. Set a schedule time.
    2. Check `adb shell dumpsys alarm` to confirm it exists.
    3. Disable the schedule.
    4. Check `dumpsys alarm` again.
- Expected before fix: Alarm remains in the system list.
- Expected after fix: Alarm is removed.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `AlarmScheduler.kt:81`.
- Weakest/uncertain part: N/A.
- Recommended action: Sync `PendingIntent` factory methods.
- Files Codex should inspect: `AlarmScheduler.kt`.
- Question Codex should answer: N/A.

# Verification Note: M6. Exported ShareActivity trusts caller-controlled extras

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/AndroidManifest.xml`
- Entry: `com.ireum.ytdl.receiver.ShareActivity`
- Evidence: `android:exported="true"` with `ACTION_SEND` and `ACTION_VIEW` filters. No permission requirement or caller validation.

- File: `app/src/main/java/com/ireum/ytdl/receiver/ShareActivity.kt`
- Function: `handleIntents(intent: Intent)`
- Evidence: Lines 145-148 read `TYPE` and `BACKGROUND` extras from the intent. `DownloadType.valueOf` is called on the untrusted `type` string (line 159). `background` extra (line 148) is used to decide whether to skip the confirmation UI (line 160).

## 2.5 Preconditions and runtime assumptions

- User action required: No (if another app launches the activity).
- App permission/access required: N/A.
- Android/API behavior required: Standard Intent handling for exported activities.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: A malicious app calling `startActivity` with crafted extras.
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| ShareActivity is exported | Yes | `AndroidManifest.xml` | `exported="true"`. |
| Trusts external TYPE extra | Yes | `ShareActivity.kt:145` | Calls `DownloadType.valueOf` on external string. |
| Trusts external BACKGROUND extra | Yes | `ShareActivity.kt:148` | Bypasses UI if `BACKGROUND=true`. |
| Invalid TYPE can cause crash | Yes | `ShareActivity.kt:159` | `valueOf` throws on unknown strings. |

## 4. Corrected interpretation

`ShareActivity` is an exported component designed to handle shared text/URLs. It reads several `Intent` extras (`TYPE`, `BACKGROUND`) directly from the caller. 
1. If `TYPE` is provided and does not match a valid `DownloadType` enum name (e.g. "video", "audio", "command", "auto"), the app will crash with an `IllegalArgumentException` in the `valueOf` call.
2. If `BACKGROUND` is set to `true`, the app will skip showing the "download card" UI and immediately queue a download for the extracted URL.

## 5. Risk assessment

- Actual impact: Denial of Service (crash) or unsolicited background network/storage work (automatic queueing).
- Trigger: External intent from any app on the device.
- User interaction required: No.
- External app trigger possible: Yes.
- Affected scope: App availability and background task queue.
- Severity judgment: Medium. While it allows unsolicited work, the core risk is a crash or unintended download rather than direct data theft or loss.

## 6. Recommended fix

- Minimal safe fix: Wrap `DownloadType.valueOf()` in a `runCatching` with a safe default. Ignore the `BACKGROUND` extra if the intent is not from a trusted internal source (e.g. check caller package).
- Better long-term fix: Set `android:exported="false"`. If external sharing is needed, use activity aliases or internal-only flags to distinguish trusted from untrusted launches.
- Avoid: Trusting any extra that changes app state/behavior from an exported activity without verification.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: N/A.
- Manual test:
    1. Use `adb shell am start -n com.ireum.ytdl/.receiver.ShareActivity -a android.intent.action.SEND --es android.intent.extra.TEXT "https://example.com" --es TYPE "INVALID"` -> Observe crash.
    2. Use `adb shell am start -n com.ireum.ytdl/.receiver.ShareActivity -a android.intent.action.SEND --es android.intent.extra.TEXT "https://example.com" --ez BACKGROUND true` -> Observe download queued without UI.
- Expected before fix: Crash in test 1, automatic queueing in test 2.
- Expected after fix: No crash (fallback to default type) in test 1, UI shown in test 2.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `ShareActivity.kt` lines 145-148 directly using untrusted extras for control flow and crashing on invalid input.
- Weakest/uncertain part: None.
- Recommended action: Validate `TYPE` and ignore `BACKGROUND` on public intents.
- Files Codex should inspect: `ShareActivity.kt`, `AndroidManifest.xml`.
- Question Codex should answer: How can we safely distinguish internal launches (e.g. via alias) from external ones to allow background queueing only for the user?

# Verification Note: M7. Exported ResumeActivity can requeue arbitrary download IDs

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/AndroidManifest.xml`
- Entry: `com.ireum.ytdl.receiver.ResumeActivity`
- Evidence: `android:exported="true"` with action `ytdlnisx.ResumeActivity`. No caller verification.

- File: `app/src/main/java/com/ireum/ytdl/receiver/ResumeActivity.kt`
- Function: `handleIntents(intent: Intent)`
- Evidence: Line 53 reads `itemID` extra. Line 63 calls `downloadViewModel.reQueueDownloadItems(listOf(id.toLong()))` without verifying the source of the intent.

## 2.5 Preconditions and runtime assumptions

- User action required: No.
- App permission/access required: N/A.
- Android/API behavior required: N/A.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: Malicious app calling the exported activity.
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| ResumeActivity is exported | Yes | `AndroidManifest.xml` | `exported="true"`. |
| Reads itemID extra | Yes | `ResumeActivity.kt:53` | `getIntExtra("itemID", 0)`. |
| Requeues arbitrary IDs | Yes | `ResumeActivity.kt:63` | Calls `reQueueDownloadItems` directly. |

## 4. Corrected interpretation

`ResumeActivity` is an exported component designed to be launched from notifications to resume a failed or paused download. However, because it is exported and its custom action is public, any app on the device can launch it with a guessed `itemID`. This triggers a database operation to move the specified download back into the active queue and starts a worker.

## 5. Risk assessment

- Actual impact: Unsolicited background work, potential battery/data drain by forcing downloads to restart.
- Trigger: External intent.
- User interaction required: No.
- External app trigger possible: Yes.
- Affected scope: Download queue state.
- Severity judgment: Medium. It allows altering internal app state (the queue), but doesn't lead to direct data theft.

## 6. Recommended fix

- Minimal safe fix: Set `android:exported="false"` in `AndroidManifest.xml`. Notifications can still launch non-exported activities within the same app.
- Better long-term fix: Use a randomly generated token extra in the `PendingIntent` and validate it in `ResumeActivity` to ensure it came from the app's own notification.
- Avoid: Exporting internal control activities.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: N/A.
- Manual test:
    1. Identify an existing download ID (e.g., from app logs or database).
    2. Use `adb shell am start -a ytdlnisx.ResumeActivity --ei itemID <ID>` -> Observe download requeueing.
- Expected before fix: Download is requeued.
- Expected after fix: Intent is rejected by the OS (exported=false) or ignored by the app.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `ResumeActivity.kt:63` calling `reQueueDownloadItems` based on untrusted `itemID`.
- Weakest/uncertain part: None.
- Recommended action: Set `exported="false"`.
- Files Codex should inspect: `ResumeActivity.kt`, `AndroidManifest.xml`.
- Question Codex should answer: Why was this activity exported in the first place? (Likely legacy or misunderstanding of notification requirements).

# Verification Note: M8. Exported WebView activities trust external extras

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/AndroidManifest.xml`
- Entries: `WebViewActivity`, `PoTokenWebViewLoginActivity`
- Evidence: Both have `android:exported="true"` and custom actions (`ytdlnisx.WebViewActivity`, `ytdlnisx.PoTokenWebViewLoginActivity`).

- File: `app/src/main/java/com/ireum/ytdl/ui/more/cookies/WebViewActivity.kt`
- Function: `onCreate`
- Evidence: Line 54 reads `url` from extras. Lines 133-134 clear all cookies if `savedInstanceState == null`.

- File: `app/src/main/java/com/ireum/ytdl/ui/more/settings/advanced/generateyoutubepotokens/webview/PoTokenWebViewLoginActivity.kt`
- Function: `onCreate`
- Evidence: Line 57 reads `url`. Lines 151-152 clear cookies if `no_auth` extra is true and it's the first launch.

## 2.5 Preconditions and runtime assumptions

- User action required: No.
- App permission/access required: N/A.
- Android/API behavior required: N/A.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: Malicious app launching the activities.
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| WebView activities are exported | Yes | `AndroidManifest.xml` | Both confirmed. |
| Trust external URL extras | Yes | Both Kotlin files | Directly use `intent.getStringExtra`. |
| Can clear cookies via extra | Yes | Both Kotlin files | Confirmed for both (`WebViewActivity` does it unconditionally on first launch). |
| Can load arbitrary URLs | Yes | Both Kotlin files | Confirmed. |

## 4. Corrected interpretation

Two activities containing WebViews are exported to the system. 
1. `WebViewActivity`: Clears **all** app cookies every time it is launched for the first time (not a configuration change). It then loads any URL provided in the `url` extra.
2. `PoTokenWebViewLoginActivity`: Clears **all** app cookies if the `no_auth` extra is true and it's the first launch. It also loads any URL provided in the `url` extra.

Both allow an external app to disrupt the user's session/authentication state (effectively logging them out of all sites in the app's context) and use the app as a browser for arbitrary (and potentially malicious) sites.

## 5. Risk assessment

- Actual impact: Session disruption (logout from all sites in the WebView), phishing risk (loading malicious URLs in a trusted app context).
- Trigger: External intent.
- User interaction required: No.
- External app trigger possible: Yes.
- Affected scope: Global WebView cookies and displayed content.
- Severity judgment: Medium. It affects privacy/session state but doesn't allow direct local file access or remote code execution outside the WebView.

## 6. Recommended fix

- Minimal safe fix: Set `android:exported="false"` for both activities.
- Better long-term fix: If external launch is needed, validate the action and the URL scheme/host against a strict allowlist (e.g. `youtube.com`).
- Avoid: Clearing global cookies based on untrusted intent extras.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: N/A.
- Manual test:
    1. Use `adb shell am start -a ytdlnisx.WebViewActivity --es url "https://example.com"` -> Observe session loss and example.com loading.
- Expected before fix: Example.com loads, previous cookies are gone.
- Expected after fix: OS rejects the launch (exported=false).

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `WebViewActivity.kt:133` and `PoTokenWebViewLoginActivity.kt:151` clearing cookies; both activities exported in manifest.
- Weakest/uncertain part: None.
- Recommended action: Set `exported="false"`.
- Files Codex should inspect: `WebViewActivity.kt`, `PoTokenWebViewLoginActivity.kt`, `AndroidManifest.xml`.
- Question Codex should answer: Is there any reason these should be reachable by other apps? (Likely not).

# Verification Note: M10. Shared batch URL stream is read fully without size cap

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/MainActivity.kt`
- Function: `handleIntents(intent: Intent)`
- Evidence: Lines 343-345 read from an `InputStream` into an unbounded `StringBuilder` in a tight loop on the UI thread. No size checks are performed on the content of the URI being read.

## 2.5 Preconditions and runtime assumptions

- User action required: Yes (user must share a file to the app).
- App permission/access required: N/A.
- Android/API behavior required: N/A.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: Another app sharing a large text file.
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Launcher aliases accept SEND application/txt | Yes | `AndroidManifest.xml` | Multiple aliases confirmed. |
| Stream is read fully | Yes | `MainActivity.kt:343` | `while` loop continues until EOF. |
| No size cap | Yes | `MainActivity.kt` | No limit on loop count or builder capacity. |
| Can cause ANR/OOM | Yes | `MainActivity.kt:344` | Builder growth and main-thread blocking loop. |

## 4. Corrected interpretation

The `MainActivity` handles `ACTION_SEND` intents with a URI stream (typically from sharing a `.txt` file). It opens the stream and reads every character into a `StringBuilder` in memory without any size limits or line-by-line processing. Crucially, this read happens on the UI thread. If a very large file is shared, the app will consume excessive memory, potentially leading to an `OutOfMemoryError` or an Application Not Responding (ANR) while the UI thread is blocked during the read.

## 5. Risk assessment

- Actual impact: App crash (OOM) or temporary freeze (ANR).
- Trigger: User sharing a large file to the app.
- User interaction required: Yes.
- External app trigger possible: Yes (any app can initiate a share).
- Affected scope: App process availability.
- Severity judgment: Medium. It's a reliability issue that can be triggered externally, though it requires a user action (selecting this app in the share sheet).

## 6. Recommended fix

- Minimal safe fix: Add a character or byte limit to the read loop. Stop reading and show an error if the limit is exceeded.
- Better long-term fix: Process the stream line-by-line using a sequence or iterator, extracting URLs as they are found rather than buffering the entire file. Move the reading to a background thread (`Dispatchers.IO`).
- Avoid: Unbounded memory allocation from external streams on the main thread.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: N/A.
- Manual test:
    1. Create a large text file (e.g., 50MB).
    2. Share it to the app via a file manager.
- Expected before fix: App hangs or crashes.
- Expected after fix: App rejects the file gracefully or processes it efficiently in the background.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: Unbounded `while` loop in `MainActivity.kt` lines 343-345 running on the UI thread.
- Weakest/uncertain part: The exact file size required to trigger OOM depends on device RAM.
- Recommended action: Limit read size and move to background thread.
- Files Codex should inspect: `MainActivity.kt`.
- Question Codex should answer: How can we efficiently parse URLs from a stream without loading the whole file into memory?

# Verification Note: H3. yt-dlp templates and extra commands execute with insufficient option policy

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: High
- Fix priority: P0
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompat.kt`
- Entry: `stripExternalFfmpegLocationOptions(commandString: String)`
- Evidence: `BLOCKED_CONFIG_STRING_OPTIONS` = `CONFIG_OPTIONS + FFMPEG_LOCATION_OPTION` (lines 28, 30). This only includes `--config`, `--config-location`, `--config-locations`, and `--ffmpeg-location`. Other critical yt-dlp options are not blocked.

- File: `app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YTDLPUtil.kt`
- Function: `addConfig(commandString: String)`
- Evidence: Writes user command text to a temporary config file (lines 1017-1019) after stripping only the few options listed in `YoutubeDLCompat`.

- File: `app/src/main/java/com/ireum/ytdl/work/TerminalDownloadWorker.kt`
- Function: `doWork()`
- Evidence: User commands from the "Terminal" mode are passed through the same weak sanitizer before execution (lines 53-56).

## 2.5 Preconditions and runtime assumptions

- User action required: Yes (pasting/importing a command).
- App permission/access required: N/A (runs with app UID).
- Android/API behavior required: N/A.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: N/A.
- Network/native tool behavior required: `yt-dlp` support for target options.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| Template/extra commands are written to executable configs | Yes | `YTDLPUtil.kt:1017` | Written to a .txt config file. |
| Sanitizer only blocks --config* and --ffmpeg-location | Yes | `YoutubeDLCompat.kt:30` | Confirmed by source. |
| Sensitive options like --exec remain allowed | Yes | `YoutubeDLCompat.kt` | Not in the blocked set. |
| Malicious commands run with app UID | Yes | Standard process behavior | Native tools run under the app context. |

## 4. Corrected interpretation

The app allows users to provide raw `yt-dlp` option strings via "Command templates," "Extra commands," or the "Terminal" mode. These strings are written to temporary configuration files and passed to the native `yt-dlp` executable. The sanitization logic is extremely narrow, only blocking `--config*` and `--ffmpeg-location`. This leaves highly sensitive options such as `--exec` (arbitrary command execution), `--downloader` (overriding download binaries), or `--cookies` (credential theft via user-writable paths) completely unrestricted.

## 5. Risk assessment

- Actual impact: Security bypass. A user copy-pasting a "helpful" template from an untrusted source can inadvertently grant arbitrary shell command execution permissions to that command under the app's own UID and permission set.
- Trigger: Execution of any download using a malicious command string.
- User interaction required: Yes (to paste/import the string).
- External app trigger possible: No.
- Affected scope: Full app-private data and any shared storage/permissions granted to the app.
- Severity judgment: High. RCE (Remote Code Execution) equivalent via copy-paste is a critical vulnerability.

## 6. Recommended fix

- Minimal safe fix: Significantly expand the `BLOCKED_CONFIG_STRING_OPTIONS` in `YoutubeDLCompat.kt` to include `--exec`, `--downloader`, `--external-downloader`, `--alias`, and options that allow writing to arbitrary paths.
- Better long-term fix: Use an allowlist-based parser for non-terminal user input. For Terminal mode, display a clear warning about execution risks and consider requiring a developer-mode toggle.
- Avoid: Relying on blacklist-based sanitization for security boundaries.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: No.
- Manual test:
    1. Create a Command Template with the content: `--exec "echo 'exploited' > /sdcard/Download/exploited.txt"`.
    2. Run any download using this template.
    3. Check if the file `exploited.txt` was created in shared storage.
- Expected before fix: File is created (command executed).
- Expected after fix: The `--exec` option is stripped or execution is blocked.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `YoutubeDLCompat.kt:30` blocked options list.
- Weakest/uncertain part: N/A.
- Recommended action: Expand the blacklist or implement an allowlist.
- Files Codex should inspect: `YoutubeDLCompat.kt`, `YTDLPUtil.kt`.
- Question Codex should answer: Which other `yt-dlp` options should be considered sensitive for redaction?

# Verification Note: M14. Failed downloads can persist logs when logging/incognito says not to

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt`
- Function: `doWork()`
- Evidence: Line 661 explicitly calls `logRepo.insert(logItem)` in the `else` branch of the `if (logDownloads)` check within the main failure handler. `logDownloads` is false if the item is incognito or the global log setting is disabled (line 274).

## 2.5 Preconditions and runtime assumptions

- User action required: N/A.
- App permission/access required: N/A.
- Android/API behavior required: N/A.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: N/A.
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| logDownloads is false when disabled or incognito | Yes | `DownloadWorker.kt:274` | Confirmed. |
| Catch block inserts logItem regardless | Yes | `DownloadWorker.kt:661` | Confirmed in the `else` branch. |
| Incognito URL/Title persists in DB on error | Yes | `DownloadWorker.kt:661` | Logs contain title/URL. |

## 4. Corrected interpretation

The `DownloadWorker` has a logic flaw in its error handling block. While it correctly respects the `logDownloads` flag during the active download phase, it unconditionally inserts a `LogItem` into the persistent database if the download fails and `logDownloads` was false. This means failed incognito downloads or downloads performed when logging is globally disabled will still leave a trace (including video title and URL) in the app's local database.

## 5. Risk assessment

- Actual impact: Privacy leak. Persistent logs of private user activity are created despite a user's request for incognito mode or disabled logging.
- Trigger: Any download failure.
- User interaction required: No.
- External app trigger possible: No.
- Affected scope: App-private database (log table).
- Severity judgment: Medium. Violates clear privacy settings, though the data remains on-device.

## 6. Recommended fix

- Minimal safe fix: Remove the `else` branch at line 660 in `DownloadWorker.kt`. Only perform database log operations if `logDownloads` is true.
- Better long-term fix: Consolidate logging through a single repository function that enforces privacy checks internally before any insert/update.
- Avoid: Overwriting privacy flags in catch blocks "for debugging."
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: No.
- Manual test:
    1. Turn on Incognito mode.
    2. Start a download for a URL that will fail (e.g., an invalid link).
    3. After failure, go to **Settings > Download logs**.
- Expected before fix: A log entry for the failed incognito download is present.
- Expected after fix: No log entry is present.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `DownloadWorker.kt:661`.
- Weakest/uncertain part: N/A.
- Recommended action: Remove the unconditional log insert on failure.
- Files Codex should inspect: `DownloadWorker.kt`.
- Question Codex should answer: Should error details be stored anywhere for incognito items? (Likely not).

# Verification Note: M15. Sensitive request data is logged or exposed

## 1. Verdict

- Verdict: Confirmed
- Re-rated severity: Medium
- Fix priority: P1
- Codex review needed: Yes
- Runtime/device test needed: No

## 2. Code evidence

- File: `app/src/main/java/com/ireum/ytdl/util/extractors/NetworkUtil.kt`
- Function: `genericRequest(url: String)`
- Evidence: Line 14: `Log.e(NetworkUtil.toString(), url)`. Every API request (which can include search terms or API keys) is logged to logcat at `ERROR` level.

- File: `app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YTDLPUtil.kt`
- Function: `parseYTDLRequestString(request : YoutubeDLRequest)`
- Evidence: Iteratively expands all configuration files and joins them into a single raw command string (lines 452-466), which often contains credentials or cookie paths. This string is then persisted in DB logs.

- File: `app/src/main/java/com/ireum/ytdl/util/NotificationUtil.kt`
- Function: `createDownloadErrored(...)`
- Evidence: Line 506 sets `VISIBILITY_PUBLIC` for error notifications. This causes video titles and error messages (containing URLs) to be fully visible on the lock screen.

## 2.5 Preconditions and runtime assumptions

- User action required: N/A.
- App permission/access required: N/A.
- Android/API behavior required: N/A.
- Background worker/scheduler trigger required: N/A.
- Provider/FileSystem behavior required: N/A.
- External app/action required: N/A.
- Network/native tool behavior required: N/A.
- Unverified assumptions: N/A.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|
| genericRequest logs full URLs to logcat | Yes | `NetworkUtil.kt:14` | Logged at ERROR level. |
| parseYTDLRequestString expands configs into logs | Yes | `YTDLPUtil.kt:466` | Joins all options including expanded files. |
| Error notifications are public on lock screen | Yes | `NotificationUtil.kt:506` | Uses `VISIBILITY_PUBLIC`. |

## 4. Corrected interpretation

The app exposes sensitive user data in three distinct ways: 
1. Logcat: All API request URLs (containing search queries, API keys, or tokens) are logged to system logs. 
2. Persistent Logs: Full native commands are expanded (including proxy credentials or cookie paths) and stored in the app's log database. 
3. UI/Lock screen: Error notifications use public visibility, showing potentially private video titles and URLs to any observer without device unlock.

## 5. Risk assessment

- Actual impact: Privacy leak and credential exposure. Search history is leaked via logcat. Authentication tokens or proxy credentials can be leaked via command expansion in logs. Private video titles are visible on the lock screen.
- Trigger: Normal app use (searching, downloading, error events).
- User interaction required: No.
- External app trigger possible: No (but other apps can read public logs on older Android versions).
- Affected scope: logcat, app-private database, lock screen.
- Severity judgment: Medium. Affects privacy and credential security across multiple layers.

## 6. Recommended fix

- Minimal safe fix: Remove URL logging in `NetworkUtil`. Redact sensitive patterns (like `api_key=` or `proxy=`) in `parseYTDLRequestString`. Change notification visibility to `VISIBILITY_PRIVATE`.
- Better long-term fix: Implement a centralized logging decorator that handles redaction and an incognito-aware notification builder.
- Avoid: Logging raw query strings or expanded command files to persistent or public streams.
- Audit fix assessment: Sufficient.

## 7. Verification test

- Unit test: N/A.
- Instrumented/device test: No.
- Manual test:
    1. Enter a search query. Check `logcat -d | grep genericRequest`.
    2. Cause a download to fail. Observe the notification on the lock screen.
    3. Check the command string in **Settings > Download logs**.
- Expected before fix: URL and query are in logcat; title is on lock screen; full expanded command is in log.
- Expected after fix: Data is redacted or visibility restricted.

## 8. Codex handoff summary

- Final verdict: Confirmed.
- Strongest evidence: `NetworkUtil.kt:14`, `NotificationUtil.kt:506`.
- Weakest/uncertain part: N/A.
- Recommended action: Redact sensitive keys and restrict notification visibility.
- Files Codex should inspect: `NetworkUtil.kt`, `NotificationUtil.kt`, `YTDLPUtil.kt`.
- Question Codex should answer: Which other native options should be considered sensitive for redaction?

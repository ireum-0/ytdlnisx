# Independent Track A checkpoint — Processing stale full-row writer follow-up

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Existing `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` — add D5 stale Processing UI writer

No new blocker root is counted. This checkpoint adds another production consumer of the same stale Download snapshot mutation authority already represented by `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`.

### Production producer

`DownloadMultipleBottomSheetDialog` obtains current Processing `DownloadItem` objects and several configuration callbacks mutate those in-memory objects, then launch independent asynchronous IO coroutines that call `downloadViewModel.updateDownload(item)`.

Examples include audio/video preference changes, filename template, sponsorblock filters, subtitle options, compatibility/container changes, and extra commands. Several callbacks use standalone `CoroutineScope(Dispatchers.IO)` rather than synchronously completing the write before the user can continue with the sheet.

The same dialog's Download/Schedule actions independently call `queueProcessingDownloads()` / scheduled queue publication.

### Queue transition is snapshot-scoped, later generic update is not

`DownloadViewModel.queueDownloads()` captures source snapshots for existing rows and uses `DownloadDao.updateForQueueIfSnapshot(...)` under the Download execution lease/claim lock to transition the exact Processing snapshot into Queued/Scheduled state.

That queue transition is correctly snapshot-fenced.

`DownloadViewModel.updateDownload(item)`, however, routes a normal non-cancelled, non-history-refusal item to `repository.update(item)`.

`DownloadRepository.update()` delegates an existing row to `DownloadDao.update(item)`.

`DownloadDao.update(item)` re-reads the current row only to preserve History-refusal / terminal-convergence debt and then performs a full-row `updateRaw(item)` upsert. It does not require current status, executionId, operationId, retryAttempt, or another UI-generation witness to equal the stale Processing snapshot.

### Concrete race

1. Processing row P is displayed in the multi-download configuration sheet.
2. User changes an option; callback mutates the in-memory P snapshot and launches async `updateDownload(P)`.
3. Before that write reaches Room, user presses Download or Schedule.
4. `queueDownloads()` successfully CAS-transitions the current P row to Queued/Scheduled and may start a worker.
5. The older configuration coroutine now reaches `updateDownload(P)` with `status = Processing` and its old execution/state fields.
6. generic DAO update accepts that stale full-row snapshot and can overwrite the newer Queued/Scheduled row back to Processing.
7. If a DownloadWorker has already claimed the row, the same stale full-row update can also erase/replace the newer execution-owned state, causing ownership loss and failed/abandoned progress.

This is not a claim that every UI click reproduces the race; the source establishes a reachable asynchronous ordering with no final CAS fence on the generic writer.

### Classification

Treat this as D5 under the existing stale Download snapshot authority root, not a new P2:

`BUG-DOWNLOAD-DELETE-SNAPSHOT-01`

The root should be understood more generally as: a previously observed Download snapshot/set must not retain mutation/destructive authority after a newer state/generation has won.

Previously confirmed D1-D4 remain unchanged.

### Acceptance direction

- UI/configuration writes for existing Download rows use column-limited updates or a snapshot/revision CAS.
- A Processing edit must prove the row is still the exact Processing generation it edited before writing.
- Once Queued/Scheduled/Active or another newer semantic state wins, a stale Processing UI write becomes a no-op/refusal rather than a full-row rollback.
- Do not erase a newer executionId or queue/retry metadata through generic UI updates.
- Multi-item callbacks should converge through one repository mutation protocol rather than unconstrained full-row coroutines.
- Tests should force delayed configuration writes across Processing -> Queued and Processing -> Active transitions.

## Non-findings in this pass

- `CommandTemplate` and `TemplateShortcut` do not form an ID/FK relationship: shortcuts persist independent command text, so template deletion does not create a dangling template reference on current evidence.
- Multiple preferred command-template rows are possible at the schema level, but this pass did not establish a blocker-level durable/destructive effect; do not count it without further evidence.
- Cookie records are Room-contained text state; no separate DB -> external cookie-file commit boundary was found in the inspected fixed-basis CRUD path.

## Count

No new root in this checkpoint.

Current canonical working count remains:

- `P0 3`
- `P1 3`
- `P2 24`

Overall verdict: `NOT_CLEAN`.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED

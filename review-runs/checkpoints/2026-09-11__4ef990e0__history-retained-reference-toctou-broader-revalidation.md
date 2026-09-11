# Historical BUG-HISTORY-02 — retained-reference TOCTOU broader-registry revalidation

Date: 2026-09-11

## Exact review basis

- Independently CLEAN implementation basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Governing Master Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Broader-registry hypothesis source: `review/remediation:TASKS.md`

This review was performed while a separate Luna F13 implementation wave was active. No moving implementation commit or diff newer than the exact fixed basis was inspected or relied on.

## Historical hypothesis

Historical P0 `BUG-HISTORY-02` described this sequence:

1. a History deletion candidate and the set of retained History file references are observed;
2. another production writer creates a new reference to the same physical target after that snapshot;
3. the selected row itself remains unchanged;
4. filesystem/provider deletion proceeds from the stale retained-reference snapshot;
5. a file newly referenced by another live History row is deleted.

The governing invariant is checklist core #8/#12: a retained-reference snapshot is not final destructive authority after concurrency, and every producer capable of creating/changing the protected reference must participate in the same mutation-ordering contract.

## Exact-source revalidation

### User-driven destructive deletion is serialized with reference mutation

`HistoryViewModel.executePreparedHistoryFileDeletion(...)` executes under `HistoryReferenceMutationCoordinator.withLock`.

Inside that coordinator domain it:

- re-reads selected records' current `downloadPath` values;
- revalidates the prepared selected-record snapshot;
- re-reads retained History references excluding the selected IDs;
- excludes currently retained targets;
- performs `HistoryFileDeletionEngine.execute(...)`;
- re-reads selected record snapshots after the external deletion;
- removes only rows whose expected target state remains unchanged.

The retained-reference observation and physical deletion therefore no longer have an unlocked window in which a cooperating History reference writer can publish a new protected reference.

### Observe Source destructive deletion uses the same ordering domain

The sync-driven History/media deletion path in `ObserveSourceWorker` also enters `HistoryReferenceMutationCoordinator.withLock` before it re-reads the current History row, validates the expected stored path/source state, constructs retained-reference protection, performs the physical file/provider deletion, and removes the matching History row.

Thus the historical user-delete and Observe-delete consumers share the same final destructive ordering primitive.

### Normal History reference creation uses the coordinator

`HistoryKeywordAssignmentRepository` is the production insertion/replacement boundary for ordinary History publication and holds `HistoryReferenceMutationCoordinator` around reference-changing writes, including:

- `insertHistory(...)`;
- `insertHistoryWithPrimarySuccess(...)` used by normal Download success publication;
- `restoreHistory(...)` used for History restoration/Undo-style reconstruction;
- `replaceHistoryPreservingAssignmentsAuthorized(...)` for authoritative replacement.

Consequently a newly committed normal Download, LocalAdd History row, restored History row, or authorized replacement cannot acquire a new `downloadPath` reference concurrently through the historical deletion window: it must order before or after the same coordinator-held destructive section.

### LocalAdd uses the coordinated insertion boundary

At exact `4ef990e0...`, `LocalAddWorker` creates matched local History items through `HistoryKeywordAssignmentRepository(db).insertHistory(item)`, not through a direct `HistoryDao.insertRaw()` call. Therefore its provider-backed local media reference is serialized with destructive History deletion.

### Backup restore uses the coordinated insertion boundary

`SettingsViewModel.restoreData(...)` restores each backup History row through `historyKeywordAssignments.insertHistory(...)` after any requested reset. The reset's `HistoryRepository.deleteAllRecords()` also holds `HistoryReferenceMutationCoordinator` before clearing playlist relations/history rows.

Thus backup import does not bypass the same reference ordering contract when recreating History `downloadPath` values.

### Reconnect and folder migration are coordinated

The two production reconnect paths in `HistoryFragment` wrap the current-row recheck and the actual `HistoryDao.updateReconnectedMedia(...)` / `updateReconnectedFile(...)` mutation in `HistoryReferenceMutationCoordinator.withLockBlocking`.

`HistoryViewModel`'s default-video-folder migration likewise performs the History path migration while holding `HistoryReferenceMutationCoordinator`.

### History metadata editors do not bypass path ownership

`HistoryViewModel.updateVideoPlayerMetadata(...)` and `updateWithKeywordNotice(...)` enter the coordinator before their History mutation. Their explicitly owned metadata update set does not independently publish a stale `downloadPath` reference outside that ordering domain.

### DAO raw primitives are not themselves authority

`HistoryDao` still exposes low-level primitives such as `insertRaw`, `insertAndGetIdRaw`, `updateRaw`, `updateDownloadPathById`, `updateDownloadPathIfUnchanged`, `updateReconnectedMedia`, and `updateReconnectedFile` without an internal mutex. That alone does not reproduce the historical defect: the reviewed production consumers that can create/change durable file references route those primitives through `HistoryReferenceMutationCoordinator` or a repository method that holds it.

The DAO methods therefore remain implementation sinks rather than independent production authorization surfaces for this historical root.

## Verdict

**CURRENT HISTORICAL P0 NOT REPRODUCED / DO NOT PROMOTE `BUG-HISTORY-02`.**

The concrete retained-reference TOCTOU described by the broader registry is closed at exact basis `4ef990e0...` by a shared History-reference mutation coordinator consumed on both sides of the race: final destructive deletion and the reviewed production reference-acquisition/change paths.

- Severity/count delta: `0`
- Canonical blocker count remains **P0 2 / P1 2 / P2 28**.
- CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.
- Overall project remains `NOT_CLEAN`.

## Root reconciliation

- Do not merge this historical P0 into `BUG-HISTORY-01`: that current P2 concerns atomic History/playlist Undo snapshot semantics, not retained-reference deletion ordering.
- Do not merge it into `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`: that P2 concerns stale Download selection/status snapshots deleting a newer Download-row generation.
- Do not merge it into `BUG-CACHE-01`: that newly promoted P2 concerns maintenance actions deleting/moving positive-live temp ownership, not published History reference retention.
- `BUG-OBSERVE-01` F3 remains CLOSED; authority of source membership and destructive retained-reference ordering are different contracts.

A future concrete production caller that changes a History media reference outside `HistoryReferenceMutationCoordinator` would be a regression and would invalidate this rejection, but no such reviewed current production path was established at the exact fixed basis.

INDEPENDENT EXECUTION: NOT EXECUTED
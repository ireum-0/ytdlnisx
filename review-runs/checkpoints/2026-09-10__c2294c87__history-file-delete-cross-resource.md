# Independent correctness checkpoint — History file-delete cross-resource authority

- Review basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diffs inspected: NO
- Verdict: NOT_CLEAN
- Canonical working count after this checkpoint: `P0 2 / P1 3 / P2 26`
- Independent execution: NOT EXECUTED

## NEW P2 — BUG-HISTORY-FILE-DELETE-TXN-01

### Semantic root
A user-requested History deletion that includes associated files crosses external storage and Room without a durable operation phase/recovery owner. Snapshot/liveness guards prevent deleting a newly retargeted file, but they do not make the file-delete → record-delete commit recoverable across process death.

### Exact production path
`HistoryViewModel.executePreparedHistoryFileDeletion()` runs under `HistoryReferenceMutationCoordinator.withLock` and revalidates current stored targets. It then:
1. calls `HistoryFileDeletionEngine.execute(currentValidation)`, which calls `gateway.delete(target)` for READY external-storage targets;
2. computes `removableRecordIds` from successful/absent file outcomes and a post-delete snapshot revalidation;
3. calls `repository.deleteRecordsWithinReferenceMutation(removableRecordIds.toList())` to remove History records/relations.

`HistoryDeletionRecord`/`HistoryDeletionValidation` are in-memory value objects, not a durable deletion-intent journal. `App.onCreate()` has no History file-deletion recovery reconciliation.

### Concrete fixed point
If the process dies after a file deletion succeeds but before `deleteRecordsWithinReferenceMutation()` commits, the media file is already gone while the History row remains durable and visible, still carrying the now-dead file reference. On restart there is no durable operation owner that knows this record was supposed to be finalized.

The current coordinator lock and snapshot revalidation solve concurrency/stale-target authority, not crash atomicity across external storage + Room.

### Relationship to existing findings
This is distinct from F17/P2-N, which covers History relational record deletion/Undo authority inside the DB. The new root is the external-file → durable-record two-system commit boundary and counts separately.

### Acceptance
- Persist a durable exact History deletion operation before the first irreversible external-file effect.
- Record per-target outcome/phase sufficiently to resume idempotently after process death.
- Finalize DB record/relationship deletion only from that exact operation authority.
- Startup must inventory unfinished deletion operations and converge them without requiring unrelated UI activity.
- Preserve current target-snapshot/reference-liveness checks before destructive effects.

## Count bookkeeping
Prior canonical working count: `P0 2 / P1 3 / P2 25`.
`BUG-HISTORY-FILE-DELETE-TXN-01` adds one P2 root.
Current canonical working count: `P0 2 / P1 3 / P2 26`.

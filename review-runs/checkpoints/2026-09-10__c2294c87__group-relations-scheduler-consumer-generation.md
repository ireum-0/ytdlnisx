# Independent correctness checkpoint — group relations and scheduler consumer generation

- Review basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diffs inspected: NO
- Verdict: NOT_CLEAN
- Canonical working count after this checkpoint: `P0 2 / P1 3 / P2 24`
- Independent execution: NOT EXECUTED

## NEW P2 — BUG-GROUP-TXN-01

### Semantic root
Keyword/Youtuber group deletion and relation mutation do not have one crash-atomic durable authority boundary. A single user deletion can persist only the destructive relationship prefix while leaving the group row alive.

### Exact production path
`HistoryFragment` performs keyword-group deletion as:
1. `keywordGroupDao.deleteMembersByGroup(id)`
2. `keywordGroupDao.deleteGroup(id)`

It performs Youtuber-group deletion as:
1. `youtuberGroupDao.deleteMembersByGroup(id)`
2. `youtuberGroupDao.deleteRelationsByGroup(id)`
3. `youtuberGroupDao.deleteGroup(id)`

The DAO methods are independent writes; neither DAO supplies a transaction wrapper for these logical operations. `KeywordGroupMember`, `YoutuberGroupMember`, and `YoutuberGroupRelation` have composite primary keys but no foreign keys tying them to a live group row.

### Concrete impact
Process death/failure after relationship deletion but before group deletion leaves the group durable and visible while its members and/or parent-child relationships have already been lost. This is silent durable relationship data loss from one user operation.

Keyword and Youtuber variants are one semantic root and count once.

### Acceptance
- Perform each logical group delete under one Room transaction, or use a durable recoverable mutation phase.
- Apply the same authority to relation/member replacement where a logical edit spans multiple durable writes.
- Validate parent/child/member endpoints at the mutation boundary and reject/reconcile orphan relations.
- Add process-death/partial-boundary coverage for keyword group deletion and Youtuber group deletion.

## Existing P2 — BUG-SCHEDULE-01, additional consumer-generation subcases

### S8 — END consumer generation is not revalidated
`WorkManagerHandoffRecovery` puts `handoffId`/`requestId` into `CancelScheduledDownloadWorker` input, but the worker does not validate them before its broad cancellation side effect (`cancelAllWorkByTag("download")`). A superseded old END worker can therefore cross the destructive consumer boundary after a newer schedule owns the semantic generation.

### S9 — START consumer generation is not revalidated
`WorkManagerHandoffRecovery.buildRequest()` puts the same generation identity into a scheduled `DownloadWorker`, but exact-basis `DownloadWorker` contains no `handoffId` consumer at all. It proceeds through recovery into queued/scheduled observation and `claimDownloadThroughProductionAdmission()`.

Producer-side generation checks and `ExistingWorkPolicy.REPLACE` do not close the consumer race: once an old worker is already running, schedule cancellation/replacement is asynchronous, and the worker has no schedule-generation fence before the queue claim. Thus a superseded/cancelled START boundary can claim/start eligible queued/scheduled work before cancellation wins.

S8/S9 are subcases of the existing scheduler semantic root; no additional count.

### Acceptance addition
Both START and END consumers must validate the exact durable scheduler generation immediately before their first claim/destructive boundary. Cancellation/REPLACE alone is not semantic authority.

## Count bookkeeping
Prior canonical working count: `P0 2 / P1 3 / P2 23`.
`BUG-GROUP-TXN-01` adds one P2 root.
S8/S9 remain under existing `BUG-SCHEDULE-01`.
Current canonical working count: `P0 2 / P1 3 / P2 24`.

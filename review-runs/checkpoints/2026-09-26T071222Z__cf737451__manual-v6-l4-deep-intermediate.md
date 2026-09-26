# Manual correctness review checkpoint — cf737451 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: d8f0f65e292b4d0ffe954008294ed3ddef0e8286

## Frozen basis
- implementation_sha: cf7374510decad9f308fdc3f1b528e731a3ea4f1
- plan_sha: 2145847a1054da28398b730b9be0ca728668f967
- ledger_sha: 899328bc91e4008e39a658387396a0106c8666ec
- Master Plan blob: 507a97c1455793b272298e29f37b945f4cfb55d7
- Checklist v6 blob: 7b553328dfcd9941d783658f49ecb16c71b98c56
- SOURCE_ARTIFACTS blob: bdee5f5efeee81426433c64ceb975208dc41a299
- TASKS blob: fae11a65fc7fe1e58bd725d64355ce4c242f398c
- TASKS_DELTA blob: 96869f414efe6c3c33d4586eecf08cccb18375cc
- CURRENT_STATUS blob: 0dd569290dfc209763b4f94c2041bad492641d44

## Same-SHA lens progression
Previous coverage:
- L1 DEEP
- L2 DEEP
- L3 DEEP
- L4 BASELINE
- L5 DEEP
- L6 DEEP

This run promotes:
- L4 Destructive ownership -> DEEP

## Scope reviewed
- TerminalDownloadWorker publication/commit/failure/stop cleanup
- TerminalExecutionRegistry cancellation/admission/release
- TerminalExecutionRecovery phases and convergence
- TerminalCancellationCoordinator
- WorkManagerHandoffRecovery Terminal cancellation/supersession
- TerminalPublicationRecovery
- PublicationRecoveryJournal
- TerminalCacheOwnership
- AppCacheManager
- CacheMaintenanceAuthority
- FolderSettingsFragment cache-clear production path
- FileUtil exact SAF/MediaStore/direct publication
- historical BUG-MOVE-01 and BUG-TERMINAL-04 independent revalidations

## Existing blockers retained
- BUG-TERMINAL-03 remains OPEN P2.
- provisional BUG-BACKUP-11 remains confirmed P2.

No new evidence in this checkpoint changes those statuses.

## Historical destructive roots revalidated
Historical BUG-MOVE-01 partial-SAF whole-source deletion was independently rejected at current-basis review 4ef990e0. Current FileUtil still performs exact per-source SAF/MediaStore publication, tracks per-source errors, and does not authorize whole-source cleanup on a partial error.

Historical BUG-TERMINAL-04 partial-publication-as-success was independently CLOSED at current-basis review 9edd3e23. Current TerminalDownloadWorker still:
- begins a PublicationRecoveryJournal before publication;
- records exact source artifacts;
- requires durable reservation/publication callbacks;
- rejects journal-write failure;
- checks that all exact source files are absent after move;
- removes the artifact manifest before semantic publication commit;
- persists COMMITTING before row deletion;
- retains recovery/quarantine state on pre-commit failure.

No regression of those exact historical roots was confirmed.

## Confirmed P2 same-root expansion — BUG-CANCEL-02 Terminal subcase

This is not counted as a new finding ID.

Canonical BUG-CANCEL-02 already owns the invariant:
once durable cancellation wins, stale worker success-side effects must be vetoed at the same commit/effect boundary.

L4 DEEP confirms the same root in Terminal.

### Production sequence

1. Terminal worker passes native execution and calls TerminalExecutionRecovery.markNativeFinished().
2. Before provider publication/semantic commit finishes, the user invokes Terminal cancellation.
3. WorkManagerHandoffRecovery.cancelTerminalDispatch() first durably supersedes the Terminal dispatch carrier.
4. It then attempts exact WorkManager cancellation and records whether that cancellation was acknowledged.
5. TerminalCancellationCoordinator does not require workManagerCancellationAcknowledged for row deletion. rowDeletionAuthorized is only dispatchSuperseded && executionConverged.
6. TerminalExecutionRegistry.cancel() calls TerminalExecutionRecovery.convergeTerminal(... STOPPED).
7. For a NATIVE_FINISHED record, convergeTerminal can terminalize it without proving provider publication has stopped, because its quiescence protocol is native-process scoped.
8. On convergence TerminalExecutionRegistry removes the active execution token.
9. TerminalViewModel / CancelTerminalNotificationReceiver can then delete the Terminal row even when WorkManager cancellation acknowledgement is false.
10. The already-running TerminalDownloadWorker has no durable-cancellation recheck between markNativeFinished() and FileUtil.moveFile()/publication.
11. If the WorkManager cancel request failed or the running worker is still unwinding, it can continue provider publication after the durable cancel outcome has won.
12. Later markCommitting() will fail against the STOPPED execution witness, but that check occurs after publication side effects can already exist.

### Direct destructive/authority effect

A user-cancelled Terminal can still create/publish destination output after cancellation has durably won. The later execution-witness failure can prevent semantic success, but it cannot undo an already-created provider/file output safely.

This is the same authoritative-cancellation-vs-stale-success root as BUG-CANCEL-02, now confirmed across the Terminal feature.

### Maintenance amplification

The same cancellation sequence can remove both live-cache protection signals before the worker is necessarily quiescent:
- the Terminal row can be deleted, so FolderSettingsFragment.hasActiveDownloads() can return false;
- TerminalExecutionRegistry removes the active token, so TerminalCacheOwnership.isLiveOwnedRoot() returns false.

AppCacheManager.delete(TERMINAL_CACHE) then has no live-owner reason to skip the still-owned staging root.

CacheMaintenanceAuthority serializes maintenance against execution admission only. It does not serialize maintenance against Terminal cancellation or the full publication lifetime.

Therefore a clear-cache action after Terminal cancellation can delete staging/control files while the old worker is still unwinding publication.

This maintenance consequence supports the BUG-CANCEL-02 Terminal subcase. It is not double-counted as a separate BUG-CACHE finding in this checkpoint.

### Existing production evidence that cancellation acknowledgement is not quiescence

DownloadWorkerCleanupProductionWiringTest explicitly notes that WorkManager cancellation can mark WorkManager rows synchronously while an already-running worker is still unwinding its finally/cleanup path.

The Terminal cancellation contract is even weaker for row deletion because workManagerCancellationAcknowledged is not part of rowDeletionAuthorized.

### Required correction

Keep the fix in the existing cancellation root:
- cancellation must not retire Terminal publication/live-cache authority until the worker/publication owner is proven quiescent;
- durable STOPPED cancellation must veto provider/file publication and semantic success at the publication boundary;
- row deletion must not be used as proof that publication ownership has ended;
- cache-maintenance live ownership must remain true through the entire staging/publication lifetime, including post-native cancellation;
- if provider side effects may already have started, retain exact journal/recovery ownership and do not let generic maintenance delete the source/control state.

### Required regression

Deterministically pause a real Terminal worker after markNativeFinished() and before/during publication, then:
- issue Terminal cancel;
- make WorkManager cancellation fail or remain not-yet-quiescent;
- prove no new destination publication can commit after durable cancellation;
- prove Terminal row/token/cache ownership are not retired early;
- attempt TERMINAL_CACHE clear concurrently and prove current staging/journal/manifest survive;
- cover publication already partially committed, unknown provider reservation, and all-output publication before cancellation;
- restart/reconcile and prove no post-cancel duplicate/replay occurs.

## New confirmed finding IDs
0.

## Lens coverage
- L1 DEEP
- L2 DEEP
- L3 DEEP
- L4 DEEP
- L5 DEEP
- L6 DEEP

primary_deep_lens: L4

## L4 effectiveness — provisional
- new confirmed P0/P1/P2 IDs: 0
- same-root existing finding expansion: BUG-CANCEL-02 Terminal subcase
- existing blockers retained: BUG-TERMINAL-03, provisional BUG-BACKUP-11
- historical roots rejected as current regressions: BUG-MOVE-01, BUG-TERMINAL-04
- checklist gaps: 0
- supporting lenses: L1, L3, L6

## Remaining scope
- re-run L1/L2/L3/L5/L6 gates this invocation
- final terminal fault/cross-attempt/live-owner matrix
- exact-SHA status/check evidence
- confirm implementation/review heads before FINAL
- append and verify FINAL checkpoint

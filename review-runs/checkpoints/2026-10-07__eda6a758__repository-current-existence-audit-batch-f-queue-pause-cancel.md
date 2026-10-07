# Repository current-existence audit — batch F — queue/pause/cancel authority

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 7fe9d036b780422b6b03a23d9725437d11c6cb14
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-QUEUE-02 — VERIFIED_OPEN P2

Historical root:
stale Queued details action can move a row to Saved after DownloadWorker has already claimed it.

Current exact source still reproduces the authority gap:
- QueuedDownloadsFragment details long-click still calls updateToStatus(id, Saved) from the UI-selected
  row;
- DownloadViewModel routes Saved to DownloadRepository.moveToSaved(id);
- moveToSavedInternal rereads the current row, but after excluding committed-History/primary-success and
  low-quality cancellation/refusal cases it unconditionally calls setStatus(id, Saved);
- there is no required current status=Queued/WaitingForMembership predicate and no refusal merely because
  the reread row is Active/PostProcessing with a nonblank executionId;
- markLinkedDownloadSaved can still mutate the linked low-quality child in the same transaction.

Disposition:
VERIFIED_OPEN P2.

Correction boundary:
only an exact currently non-running queue state may transition to Saved; Active/PostProcessing ownership
must refuse the stale UI action or first enter the exact stop/quiescence protocol.

## BUG-QUEUE-03 — VERIFIED_OPEN P2

Historical root:
durable Queued intent can survive asynchronous WorkManager enqueue failure without a carrier.

Current exact source:
- startDownloadWorker(...) persists/consumes already durable queue state and calls enqueueUniqueWork;
- ordinary callers default awaitAcceptance=false;
- enqueueAndAwait observes Operation.result only when awaitAcceptance=true;
- ordinary immediate and WorkManager-backed scheduled requests therefore return Result.success after
  enqueue invocation without proving asynchronous acceptance;
- App startup runs DownloadExecutionRecovery for abandoned Active/PostProcessing/native-finalization debt
  and WorkManagerHandoffRecovery for explicitly staged handoffs, but no general exact reconciliation was
  established that scans ordinary orphan Queued rows and reconstructs their missing DownloadWorker carrier.

Disposition:
VERIFIED_OPEN P2.

Correction boundary:
ordinary queue publication needs exact durable scheduler responsibility/acceptance with cold-start
reconciliation and exactly-one execution semantics.

## BUG-QUEUE-04 — VERIFIED_CLOSED

Historical root:
a stale Queued multi-select Delete could be claimed Active before deletion, then have its row/cache
removed without exact owner cancellation.

Current exact path:
- the UI still calls cancelDownloadOnly(id) before deleteAllWithID(ids);
- cancelDownloadOnly now rereads the exact current row under the per-Download execution side-effect lease
  and worker execution lock;
- for a live execution it first persists an operation-aware USER_CANCEL recovery carrier, converges the
  exact semantic cancellation, and requires exact native/post-processing quiescence before returning;
- persistence/quiescence failure retains recovery responsibility and throws instead of allowing the
  delete sequence to continue as success;
- only after all per-id cancel calls complete does the UI reach deleteAllWithID.

Thus the original Queued -> Active race no longer skips owned cancellation before row/cache deletion.
Disposition: VERIFIED_CLOSED.

This does not prove every generic deleteAllWithIDs caller safe; it closes the historical Queued
multi-select failure path described by BUG-QUEUE-04.

## BUG-PAUSE-01 — VERIFIED_CLOSED

Historical root:
failed first Pause persistence still emitted Resume UI.

Current PauseDownloadNotificationReceiver:
- persists a USER_PAUSE / SEMANTIC_STOP_PENDING recovery carrier for exact D/E;
- converges the exact semantic pause;
- sets local paused=true only for committed/already-satisfied Pause;
- performs quiesceAfterDurableStop;
- resets paused=false when quiescence does not converge;
- Resume notification is published only when paused remains true;
- persistence failure retains exact recovery responsibility and does not publish normal Resume.

Disposition: VERIFIED_CLOSED.

## BUG-PAUSE-02 — VERIFIED_CLOSED

Historical root:
durable Paused state could be exposed as normally resumable even when exact native quiescence failed.

Current receiver requires quiesceAfterDurableStop to succeed before keeping paused=true and publishing
Resume. Failed quiescence retains operation-aware recovery responsibility while the per-Download stop
protocol remains authoritative.

Disposition: VERIFIED_CLOSED.

## BUG-CANCEL-01 — VERIFIED_CLOSED

Historical root:
native termination could occur before authoritative cancellation persistence.

Current notification/in-app exact cancel path:
- first persists an exact USER_CANCEL semantic-stop recovery carrier;
- then converges the durable Cancelled semantic;
- only after semantic commit is acknowledged does it enter quiesceAfterDurableStop;
- semantic persistence failure is retained as recovery debt and does not authorize direct native stop as
  a completed cancellation.

Disposition: VERIFIED_CLOSED.

## BUG-CANCEL-03 — VERIFIED_CLOSED

Historical root:
successful Cancelled write followed by failed exact native quiescence was represented as completed
cancellation.

Current paths:
- CancelDownloadNotificationReceiver requires quiesceAfterDurableStop to converge before cancelling the
  running notification;
- in-app/bulk paths use exact recovery responsibility and check quiescence results rather than discarding
  the Boolean;
- failures retain exact recovery responsibility.

Disposition: VERIFIED_CLOSED.

## BUG-CANCEL-04 — VERIFIED_CLOSED

Historical root:
pre-write recovery carrier did not remember whether its semantic purpose was Cancel or Pause, allowing
generic requeue recovery after first semantic-write failure.

Current DownloadExecutionRecovery carrier durably records:
- RecoveryDisposition = GENERIC / USER_CANCEL / USER_PAUSE / HISTORY_FINALIZATION;
- RecoveryPhase = SEMANTIC_STOP_PENDING / NATIVE_QUIESCENCE_PENDING / NATIVE_QUIESCENT.

recordPending validates and persists the user-stop disposition/phase, and the current stop callers enter
through the exact USER_CANCEL/USER_PAUSE semantic phase. Recovery can therefore distinguish failed
first-write stop intent from generic abandoned execution.

Disposition: VERIFIED_CLOSED.

## Batch result

roots_audited: 8
verified_closed: 6
verified_open: 2
reopened: 0
not_verified: 0

Lower-bound inventory progress:
- candidate IDs: 136
- audited: 60
- verified closed/currently not reproduced: 54
- verified open: 6
- not yet audited inside lower bound: 76
- checkpoint-only discovery still pending.

Verified-open non-canonical repository roots so far:
- BUG-PLAYER-01 — P2
- BUG-QUEUE-01 — P3
- BUG-QUEUE-05 — P2
- BUG-DATE-03 — P2
- BUG-QUEUE-02 — P2
- BUG-QUEUE-03 — P2

Current narrow download canonical remains P0=0 / P1=0 / P2=8.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.

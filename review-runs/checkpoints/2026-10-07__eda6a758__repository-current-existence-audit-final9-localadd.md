# Repository current-existence audit — final-9 LocalAdd dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 17f3bf1f04c8e3be7b2ea13ab754eeb78f392fd6
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-LOCALADD-02 — VERIFIED_CLOSED

Historical root:
LocalAdd WorkManager intent could become durable while its SharedPreferences input payload was still only
an asynchronous apply() write.

Current producer uses LocalAddResponsibilityReconciler.publishSession():
- LocalAddStorage.beginSession commits the exact entry payload and explicit WorkManager owner marker in
  one synchronous SharedPreferences commit;
- only after that durable commit does it call enqueueUniqueWork;
- Operation.result is awaited;
- accepted request identity is then durably recorded;
- App startup runs LocalAddResponsibilityReconciler.reconcileStartup(), which can rebuild a missing/failed
  WorkManager owner from the still-durable exact session.

The original durable-work / nondurable-input ordering window is gone.

Disposition: VERIFIED_CLOSED.

## BUG-LOCALADD-03 — VERIFIED_CLOSED

Historical root:
unresolved LocalAdd candidates and the continuation identity were published through asynchronous apply()
before worker success/notification.

Current source:
- savePending() uses synchronous commit and throws on failure;
- the pending payload is committed before notification publication;
- pending sessions are discoverable directly from durable local_add_pending_<sessionId> keys via
  loadPendingSessionIds();
- the legacy single open-session pointer is no longer the sole discovery authority;
- worker success is reached only after savePending returns.

A process death after notification/success cannot remove the already-committed pending payload, and a
lost pointer/notification does not make the durable session undiscoverable.

Disposition: VERIFIED_CLOSED.

## BUG-LOCALADD-04 — VERIFIED_CLOSED

Historical root:
one entry-local failure could abort the worker after earlier side effects, leaving later siblings and
in-memory pending outputs with no durable remainder owner.

Current worker can still throw from an entry-local provider/Room operation, but the historical loss
semantics are closed by the later responsibility protocol:
- the exact input session remains durably stored while the worker runs;
- worker completion retires the session only after the full loop/pending publication succeeds;
- an exceptional worker exit therefore leaves the session payload and owner marker intact;
- reconcileStartup() inspects the exact owner; if its previous WorkRequest is finished/absent it
  publishes a replacement request and awaits acceptance;
- replay is idempotent at the History mutation boundary because insertLocalHistory() now rechecks exact
  URL/storage identity while holding HistoryReferenceMutationCoordinator and the same Room transaction
  as insertion;
- unresolved candidates are rebuilt on replay and savePending is durable before success.

Thus an entry failure may delay the batch until reconciliation, but it no longer strands the remaining
durable intent or duplicates already-committed History rows.

Disposition: VERIFIED_CLOSED.

This closure relies on durable whole-session replay rather than per-entry failure isolation. A future
change that retires the session on failed WorkInfo or removes startup replay would reopen this root.

## BUG-LOCALADD-05 — VERIFIED_CLOSED

Historical root:
two concurrent LocalAdd workers could both observe "absent" before the History mutation lock and then
serialize two successful auto-ID inserts.

Current final publication uses HistoryKeywordAssignmentRepository.insertLocalHistory():
- acquires HistoryReferenceMutationCoordinator;
- opens one Room transaction;
- rechecks current History rows for exact URL or LocalAddStorageIdentityPolicy semantic identity inside
  that protected transaction;
- returns AlreadyPresent when another session already won;
- only otherwise inserts the new History row.

The stale pre-lock observation is no longer publication authority.

Disposition: VERIFIED_CLOSED.

## Immediate accounting

Lower-bound 136-ID population:
- audited IDs: 131
- VERIFIED_CLOSED/currently-not-reproduced: 80
- VERIFIED_OPEN: 50
- SUPERSEDED_ALIAS: 1
- remaining unaudited IDs: 5

Remaining lower-bound IDs:
- BUG-LOWQUALITY-01
- BUG-LOWQUALITY-02
- BUG-LOWQUALITY-03
- BUG-TERMINAL-08
- BUG-TERMINATE-01

Current narrow download canonical remains P0=0 / P1=0 / P2=8.

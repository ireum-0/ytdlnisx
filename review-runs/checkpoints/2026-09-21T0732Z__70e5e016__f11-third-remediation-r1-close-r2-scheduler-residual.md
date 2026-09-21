# F11 third-remediation independent re-review — R1 closure and R2 scheduler acceptance residual

Date: 2026-09-21

Defect-ID: `BUG-BACKUP-03`
Reviewed-Implementation-SHA: `70e5e016155df8c14aa96984f08624957c11afe4`
Reviewed-Range: `7efd3fe2579e421c545d1ed6287713dec02e9225..70e5e016155df8c14aa96984f08624957c11afe4`
Review-Base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
Prior-Review-HEAD: `35f324bed2a2b6894c5fd09c2c1e294686c5d9b6`
Reviewed-Ledger-SHA: `899328bc91e4008e39a658387396a0106c8666ec`

Verdict: `NOT_CLEAN`

Current blocker:
- [P0] `BUG-BACKUP-03`

Disposition:
- F11-R1 = CLOSED at exact source `70e5e016...`
- F11-R2 = STILL_OPEN
- F11-R3 = CLOSED / no source evidence of regression
- F11-R4 = CLOSED / no source evidence of regression

Canonical blocker-count delta: `0`.

The third-wave push is a normal forward-only 12-commit range from exact prior completed implementation `7efd3fe2...`, with exact merge base `7efd3fe2...`, 12 ahead and 0 behind.

## F11-R1 — CLOSED

### Legacy pre-owner-marker LocalAdd responsibility

Final source reconstructs a legacy markerless LocalAdd responsibility only when all of the following agree:

- a durable `local_add_entries_<sessionId>` payload exists;
- WorkManager has an unfinished WorkSpec tagged as LocalAdd;
- that WorkSpec's exact worker class is `LocalAddWorker`;
- that WorkSpec's input carries the exact same `KEY_SESSION_ID`;
- at most one such live owner exists.

`LocalAddResponsibilityReconciler.captureQuiescedSessions()` fail-closes on ambiguous live owners and does not treat raw entry keys as liveness authority.

When the exact legacy owner is proven, `LocalAddStorage.trackActiveOwner(...)` creates the durable owner marker before F11 cancels the LocalAdd tag. The exact session ID is then journaled and reconciled after commit.

Stale entry payloads and finished WorkSpecs do not satisfy this proof and are not revived.

### LocalAdd runtime preference authority

`BackupSettingsUtil.isPortablePreferenceKey()` excludes the entire `local_add_` namespace.

Settings Restore:

- snapshots destination preferences;
- clears the portable graph;
- restores every non-portable destination-local key from the destination snapshot;
- accepts imported settings only through `putPortable(...)`, which requires `isPortablePreferenceKey(item.key)`.

Therefore current LocalAdd runtime/session authority survives settings-only Reset, new backups exclude it, and historical backups cannot import foreign LocalAdd execution authority.

### LocalAdd producer/quiescence ordering

The production LocalAdd path in `HistoryFragment` now uses:

`URI expansion`
→ request/session creation
→ `LocalAddResponsibilityReconciler.publishSession(...)`
→ `beginSession`
→ `enqueueUniqueWork(KEEP)`
→ await `Operation.result`
→ `markOwnerAccepted`.

Only the short final publication/acceptance transfer is inside `RestoreMutationAdmission.withOrdinaryMutation`; URI expansion remains outside.

F11 Restore publication uses the same admission mutex before quiescence. Thus:

- an ordinary producer that wins first completes durable session publication + WorkManager acceptance + owner marker before Restore can publish;
- if Restore wins first, the producer cannot enter the ordinary mutation boundary and cannot publish a late LocalAdd owner into the active Restore graph.

F11 then captures exact owners under restore mutation, journals them, awaits tag cancellation, polls quiescence, and reconciles journaled sessions post-commit.

User retirement/cancellation is also ordered under ordinary admission: WorkManager cancellation acceptance is awaited before the durable RETIRED marker is committed.

The actual History UI producer and cancel action are wired to `publishSession(...)` and `retireAndCancel(...)`.

Implementation-agent exact-candidate instrumentation evidence includes the targeted legacy/live/stale/runtime/producer cases, but this checkpoint's semantic decision is based on exact final GitHub source.

## F11-R2 — STILL OPEN

### Confirmed residual: ordinary scheduler WorkManager acceptance can cross Restore publication

The prior canonical scheduler residual required the complete ordinary scheduler external-authority effect to be ordered against Restore, including WorkManager acceptance where acceptance is the authority transfer.

Final source improves cancellation materially:

- ordinary scheduler cancellation is inside `RestoreMutationAdmission`;
- START/END retry/attempt jobs are tombstoned/cancelled;
- `cancelUniqueWork(...).result` is awaited;
- only then are the durable scheduler carrier rows deleted;
- AlarmManager cancellations occur inside the same ordinary admission boundary.

However ordinary WorkManager scheduler handoff publication still has a request/acceptance gap.

In `WorkManagerHandoffRecovery.performAttempt(...)` for `authority == null`:

1. the current carrier/generation is checked;
2. `RestoreMutationAdmission.withOrdinaryMutation` covers only the call to `enqueueUniqueWork(...)`;
3. that call returns an asynchronous WorkManager `Operation`;
4. ordinary admission is released;
5. `awaitOperation(operation)` waits for WorkManager acceptance outside admission;
6. `finalizeAccepted(...)` later reacquires ordinary admission.

This source itself defines `Operation.result` as the acceptance boundary elsewhere, so returning from `enqueueUniqueWork(...)` is not acceptance.

A reachable ordering remains:

1. ordinary scheduler handoff C enters ordinary admission and calls `enqueueUniqueWork(...)`; the asynchronous Operation has not completed;
2. ordinary admission is released;
3. Restore wins the same mutex and publishes active ownership;
4. Restore quiescence/cancellation can complete before C's WorkManager transaction is accepted;
5. C's WorkRequest is then accepted after the destructive/quiescence boundary;
6. final carrier acknowledgement cannot make C current again: `finalizeAccepted(...)` either blocks on Restore admission and/or observes a newer/superseding generation and returns `SUPERSEDED`;
7. nevertheless, the already accepted stale WorkRequest is not cancelled by the superseded path.

For scheduler START, `buildRequest(...)` creates a `DownloadWorker` WorkRequest. `DownloadWorker` does not consume or validate the WorkManager handoff ID/request ID/generation. Its initial Restore fence is only:

`if (RestoreGate.isRestoreInProgress(...)) return Result.retry()`.

Therefore a stale ordinary scheduler WorkRequest accepted after F11's quiescence cancellation can retry while Restore is active and later execute after the Restore pointer is retired. A later `SUPERSEDED` carrier result does not revoke that external WorkManager owner.

This is the same F11-R2 scheduler authority-effect root, not a new defect ID and not a count increment.

### Coverage gap confirms the same residual boundary

The new `F11SchedulerExternalAuthorityProductionWiringTest` proves:

- ordinary scheduler cancel awaits external cancellation before carrier retirement;
- Restore winning before an ordinary scheduler cancel prevents that cancel effect;
- Restore winning before ordinary scheduler carrier preparation rejects new carrier publication.

It does not exercise the remaining crossing:

`ordinary enqueue call admitted`
→ `Operation.result still pending`
→ `Restore publication/quiescence wins`
→ `old WorkManager request accepted late`.

The implementation-agent PASS counts therefore do not close this exact residual.

### Additional scheduler consumer ordering remains subject to R2 closure

`DownloadSettingsFragment` still performs some preference and scheduler effects in separate ordinary-admission acquisitions:

- `schedule_start` / `schedule_end` durable preference commit occurs via `applyOrdinaryPreferences(...)`, then `scheduler.schedule()` reacquires admission separately;
- AndroidX `use_scheduler` framework persistence occurs after the preference-change listener returns, while the listener's scheduler external effect has already run under its own admission.

These paths do prevent an external scheduler mutation from newly entering after Restore has already won, but they do not establish one indivisible ordinary authority transfer between the durable scheduling preference decision and its external scheduler effect. Full R2 consumer closure must account for these paths when repairing the confirmed acceptance gap.

## Preserved closures

- F11-R2 ordinary handoff-carrier final Room mutation remains source-closed.
- F11-R3 Restore exact-alarm fallback retains Restore authority and awaits WorkManager acceptance + durable carrier acknowledgement before allowing Restore completion.
- F11-R4 Restore scheduler identities remain operation/boundary-stable; Restore Download replay remains operation-scoped.
- No Room schema/migration change is present in the 12-commit third-wave range.
- No source evidence in this checkpoint reopens F10 or F20 identity/session/cancellation semantics.

## Verification evidence

Implementation-agent evidence for exact pushed candidate `70e5e016...` reports, among other gates:

- direct F11 third-remediation instrumentation: 9/9 PASS;
- Automatic-keyword production wiring: 8/8 PASS;
- History undo persistence: 10/10 PASS;
- Reset transaction: 26/26 PASS;
- LowQuality controlled repeat: 125/125 PASS;
- cleanup initial full-family failure at 37/69 preserved, followed by exact-method 1/1 PASS and controlled full-class 69/69 PASS.

Those results are implementation-agent evidence only. No independent Android execution was performed in this review.

CLEAN-basis consequence:
- do not advance `CLEAN_REVIEW_BASIS` from `90afaec157607669ea32fa41877e7f0efcdcca86`;
- `70e5e016...` is `NOT_CLEAN` because F11-R2 remains open.

Ledger-May-Close: `NO`

INDEPENDENT EXECUTION: NOT EXECUTED

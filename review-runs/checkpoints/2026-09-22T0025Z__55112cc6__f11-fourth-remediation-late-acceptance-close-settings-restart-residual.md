# F11 fourth-remediation independent re-review — late-acceptance closure, scheduler-settings restart residual

Date: 2026-09-22

Defect-ID: `BUG-BACKUP-03`
Reviewed-Implementation-SHA: `55112cc6d5234e44b785fe655007a7fb58ac0553`
Reviewed-Range: `70e5e016155df8c14aa96984f08624957c11afe4..55112cc6d5234e44b785fe655007a7fb58ac0553`
Review-Base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
Prior-Canonical-F11-Review: `a2ebe22efeeeabfe7d063016952f0dd7a4f97d11`
Reviewed-Ledger-SHA: `899328bc91e4008e39a658387396a0106c8666ec`

Verdict: `NOT_CLEAN`

Current blocker:
- [P0] `BUG-BACKUP-03`

Disposition:
- F11-R1 = CLOSED / no regression found
- F11-R2 = STILL_OPEN
  - R2 late ordinary scheduler WorkManager acceptance subcase = CLOSED
  - R2 same-process scheduler preference-vs-Restore ordering subcase = CLOSED
  - R2 durable scheduler preference/external-effect restart atomicity = STILL_OPEN
- F11-R3 = CLOSED / no regression found
- F11-R4 = CLOSED / no regression found

Canonical blocker-count delta: `0`.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

Ledger-May-Close: `NO`

## Git / range verification

The pushed implementation branch was independently verified at:

`55112cc6d5234e44b785fe655007a7fb58ac0553`

Exact fourth-wave range from prior reviewed implementation:

`70e5e016155df8c14aa96984f08624957c11afe4..55112cc6d5234e44b785fe655007a7fb58ac0553`

Shape:
- 6 forward commits
- 0 behind
- merge base exactly `70e5e016155df8c14aa96984f08624957c11afe4`
- linear parent chain
- no Room schema/migration files in the range

Whole F11 shape from original base:
- base `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
- head `55112cc6d5234e44b785fe655007a7fb58ac0553`
- 41 forward commits
- 0 behind
- merge base exactly the original F11 base
- 109 changed files
- no schema/migration file

Fourth-wave commits were verified as:
1. `1e3ec11df3950f032dbe0c0571eebd0d98121c5c` — production R2 correction, required BUG-BACKUP-03 / a2ebe22e / F11-R2 / P0 trailers.
2. `fed917966b4406be1c0543b82327232a3365c01a` — scheduler late-acceptance/process-death/setting-order tests, required trailers.
3. `722997330f5708fd689c8e181ea2f942e50c5e82` — superseded carrier retirement after cancellation, required trailers.
4. `c20321ae437f4cd6fb17574f24fe8d77e872f072` — R3 accepted scheduler carrier test alignment, required trailers.
5. `3b4ab56c6f902b6c5e5917428cb70e0a1466113a` — real WorkManager carrier-retirement proof alignment, required trailers.
6. `55112cc6d5234e44b785fe655007a7fb58ac0553` — test-only provider-aware backup publication harness correction, continuation attribution and canonical defect delta 0.

## F11-R2 — late ordinary scheduler acceptance is CLOSED

The exact residual recorded at `a2ebe22e...` was:

ordinary enqueue request admitted
→ WorkManager `Operation.result` still pending
→ Restore wins publication/quiescence
→ old request accepts late
→ stale WorkRequest can later mutate after Restore retires.

Final source closes that exact boundary with durable request identity plus worker-side semantic fencing and exact revocation.

### Durable exact request identity

`WorkManagerHandoffCarrier` persists:
- handoff ID;
- semantic generation ID;
- exact WorkRequest UUID as `requestId`;
- scheduler START/END boundary;
- state.

Scheduler requests are built with:
- WorkRequest ID exactly equal to durable `requestId`;
- handoff ID;
- request ID;
- generation ID;
- boundary in WorkManager input.

The new `SUPERSEDED` state is a durable tombstone.

### Finalization cannot revive a stale late acceptance

Ordinary `performAttempt(...)` intentionally continues to keep only the enqueue publication call inside ordinary Restore admission, preserving the prior deadlock correction.

After `Operation.result` resolves, `finalizeAccepted(...)` now checks scheduler authority before and during final carrier mutation:
- Restore active;
- process generation;
- exact durable current boundary carrier.

If the request is stale, it invokes exact request-ID revocation and returns `SUPERSEDED`.

This closes the previous "later SUPERSEDED but external owner remains live" gap.

### Worker boundary is durable, not process-local

Both scheduler consumer workers validate the exact durable identity before semantic mutation.

`DownloadWorker` for START:
- loads exact handoff/request/generation/boundary inputs;
- requires actual WorkRequest UUID == durable request ID;
- validates current outstanding carrier for the same boundary;
- `SUPERSEDED` or missing carrier => STALE => terminal success/no mutation;
- PENDING enqueue => retry;
- only ACCEPTED current exact carrier proceeds.

`CancelScheduledDownloadWorker` applies the analogous END fence before cancellation/download mutation.

Thus even if an external cancellation fails or a stale request appears after process restart, deleting or superseding the old durable carrier makes that old worker semantically stale rather than live.

### Durable restart cleanup

Startup `WorkManagerHandoffRecovery.reconcile(...)`:
- processes durable `SUPERSEDED` rows;
- cancels exact request UUID when it is still unfinished;
- deletes the exact tombstone only after the request is absent/terminal;
- retains ACCEPTED scheduler carrier identity while WorkInfo is absent or unfinished, avoiding the former missing-carrier ambiguity.

The worker itself treats a truly missing carrier as STALE, so tombstone cleanup does not donate authority to a later old WorkRequest.

No process-local map is the sole worker mutation authority.

### Old generation cannot cancel a newer Restore generation

Ordinary scheduler cancellation:
- runs under ordinary Restore admission;
- marks the exact current scheduler carrier superseded before external cancellation;
- waits for START/END unique-work cancellation;
- retires outstanding/superseded boundary rows while the same ordinary authority is held.

After Restore wins publication, the old ordinary cancellation cannot enter.

Post-restart superseded cleanup cancels by exact old WorkRequest UUID rather than broad tag/name, so it cannot cancel a distinct newer Restore request.

## F11-R2 — same-process scheduler settings ordering is CLOSED

The prior consumer split is materially corrected.

### schedule_start / schedule_end

`DownloadSettingsFragment` no longer commits the preference and later calls `scheduler.schedule()` in a separate admission.

It calls `AlarmScheduler.updateScheduleBoundary(...)`, which:
- acquires one ordinary Restore admission;
- durably commits the selected boundary value;
- republishes the scheduler graph while that same admission is held.

If Restore has already won, the method returns false without changing the preference or publishing the external effect.

### use_scheduler

The preference listener calls `AlarmScheduler.updateSchedulerEnabled(...)`.

The controller:
- acquires one ordinary Restore admission;
- durably commits `use_scheduler`;
- performs enable schedule or disable cancel/successor behavior before releasing that admission.

The listener then returns false, preventing AndroidX from performing a second persistence write after the external effect. It updates the in-memory preference UI only after the controller reports success.

Therefore the specific same-process preference/effect split recorded at `a2ebe22e...` is closed.

## F11-R2 — STILL OPEN: process death between durable scheduler preference decision and external effect

The fourth-wave governing prompt explicitly requires process-death scenario 15:

`process death between durable scheduler preference decision and external effect`

and requires:

`Every surviving semantic owner must be reconstructible from durable evidence. No process-local-only rescue.`

Final source does not satisfy that scenario.

### Exact final source ordering

For `schedule_start` / `schedule_end`:

`AlarmScheduler.updateScheduleBoundaryWithinOrdinaryMutation(...)`

does:

1. `SharedPreferences.Editor.putString(...).commit()`
2. `scheduleWithinOrdinaryMutation()`

For `use_scheduler`:

`AlarmScheduler.updateSchedulerEnabled(...)`

does:

1. `SharedPreferences.Editor.putBoolean(...).commit()`
2. enable: `scheduleWithinOrdinaryMutation()`, or
3. disable: `cancelWithinOrdinaryMutation()` then enqueue the ordinary Download successor.

The Restore admission mutex orders competing threads in the current process, but it is not a durable transaction owner after process death.

### No durable transition debt exists before the preference commit becomes authoritative

The new scheduler WorkManager carrier is created only inside the later scheduler effect path.

There is no durable scheduler-settings transition record published atomically with the preference commit that says:
- which preference generation owns the transition;
- whether external cancellation has completed;
- whether START was republished;
- whether END was republished;
- whether disable successor publication is still owed;
- whether the transition must be resumed after restart.

Reachable examples:

#### schedule_start / schedule_end

old external scheduler graph exists
→ ordinary transition enters admission
→ new schedule preference commit succeeds
→ process dies before `scheduleWithinOrdinaryMutation()`

After restart:
- durable preference contains the new value;
- old alarms/handoffs can still represent the old value;
- no new scheduler carrier exists proving the new external publication is owed.

A later death after old cancellation but before both new START/END publications can instead leave the new preference with only a partial/no external scheduler graph.

#### use_scheduler disable

`use_scheduler=false` commit succeeds
→ process dies before `cancelWithinOrdinaryMutation()`

After restart:
- durable preference says disabled;
- old alarm/WorkManager scheduler authority may still be live;
- no durable transition debt requires cancellation.

#### use_scheduler enable

`use_scheduler=true` commit succeeds
→ process dies before scheduler publication

After restart:
- durable preference says enabled;
- no durable scheduler owner need exist;
- no transition carrier proves publication remains owed.

### Startup recovery cannot reconstruct the missing transition

`App.onCreate()` waits for Restore recovery and invokes `WorkManagerHandoffRecovery.reconcile(...)`.

That reconciler can recover scheduler carriers that already exist.

It cannot infer or safely reconstruct an external scheduler transition that died before a new carrier was created, because the exact transition generation/phase is absent.

The reviewed startup path does not invoke a scheduler-settings reconciler that consumes durable preference transition debt and converges AlarmManager/WorkManager state.

Therefore scenario 15 is not fail-closed/recoverable from durable evidence.

### Existing new tests do not close scenario 15

`F11SchedulerExternalAuthorityProductionWiringTest` exercises:
- ordinary-wins versus Restore publication;
- Restore-wins versus ordinary preference/effect transition;
- START/END stale-worker fencing;
- scheduler cancellation ordering.

It does not inject process death/fault boundaries between:
- preference commit and scheduler effect;
- old cancellation and new START publication;
- START and END publication;
- disable preference commit and cancellation/successor publication.

`WorkManagerHandoffProductionTest` does include scheduler process-death/restart coverage, but that coverage begins from an already-existing durable WorkManager handoff carrier. It cannot cover the settings-transition gap before such a carrier exists.

This is the same F11-R2 scheduler authority root. Do not create a new defect ID or increment the canonical count.

## Required next correction boundary

Do not reopen the late-acceptance fix or broaden the scheduler architecture.

The next correction should add a narrow durable scheduler-settings transition owner/debt that makes the preference decision and external effect restart-convergent.

The design must guarantee:
- durable transition identity/state exists before the preference value can become an unrecoverable committed decision;
- restart can distinguish enable, disable, schedule-start and schedule-end transitions;
- restart can resume or converge the exact owed external effect without guessing from process-local state;
- partial START/END publication is recoverable;
- disable cancellation/successor publication is recoverable;
- completion retires only the exact transition after the external effect reaches its defined acceptance/completion boundary;
- ordinary-vs-Restore ordering remains one winner;
- the current late-acceptance durable carrier/worker fence is preserved;
- the prior global-mutex deadlock is not reintroduced.

A durable debt/journal co-committed with the preference decision and consumed by startup reconciliation is one acceptable shape; exact implementation remains open.

Required deterministic proof must include fourth-wave process-death matrix scenario 15 and the partial-effect sub-boundaries above.

## Preserved closures / regression review

### F11-R1
The fourth-wave production range does not modify LocalAdd producer/storage/reconciler code. The scheduler-specific RestoreTransactionCoordinator change does not weaken the previously reviewed LocalAdd capture/publication boundary. R1 remains CLOSED.

### F11-R3
Restore scheduler carrier identity/acceptance remains durable. Accepted scheduler carriers are now retained while their exact WorkManager owner is absent/unfinished, which strengthens rather than weakens the R3 fallback ownership proof. R3 remains CLOSED.

### F11-R4
Restore scheduler handoff identity remains operation/boundary stable and exact request UUID is preserved. No source evidence of current-owner multiplication was found. R4 remains CLOSED.

### F10 / F20 / F6-F7
- F10 cleanup coordinator source is not changed by this range.
- Scheduler-specific additions to `DownloadWorker` are conditional on a nonblank scheduler handoff ID; ordinary non-scheduler Download execution continues through the existing path.
- exact scheduler request identity is strengthened rather than replacing normal Download execution identity.
- backup provider harness change is androidTest-only and does not alter destination-local/transient production policy.

No source evidence in this range independently reopens these closures.

## Provider-aware backup test harness correction

Final commit `55112cc6...` is test-only.

`BackupPublicationTestSupport`:
- reads `content://` through the exact `ContentResolver` URI;
- deletes `content://` through the exact provider URI;
- handles `file://` using its URI path;
- uses `File` only for raw paths.

The three corrected backup tests retain their semantic assertions and do not force `backup_path` to an app-private raw path.

The exact final test class source contains:
- BackupPaused: 4 `@Test` methods;
- BackupPreference: 4 `@Test` methods;
- BackupSettings: 8 `@Test` methods.

This is consistent with the reported final broad-equivalent class counts 4/4, 4/4, 8/8.

F8 / `BUG-BACKUP-05` remains CLOSED.

## Implementation-agent verification evidence

The completion report states exact final SHA `55112cc6...` was the tested and pushed SHA and reports:
- broad-equivalent intended identity set: 307 unique identities / 16 classes;
- missing 0;
- duplicates 0;
- aggregate 307/307, 0 skipped, 0 new semantic failures;
- frozen 27 exact baseline failures unchanged;
- focused scheduler / handoff / Restore / history / low-quality / cleanup / backup gates as reported;
- prior valid and incomplete attempts preserved rather than rewritten.

The original monolithic 307-test attempt remains `ATTEMPTED NOT COMPLETED`.

The earlier BackupPaused, BackupReset and Cleanup failures remain historical evidence with their authorized controlled/corrected follow-ups.

These runtime results are implementation-agent evidence only.

## Final disposition

F11 cannot be declared CLEAN at `55112cc6...` because the governing fourth-wave process-death matrix still has one source-visible R2 gap at scheduler preference/external-effect transition scenario 15.

Do not advance the CLEAN review basis.

Do not close the ledger for BUG-BACKUP-03.

INDEPENDENT EXECUTION: NOT EXECUTED

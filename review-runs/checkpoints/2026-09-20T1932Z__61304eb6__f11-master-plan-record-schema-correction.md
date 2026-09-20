# F11 final re-review — Master Plan record-schema conformance correction

Date: 2026-09-20

Defect-ID: `BUG-BACKUP-03`
Reviewed-Implementation-SHA: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`
Review-Base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
Reviewed-Range: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff..61304eb6f11b10ac66057a1978d5b1f8f75019b0`
Verdict: `NOT_CLEAN`
Canonical-Blocker: `[P0] BUG-BACKUP-03`
Canonical-Count-Delta: `0`
Reviewer: `ChatGPT independent reviewer`
Review-Date: `2026-09-20`
Ledger-May-Close: `NO`

## Attribution

- F11-R1 / R2 / R3 are residual subcases of the already-canonical P0 root `BUG-BACKUP-03` in the reviewed F11 implementation. They do not create new canonical roots.
- F11-R4 is CLOSED on the exact reviewed implementation.
- Blocking status and attribution remain separate: regardless of whether a residual is newly exposed by the F11 implementation or represents incomplete closure of the historical root, the exact reviewed F11 domain remains NOT_CLEAN while the P0 root has open residuals.

## Exact source paths/functions supporting the open residuals

### F11-R1 — LocalAdd responsibility
- `app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt` — `quiesce()`, `reconcilePostCommit()`.
- `app/src/main/java/com/ireum/ytdl/ui/downloads/HistoryFragment.kt` — LocalAdd session creation / WorkManager enqueue.
- `app/src/main/java/com/ireum/ytdl/util/LocalAddStorage.kt` — durable session entries.
- `app/src/main/java/com/ireum/ytdl/work/LocalAddWorker.kt` — session consumption / execution owner.
- `app/src/main/java/com/ireum/ytdl/App.kt` — startup recovery inventory.

Authority chain:
`user-submitted LocalAdd session -> durable LocalAddStorage session -> WorkManager owner -> F11 History quiescence cancels owner -> durable session survives -> no F11/startup reconstruction`.

Required remediation boundary:
preserve/reconstruct or explicitly and durably retire the exact LocalAdd responsibility that F11 quiesces; do not broaden F20 identity semantics.

### F11-R2 — mutation admission consumer closure
- `RestoreMutationAdmission.kt` — shared publication/mutation authority.
- `BaseSettingsFragment.kt`, `DownloadSettingsFragment.kt`, `ProcessingSettingsFragment.kt` — AndroidX Preference actual persistence paths.
- `BackupSettingsUtil.kt` — portable preference authority.
- `WorkManagerHandoffRecovery.kt` — ordinary HARD_SUB_SCAN / SCHEDULE_START / SCHEDULE_END / OBSERVE_RETRY carrier mutation paths.
- `RestoreTransactionCoordinator.kt` — authoritative Reset deletion/replacement of the same carrier domains.

Authority chain A:
`AndroidX Preference writer -> framework auto-persistence outside shared admission -> Restore publication/apply -> late portable setting mutation`.

Authority chain B:
`ordinary carrier producer passes point-in-time gate -> Reset publishes/applies and removes carrier scope -> ordinary carrier transaction resumes/inserts after authoritative Reset`.

Required remediation boundary:
route the actual final Preference and ordinary handoff-carrier durable mutation boundaries through the same Restore publication/mutation ordering, with validated lock order.

### F11-R3 — exact-alarm failure fallback
- `DownloadRepository.kt` — restore-owned Download scheduling entry.
- `AlarmScheduler.kt` — exact alarm publication and fallback call.
- `WorkManagerHandoffRecovery.kt` — restore scheduler carrier, `ensureConvergenceForRestore`, `performAttempt`, delayed retry, WorkRequest initial-delay construction.
- `RestoreTransactionCoordinator.kt` — RECONCILING -> COMPLETE ownership lifetime.

Authority chain:
`restore scheduler carrier -> exact alarm publication fails -> only process-local future retry retains Restore authority -> Restore completes/retires authority -> delayed retry can no longer establish owner -> carrier remains pending until later startup`.

Required remediation boundary:
while Restore reconciliation authority remains valid, establish and await an accepted durable successor (for example a delayed WorkManager request using the existing `notBeforeAt` initial-delay semantics), and preserve R4 stable replay identity.

## Closed residual

F11-R4:
- source paths: `DownloadRepository.startDownloadWorkerForRestore`, Restore WorkManager naming/policy, `DownloadWorker.doWork()` RestoreGate behavior.
- disposition: `CLOSED`.
- relation: preserve this closure while fixing R3.

## Execution evidence

- Exact GitHub remote/ancestry/source review: independently performed.
- Implementation-agent runtime evidence: reported focused/broad PASS with the frozen 27 inherited baseline failures.
- Independent runtime execution for this re-review: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED

## Relation to prior findings/checkpoints

- F11 design checkpoint: `393ed9a2b0ade7ead283637f2a932a9a8456f99a`.
- First canonical F11 review: `8b5b064c63fc04de9b2d18346954ab5dfdec625e`.
- Full final F11 re-review: `15cf6c3f969266a5a6c48f8713ff5bfc07b52dc3`.
- Checklist-v6 output correction: `28f189c06a01cb05918ed7c65e991cc6760fc502`.

## Ledger disposition

`ledger/remediation` is reference-only for this wave.

`BUG-BACKUP-03` MUST NOT receive a closure record while R1/R2/R3 remain open.

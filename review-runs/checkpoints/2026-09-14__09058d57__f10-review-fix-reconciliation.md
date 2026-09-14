# F10 review-fix reconciliation — 2026-09-14 — `09058d57`

## Authority

- Implementation branch: `checkpoint/pre-baseline-review`
- Review base: `f1a159db41f1281a31e1e06df486e4f67cdc3d89`
- F10 review-fix checkpoint: `73c91aa8e951302fd1091f779cb5c621b655a170`
- Completed wave head: `09058d574d19d211cd0db36cbf47726bb8fa7dc5`
- Governing checklist: Review Checklist v6
- Prior F10 checkpoint: `7bbe233a89b046a937b5166e0d2a2406f0a6bb62`

## Verdict

**F10 / `BUG-CLEANUP-01`: NOT_CLEAN**

The previous stale destructive-effect residual is source-level closed by the new generation-owned destructive-effect gate, but the repair introduces a distinct blocker-relevant consumer regression at the production settings boundary.

## Original residual — closed

`CleanupScheduleCoordinator.withCurrentDestructiveEffect()` acquires the same coroutine `Mutex` that `configure()` and startup reconciliation acquire before authority transition. It revalidates generation/cadence while holding that gate and executes the destructive cleanup body before releasing it.

Therefore the two relevant orderings now converge correctly:

1. new disable/supersession acquires the gate and durably commits first -> the old worker later acquires the gate, fails current-generation validation, and cannot enter the destructive cleanup effect;
2. old worker acquires the gate first -> disable/supersession cannot durably commit new authority until the already-admitted destructive effect completes.

The real worker routes cancelled/error deletion and DOWNLOAD_TEMP cleanup through this gate. Existing generation/cadence successor checks, durable scheduling debt, replay fencing, and calendar cadence code remain present.

Disposition of the prior point-in-time authority race: **CLOSED at `73c91aa8...`**.

## Fix-induced P2 regression — main-thread settings block

The gate changes the observable contract of `CleanupScheduleCoordinator.configure()`.

Production `DownloadSettingsFragment` invokes `configure(requireContext(), newValue as? String)` directly from the `cleanup_leftover_downloads` Preference change listener. This is a UI/main-thread callback.

`configure()` is now implemented as `runBlocking { destructiveEffectMutex.withLock { ... } }`.

If an automatic cleanup has already acquired `destructiveEffectMutex`, the cleanup body may perform Room deletions, low-quality ledger refreshes, active-download reads, and DOWNLOAD_TEMP filesystem deletion before releasing the mutex. A user changing or disabling the cleanup preference during that interval blocks the main thread inside `runBlocking` until the entire destructive effect completes.

The production-wiring tests intentionally prove this waiting behavior by asserting that reconfigure/disable remains incomplete while the cleanup effect is held. That ordering is correct for authority, but the synchronous UI consumer makes the waiting contract unsafe: sufficiently slow database/filesystem cleanup can freeze the settings UI and cross Android's main-thread responsiveness/ANR boundary.

This is a material semantic-contract/consumer regression under Checklist v6 consumer-closure rules. It is not the old stale-authority root, but it prevents F10 from becoming CLEAN.

Required correction: preserve the same authority/effect ordering without synchronously waiting on the UI thread for an in-flight destructive cleanup. The production settings consumer and any other synchronous consumers must participate in an asynchronous/suspend-safe transition contract or an equivalent non-main-thread handoff while preserving honest preference acceptance/authority semantics. Do not weaken the generation-owned effect gate back into a point-in-time check.

## Count / basis

- Prior canonical blockers: P0 2 / P1 0 / P2 20.
- Old F10 residual closed: P2 -1.
- Fix-induced F10 consumer regression: P2 +1.
- Resulting canonical blockers after this F10 reconciliation: **P0 2 / P1 0 / P2 20**.
- Net count delta: **0**.
- Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- F11 / `BUG-BACKUP-03` remains blocked because F10 is not yet clean.

Implementation-agent compile/build claims are evidence only. Runtime instrumentation was reported not executed because no device was available.

INDEPENDENT EXECUTION: NOT EXECUTED

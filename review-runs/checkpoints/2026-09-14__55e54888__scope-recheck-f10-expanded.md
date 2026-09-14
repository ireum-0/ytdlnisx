# Independent correctness scope recheck at 55e54888

- Exact implementation SHA: `55e54888a0e03c17a4cbdb51817d3ad4336107f4`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger ref-only: `899328bc91e4008e39a658387396a0106c8666ec`
- Prior combined verdict: `c844cee44aca0e1c781fafbe9f1d05d238e8ca4a`

## Verdict

The previously issued next-wave instruction targeting only the already-recorded F10 `scheduleSuccessor()` pre-debt frontier is too narrow. Exact-source recheck confirms three additional production subcases owned by the same `BUG-CLEANUP-01` P2 persistence/recovery/one-logical-schedule root. Canonical blocker count does not increase.

### F10 confirmed same-root subcases

1. **Known successor pre-debt failure frontier remains.** `scheduleSuccessor()` queries WorkManager before successor debt exists, and the first successor-debt commit can also fail. Repeated failure is converted by `CleanUpLeftoverDownloads` into finite worker retries; exhaustion can leave enabled cadence with no successor work, debt, or replay owner.

2. **Existing-authority startup repair can fail before creating debt.** In `reconcileSuspending()`, when cadence/generation already exist but no current work and no debt exist, `enqueueNextLocked(... persistDebt=true)` performs the first recovery-debt commit. If that commit returns false, no request is enqueued and `ensureReplayOwnerLocked()` is never reached. `App` invokes cleanup reconciliation as one startup coroutine, so the running process can remain enabled but unscheduled until another reconciliation/startup.

3. **Restart does not reconstruct replay ownership for unmatched surviving successor debt while a predecessor is unfinished.** Reconciliation treats any unfinished same-generation/cadence work as `hasCurrent`. `clearDebtForCurrentWorkLocked()` can correctly leave an exact successor debt unmatched, but the `hasCurrent` branch then returns without `ensureReplayOwnerLocked()`. Because replay ownership is process-local, a process restart can therefore leave durable successor debt without a replay owner while the predecessor remains unfinished. If that predecessor later terminalizes without publishing the successor, the debt is stranded until another reconciliation/startup.

4. **WorkManager discovery failure is treated as authoritative absence.** `reconcileSuspending()` maps failure of `getWorkInfosForUniqueWork(...).get()` to `emptyList()`, then enters the missing-work repair path, requests cancellation, and may REPLACE work under the same generation/cadence. Unknown discovery is not proof that no exact live owner exists. A previously live occurrence can survive asynchronous cancellation and still pass the generation/cadence destructive-effect gate. The recovery path must fail closed/retry on unknown scheduler state rather than use discovery failure as replacement authority.

All four belong to the existing F10 semantic root. `F10`: OPEN P2, count delta `0`. `F11 / BUG-BACKUP-03` remains blocked.

## F20 scope recheck

The provider authority/document-ID source fix remains valid: exact `Uri.authority` and exact `DocumentsContract.getDocumentId()` are preserved. F20 remains `SOURCE-FIXED / EXECUTION-NOT-VERIFIED`; no source change is authorized solely from this recheck.

`LocalAddStorageIdentityPolicy.parseUri()` still trims the whole input string before parsing. This could matter for whitespace-significant raw/file paths, but this recheck did not establish the complete current production producer -> suppressing consumer -> concrete incorrect-impact chain required to promote it. Record as candidate/hardening only; no count or implementation-scope change.

## History deletion observation

`HistoryFileDeletionTargetParser` still lowercases content-provider authority in a destructive deletion deduplication key. This is a real deletion-domain identity concern, but it is outside F20 LocalAdd scope. Its canonical root relation must be reconciled against the existing deletion inventory, including `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` and adjacent History deletion authority roots, before count or implementation authorization changes. No count delta in this checkpoint.

## Canonical consequence

- P0: 2
- P1: 0
- P2: 20
- Overall: `NOT_CLEAN`
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Next implementation root remains F10 only, but its authorized scope must include all four confirmed subcases above rather than only the successor pre-debt case.
- Do not start F11 until F10 independently closes.
- Do not modify F20 source from this recheck; execute its production-wiring instrumentation if a device/emulator becomes available.

INDEPENDENT EXECUTION: NOT EXECUTED
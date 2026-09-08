# Independent correctness review final checkpoint

## Fixed target
- implementation: `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529`
- implementation parent: `563b4b34f1298b22bfff061b1c1902b6e97598b3`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review baseline fixed at run start: `924eba50fcae9fb5f8eb8e4ac9894e8ae4a807c2`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Independent verdict
`NOT_CLEAN`

- P0: 0
- P1: 0
- P2: 1 existing/open
- new P0/P1/P2 findings: 0
- material status change from prior review of this implementation SHA: none
- result: `no material change`

## Finding recount
### Existing BUG-OUTPUT-01 P2 — provider UNKNOWN recovery does not converge
The pinned production source correctly preserves provider creation ambiguity:
- `FileUtil` persists an operation-bound reservation intent before `ContentResolver.insert()` / `DocumentsContract.createDocument()`.
- provider exception/null transitions that reservation to durable UNKNOWN rather than clearing it;
- the same publication attempt aborts instead of falling through to another provider/raw publication path;
- `PublicationRecoveryJournal` refuses to clear/replay UNKNOWN and does not treat it as an exact destination.

The remaining blocker is unchanged. No reviewed production consumer resolves UNKNOWN to a finite exact outcome. In Terminal recovery, `TerminalPublicationRecovery.reconcile()` treats reservation intent or UNKNOWN as unresolved, marks `reservationFailed`, and returns from that record without exact object discovery, deterministic rollback, or another terminal resolution. `TerminalPublicationRecovery.admit()` continues to block a new native execution while that prior journal exists. App startup invokes the same reconciler, so restart preserves the fence but does not add a convergence mechanism.

This remains safe against duplicate replay but can leave durable recovery debt indefinitely. Under v6, a surviving carrier must have recovery that preserves semantic identity and is actually discoverable/convergent through the full consumer/authority-effect graph; fail-closed indefinite blocking is not closure by itself.

Disposition: existing `BUG-OUTPUT-01`, severity P2, OPEN / incomplete remediation.

## Production paths re-traced
- `FileUtil.moveFile()` provider/raw selection and fallback ordering.
- MediaStore `insert` -> exact URI reservation -> stream copy -> `IS_PENDING` finalization -> exact journal commit.
- SAF `createDocument` -> exact URI reservation -> stream copy -> exact journal commit.
- `PublicationRecoveryJournal`: PREPARED/PUBLISHING/PARTIAL/COMPLETE/COMMITTING/COMMITTED/QUARANTINED plus intent/UNKNOWN/exact reservation transitions.
- `TerminalDownloadWorker`: exact-source manifest, publication journal, callbacks, stranded-source checks, semantic DAO commit, outer catch, recovery invocation.
- `TerminalPublicationRecovery`: admission, journal reservation reconciliation, committed witness, quarantine conversion, recovery-root retirement.
- `CacheImportPlanner`: ordinary owned-root import vs explicit Terminal recovery carrier separation.
- `App.onCreate`: startup Download recovery and Terminal publication recovery wiring.
- Download worker completion/failure path was recounted sufficiently to find no new contract consumer that converts provider UNKNOWN into a finite exact resolution.

## Confirmed fixed/non-regressed invariants
- provider null/exception is not treated as proof of non-creation;
- UNKNOWN does not authorize replay/fallback;
- exact provider URI is persisted before later publication authority when returned;
- Terminal semantic commit is not downgraded by later sidecar cleanup failure;
- marker-revoked Terminal recovery roots are excluded from ordinary cache import;
- committed Terminal tombstone/admission remains idempotent.

## Checklist evolution
No new checklist gap this run. The previously proposed refinement remains sufficient: external allocation completion must distinguish `PROVEN_NOT_CREATED`, `CREATED_WITH_EXACT_ID`, and `UNKNOWN`; preserving UNKNOWN is only a safety fence, and closure additionally requires a production convergence owner (exact discovery, deterministic rollback, provider-supported idempotency/reconciliation, or explicit finite quarantine/resolution protocol).

## Exact upstream semantic basis
- AOSP `ContentResolver.insert`: provider `RemoteException` can be converted to `null`; null is not proof that no external mutation occurred.
- AOSP `DocumentsContract.createDocument`: provider-side failure can be caught and represented as `null`; callers cannot safely infer proven non-creation solely from that result.

## Evidence / gate
- final implementation HEAD recount: `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529` (unchanged from run start)
- GitHub combined status contexts for exact SHA: none
- independent JVM/emulator/device execution: `NOT_VERIFIED`
- source semantic verdict therefore remains `NOT_CLEAN`; tests are not used as a substitute for the source review.

## Checkpoint summary
Mid-run checkpoint was committed separately. This final checkpoint records the same existing P2 with no new finding or state change: **no material change**.

# Independent correctness review final checkpoint

## Fixed target
- implementation: `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529`
- parent: `563b4b34f1298b22bfff061b1c1902b6e97598b3`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review baseline: `6e8bffd5b7cd14404140729c1e1362c12a7aa41b`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Completed review scope
- full current MediaStore/SAF provider publication boundary in `FileUtil.kt`;
- reservation/UNKNOWN state transitions in `PublicationRecoveryJournal.kt`;
- Download recovery consumer and retry fencing in `DownloadWorker.kt`;
- Terminal production publication path in `TerminalDownloadWorker.kt`;
- Terminal admission/startup reconciliation in `TerminalPublicationRecovery.kt`;
- new journal tests for UNKNOWN semantics;
- Master Plan BUG-OUTPUT-01 gate and v6 carrier/recovery/consumer-closure invariants;
- exact implementation HEAD recount and GitHub combined status.

## Final provisional P0/P1/P2
- P0: 0
- P1: 0
- P2: 1 existing/open

## State change
The prior P2's duplicate-risk replay mechanism is source-level fixed. Provider null/exception outcomes are no longer treated as PROVEN_NOT_CREATED: the reservation becomes UNKNOWN, current-attempt fallback is stopped, restart reserveIntent/rebind is rejected, and UNKNOWN cannot be cleared or promoted into a destination.

BUG-OUTPUT-01 is nevertheless still NOT_CLEAN because UNKNOWN has no production convergence owner. Download recovery throws on reservation-intent or UNKNOWN carriers. Terminal reconciliation sets `reservationFailed` and skips further convergence for the same states. Terminal admission then remains BLOCKED while the durable journal/carrier exists. The implementation prevents duplicate publication but can permanently strand an operation after an ambiguous provider result.

## Fixed invariants confirmed
- provider UNKNOWN no longer clears the duplicate-prevention fence;
- no same-attempt fallback after UNKNOWN;
- no retry/rebind from a pre-existing reservation/UNKNOWN carrier;
- unknown reservation is not a destination and cannot authorize publication;
- exact implementation SHA did not change during the review.

## Open candidates/questions
- No additional P0/P1/P2 candidate confirmed in the reviewed BUG-OUTPUT production graph.
- A product/recovery policy is still required for UNKNOWN provider identity: exact discovery, deterministic rollback, provider-supported idempotency, or explicit durable user-facing quarantine resolution. Simply retaining a permanent BLOCKED fence is not semantic convergence.

## Remaining review scope
- None required to establish this round's verdict in the BUG-OUTPUT provider-publication domain. Wider unrelated baseline defects were not re-audited exhaustively and remain outside this round's focused current-remediation closure recount.

## Exact upstream semantic basis
- Android AOSP `ContentResolver.insert()` current source basis from `platform/frameworks/base`, surfaced commit `0ad99c059735d6d4a9f9fb5379c8b1b35ce01f38`: a provider `RemoteException` may yield `null`, so null cannot prove absence of provider-side mutation.
- Android `DocumentsContract.createDocument()` is an external provider mutation boundary; exact returned URI is required for destination authority. If that URI is unavailable after an ambiguous call, outcome remains UNKNOWN.

## Execution evidence
- GitHub combined status contexts for `b5c57f00...`: none.
- Independent JVM/emulator/device execution: `NOT_VERIFIED`.
- Verdict is source-semantic and does not treat tests as a substitute for production-path review.

## Verdict
`NOT_CLEAN`

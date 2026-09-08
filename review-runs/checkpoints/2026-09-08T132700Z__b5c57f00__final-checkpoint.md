# Independent correctness review final checkpoint

## Fixed target
- implementation: `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529`
- parent: `563b4b34f1298b22bfff061b1c1902b6e97598b3`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review baseline at run start: `e5e4fe26fee86cb69760a68361c550873e3cf1be`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Completed review scope
- fresh exact branch-head pinning and final implementation recount;
- pinned Master Plan manifest and v6 governing checklist reload;
- prior final checkpoint used only as dedup/status baseline, not as source truth;
- current `PublicationRecoveryJournal.kt` reservation intent / UNKNOWN / clear / publish state machine;
- current `FileUtil.kt` provider reservation callbacks, UNKNOWN propagation and fallback fence;
- current `DownloadWorker.kt` unresolved provider recovery branch;
- current `TerminalPublicationRecovery.kt` admission, journal reconciliation, reservation handling and recovery-carrier retirement;
- GitHub combined status for the exact implementation SHA;
- fresh upstream semantic re-check for Android provider insert/create boundaries.

## Final provisional P0/P1/P2
- P0: 0
- P1: 0
- P2: 1 existing/open

## Independent verdict
`NOT_CLEAN` — `no material change` from the immediately preceding review of the same implementation SHA.

## Existing P2 / disposition
BUG-OUTPUT-01 provider-UNKNOWN convergence remains OPEN. The source correctly preserves ambiguous provider creation as an UNKNOWN durable reservation and prevents replay/fallback, but no production convergence owner resolves that state. Download recovery throws on pending/UNKNOWN provider reservation. Terminal reconciliation treats the same reservation as unresolved and returns without semantic convergence; admission remains BLOCKED while the prior journal/carrier exists. This is a safe fence against duplicate publication, but it can strand the operation indefinitely and therefore does not satisfy v6 recovery semantic-identity/discovery/consumer-closure requirements.

## Confirmed fixed invariants
- provider null/exception is not collapsed to PROVEN_NOT_CREATED;
- UNKNOWN cannot be cleared by ordinary reservation cleanup;
- UNKNOWN is not promoted to output authority;
- provider fallback/replay remains stopped after UNKNOWN;
- unresolved Terminal publication debt blocks native re-execution;
- no alternate `FileUtil` provider path was found that bypasses the UNKNOWN callback/fence in the reviewed production graph.

## Open candidates/questions
- no new P0/P1/P2 candidate confirmed;
- remaining product/correctness obligation is unchanged: provide a production convergence owner for UNKNOWN via exact external-object discovery, deterministic rollback, provider-supported idempotency/reconciliation, or explicit durable quarantine/resolution semantics.

## Checklist evolution
No new checklist gap this round. Keep the prior refinement: externally allocated identity must model `PROVEN_NOT_CREATED`, `CREATED_WITH_EXACT_ID`, and `UNKNOWN`; preserving UNKNOWN is only a safety fence, not closure, unless an actual production owner can converge it to a terminal semantic state.

## Exact upstream semantic basis
- AOSP `platform/frameworks/base` `ContentResolver.java` at surfaced commit `0ad99c059735d6d4a9f9fb5379c8b1b35ce01f38`: `ContentResolver.insert()` catches provider `RemoteException` and may return `null`; therefore `null` cannot prove that provider-side mutation did not occur.
- AOSP `DocumentsContract.java` exact surfaced revision `6d2c0e5`: public `createDocument()` catches provider-side exceptions and returns `null`; the internal call returns the provider-supplied URI. An ambiguous/null result therefore does not supply exact destination identity.

## Execution evidence
- GitHub combined status contexts for `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529`: none.
- Independent JVM/emulator/device execution: `NOT_VERIFIED`.
- Tests were not used as a substitute for production source-semantic review.

## Final recount
`checkpoint/pre-baseline-review` remained exactly `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529` through final recount.

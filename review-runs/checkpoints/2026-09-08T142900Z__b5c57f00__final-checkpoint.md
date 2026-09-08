# Independent correctness review final checkpoint

## Fixed target
- implementation: `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529`
- parent: `563b4b34f1298b22bfff061b1c1902b6e97598b3`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review governance at run start: `7dcdf43f7a4b11df2244bb41faaed2d2e792fc12`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Master Plan manifest SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Completed review scope
- fresh exact HEAD pinning for checkpoint/plan/review/ledger and final implementation recount;
- v6 core invariants, recovery-discovery, semantic-identity, consumer-closure and CLEAN gate;
- current production `FileUtil.moveFile()` provider create/insert reservation, UNKNOWN propagation and fallback fence;
- current `PublicationRecoveryJournal` intent / UNKNOWN / exact reservation state machine;
- current Download `recoverPriorPublication()` retry/restart consumer path;
- current `TerminalPublicationRecovery.admit()` and `reconcile()` startup/re-entry consumer path;
- prior same-SHA checkpoint used only for dedup/status comparison, not source truth;
- fresh Android framework semantic re-check for `ContentResolver.insert()` and `DocumentsContract.createDocument()`;
- exact implementation GitHub combined status.

## Final provisional P0/P1/P2
- P0: 0
- P1: 0
- P2: 1 existing/open

## Independent verdict
`NOT_CLEAN` — `no material change` from the immediately preceding independent review of the same implementation SHA.

## Existing P2 / disposition
BUG-OUTPUT-01 provider-UNKNOWN convergence remains OPEN. Current source correctly records ambiguous provider creation as an UNKNOWN durable reservation, refuses ordinary clear/promotion, and fences fallback/replay. However no production convergence owner resolves that state. Download recovery throws `Download publication recovery has an unresolved provider reservation`; Terminal reconciliation treats intent/UNKNOWN as `reservationFailed` and returns without convergence, while admission remains BLOCKED while the unresolved journal/carrier survives. This is a safe duplicate-prevention fence, but it can strand the operation indefinitely and does not satisfy v6 recovery semantic-identity/discovery/consumer-closure requirements.

## Confirmed fixed invariants
- ambiguous provider completion is not collapsed to PROVEN_NOT_CREATED;
- UNKNOWN cannot be cleared by ordinary reservation cleanup;
- UNKNOWN is not promoted to exact output authority;
- provider fallback/replay is stopped after UNKNOWN;
- unresolved Terminal publication debt blocks new native execution;
- no new implementation delta exists relative to the preceding same-SHA review.

## Open candidates / questions
- no new P0/P1/P2 candidate confirmed;
- unchanged outstanding obligation: provide a production convergence owner for UNKNOWN via exact external-object discovery, deterministic rollback, provider-supported idempotency/reconciliation, or explicit durable quarantine/resolution semantics.

## Checklist evolution
No new checklist gap. Retain the existing refinement: externally allocated identity must distinguish `PROVEN_NOT_CREATED`, `CREATED_WITH_EXACT_ID`, and `UNKNOWN`; preserving UNKNOWN is a safety fence, not correctness closure, unless an actual production owner can converge it to a terminal semantic state.

## Exact upstream semantic basis
- AOSP `platform/frameworks/base` `ContentResolver.java` surfaced at `0ad99c059735d6d4a9f9fb5379c8b1b35ce01f38` / current indexed sibling `ee540c32457f6228a6eef47ec1fb6828a9d84126`: provider `RemoteException` on `insert()` may result in `null`, so null does not prove provider-side non-mutation.
- AOSP `DocumentsContract.java` surfaced revision `6d2c0e5`: public `createDocument()` catches provider-side exceptions and returns null; the internal call otherwise returns the provider-supplied URI. An ambiguous/null result therefore does not supply exact destination identity.

## Execution evidence
- GitHub combined status contexts for exact implementation SHA: none.
- Independent JVM/emulator/device execution: `NOT_VERIFIED`.
- Tests were not used as a substitute for production source-semantic review.

## Final recount
`checkpoint/pre-baseline-review` remained exactly `b5c57f00c5e6dbd9fb6dfb6fc17c0eeccbfee529` through the final recount.

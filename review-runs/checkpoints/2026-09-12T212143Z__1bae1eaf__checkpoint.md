# Independent correctness review checkpoint

- Run checkpoint: bootstrap / frozen-scope
- Exact implementation SHA: `1bae1eafea08942f11e6df30e4a13a515dda621c`
- Frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review/remediation start SHA: `6d84ec6cf65c9226624c83814d5c261ba00e07c7`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Review scope completed

- Fresh-fetched and froze the four requested branch heads.
- Re-opened v6 core invariants and mandatory execution order.
- Re-opened current production backup/restore implementation at the frozen implementation SHA; review is not diff-only.

## Provisional blocker state

- P0: 2
- P1: 1
- P2: 29
- No new blocker is established at this checkpoint.

## Confirmed/fixed invariants so far

- Restore custom-thumbnail destination publication continues to allocate destination History identity before live thumbnail binding and uses UUID-scoped final names; the old deterministic `restored_<backupHistoryId>` aliasing path is absent in the reviewed source.
- Backup publication now passes the exact `saveFile` as `sourceFiles = listOf(saveFile)`; stale sibling enumeration is not the current residual under review.

## Open candidates / questions

- Re-prove existing `BUG-BACKUP-04` residual: same-second concurrent backup invocations can calculate the same staging pathname, and the reviewed path appears to lack a single-flight or unique per-invocation staging identity.
- Recount whether any current exact-SHA execution evidence or governance state has changed since the previous final checkpoint.
- Reconfirm `BUG-BACKUP-02` source closure versus exact-SHA execution gate.

## Remaining review scope

- Trace current backup caller concurrency and final publication helper through the final filesystem mutation/effect boundary.
- Trace restore custom-thumbnail staging -> destination identity -> live publication -> DB binding -> cleanup.
- Inspect exact-SHA status/check-run/workflow evidence.
- Final canonical recount and final checkpoint.

## Exact upstream semantic basis used

- Java `java.io.File.createNewFile()` (Oracle Java SE API): atomic only for existence-check + creation of that pathname; Oracle explicitly says not to use it as a file-locking protocol.
- Kotlin stdlib `File.writeText`: replaces the content of an existing file.
- These semantics are used only with the frozen production source; tests are not a substitute for source-semantic review.

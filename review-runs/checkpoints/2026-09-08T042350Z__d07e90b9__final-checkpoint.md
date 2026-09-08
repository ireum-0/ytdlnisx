# Final hourly correctness review checkpoint

## Fixed review target
- Implementation: `d07e90b98af48496bb20b5ab5b6e35136703b0dd`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review governance: `9a8e188617ab6be1bec62527f3e3f6aa26c58e53`
- Ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` at pinned review SHA
- Upstream semantic basis: yt-dlp `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Final verdict
`NOT_CLEAN`

- P0: 0
- P1: 0
- P2: 1 existing/open (`BUG-OUTPUT-01` Terminal publication recovery closure)
- New canonical findings: 0
- Material status change vs immediately preceding review of the same implementation SHA: none

## Reviewed production path
- Terminal command/output attempt setup and exact staging ownership.
- Current-attempt output manifest and publication journal creation.
- Destination reservation and published-destination persistence around `FileUtil.moveFile`.
- Partial failure cleanup and marker-revoked recovery carrier creation.
- Startup `TerminalPublicationRecovery.reconcile()` candidate construction.
- `TerminalCacheOwnership.listRecoveryRoots()` and normal-vs-recovery authority separation.
- Application startup consumer of reconciliation/recovery discovery.
- Terminal semantic completion ordering (`dao.updateLog`/notification/`dao.delete`) and publication-journal clearing.
- Carrier-loss/process-death matrix, outer cleanup, and consumer-closure requirements under v6.

## Existing P2 remains open
The exact implementation is unchanged from the previous review and the same two source-level closure gaps remain:

1. After exact publication succeeds, the worker removes the artifact manifest and live ownership marker and recursively deletes the staging root before the later Terminal semantic completion and before clearing the publication journal. A process death in this interval leaves a durable journal whose source root/marker no longer survives. Startup `TerminalPublicationRecovery.reconcile()` rejects that journal before reconciliation because it requires the source root to still be a directory and requires a valid live ownership marker for the execution. The surviving durable carrier is therefore not discoverable through the production recovery candidate path.

2. Marker-revoked recovery carriers that are discovered by `CacheImportPlanner.collectRecovery()` are only enumerated/logged by `App.onCreate()`. There is no production recovery owner in this checkpoint that consumes the exact carrier to complete/reconcile semantic publication responsibility and retire the debt. Discovery alone does not satisfy v6 semantic recovery/consumer-closure requirements.

These are not new defect IDs; they remain the existing BUG-OUTPUT-01 Terminal P2 incomplete-remediation finding.

## Confirmed fixed/non-regressed invariants
- Ordinary Terminal cleanup no longer recursively deletes an exact partial-publication remainder.
- Recovery-only carriers are separate from ordinary import/live ownership authority.
- Destination reservation and publication observations are journaled around the actual publication boundary.
- Marker-revoked recovery roots are explicitly enumerable when their recovery carrier and staged remainder survive.

## Review retrospective
No new finding was introduced in this run, so no new checklist-gap attribution is required. The prior lesson remains applicable: physical preservation, candidate discovery, and semantic recovery convergence are three separate closure obligations. A recovery carrier must survive every supported carrier-loss order, be selected by production candidate construction, and be consumed by an owner that converges exact semantic state.

## Checklist evolution
No additional checklist change beyond the prior carrier-loss/recovery-consumer rule is proposed in this run. v6 core invariants 6, 10, 11, 16, and the mandatory recovery discovery / multi-ledger / consumer-closure sequence already cover the observed P2.

## Evidence state
- GitHub combined status contexts for exact implementation SHA: none.
- Independent JVM/emulator execution: `NOT_VERIFIED`.
- CLEAN is therefore unavailable independently even aside from the open P2.
- Final branch recount: `checkpoint/pre-baseline-review` remained at `d07e90b98af48496bb20b5ab5b6e35136703b0dd` through finalization.

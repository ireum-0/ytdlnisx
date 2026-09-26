# Manual correctness review checkpoint — cf737451 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: `5741a02f5f308c6b76cfe6e41474eb68d370aeba`

## Frozen review basis

- implementation branch: `checkpoint/pre-baseline-review`
- implementation_sha: `cf7374510decad9f308fdc3f1b528e731a3ea4f1`
- plan/remediation_sha: `2145847a1054da28398b730b9be0ca728668f967`
- review/remediation_parent_sha: `5741a02f5f308c6b76cfe6e41474eb68d370aeba`
- ledger/remediation_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- Master Plan blob: `507a97c1455793b272298e29f37b945f4cfb55d7`
- Review Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- SOURCE_ARTIFACTS blob: `bdee5f5efeee81426433c64ceb975208dc41a299`
- TASKS blob: `fae11a65fc7fe1e58bd725d64355ce4c242f398c`
- TASKS_DELTA blob: `96869f414efe6c3c33d4586eecf08cccb18375cc`
- CURRENT_STATUS blob: `0dd569290dfc209763b4f94c2041bad492641d44`

## Same-SHA progression

This is a repeated implementation SHA. The previous manual review established:
- L1 BASELINE
- L2 BASELINE
- L3 DEEP
- L4 BASELINE
- L5 BASELINE
- L6 BASELINE

Per same-SHA progression, this run promotes:
- `L1 Durability & recovery` -> **DEEP**

Selection reason:
- the open BUG-TERMINAL-03 residual is now specifically a persisted-generation/restart compatibility defect;
- L1 is directly relevant and was not yet DEEP for this SHA;
- the run must not reuse the prior L3 verdict as a substitute for fresh v6 review.

## L1 DEEP scope reviewed

Fresh source-semantic inspection covered:
- current and immediately prior Room database version;
- `TerminalViewModel.insert()`;
- `TerminalCommandIntentMaterializer`;
- row/carrier atomic staging;
- `WorkManagerHandoffRecovery.reconcileTerminalDispatches()`;
- request construction and retry identity;
- worker current-authority checks;
- `TerminalExecutionRegistry.admit()`;
- `TerminalExecutionRecovery` admission, process-death reconciliation, native generation binding, terminal convergence;
- `TerminalPublicationRecovery`;
- application startup ordering for execution/publication and WorkManager handoff reconciliation;
- current configured-provider materialization tests;
- exact GitHub status/check-run evidence.

## Stronger persisted-generation proof

The immediately prior implementation:
`b6512ae21a08f76d161f537074ede7a0ffcc045c`

and the current implementation:
`cf7374510decad9f308fdc3f1b528e731a3ea4f1`

both declare Room database version **63**.

Therefore the BUG-TERMINAL-03 legacy-state path does not depend on a speculative cross-schema migration. A nonterminal Terminal row/carrier created on `b6512ae2` can remain in the same Room schema when the application moves to `cf737451`.

No current migration rewrites or removes `terminalDownloads` provider identity because the current change does not change the Room schema at all.

This strengthens the previous Module-F blocker:
- the old row/carrier can survive directly;
- the old representation contains only the user command;
- current startup reconciliation accepts/reconstructs that command unchanged;
- current planner later reads current `command_path`;
- a prior T1 created while A was configured can therefore execute/publish under B after update/restart.

## Current-format durability/recovery findings

For a new current-format Terminal:

1. configured ProviderTree materialization occurs before row insertion;
2. the materialized row and TERMINAL_DISPATCH carrier are committed inside the same Room transaction;
3. if row/carrier persistence does not complete, no partial row-only dispatch owner is established;
4. after row+carrier commit but before enqueue, startup `reconcileTerminalDispatches()` can rediscover and enqueue the exact carrier;
5. enqueue/request retry advances request identity while preserving the carrier command/fingerprint;
6. worker admission rechecks row/carrier/request identity before execution;
7. `TerminalExecutionRegistry.admit()` persists an execution witness before native work;
8. process death with an ADMITTED witness is reconciled without replaying native work when the exact native generation is absent;
9. NATIVE_STARTED recovery requires exact generation/quiescence evidence;
10. NATIVE_FINISHED without semantic commit is terminalized rather than replayed;
11. COMMITTING/COMMITTED recovery converges row deletion and witness retirement;
12. publication recovery remains separately durable through publication journal/recovery carriers.

No new current-format L1 defect was confirmed in this pass.

## Candidate checked: configuration snapshot before Room admission

`TerminalViewModel.insert()` reads/materializes the configured provider before entering `RestoreMutationAdmission.withOrdinaryMutation`.

This was reviewed as a possible stale-configuration race.

Disposition: **not a confirmed defect**.

Reason:
- the provider snapshot is the command's intended authority at run initiation;
- preserving that snapshot across a later ordinary settings change is consistent with the new exact-intent contract;
- if a Restore operation becomes active before the durable insert reaches ordinary-mutation admission, the admission check fails closed rather than committing through the active Restore;
- no path was established where a Restore-owned newer provider is silently adopted as old intent authority.

This candidate is therefore rejected rather than promoted to a new finding.

## Provisional verdict

- BUG-TERMINAL-03: remains OPEN P2
- new confirmed P0/P1/P2 IDs: 0
- material status transition: none
- exact-final-SHA CI/check evidence: still absent
- provisional overall verdict: NOT_CLEAN

## Lens coverage current SHA

- L1: **DEEP**
- L2: BASELINE
- L3: DEEP
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

primary_deep_lens: L1

## Remaining scope

- re-run the remaining L2/L4/L5/L6 v6 baseline gates on this same SHA rather than inheriting the previous verdict;
- revalidate semantic-contract delta and Module B/C/F/H closure;
- refresh terminal/cross-attempt matrices;
- fresh-fetch implementation/review heads before FINAL write;
- append and verify a FINAL checkpoint.

# Manual correctness review checkpoint — cf737451 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: 4bd5f67e5281f65127d7cab84a9b164cfd57fd17

## Frozen review basis

- implementation branch: `checkpoint/pre-baseline-review`
- implementation_sha: `cf7374510decad9f308fdc3f1b528e731a3ea4f1`
- plan/remediation_sha: `2145847a1054da28398b730b9be0ca728668f967`
- review/remediation_parent_sha: `4bd5f67e5281f65127d7cab84a9b164cfd57fd17`
- ledger/remediation_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- Master Plan blob: `507a97c1455793b272298e29f37b945f4cfb55d7`
- Master Plan SHA-256: `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`
- Review Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Review Checklist v6 SHA-256: `61f4c1f9c278601a773058d28682ca36c8f5465e67445458608dace8d5ca8c95`
- SOURCE_ARTIFACTS blob: `bdee5f5efeee81426433c64ceb975208dc41a299`
- TASKS blob: `fae11a65fc7fe1e58bd725d64355ce4c242f398c`
- TASKS_DELTA blob: `96869f414efe6c3c33d4586eecf08cccb18375cc`
- CURRENT_STATUS blob: `0dd569290dfc209763b4f94c2041bad492641d44`

## Reviewed scope so far

Current candidate commit `cf737451` was reviewed against its parent `b6512ae2` and the prior independent hold checkpoint.

Production paths opened end-to-end so far:

- `TerminalViewModel.insert()`
- `TerminalCommandIntentMaterializer`
- `TerminalProviderDestinationOption`
- `TerminalDestinationAuthority`
- `TerminalCommandPlanner` / `TerminalCommandPlanFactory`
- `WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction()`
- `WorkManagerHandoffRecovery.reconcileTerminalDispatches()`
- `WorkManagerHandoffRecovery.buildRequest()`
- `WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest()`
- `TerminalDownloadWorker.doWorkInternal()`
- startup call site in `App`
- provider publication handoff through `FileUtil.moveFile(... destDir = plan.downloadLocation ...)`
- current Terminal SAF/materializer regression tests

## Confirmed source-semantic improvement

For newly inserted Terminal rows, configured ProviderTree authority is now materialized into the exact durable `TerminalItem.command` before the row/carrier transaction. The same materialized command is staged into the TERMINAL_DISPATCH carrier and fingerprinted. A later configured A -> B change therefore cannot redirect a newly-created T1 whose durable command already contains A.

Authored native `-P/--paths` precedence and explicit Folder-picked provider metadata remain more specific than the configured provider default. Provider destinations continue to stage into app-owned cache rather than being reconstructed as native raw paths.

## Provisional blocker — same-root incomplete closure

**P2 / BUG-TERMINAL-03 remains OPEN provisionally.**

v6 Module F (Persisted schema-generation compatibility) is triggered because this remediation strengthens exact durable provider identity over already-persisted Terminal state.

Concrete supported-upgrade/restart path:

1. On the immediately prior implementation, create durable Terminal T1 while configured provider is A.
2. The old durable row/carrier contain only the user command; configured A is absent from `TerminalItem.command` and the carrier fingerprint.
3. T1 remains nonterminal across process death/app update.
4. Run `cf737451`.
5. Startup `WorkManagerHandoffRecovery.reconcile()` calls `reconcileTerminalDispatches()`.
6. If the old row and old carrier still match each other, `ownerMatches` is true and the legacy command is retained unchanged. If the carrier needs reconstruction, the same legacy `terminal.command` is staged unchanged.
7. Worker admission accepts that legacy row/carrier because exact row/carrier command/fingerprint equality still holds.
8. `TerminalCommandPlanFactory.create()` then reads the current `command_path` preference.
9. With no provider metadata in the legacy command, current configured provider B becomes the execution/publication destination.
10. The pre-update durable Terminal intent can therefore still be redirected A -> B.

This is the same violated invariant as the prior hold, now surviving through a supported persisted-state generation rather than through newly-created rows.

Current regression tests create rows through the new `TerminalViewModel.insert()`; they do not seed an old nonterminal Terminal row/carrier that lacks provider metadata and trace startup/retry/recovery after upgrade.

## v6 semantic-contract closure

Trigger: YES.

Changed boundary:
- configured ProviderTree authority changed from late mutable planning input to intended durable Terminal semantic identity.

New direct consumers reviewed:
- Terminal row
- TERMINAL_DISPATCH carrier
- command fingerprint
- WorkRequest input
- worker dispatch admission
- planner provider precedence
- provider publication destination

Residual consumer/effect gap:
- startup/recovery handling of old persisted Terminal rows/carriers that predate the stronger identity.

Module B — External scheduler handoff: reviewed baseline; exact carrier/request identity remains bound to row command.
Module C — External authority projection: reviewed baseline; ProviderTree remains exact content URI and never native raw authority.
Module F — Persisted schema-generation compatibility: **GAP / blocker**.
Module H — Persisted executable configuration fan-out: partial; current configured provider read is safe for newly materialized commands but remains authoritative for legacy commands lacking provider metadata.

## Lens coverage current SHA

lens_coverage_current_sha:
- L1 Durability & recovery: BASELINE
- L2 Identity & provenance: BASELINE
- L3 Concurrency & authority: DEEP (in progress)
- L4 Destructive ownership: BASELINE
- L5 Platform contract closure: BASELINE
- L6 Cross-feature semantic propagation: BASELINE

primary_deep_lens: L3
lens_selection_reason: current change is an authority-freeze/remediation across durable dispatch, mutable configuration, restart, and scheduler admission; cross-attempt A->B reinterpretation is the highest-risk same-root boundary.

## Lens effectiveness — provisional, not cumulative

- L1: confirmed new row+carrier are atomic; legacy persisted-state recovery remains open through the same blocker.
- L2: confirmed new provider URI participates in exact command/fingerprint provenance; legacy row lacks that identity dimension.
- L3: confirmed new A->B post-insert mutation is fenced; discovered persisted-generation A->B authority reinterpretation.
- L4: no new direct provider/raw authority confusion found so far; publication stays staged and journaled.
- L5: no independent CI/check-run evidence exists for exact SHA; GitHub reports zero check runs/statuses.
- L6: direct current consumers reviewed; persisted recovery consumer is the material propagation miss.

new_confirmed_findings: 0
existing_finding_status_changes: 0
confirmed_residuals_or_subcases:
- BUG-TERMINAL-03 persisted-generation / pre-materializer nonterminal Terminal residual
rejected_candidates_with_proof:
- newly-created T1 redirected by later A->B preference change: rejected; provider A is now in durable command/carrier/fingerprint and planner gives command metadata precedence.
not_verified_candidates:
- exact independent execution evidence for final SHA remains NOT_VERIFIED
checklist_gaps_triggered: 0

## Remaining scope

- finish terminal/cross-attempt matrix for retry, process death, startup reconcile, stale/new owner, and cancellation;
- confirm no alternate production creation/migration path materializes old Terminal state;
- finish L1-L6 baseline recount and conditional modules;
- classify verification evidence for exact final SHA;
- write and verify one FINAL append-only checkpoint.

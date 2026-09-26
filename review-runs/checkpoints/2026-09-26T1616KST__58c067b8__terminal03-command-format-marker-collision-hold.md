# BUG-TERMINAL-03 — persisted-generation command-format marker collision hold

checkpoint_kind: SECTION6_COMPLETION_REVIEW
review_parent_sha: `afe45082627e4f5f90b45a19efe412789e847ee2`
reviewed_implementation_sha: `58c067b8a7a82320e9a369b77c642b025579d8c8`
reviewed_parent_sha: `cf7374510decad9f308fdc3f1b528e731a3ea4f1`
original_clean_review_basis: `74f57e695db30b701ad429af311c39a763bfe086`
protocol_blob: `76cfd81cc7d28e01d669bcca24beaf4ecf945036`

## Independent verdict

**NOT_CLEAN — BUG-TERMINAL-03 remains OPEN P2.**

The implementation at `58c067b8...` materially improves the persisted-generation path:
- an ordinary pre-materializer row with no Terminal-owned metadata is refused;
- an outstanding ambiguous TERMINAL_DISPATCH carrier is superseded;
- a missing carrier is not reconstructed into runnable authority;
- retry/current-authority and worker admission re-check the durable-authority classifier;
- legacy rows with exact provider metadata or a valid authored native home remain self-bound;
- current-format provider metadata remains stripped before yt-dlp/native parsing and exact provider A remains bound across later preference B;
- an already-admitted older execution witness is converged by TerminalExecutionRecovery without replanning through current command_path.

However, the new command-format marker is stored inside the same user-controlled durable command string that existed before the marker had any application meaning. It is therefore not, by itself, proof that the row was created by the new materializer.

A supported pre-marker row can carry the exact future marker bytes and is then misclassified as current format.

This is a same-root persisted-generation residual. New finding-ID delta: **0**.

## Confirmed residual

Exact immediately-prior source `b6512ae2...`:

`TerminalViewModel.insert()` stores the supplied `TerminalItem.command` unchanged and stages the exact same string into TERMINAL_DISPATCH. No command-format option exists in that implementation and there is no reserved-token admission check at insertion.

Concrete sequence:

1. Run `b6512ae21a08f76d161f537074ede7a0ffcc045c` with configured ProviderTree A.
2. A user command contains the literal token:
   `--ytdlnisx-terminal-command-format=1`
   but contains neither Terminal provider metadata nor an authored native home `-P/--paths`.
3. The row and matching generation-1 TERMINAL_DISPATCH carrier persist that command unchanged.
4. The task remains nonterminal before provider planning/native execution.
5. Update to `58c067b8a7a82320e9a369b77c642b025579d8c8`.
6. Current `command_path` is provider B.
7. `TerminalCommandMetadata.strip()` recognizes the historical literal as `CURRENT_FORMAT=1`.
8. `classifyDurable()` returns `CurrentFormat` before requiring any independently generated persisted-generation proof.
9. `reconcileTerminalDispatches()` therefore does not enter the ambiguous-row refusal. The old carrier still satisfies the generation-1 equality checks.
10. `isCurrentTerminalDispatchAuthority()` and `isCurrentTerminalDispatchRequest()` likewise accept the command because the same marker causes `hasDurableTerminalOutputAuthority()` to return true.
11. `TerminalCommandPlanner.create()` strips the format marker. There is still no bound provider metadata and no authored path.
12. The planner therefore falls back to the current environment destination B.
13. The pre-marker T1 can again acquire B and publish there.

The marker solves the common old representation but does not authenticate the persisted generation that wrote it. The exact bytes it treats as generation evidence were legal durable user-command bytes before that evidence contract existed.

## Why current regressions do not close this case

The new persisted-generation A-I suite seeds ordinary ambiguous legacy commands without the exact future marker token.

`TerminalCommandMetadataTest` proves:
- marker `=1` is trusted as current format;
- an unknown marker such as `=99` is not trusted.

It does not prove that a generation-1 row/carrier containing the exact `=1` bytes is rejected as historical state.

The new-row tests necessarily create marker `=1` through the new materializer and therefore cannot distinguish those rows from a historical user command containing the same bytes.

## Minimal correction boundary

Stay within BUG-TERMINAL-03 persisted-generation identity.

The current-format proof needs a durable dimension that a pre-marker row/carrier cannot already possess merely because of its command text.

A narrow no-Room-migration option is already available in the existing TERMINAL_DISPATCH carrier:
`sourceConfigurationGeneration`.

One acceptable design is:
- stage newly materialized/current-format Terminal dispatches with a new Terminal dispatch format generation, e.g. `2L`;
- require both the current command-format marker and that durable carrier generation before treating a marker-only raw/default command as current-format complete;
- keep generation-1 legacy commands runnable only when they are independently `SelfBound` by exact provider metadata or valid authored native home;
- treat generation-1 marker-only commands as legacy/ambiguous even if their command text happens to contain `--ytdlnisx-terminal-command-format=1`;
- do not reconstruct a runnable current-generation carrier from a marker-only row when no independently current carrier exists;
- preserve the exact request/fingerprint/cancellation/recovery contract.

An equivalent independently durable creation-generation proof is acceptable.

Do not solve this by:
- trusting marker position/order as proof of origin;
- guessing from current preference;
- deleting the old row to make recovery green;
- weakening carrier/request/fingerprint checks;
- blocking all new raw/default commands;
- broadening into BUG-BACKUP-11, BUG-CANCEL-02, notification identity, or unrelated Terminal publication work.

## Required regressions

At minimum add a production-wiring persisted-state case that seeds the immediately-prior representation directly:

1. **Historical exact-marker collision**
   - generation-1 Terminal row/carrier;
   - command literally contains `--ytdlnisx-terminal-command-format=1`;
   - no explicit provider metadata;
   - no authored native home;
   - current configured provider B;
   - expected: non-runnable, no reconstructed runnable owner, no B publication authority.

2. **Historical marker + explicit provider C**
   - generation-1 carrier;
   - exact marker bytes plus valid provider C metadata;
   - current preference B;
   - expected: stays self-bound to C if otherwise valid.

3. **Historical marker + authored native path**
   - generation-1 carrier;
   - exact marker bytes plus valid authored native home;
   - current provider B;
   - expected: authored native authority remains self-bound.

4. **Genuine current-format raw/default**
   - new writer/current dispatch generation;
   - marker present, no provider metadata;
   - expected: remains runnable under the accepted current raw/default behavior.

5. **Genuine current-format provider A**
   - new writer/current dispatch generation;
   - marker + provider A;
   - later preference B;
   - expected: remains A across restart/retry.

6. **Missing-carrier historical marker row**
   - marker-only row whose durable current-generation proof is absent;
   - expected: no runnable carrier is synthesized from command text alone.

## Other current review state

Forward review-only advancement through `review/remediation@afe45082627e4f5f90b45a19efe412789e847ee2` was reconciled under protocol §3.5.

Those reviews do not close this exact implementation result. They establish additional independent state that remains outside this same-root follow-up:
- provisional `BUG-BACKUP-11` is a distinct confirmed P2 concerning backup/restore reconstruction of provider locator without destination-side authorization;
- `BUG-CANCEL-02` has a confirmed Terminal publication/cancellation subcase under the existing finding root;
- neither is fixed by `58c067b8...`;
- both keep the global remediation state NOT_CLEAN even after a future BUG-TERMINAL-03 closure.

Canonical-count reconciliation:
- prior private handoff: P0=0, P1=0, P2=17;
- L6 DEEP independently confirmed one distinct new P2, provisional BUG-BACKUP-11;
- later reviews retain it and no later review rejects it;
- BUG-CANCEL-02 Terminal is a same-root expansion, count delta 0;
- this marker collision is same BUG-TERMINAL-03, count delta 0.

Therefore current canonical totals are:
- P0 = 0
- P1 = 0
- P2 = 18

`CLEAN_REVIEW_BASIS` remains `74f57e695db30b701ad429af311c39a763bfe086`.

## Verification evidence

Independently verified from GitHub:
- implementation remote HEAD = `58c067b8a7a82320e9a369b77c642b025579d8c8`;
- exact parent = `cf7374510decad9f308fdc3f1b528e731a3ea4f1`;
- compare cf737451 -> 58c067b8 = ahead 1 / behind 0;
- exact changed production and regression source inspected;
- exact-SHA GitHub combined statuses: none;
- exact-SHA GitHub workflow runs: none;
- exact-SHA GitHub check runs: 0.

Implementation-agent reported exact-final-SHA evidence at `58c067b8...`:
- TerminalPersistedGenerationAuthorityProductionWiringTest 9/9;
- TerminalSafDestinationAuthorityProductionWiringTest 13/13;
- TerminalDispatchHandoffProductionWiringTest 8/8;
- TerminalExecutionProductionWiringTest 2/2;
- affected connected total 32/32;
- TerminalCommandMetadataTest 9/9;
- TerminalCommandIntentMaterializerTest 8/8;
- TerminalCommandPlanTest 20/20;
- recovery/output JVM total 24/24;
- JVM total 61/61;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

Those execution claims are implementation-agent evidence only. They do not cover the historical exact-marker collision above and are not independent reviewer execution.

## Disposition

`58c067b8...` is **SOURCE-IMPROVED / SAME-ROOT RESIDUAL CONFIRMED**.

Do not advance CLEAN basis.
Do not close BUG-TERMINAL-03.
Authorize only the narrow persisted-generation marker-proof follow-up described above.

INDEPENDENT EXECUTION: NOT EXECUTED

# 3ffe5b35 — BUG-TERMINAL-11 provider-metadata strictness hold

checkpoint_kind: SECTION6_COMPLETION_REVIEW
review_parent_sha: `dd51619fa1d0c1dfd788878b427af2b92e5d758f`
reviewed_implementation_sha: `3ffe5b3545dc3616616a9d1e8b3758b93147ddd0`
reviewed_parent_sha: `deabc91ff37a06e861813a8b471ba356b4c6906f`
protocol_blob: `c6cac5f3d7ad10dddb68343f6684e95ae915366c`
governing_checklist: `REVIEW_CHECKLIST_V7_OPERATIONAL.md`
governing_checklist_blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`

## Independent verdict

**NOT_CLEAN — BUG-TERMINAL-11 remains OPEN P2.**

The original sibling-abort path is source-fixed at `3ffe5b35...`:
recognized malformed durable metadata becomes a typed per-row non-runnable
classification; one malformed historical row no longer aborts the whole
Terminal reconciliation transaction; its exact carrier is superseded, a missing
carrier is not synthesized, row/command/log are preserved, valid siblings keep
converging, and worker admission fails closed before planner/native. The catch is
narrowly limited to `TerminalCommandMetadataException`.

BUG-TERMINAL-03 generation-2 provenance remains preserved.

A same-root provider-metadata strictness residual remains.

## Confirmed residual

`TerminalProviderDestinationOption.PATTERN` matches only occurrences that have
a captured value. `extract()` returns `Extracted(command, null)` when there
are zero matches and has no bare/empty-token detector equivalent to
`TerminalCommandMetadata.BARE_TOKEN`.

Therefore current input containing either:

- `--ytdlnisx-terminal-provider-destination`
- `--ytdlnisx-terminal-provider-destination=`

can be treated as though no Terminal-owned provider metadata exists.

Concrete path:

1. User submits a normal command plus one of the bare/empty provider tokens.
2. `TerminalViewModel.insert()` calls
   `TerminalCommandIntentMaterializer.materialize()`.
3. `TerminalCommandMetadata.strip()` delegates provider parsing to
   `TerminalProviderDestinationOption.extract()`.
4. The malformed provider token produces no regex match, so the command is
   returned unchanged with `providerTreeUri=null`.
5. The materializer stamps the current command-format marker.
6. Row + TERMINAL_DISPATCH carrier commit at current format generation 2.
7. Durable classification later again fails to recognize the provider token and
   returns `CurrentFormat` because marker + generation 2 are valid.
8. Worker admission can therefore accept the request.
9. Planner stripping again leaves the unmatched app-owned provider token in the
   command, so the private Terminal option can reach yt-dlp/native config instead
   of being rejected before durability.

With a configured provider default, one valid provider option can be prepended
while the malformed unmatched token still remains in the command.

This directly contradicts the accepted correction contract that current
composition remain strict and that malformed Terminal-owned metadata never
become durable or reach planner/native.

Existing tests cover bare/empty/repeated command-format metadata and repeated
provider metadata, but not bare/empty provider metadata.

New finding-ID delta: **0**. This is same BUG-TERMINAL-11 root.

## Governing v7 review

L1 Durability & recovery — BASELINE:
- historical recognized-malformed sibling recovery is fixed;
- residual allows malformed current input to become durable generation-2 state.

L2 Identity & provenance — BASELINE:
- generation provenance remains exact;
- residual is incomplete recognition of the Terminal-owned provider namespace.

L3 Concurrency & authority — BASELINE:
- no new race; row/carrier transaction, retry generation, request identity and
  supersession remain exact.

L4 Destructive ownership — BASELINE:
- no new destructive mutation; residual occurs before publication.

L5 Platform contract closure — BASELINE:
- no new Android platform boundary; BUG-BACKUP-11 grant provenance remains
  separately owned.

L6 Cross-feature semantic propagation — **DEEP**:
- primary DEEP lens by R1/R2/R3 because the changed Terminal metadata contract
  is shared by composition, durability, recovery, worker admission and planner;
- command-format and provider-metadata parsers do not expose equivalent malformed
  token coverage;
- Module H / contract-outward closure therefore remains open.

Lens coverage:
- L1 BASELINE
- L2 BASELINE
- L3 BASELINE
- L4 BASELINE
- L5 BASELINE
- L6 DEEP

Next not-yet-DEEP lens: **L2**, because the remaining subcase is exact
Terminal-owned provider identity/representation.

## Trigger map

Module B — TRIGGERED:
scheduler identity remains exact, but exact scheduling cannot repair a malformed
semantic command already admitted by the producer.

Module C — TRIGGERED / OPEN:
valid provider URI projection remains exact; bare/empty provider metadata is not
recognized as provider metadata at all. Do not broaden into BUG-BACKUP-11 grant
liveness.

Module F — TRIGGERED:
historical malformed-format/repeated-provider sibling cells and BUG-TERMINAL-03
generation collision are source-closed. Current-producer bare/empty provider
coverage remains part of this same-root follow-up.

Module H — TRIGGERED / FAIL:
producer -> row/carrier -> recovery -> worker -> planner/native fan-out is not
closed because an app-owned malformed provider token can survive all the way to
native config.

v7 thread-affinity rule — not triggered.
v7 invalid-transformation propagation — not triggered.

## Semantic-contract closure

Changed boundary:
Terminal-owned metadata parsing + durable classification.

Closed cells:
- malformed/repeated format;
- repeated provider;
- historical sibling continuation;
- outstanding/missing carrier;
- restart;
- recognized-malformed worker rejection.

Open cell:
- bare/empty provider option current input.

Final recount: **FAIL / same-root residual**.

## Verification evidence

Remote implementation HEAD:
`3ffe5b3545dc3616616a9d1e8b3758b93147ddd0`

Parent:
`deabc91ff37a06e861813a8b471ba356b4c6906f`

Compare: ahead 1 / behind 0.

Changed production:
- TerminalCommandMetadata.kt
- TerminalProviderDestinationOption.kt
- WorkManagerHandoffRecovery.kt

Changed tests:
- TerminalCommandMetadataTest.kt
- TerminalPersistedGenerationAuthorityProductionWiringTest.kt

GitHub exact-SHA status/check/workflow evidence: none.

Implementation-agent evidence:
- connected 74/74 across reported clean-process partitions;
- JVM 65/65;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

No reported executed test covers bare/empty provider metadata.

INDEPENDENT EXECUTION: NOT EXECUTED

## Disposition

- BUG-TERMINAL-03: remains FIXED-CLOSED
- BUG-TERMINAL-11: SOURCE-IMPROVED / SAME-ROOT PROVIDER-METADATA STRICTNESS RESIDUAL
- overall: NOT_CLEAN
- canonical totals remain P0=0 / P1=0 / P2=19
- CLEAN_REVIEW_BASIS remains
  `74f57e695db30b701ad429af311c39a763bfe086`

Authorize only a narrow BUG-TERMINAL-11 provider-metadata strictness follow-up.

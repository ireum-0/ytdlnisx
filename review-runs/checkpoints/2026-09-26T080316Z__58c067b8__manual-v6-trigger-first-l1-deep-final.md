# Manual correctness review — 58c067b8 — FINAL

checkpoint_kind: FINAL
run_mode: manual_trigger_3
review_parent_sha: `3017280838bf995abff7ea9288e90a64c2d43388`

## Independent verdict

scope_verdict: **NOT_CLEAN**
overall_remediation_gate: **NOT_CLEAN**

Pinned implementation:
`checkpoint/pre-baseline-review@58c067b8a7a82320e9a369b77c642b025579d8c8`

Implementation parent:
`cf7374510decad9f308fdc3f1b528e731a3ea4f1`

The implementation branch remained on the pinned SHA through FINAL.

Material source change from the prior full manual-review basis:
**YES** — one descendant implementation commit, `fix: refuse persisted Terminal commands with no durable destination authority`.

This SHA had already received targeted current-source review and a later v7-shadow review. Under Review Lens Selection Policy v1, the shadow's governing-v6 lens work established L2 DEEP for this SHA. This run therefore re-ran the full governing review and promoted **L1 Durability & recovery -> DEEP**.

Frozen governance:
- `plan/remediation@2145847a1054da28398b730b9be0ca728668f967`
- `review/remediation` parent for FINAL: `3017280838bf995abff7ea9288e90a64c2d43388`
- `ledger/remediation@822ffe6a9cd45b951550fcb559557f0cf0798610`
- Master Plan blob: `507a97c1455793b272298e29f37b945f4cfb55d7`
- Review Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Review Lens Selection Policy v1 blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
- SOURCE_ARTIFACTS blob: `bdee5f5efeee81426433c64ceb975208dc41a299`
- TASKS blob: `fae11a65fc7fe1e58bd725d64355ce4c242f398c`
- TASKS_DELTA blob: `96869f414efe6c3c33d4586eecf08cccb18375cc`
- CURRENT_STATUS blob: `0dd569290dfc209763b4f94c2041bad492641d44`

Checklist v7 is not governing. Its promotion gate remains HOLD pending a prospective shadow on a later newly observed implementation SHA.

## Findings

### P2 — BUG-TERMINAL-03 remains OPEN

58c materially improves the common persisted-generation case:
- new Terminal commands are stamped with `--ytdlnisx-terminal-command-format=1`;
- current provider destinations are materialized before row/carrier persistence;
- ambiguous ordinary legacy rows are refused;
- ambiguous outstanding carriers are superseded;
- worker admission repeats exact durable-authority checks;
- current provider-bound rows remain bound to their exact provider across later preference changes.

The remediation still does not independently prove which persisted generation wrote the format marker.

#### Confirmed same-root residual — historical exact-marker collision

Immediately prior supported writer `b6512ae2...`:
- stored `TerminalItem.command` unchanged;
- staged that same command into TERMINAL_DISPATCH;
- used `sourceConfigurationGeneration = 1L`;
- had no reserved semantic for the future command-format token.

Current 58c:
- still stages TERMINAL_DISPATCH with `sourceConfigurationGeneration = 1L`;
- `TerminalCommandMetadata.classifyDurable()` returns `CurrentFormat` when the command contains marker `=1`;
- that classification occurs without an independently durable proof that the 58c writer created the row.

Concrete supported path:

1. On `b6512ae2...`, persist a nonterminal Terminal row and matching generation-1 carrier.
2. User command literally contains `--ytdlnisx-terminal-command-format=1`.
3. Command has no Terminal provider metadata and no authored native home destination.
4. Upgrade to 58c. Room DB remains version 63, so this state survives without a migration boundary that can disambiguate it.
5. Current configured `command_path` is B.
6. `classifyDurable()` recognizes the historical bytes as `CurrentFormat`.
7. Existing generation-1 carrier can satisfy current row/carrier/fingerprint/request checks.
8. If the carrier is missing, startup reconciliation can reconstruct another generation-1 carrier because the marker causes the row to be treated as having durable authority.
9. Worker repeats the same false generation classification and admits the request.
10. Planner strips the Terminal-owned marker.
11. With no bound provider metadata and no authored native home, planning falls back to the current configured destination B.
12. Old T1 can therefore execute/publish under authority not durably bound to its original persisted intent.

This is not a new finding ID. It is a persisted-generation/provenance residual under BUG-TERMINAL-03.

#### Why current tests do not close the collision

`TerminalPersistedGenerationAuthorityProductionWiringTest` correctly seeds generation-1 legacy state directly and contains generation-1 carriers.

At the pinned SHA:
- occurrences of `sourceConfigurationGeneration = 1L`: 3
- occurrences of exact historical collision token `--ytdlnisx-terminal-command-format=1`: 0

Current new-row tests use the new materializer, so they cannot distinguish a genuinely current marker from identical bytes legally persisted by the old writer.

#### Required correction

Use a durable generation/provenance dimension that supported pre-marker rows cannot already possess by command text alone.

A narrow existing carrier-based option:
- assign genuinely current-format Terminal dispatches a new `sourceConfigurationGeneration`, e.g. 2;
- require current marker + independently current carrier generation for marker-only raw/default commands;
- keep generation-1 commands runnable only when independently SelfBound by exact provider metadata or valid authored native home;
- do not synthesize a runnable current-generation carrier from a marker-only legacy row when independent current-generation evidence is absent.

Equivalent independently durable generation proof is acceptable.

### P2 — provisional BUG-BACKUP-11 remains confirmed

No status change.

Relevant production blobs are identical to the prior cf737 review and were re-read at 58c.

Current source still:
- treats `cache_path` as destination-local/non-portable;
- does not classify `command_path` as non-portable;
- serializes portable SharedPreferences values including command_path;
- normal Folder configuration obtains a persistable SAF grant before storing command_path;
- merge/reset restore writes imported command_path as a String without re-performing or proving equivalent destination-side grant authority;
- later Terminal materialization can consume that imported locator as executable provider configuration.

58c does not address this distinct backup/restore authority root.

### P2 root expansion — BUG-CANCEL-02 Terminal subcase remains confirmed

No status/count change.

Relevant cancellation, Terminal worker-publication and cache-maintenance production blobs are unchanged from cf737 and were re-read at 58c.

Current source still permits:
- dispatch supersession + native execution convergence to authorize Terminal row deletion;
- `workManagerCancellationAcknowledged` is not part of `rowDeletionAuthorized`;
- `NATIVE_FINISHED` execution can converge STOPPED without proving post-native provider publication has quiesced;
- active Terminal execution token can be removed;
- Terminal cache live ownership depends on that process-local token;
- cache-clear UI can also lose the Terminal-row active-count signal.

The stale worker has no durable-cancellation recheck between `markNativeFinished()` and provider publication.

Thus durable cancellation can still win while post-native publication or cache ownership remains live.

### New confirmed P0/P1/P2 finding IDs

**0**

## Trigger map

### Module F — Persisted schema-generation compatibility
**TRIGGERED / OPEN / FAIL**

Reason:
58c introduces stronger durable-authority classification over supported pre-change Terminal row/carrier state.

Result:
exact historical marker bytes collide with the new generation marker. Supported old generation-1 state is not distinguishable from marker-only current state.

### Module B — External scheduler handoff
**TRIGGERED / REVIEWED / no separate new root**

Current mechanical handoff remains exact:
- row and carrier staged transactionally for new writes;
- exact carrier command/fingerprint is copied into WorkRequest;
- request ID / generation ID / boundary / fingerprint are checked at worker admission;
- startup reconciliation and retry preserve exact carrier/request identity;
- cancellation supersedes the exact outstanding carrier.

But exact handoff cannot repair false persisted-generation provenance. A legacy marker-collision row/carrier can be exact and still semantically unauthorized.

### Module C — External representation / authority projection
**TRIGGERED / OPEN**

BUG-TERMINAL-03:
marker bytes are treated as generation proof and can allow current destination authority to be projected onto old intent.

BUG-BACKUP-11:
restored provider locator still does not prove destination-side SAF authorization.

### Module H — Persisted executable configuration fan-out
**TRIGGERED / OPEN**

Production producers/consumers reviewed:
- immediately prior plain Terminal writer;
- current materializing Terminal writer;
- Terminal Room row;
- TERMINAL_DISPATCH carrier;
- WorkRequest input;
- startup reconciliation;
- worker request admission;
- Terminal-owned metadata stripping;
- command planner;
- configured command_path;
- provider publication;
- backup/restore writer of command_path.

No independent new H finding beyond BUG-TERMINAL-03 and BUG-BACKUP-11.

### Module A — Platform capability/admission
**TRIGGERED by existing BUG-BACKUP-11 / OPEN**

Normal SAF folder selection establishes persistable provider permission; backup/restore does not reconstruct/prove the same authority.

No new foreground-service/manifest blocker was confirmed by the 58c delta.

### Module I — Maintenance vs live-owner namespace
**TRIGGERED by existing BUG-CANCEL-02 Terminal subcase / OPEN**

Terminal cache liveness can disappear after cancellation before post-native publication is proven quiescent.

## Full governing review

### L1 — Durability & recovery — **DEEP**

Positive closure for genuine current writes:
- configured provider authority is materialized before durable insertion;
- Terminal row + TERMINAL_DISPATCH are one Room transaction;
- carrier carries exact durable command and fingerprint;
- WorkRequest carries exact carrier fields;
- execution witness is persisted before native work;
- publication journal owns exact source/destination recovery once publication begins;
- terminal publication recovery does not replay from mutable command_path.

Persisted-generation failure:
- DB schema remains version 63;
- old row/carrier remains directly supported durable state;
- both old and new Terminal carriers use sourceConfigurationGeneration=1;
- new command-format bytes are not independent generation proof;
- startup reconstruction can recreate a runnable generation-1 owner from a historical marker-only row;
- execution recovery record contains subjectId/executionToken/processId/native generation, not original Terminal command-format generation or original destination authority;
- once false generation admission succeeds, downstream execution recovery cannot reconstruct the missing provenance.

L1 result:
**FAIL under existing BUG-TERMINAL-03.**

Primary DEEP selection:
- R1: L1 directly owns remaining durable generation/recovery compatibility among not-yet-DEEP lenses.
- R2: Module F remains open and is the central unresolved triggered module.
- R3-R5 not needed.

### L2 — Identity & provenance — DEEP revalidated

Prior same-SHA L2 DEEP remains valid and was independently re-read.

Exact bytes are not sufficient provenance when the old writer could legally persist the same bytes.

Current row/carrier/request/fingerprint equality is exact but equality is over a falsely authenticated generation.

No new L2 finding ID.

### L3 — Concurrency & authority — BASELINE

Current-format provider A remains bound against later configured provider B.

Marker-collision legacy intent can nevertheless be reauthorized from later mutable configuration because the generation check is false.

This is same BUG-TERMINAL-03 root, not a separate race finding.

Existing cancellation-vs-publication authority race remains under BUG-CANCEL-02.

### L4 — Destructive ownership — BASELINE

58c does not modify Terminal publication/destructive ownership code.

Current exact publication journaling and per-source cleanup remain strong on ordinary paths.

Existing cancellation/publication/cache-maintenance gap remains open under BUG-CANCEL-02.

No new destructive root.

### L5 — Platform contract closure — BASELINE

No platform behavior is required to reproduce the marker collision.

BUG-BACKUP-11 SAF locator/grant distinction remains open.

Exact-SHA platform/runtime execution was not independently performed.

### L6 — Cross-feature semantic propagation — BASELINE

58c marker semantics were traced end-to-end:
writer -> Room row -> scheduler carrier -> WorkRequest -> startup reconciliation -> worker admission -> metadata stripping -> planner -> publication.

The exact historical marker collision survives that whole chain.

Existing backup/restore and cancellation cross-feature findings remain distinct.

## Terminal persisted-generation fault matrix

- ordinary legacy row, no marker/provider/authored home:
  **SAFE refusal** — non-runnable; outstanding carrier superseded.
- ordinary legacy row missing carrier:
  **SAFE refusal** — no runnable owner reconstructed.
- legacy generation-1 row/carrier with exact future marker only:
  **UNSAFE** — falsely CurrentFormat; can execute under later configured destination.
- legacy exact-marker row with provider C metadata:
  **self-bound** — provider C remains explicit; no B adoption confirmed.
- legacy exact-marker row with valid authored native home:
  **self-bound** — authored output authority remains explicit.
- genuine current-format provider A + later preference B:
  **SAFE source-semantically** — command includes A and request/fingerprint preserve A.
- genuine current-format raw/default:
  accepted under the existing late-bound raw/default policy; not the persisted-generation collision itself.
- marker-collision row with matching existing carrier:
  **UNSAFE** — carrier equality does not prove generation.
- marker-collision row with missing carrier:
  **UNSAFE** — startup can synthesize another generation-1 carrier.
- marker-collision WorkManager retry:
  **UNSAFE same root** — retry preserves exact but falsely authenticated carrier semantics.
- process death after an execution witness exists:
  recovery prevents arbitrary duplicate native replay, but cannot repair the already-lost destination-generation provenance.

Matrix:
**NOT_CLOSED**

## Cross-attempt / live-owner matrix

- current provider-bound T1, preference A -> B after insertion:
  SAFE for T1.
- supported old marker-only T1, preference changes before resume:
  UNSAFE / BUG-TERMINAL-03.
- imported command_path locator without destination grant:
  UNSAFE configuration / BUG-BACKUP-11.
- Terminal cancel after native finished but before/during publication:
  UNSAFE / BUG-CANCEL-02 Terminal subcase.
- clear TERMINAL_CACHE after cancellation retires row/token while worker still unwinds:
  UNSAFE / same BUG-CANCEL-02 root.
- publication recovery after process death:
  durable journal/carrier remains the owner; no additional new recovery-discovery root confirmed.

Matrix:
**NOT_CLOSED**

## Verification evidence

Pinned implementation:
`58c067b8a7a82320e9a369b77c642b025579d8c8`

GitHub exact-SHA:
- combined status contexts: 0
- check runs: 0

Independent reviewer execution:
**NOT EXECUTED**

Existing implementation-agent evidence recorded in prior review:
- affected connected tests reported 32/32;
- JVM tests reported 61/61;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

Those are non-independent implementation-agent claims and do not cover the exact historical marker-collision fixture.

## Review retrospective

The 58c remediation addressed the common legacy ambiguity but used bytes inside the pre-existing user-controlled command representation as generation evidence.

The important distinction is:
- **representation marker**: can be byte-identical across old and new producers;
- **creation-generation provenance**: must come from a durable dimension old state could not already possess.

The new trigger-first policy correctly forced Module F to completion immediately. The defect did not have to wait for a future L1/L2 rotation.

The earlier same-SHA v7 shadow had already made L2 DEEP. This manual run initially recorded L2 as the promotion, then append-only corrected the lens history after reconciling that shadow checkpoint. The final lens progression therefore promotes L1, not L2.

No previous checkpoint was overwritten.

## Checklist evolution

No new governance change is proposed by this run.

Current v6 Module F was sufficient to require supported old-state compatibility review, and the trigger-first lens policy correctly made that obligation immediate.

A non-governing v7 candidate already contains a more explicit marker/sentinel collision proof rule. Its promotion remains HOLD pending a prospective shadow on a later new SHA. This review does not promote or modify v7.

Checklist gap newly created by this run:
**none**

Proposed checklist change:
**none in this run**

## Lens coverage / effectiveness

lens_coverage_current_sha:
- L1: **DEEP**
- L2: **DEEP**
- L3: BASELINE
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

primary_deep_lens:
**L1 Durability & recovery**

primary_deep_selection_reason:
**R1 + R2**

remaining_not_yet_deep:
- L3
- L4
- L5
- L6

next_not_yet_deep_lens:
**L3 Concurrency & authority**

next_lens_selection_reason:
R1/R2 continuation. The current root's direct L1/L2 generation/provenance owners are now DEEP; among remaining lenses, L3 is nearest to the unresolved reconcile/retry authority question and exact stale/current ownership across asynchronous boundaries. This is a continuation hint, not an immutable lock.

## Checkpoint summary

Earlier same-run intermediate:
- `review-runs/checkpoints/2026-09-26T075641Z__58c067b8__manual-v6-trigger-first-l2-deep-intermediate.md`
- commit `b364a005fcdc92d4faa359a3be68828a02cdd62f`

Lens-state correction:
- `review-runs/checkpoints/2026-09-26T080008Z__58c067b8__manual-v6-lens-state-correction-intermediate.md`
- commit `3017280838bf995abff7ea9288e90a64c2d43388`

Final result:
- implementation SHA unchanged during run;
- BUG-TERMINAL-03 remains OPEN P2;
- provisional BUG-BACKUP-11 remains confirmed P2;
- BUG-CANCEL-02 Terminal same-root subcase remains confirmed;
- new confirmed P0/P1/P2 IDs: 0;
- L1 promoted to DEEP;
- current same-SHA coverage: L1 DEEP, L2 DEEP, L3-L6 BASELINE;
- overall verdict: NOT_CLEAN.

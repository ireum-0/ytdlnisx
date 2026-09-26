# Manual correctness review checkpoint — 58c067b8 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: `5b3e57e4b6c62d4042d47bca0796c4b6e3fbecc1`

## Frozen review basis

- implementation branch: `checkpoint/pre-baseline-review`
- implementation_sha: `58c067b8a7a82320e9a369b77c642b025579d8c8`
- implementation_parent_sha: `cf7374510decad9f308fdc3f1b528e731a3ea4f1`
- plan/remediation_sha: `2145847a1054da28398b730b9be0ca728668f967`
- review/remediation_parent_sha: `5b3e57e4b6c62d4042d47bca0796c4b6e3fbecc1`
- ledger/remediation_sha: `822ffe6a9cd45b951550fcb559557f0cf0798610`
- Master Plan blob: `507a97c1455793b272298e29f37b945f4cfb55d7`
- Review Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Review Lens Selection Policy v1 blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
- SOURCE_ARTIFACTS blob: `bdee5f5efeee81426433c64ceb975208dc41a299`
- TASKS blob: `fae11a65fc7fe1e58bd725d64355ce4c242f398c`
- TASKS_DELTA blob: `96869f414efe6c3c33d4586eecf08cccb18375cc`
- CURRENT_STATUS blob: `0dd569290dfc209763b4f94c2041bad492641d44`

Checklist v7 remains a non-governing candidate; this run is governed by v6 plus Review Lens Selection Policy v1.

## Material semantic delta

58c introduces a Terminal-owned durable command-format marker and refuses persisted Terminal commands classified as lacking durable output authority.

Current new-writer path:
`TerminalViewModel.insert -> TerminalCommandIntentMaterializer.materialize -> row + TERMINAL_DISPATCH in one Room transaction`.

Current marker:
`--ytdlnisx-terminal-command-format=1`.

Current carrier still writes:
`sourceConfigurationGeneration = 1L`.

The immediately prior supported writer `b6512ae2...` stored the caller-supplied command unchanged, and the immediately prior carrier already used `sourceConfigurationGeneration = 1L`.

Room DB remains version 63, so the semantic format strengthening has no Room migration boundary that makes pre-marker rows impossible.

## Trigger map

### Module F — persisted schema-generation compatibility
trigger: YES
reason: stronger durable-generation/authority classification over already persisted Terminal row/carrier state.
status: **OPEN / FAIL**

Evidence:
- pre-marker writer legally stored arbitrary user command text unchanged;
- the future marker bytes were not reserved at that time;
- current `classifyDurable()` treats marker `=1` as `CurrentFormat` before requiring an independently durable creation-generation proof;
- both prior and current TERMINAL_DISPATCH carriers use sourceConfigurationGeneration=1;
- current persisted-generation instrumentation has zero occurrences of the exact collision token `--ytdlnisx-terminal-command-format=1`.

Concrete supported old-state collision:
generation-1 row/carrier + exact future marker bytes + no provider metadata + no authored native home.

Result:
old state is classified CurrentFormat and can retain/reacquire runnable dispatch authority.

### Module B — external scheduler handoff
trigger: YES
reason: 58c changes Terminal WorkManager admission/reconcile authority.

status: **CLOSED for mechanical handoff; no separate new root**

Current row/carrier/request/fingerprint/request-ID equality remains exact and worker rechecks it before admission.

However exact equality cannot cure Module-F provenance failure: the marker-collision row and its generation-1 carrier can be internally exact while semantically belonging to the old representation.

### Module C — external representation / authority projection
trigger: YES
reason: persisted command representation determines provider/output authority.

status: **OPEN / same BUG-TERMINAL-03 root**

For a marker-only historical command, `TerminalCommandPlanner` strips the marker, finds no bound provider and no authored native home, and may resolve the current configured destination. Thus a false generation classification can project newer provider/raw authority onto old durable intent.

### Module H — persisted executable configuration fan-out
trigger: YES
reason: durable user Terminal command is later parsed/interpreted/executed, with old/new producers and recovery reconstruction.

status: **OPEN / same BUG-TERMINAL-03 root**

Reviewed producers/consumers:
- prior Terminal writer;
- current materializing writer;
- Terminal row;
- TERMINAL_DISPATCH carrier;
- startup reconciliation;
- worker request admission;
- command metadata stripping;
- planner;
- provider publication.

No independent new H root beyond the persisted-generation collision.

### Module A — platform capability/admission
trigger: YES from existing open BUG-BACKUP-11
status: **OPEN / existing distinct P2**

Normal command_path selection obtains a persistable SAF grant. Generic backup/restore still serializes/restores command_path as a portable String without re-establishing or proving destination-side authorization.

### Module I — maintenance vs live-owner namespace
trigger: YES from existing BUG-CANCEL-02 Terminal subcase
status: **OPEN / existing-root subcase**

Cancellation can converge/remove the Terminal execution token and authorize row deletion based on dispatch supersession + native execution convergence without requiring WorkManager cancellation acknowledgement or post-native publication quiescence. Terminal cache liveness is process-token based, so maintenance can lose its live-owner signal while publication is still unwinding.

## Confirmed finding state

### BUG-TERMINAL-03 — P2 — remains OPEN

58c materially improves the common legacy case but does not authenticate the generation of the command-format marker.

Exact reproduction:
1. On pre-marker implementation `b6512ae2...`, create a nonterminal generation-1 Terminal row/carrier with literal user text `--ytdlnisx-terminal-command-format=1`, no provider metadata, and no authored native home.
2. Upgrade to 58c.
3. Change current command_path to B.
4. Current `classifyDurable()` accepts the old command as CurrentFormat.
5. Existing generation-1 carrier can satisfy the exact handoff gates.
6. Planner strips the marker and can fall back to current configured destination B.
7. Old T1 can therefore execute under authority not durably bound to the old intent.

Classification:
same-root persisted-generation residual, not a new finding ID.

Required correction:
use an independently durable creation-generation dimension that pre-marker state could not already possess. One narrow option is a new TERMINAL_DISPATCH sourceConfigurationGeneration for genuinely current-format rows, while legacy generation-1 marker-only rows remain ambiguous unless independently SelfBound.

### provisional BUG-BACKUP-11 — P2 — remains confirmed

The relevant production blobs are unchanged from cf737 and were re-read in this run.

`cache_path` is explicitly destination-local in BackupSettingsUtil while `command_path` remains portable. Normal command_path UI obtains persistable URI permission; merge/reset restore writes the serialized preference string without equivalent destination-side authorization proof.

No status change.

### BUG-CANCEL-02 Terminal subcase — remains confirmed under existing P2 root

The relevant cancellation, worker-publication and cache-maintenance production blobs are unchanged from cf737 and were re-read in this run.

Row deletion authorization remains `dispatchSuperseded && executionConverged`; WorkManager cancellation acknowledgement is not required. Terminal cache live ownership still depends on the process-local active execution token.

No status/count change.

## L1-L6 baseline coverage

### L1 Durability & recovery — BASELINE
Positive:
- current writer materializes before atomic row/carrier persistence;
- request/fingerprint recovery remains durable.

Gap:
- durable bytes do not prove which persisted generation created them.
- supported old state survives because DB schema version is unchanged.

No separate new L1 root.

### L2 Identity & provenance — BASELINE -> **selected DEEP**
FAIL:
the format-marker bytes are treated as provenance of the new writer even though supported old durable user commands could already contain those exact bytes.

Primary DEEP selection:
- R1: direct current-root ownership — BUG-TERMINAL-03 is a persisted generation/provenance defect.
- R2: triggered Module F exposes unresolved generation identity.
- R3-R5 not needed.

### L3 Concurrency & authority — BASELINE
Exact current row/carrier/request equality is preserved, but exact equality over a falsely classified generation is insufficient authority.
Existing cancellation/publication TOCTOU remains separate under BUG-CANCEL-02.

### L4 Destructive ownership — BASELINE
No destructive change was introduced by the marker patch.
Existing BUG-CANCEL-02 Terminal publication/live-owner subcase remains reachable because those source blobs are unchanged.

### L5 Platform contract closure — BASELINE
Existing BUG-BACKUP-11 SAF locator/grant mismatch remains.
No new platform/API root is required to reproduce the marker collision.

### L6 Cross-feature semantic propagation — BASELINE
The marker semantics were traced across writer, Room row, scheduler carrier, startup recovery, worker admission and planner.
The exact collision survives that propagation.
Existing backup/restore and cancellation cross-feature roots remain independent.

## Lens state

lens_coverage_current_sha:
- L1: BASELINE
- L2: **DEEP**
- L3: BASELINE
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

primary_deep_lens: L2
primary_deep_selection_reason: R1 + R2

remaining_not_yet_deep:
- L1
- L3
- L4
- L5
- L6

next_not_yet_deep_lens: L1
next_lens_selection_reason:
R2 — after L2, the closest remaining unresolved same-root surface is the durable generation/recovery carrier contract exposed by Module F. This is a continuation hint only and must be recomputed from fresh evidence.

## Finding/count discipline

- new finding IDs in this run so far: 0
- BUG-TERMINAL-03: existing P2, same-root residual confirmed
- provisional BUG-BACKUP-11: existing distinct P2 retained
- BUG-CANCEL-02 Terminal: existing-root subcase retained
- no duplicate root created
- unsupported runtime conclusions remain NOT_VERIFIED

## Remaining scope

- complete L2 DEEP sibling/provenance search and rejected-candidate proof;
- final L1/L3/L4/L5/L6 recount;
- terminal fault / cross-attempt / live-owner matrices;
- exact-SHA GitHub status/check evidence;
- fresh-fetch implementation and review heads;
- append and verify FINAL checkpoint.

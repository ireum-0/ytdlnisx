# Manual correctness review — e3ae6f04 — tooling L1 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 45963306ee2a89c485819daee01693666990aebc

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 28ddcac87350f8ecd6af2e5d39831163ddcb276d
implementation_sha: e3ae6f0475f172f537eeedf27d42a18faa79692e
implementation_parent: 21a04168286ae6562adbf219b8ea6a03984a2e25
implementation_branch: checkpoint/pre-baseline-review

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
plan_tip: 2145847a1054da28398b730b9be0ca728668f967
protocol_blob: 71e2be79a50ec79051400f3b34f1eb4e91fcac2d
review_checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
review_checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
review_lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
review_lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
ledger_tip: b98d315006fa19fc6f22b017f43a91899db5fb81

intermediate_checkpoint:
review-runs/checkpoints/2026-09-27__e3ae6f04__manual-v7-tooling-l1-deep-intermediate.md
intermediate_commit: 28ddcac87350f8ecd6af2e5d39831163ddcb276d

## Independent verdict

NOT_CLEAN.

No new tooling root was created by the L1 pass.

Current tooling:
- BUG-TOOLING-01 = OPEN P2
- BUG-TOOLING-02 = OPEN P2
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=2
- new_finding_ids=0

Production active-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

INDEPENDENT EXECUTION: NOT EXECUTED

## Findings

Tooling evidence/recovery paths are fail-closed at source level for interruption:

- each invocation receives a unique run directory;
- JSON evidence is written to a temporary file before rename;
- verification.json is emitted only after the gate loop completes;
- missing/unparseable verification evidence cannot satisfy Complete-Wave;
- a retry does not overwrite the first run;
- infrastructure retry evidence must bind to the exact candidate SHA/tree and
  exact requested connected class, and the retry requires a fresh bounded
  device-health pass.

A completion interrupted after the exact TestedSha is already the remote head
can be re-entered: Complete-Wave can revalidate the evidence and exact remote
state and report the exact already-pushed state. The open BUG-TOOLING-02 still
blocks treating the current Push implementation as safe because its write
source is mutable local HEAD.

No new root is created merely because local evidence is Git-ignored. Current
consumers fail closed when required evidence is absent or unreadable, and no
governing current threat model establishes external evidence-file tampering as
a separate correctness root.

BUG-DOWNLOAD-01 remains OPEN P2.

Fresh L1 review confirms the contrast between the dedicated recovery owner and
the two worker-side reads:

- DownloadExecutionRecovery keeps the durable row/journal/marker/producer state
  as restart carriers;
- scheduleRecovery is only the live retry owner;
- recovery-loop DB-read failures are caught and retried with responsibility
  retained;
- shouldStopForUserRequest() instead collapses Room read failure to null and can
  return normal ownership-loss STOP;
- the child finalizer also collapses read failure to null and can release exact
  process-local ownership/bookkeeping as if ownership transfer were proven.

This is the existing BUG-DOWNLOAD-01 root, not a new production finding.

## Trigger/module status

- tooling partial evidence / interrupted verification: PASS at source level
- tooling infrastructure recovery carrier: PASS at source level
- tooling exact execution-tree provenance: OPEN — BUG-TOOLING-01
- tooling exact final remote mutation authority: OPEN — BUG-TOOLING-02
- tooling interrupted completion re-entry: PASS only when exact TestedSha is
  already authoritative remotely; BUG-TOOLING-02 remains the current Push
  blocker
- production durable responsibility after indeterminate authoritative read:
  OPEN — BUG-DOWNLOAD-01
- newly triggered schema/ABI/external-representation module: NONE

## L1-L6 coverage

- L1 Durability & recovery: DEEP
- L2 Identity & provenance: DEEP
- L3 Concurrency & authority: DEEP
- L4 Destructive ownership: BASELINE
- L5 Platform contract closure: BASELINE
- L6 Cross-feature semantic propagation: BASELINE

primary_deep_lens: L1 Durability & recovery
primary_deep_selection_reason: R1/R2 — unresolved correctness depends on whether
responsibility survives interruption and indeterminate observation.

remaining_not_yet_deep:
- L4
- L5
- L6

next_not_yet_deep_lens: L4 Destructive ownership
next_lens_selection_reason: next deterministic remaining lens, with direct
relevance to remote mutation and production release/delete authority.

## Review retrospective

The L1 pass did not add a third tooling defect. The tooling's local evidence
lifecycle is conservative under missing, partial, and retried runs.

The material durability gap remains production BUG-DOWNLOAD-01: a transient
authoritative DB-read failure can leave the dedicated recovery model entirely
and be interpreted as revocation/absence.

The two existing tooling roots remain exactly as established by the prior L2
and L3 runs.

## Checklist evolution

No checklist or protocol change is required.

The existing L1 durable-carrier/recovery rules were sufficient to distinguish
safe tooling interruption behavior from the production read-failure gap.

## Checkpoint summary

manual_review_run_status: FINAL
overall_verdict: NOT_CLEAN

implementation_sha: e3ae6f0475f172f537eeedf27d42a18faa79692e
production_counts: P0=0 / P1=0 / P2=13
tooling_counts: P0=0 / P1=0 / P2=2
new_finding_ids: 0

primary_deep_lens: L1
next_not_yet_deep_lens: L4

persisted_tooling_prompt:
ytdlnisx/prompts/2026-09-27_BUG-TOOLING-01_02_EXACT_SOURCE_AND_PUSH_AUTHORITY_FIX.md

No prompt replacement is required by this L1 run.

INDEPENDENT EXECUTION: NOT EXECUTED

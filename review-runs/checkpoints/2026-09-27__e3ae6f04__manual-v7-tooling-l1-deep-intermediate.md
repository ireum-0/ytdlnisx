# Manual correctness review — e3ae6f04 — tooling L1 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 45963306ee2a89c485819daee01693666990aebc

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 45963306ee2a89c485819daee01693666990aebc
implementation_sha: e3ae6f0475f172f537eeedf27d42a18faa79692e
implementation_parent: 21a04168286ae6562adbf219b8ea6a03984a2e25
implementation_branch: checkpoint/pre-baseline-review

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: 71e2be79a50ec79051400f3b34f1eb4e91fcac2d
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

Governance is fixed for this manual run.

## Current disposition

Overall remains NOT_CLEAN.

This same-SHA run recomputed triggers and promoted L1 Durability & recovery to
DEEP. The prior L2/L3 conclusions were not reused as substitutes for this run.

Current counts remain:
- production P0=0 / P1=0 / P2=13
- tooling P0=0 / P1=0 / P2=2

Current tooling findings remain:
- BUG-TOOLING-01 OPEN P2
- BUG-TOOLING-02 OPEN P2

new_finding_ids: 0

INDEPENDENT EXECUTION: NOT EXECUTED

## Tooling L1 producer/carrier/consumer review

### Evidence creation

Each Preflight/Verification/Completion invocation creates a unique ignored
directory under build/remediation-agent/<candidate>/<run-id>.

Write-RemediationJson writes a complete temporary file first and then renames it
into the requested evidence path. A process failure before rename may leave an
orphan temporary file but does not create a parseable final evidence record.

Verification writes verification.json only after the gate loop has completed.
If the process dies before that write, Complete-Wave cannot accept the run
because the explicitly supplied verification evidence path is missing or
unparseable.

Retries use a fresh run directory, so a later attempt does not overwrite the
first run's evidence.

### Infrastructure recovery retry

Read-PriorInfrastructureEvidence requires:
- an existing file under ignored build/remediation-agent output;
- exact unchanged candidate SHA;
- exact unchanged candidate tree;
- exact requested connected test class;
- zero-test infrastructure evidence;
- an allowed infrastructure event kind.

A recovery retry additionally requires a non-empty caller recovery
authorization and exactly one matching connected class. Before the retried
connected gate runs, bounded device health must pass.

This is a durable/file-carried retry relationship rather than implicit reuse of
the previous process-local state.

No current source path automatically converts an incomplete/failed
verification invocation into PASS after restart.

### Completion recovery

Complete-Wave reparses the supplied verification evidence and requires:
- evidenceKind exact_source_verification;
- status PASS;
- exact candidate SHA/tree;
- every caller-required gate exactly once and PASS.

If completion exits before any Push, a later invocation can repeat the checks.

If an exact TestedSha is already the remote implementation head, a later
completion invocation can re-read that state and report ALREADY_PUSHED_EXACT_SHA
after revalidating the evidence. This is a valid recovery path for an
interrupted completion whose exact remote mutation already succeeded.

The currently open BUG-TOOLING-02 still prevents treating the existing Push
implementation as fully safe because its mutation source is mutable HEAD. That
is the existing L3 root, not a new L1 root.

### Evidence retention limits

The evidence carrier is a local ignored file, not a committed immutable review
record. Current tooling therefore relies on the project/handoff preservation
discipline to retain prior evidence directories.

This run does not create a new correctness root solely from that fact:
- completion fails closed if required evidence is absent/unreadable;
- no current source path fabricates PASS from a missing carrier;
- the governing workflow already treats implementation-agent execution as
  evidence only and requires independent review for semantic closure.

Evidence-file tampering by an external actor is not established as a supported
current project threat model by the pinned governance, so this run does not
invent a new adversarial integrity finding.

## Production L1 — BUG-DOWNLOAD-01

Fresh current-source review again confirms that DownloadExecutionRecovery has
a real durable/live recovery model:

- durable recovery state is carried by the Download row and dedicated recovery
  journal/marker/producer carriers;
- scheduleRecovery is explicitly only the live retry owner;
- retry iterations preserve responsibility across ordinary recovery/read
  failures and retry with bounded backoff;
- recovery read failures in the recovery owner are caught inside the retry
  loop rather than being converted to normal ownership loss.

BUG-DOWNLOAD-01 remains open because two worker-side authoritative reads do not
enter that model when the read itself is indeterminate.

1. shouldStopForUserRequest():
   - Room read exception -> runCatching(...).getOrNull() -> null
   - null -> lostExecutionOwnership for a nonblank exact execution token
   - ordinary STOP can be returned without first installing typed durable
     recovery responsibility for the indeterminate observation.

2. child finalizer:
   - Room read exception -> null
   - null -> stillOwnsAttempt=false
   - exact process-local execution/process owner and bookkeeping can be
     released under the branch intended for proven ownership transfer.

The contrast with DownloadExecutionRecovery is material:
the dedicated recovery owner retries read failures while retaining durable/live
responsibility; these worker-side reads silently reinterpret read failure as
revocation/absence instead.

This is the same BUG-DOWNLOAD-01 root, not a new finding.

## Trigger map

- tooling evidence write atomicity / partial-run recovery:
  TRIGGERED / PASS at source level.
- tooling infrastructure retry carrier:
  TRIGGERED / PASS at source level for exact candidate/class binding and fresh
  health preflight.
- tooling exact execution-tree provenance:
  TRIGGERED / OPEN — BUG-TOOLING-01.
- tooling final remote mutation authority:
  TRIGGERED / OPEN — BUG-TOOLING-02.
- tooling post-interruption completion recovery:
  TRIGGERED / PASS only for exact TestedSha already present remotely; existing
  BUG-TOOLING-02 remains the blocker on the current Push implementation.
- production durable recovery ownership after indeterminate authoritative read:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01.
- no newly triggered schema/ABI/external-representation module from the
  unchanged tooling SHA.

## Lens coverage

- L1 Durability & recovery: DEEP in this run
- L2 Identity & provenance: DEEP
- L3 Concurrency & authority: DEEP
- L4 Destructive ownership: BASELINE
- L5 Platform contract closure: BASELINE
- L6 Cross-feature semantic propagation: BASELINE

primary_deep_lens: L1 Durability & recovery

primary_deep_selection_reason:
R1/R2 — after L2/L3 were already DEEP, the highest current unresolved risk is
whether evidence/recovery responsibility survives interruption and
indeterminate reads. Fresh source confirms tooling interruption paths fail
closed, while BUG-DOWNLOAD-01 still loses recovery responsibility at the
worker-side read boundary.

remaining_not_yet_deep:
- L4
- L5
- L6

next_not_yet_deep_lens: L4 Destructive ownership

next_lens_selection_reason:
L4 is the next deterministic remaining lens and is materially relevant because
BUG-TOOLING-02 controls remote mutation authority and several production P2
roots involve deletion/release/ownership boundaries.

## Remaining scope before FINAL

- verify there is no tooling path that consumes a partial recovery/evidence
  record as PASS;
- verify completion/check-mode failure evidence does not erase prior run
  evidence;
- re-check current refs;
- append FINAL on the fixed implementation/governance basis;
- update handoff review tip/lens state without changing the persisted combined
  tooling implementation prompt unless new correction scope is discovered.

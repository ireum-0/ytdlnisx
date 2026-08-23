# Remediation Review Checklist v4 — Operational Mirror

This file is the ledger-branch operational mirror used by scheduled correctness review. The governing source artifact remains `remediation-review-checklist-v4.md`, whose SHA-256 is recorded in `SOURCE_ARTIFACTS.md`. This mirror exists so automated reviews can apply the v4 review procedure directly from GitHub. It must not be silently weakened. A semantic change to this mirror requires separate review.

## Core invariants

1. An authoritative observation is not enough; it must reach a durable carrier.
2. A durable carrier in one attempt is not enough; stateful retry/re-entry must reconstruct the same semantic barrier.
3. Mutable retry fields must not retroactively re-authorize a previously rejected privileged operation.
4. If privileged authority is abandoned, it must be explicitly and irreversibly removed before ordinary retry proceeds.
5. Non-authoritative side effects must not block or replace authoritative persistence.
6. Recovery must preserve semantic identity, not merely escape a running state.
7. One item's unrecoverable failure must not strand unrelated sibling work.
8. A candidate may be rejected only after the original invariant disproves it.
9. Correctness is decided before attribution.
10. CLEAN requires semantic closure and the required execution evidence.

## Mandatory reviewer execution order

1. Fix/review scope and invariant.
2. First authoritative observation.
3. Carrier-creation gap.
4. Helper-internal throwable window.
5. Semantic preservation.
6. Persistence barrier and first-write fault injection.
7. Recovery semantic identity.
8. Multi-ledger durability and process-death windows.
9. Post-commit barrier.
10. Outer catch/final result.
11. Filesystem/reference/cleanup authority at the actual mutation point.
12. Retry/reconfigure/raw-requeue entry-point inventory.
13. Cross-attempt semantic matrix.
14. Expected-identity mutability.
15. Concurrency, sibling isolation, lock order, exact ownership.
16. Candidate-rejection re-proof.
17. Test assertions and actual production wiring coverage.
18. Terminal fault matrix.
19. CLEAN gate.
20. Attribution classification last.

## 1. First authoritative observation

Identify where source, type, ownership, target, provenance, cancellation, or another semantic fact first becomes authoritative. Do not let later reauthorization silently reinterpret the same decision. If authorization occurs twice, assume state may change between calls unless synchronization proves otherwise.

## 2. Carrier-creation gap and helper throwable window

After an authoritative result is obtained, enumerate everything that can throw before the result is returned, wrapped in a typed carrier, or durably persisted. Open helper bodies; do not reason only from helper names or signatures. Inspect DB queries, filesystem operations, cleanup/finally blocks, notifications, logging, EventBus/callbacks, resource close, and batch cleanup.

## 3. Semantic preservation

Do not collapse meaningful distinctions such as `Authorized`, `TargetMissing`, `SourceMismatch`, `TypeMismatch`, cancellation, committed success, or exact ownership into Boolean/null/empty collection/generic exception/string message unless the lost distinction is provably irrelevant. Distinguish authorization outcome from side-effect execution outcome.

## 4. Persistence barrier

From authoritative failure to durable terminal state, enumerate every throwing call. Non-authoritative work must not prevent or replace authoritative persistence.

### Mandatory first-write fault injection

For every authoritative terminal branch, ask what happens if the first terminal persistence write throws. Verify that:

- the exception is not swallowed;
- the original issue/disposition survives recovery;
- the row cannot remain `Active`/`PostProcessing` without a live owner or durable recovery carrier;
- linked ledgers are not silently skipped;
- a handled `Result.success()` is not emitted for a stale running row;
- retry/requeue cannot erase the authoritative decision.

### Mandatory swallow audit

Search durable writes and terminal repository calls for `catch (Exception)`, `runCatching`, `.onFailure`, `getOrNull`, `getOrDefault`, ignored results, and logging-only catches. Determine whether write failure reaches a recovery owner or is incorrectly treated as completion.

## 5. Recovery semantic identity

Recovery must preserve the original issue code, stage, terminal disposition, ownership/authorization meaning, and linked-child reason where required. Converting an authoritative mismatch or target-deleted result into generic `UNKNOWN` Error is not semantic recovery.

## 6. Multi-ledger durability and process death

Review the whole operation ledger, not one row: Download, History, linked child/parent operation, retry metadata, operation/attempt identity, status/reason, filesystem references, keyword/reference tables, and other persistent carriers. Assume process death between distinct durable writes and prove startup/restart reconciliation restores the exact semantic state.

## 7. Post-commit barrier

Once an authoritative mutation commits, later ancillary failure must not pretend the mutation never committed. Review logging, notification, keyword work, old-file cleanup, queue finalization, indexing, analytics, and cancellation observation after commit. Distinguish post-commit warning/finalization debt from pre-commit failure.

## 8. Outer-catch reinterpretation

Trace each inner exception through every outer catch and finally to the actual final state:

`exception -> outer catch -> DB update -> ledger transition -> queue delete/preserve -> notification -> DownloadOutcome -> WorkManager result/exception -> finally`.

Check for downgrade to `UNKNOWN`, partial success, retry, TargetMissing, cancellation, or success.

## 9. Created-output and cleanup interaction

File existence is never stronger evidence than authoritative ownership/authorization failure. Cleanup incomplete/failed must not open generic partial success merely because output files survive. Track which files existed before, were created by this attempt, remain referenced, and are authorized to delete.

## 10. Filesystem and reference authority

Authority must remain valid at the actual read/mutate/delete moment, not only at an earlier point-check. Inspect TOCTOU between DB/reference snapshot and filesystem mutation, URI/path aliases, retained-reference changes, execution changes, pause/cancel/resume, and new replacement attempts. Destructive intervals must participate in a canonical synchronization/lease order.

## 11. Retry, reconfigure, resume, restart, restore

Inventory every state-changing re-entry path. Same-settings retry, manual/raw requeue, reconfigure, notification resume/retry, startup reconciliation, process restart, and restore must not reinterpret a prior authoritative refusal or committed success. If authority is intentionally abandoned, prove the privileged marker/authority is durably and irreversibly removed first.

## 12. Concurrency, sibling isolation, lock order

One item's failure must not cancel or strand unrelated durable work. Review supervisor/coroutine semantics, batch loops, cleanup aggregation, owner release, native-process cleanup, and startup recovery. Build a lock-order graph for process-global mutexes, Room transactions, per-download leases, and filesystem/reference locks. Reject any reachable AB/BA ordering.

## 13. Stale full-row writers

Any UI/background writer that captures a row before a synchronization boundary and later writes a full stale row is suspect. Prove it rereads authoritative state inside the canonical boundary or performs field-owned updates only. Protect semantic-commit identity, source/type, downloadId/reference fields, paths, and other fields owned by concurrent remediation logic.

## 14. Test quality and evidence hierarchy

Test source existence is not PASS. Prefer evidence in this order:

1. executed production-path worker/repository fault-injection PASS;
2. executed focused integration/Room PASS;
3. executed focused JVM wiring PASS;
4. executed helper-level pure test PASS;
5. test added but NOT EXECUTED;
6. SOURCE-LEVEL ONLY.

Record exact status as `PASS`, `FAIL`, `ATTEMPTED, NOT COMPLETED`, `NOT EXECUTED`, or `SOURCE-LEVEL ONLY`. Helper-only tests do not prove actual Worker/Room/WorkManager/ledger wiring.

## 15. Mandatory terminal fault matrix

For each relevant authoritative terminal branch, record:

- authoritative decision;
- first persistence call;
- behavior if first persistence throws;
- recovery carrier;
- behavior if recovery write throws;
- durable Download state;
- durable linked-ledger state;
- filesystem effect;
- final DownloadOutcome;
- WorkManager result or exceptional exit;
- whether a row can remain `Active`/`PostProcessing`;
- whether the issue can be downgraded/reinterpreted;
- cross-attempt re-entry behavior;
- direct test/fault-injection evidence.

For History replacement/Finding-A-class paths, explicitly cover at least: SourceMismatch, TypeMismatch, TargetMissing, Authorized + cleanup incomplete/failed, committed replacement + ancillary/finalization failure, and cancellation.

## 16. Mandatory cross-attempt matrix for stateful remediation

For each previous authoritative state and each applicable re-entry path, answer:

- durable carrier restored?
- privileged marker survives?
- mutable identity changes?
- next-attempt interpretation?
- destructive authority possible?
- safe?

At minimum inspect same-settings retry, manual/raw requeue, reconfigure, notification resume/retry, restart/reconcile, and restore. Any unknown cell means cross-attempt closure is incomplete.

## 17. Candidate-rejection discipline

Before dismissing a suspicious path, write the candidate in its strongest concrete form and test it against the original invariant.

Do **not** reject a candidate merely because:

- the user explicitly changed something;
- code re-authorizes;
- current source/type happens to match;
- it resembles another follow-up;
- its fix may overlap a later finding;
- occurrence probability is low;
- no test exists yet.

A rejection requires proof that at least one of these is true:

- the production path is unreachable;
- privileged marker/authority is definitely abandoned before re-entry;
- a durable semantic barrier is restored in the next attempt;
- immutable original identity is checked before destructive mutation;
- another defect owns it **and** the current invariant is not violated.

Record the rejection proof, not reviewer intuition.

## 18. Correctness first, attribution second

First decide whether the production behavior is wrong. Only then classify it as current-finding blocker, remediation regression, incomplete closure, pre-existing baseline defect, separately owned defect, remediation follow-up, deferred P3/nonblocking item, verification gap, or false positive. "Not the current fix order" never means "not a bug."

## 19. CLEAN gate

### CLEAN

- open current-change P1/P2 = 0;
- accepted/waived current-change P1/P2 = 0;
- required focused verification completed;
- mandatory terminal fault matrix closed;
- mandatory cross-attempt matrix closed where stateful;
- first terminal persistence failure directly verified;
- recovery-write failure or exact semantic preservation directly verified;
- actual production wiring is not substituted with helper-only tests.

### CLEAN_WITH_WAIVERS

- open current-change P1/P2 = 0;
- explicit accepted/waived current-change P1/P2 exists with exact risk/evidence/owner.

### NOT_CLEAN

If any of the following remains: open current-change P1/P2, remediation regression, cross-attempt reinterpretation, mutable-state reauthorization of privileged work, stale `Active`/`PostProcessing` with handled completion, sibling stranding, mandatory first-write/recovery-write path without proof, or unknown required cross-attempt path.

## Minimal required review output

- `Verdict: CLEAN | CLEAN_WITH_WAIVERS | NOT_CLEAN`
- Current blockers
- Verification gaps
- Separately owned/nonblocking findings
- Terminal fault matrix
- Cross-attempt matrix
- Candidate rejections and rejection proof
- Verification evidence with exact execution labels

This operational mirror is intentionally strict. If a review cannot complete a mandatory v4 section, record the gap; do not infer CLEAN.

# YTDLnisX Correctness Remediation Tasks

> Current task registry for the correctness-remediation program.
>
> This file follows `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md` for the
> authoritative F1→F22 remediation order. Remediation-discovered follow-ups are
> tracked separately and **do not increase the 22-defect baseline count** unless
> explicitly promoted.
>
> Evidence snapshot used for the current F1 review:
> `checkpoint/pre-baseline-review@b13f58e8d63f0d6ee85e2bf13e21a3f59d2de11c`
>
> Review checklist:
> `remediation-review-checklist-v3.md`

## Status vocabulary

- **Open** — baseline defect has not been remediated.
- **In Progress** — implementation/review-fix work is active.
- **Partial** — important correction exists, but the clean gate has not closed.
- **Luna-clean** — the full original finding scope is clean under the Luna review loop.
- **Blocked** — a required dependency or review blocker prevents progression.
- **Follow-up** — real remediation-discovered issue outside the current baseline item.
- **Verification gap** — production defect is not demonstrated, but required proof/tests are incomplete.

## Global review rules

1. Review the full original scope (`review_base..review_head`), not only the latest fix.
2. Do not implement a Luna review finding until it is independently verified against the
   user-authorized review HEAD.
3. A later GitHub commit is excluded from review until the user supplies a Luna result
   that explicitly reports that HEAD.
4. One logically attributable correction per commit. No amend/rebase/squash/force-push
   of referenced commits.
5. Finding attribution is performed **after** correctness is established.
6. `ATTEMPTED, NOT COMPLETED` is never converted to PASS.
7. A current finding cannot be declared P0/P1/P2 CLEAN unless the v3 mandatory terminal
   fault matrix is complete.

---

# Current remediation focus

## F1 — BUG-BACKUP-01 — Remap and authorize History replacement targets

**Priority:** P0  
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**State:** **Partial / NOT CLEAN**  
**Authorized review HEAD:** `b13f58e8d63f0d6ee85e2bf13e21a3f59d2de11c`

### Core invariant

Numeric History ID alone must never authorize:

- previous-media access;
- destructive History replacement;
- quality-rejection cleanup;
- old-media deletion.

Replacement authority requires at minimum:

- intended source identity;
- compatible media type;
- current live History target.

Where cleanup is target-derived, the exact authorized current History snapshot must be
used.

### Implemented production semantics through the authorized review HEAD

The current F1 lineage has already implemented:

- restore-time remapping of regular and quality `HistoryRedownloadMarker` IDs through
  `importedHistoryIdMap`;
- fail-closed handling for unmappable markers;
- transactional current-target authorization;
- strict destructive source identity;
- source/type mismatch separation from target deletion;
- exact authorized target snapshots for replacement and target-derived cleanup;
- typed quality-cleanup execution results (`Completed`, `Incomplete`, `Failed`);
- partial-success denial when cleanup is incomplete/failed;
- mismatch same-settings retry blocking;
- restore neutralization of unmappable markers.

### Finding A — current status

Finding A semantics:

- `Authorized` → normal authorized replacement/cleanup.
- `TargetMissing` → `TARGET_DELETED`.
- `SourceMismatch` / `TypeMismatch` → `PRESERVE_FAILED`.
- mismatch must retain a distinct diagnostic and must not be downgraded by later
  reauthorization, cleanup, notification/logging, surviving outputs, partial success,
  retry, or generic recovery.

### Current Finding A blocker

#### P2 — Preserve authoritative History mismatch when the first terminal Download persistence fails

**State:** Open — current Finding A blocker  
**Attribution:** incomplete F1 closure / current Finding A  
**Location:** `DownloadWorker.kt` History failure persistence path

Reachable path:

```text
SourceMismatch / TypeMismatch becomes authoritative
→ exact mismatch issue is created
→ Download Error + exact mismatch code/stage are prepared
→ first terminal dao.update(downloadItem) throws
→ local catch logs and swallows the persistence failure
→ linked low-quality FAILED transition is not reached
→ preserveQueueRecord remains true
→ outer non-cancellable recovery is not entered
→ durable row may remain Active/PostProcessing
→ worker can still finish as handled work / Result.success()
```

Required result:

- once source/type mismatch is authoritative, its exact issue identity must survive every
  later persistence/recovery path;
- failure of the first terminal Download write must not be swallowed;
- recovery must preserve the original mismatch rather than converting it to `UNKNOWN` or
  generic `HISTORY_WRITE_FAILED`;
- when persistence is recoverable, the Download row must durably become `Error` with the
  original mismatch code/stage;
- the linked low-quality child must become `FAILED` with the same mismatch reason when its
  transition is reached;
- if terminal persistence remains impossible, the item must not silently finish with a
  stale running row;
- cancellation must remain cancellation.

### Finding A verification gate after the blocker fix

The review-fix is not enough by itself. Before Finding A can be declared clean, focused
verification must cover at least:

- SourceMismatch + first terminal Download write failure;
- TypeMismatch + first terminal Download write failure;
- recovery preserves the exact authoritative mismatch and never produces `UNKNOWN`;
- no stale `Active/PostProcessing` + handled completion;
- linked low-quality child reason when persistence succeeds;
- recovery-write failure behavior;
- cancellation propagation;
- quality-cleanup partial deletion / nonthrowing failure;
- first cleanup authorization preservation;
- surviving candidate accounting;
- partial-success denial;
- helper/worker throwable boundary.

Current focused Gradle status remains:

**ATTEMPTED, NOT COMPLETED**

No PASS may be inferred from that result.

### Finding B — next only after Finding A closes

#### P2 — Source-less `DownloadType.command` History redownload needs stable opaque target identity

**State:** Confirmed, not started

Required result:

- never authorize `command + blank URL` from numeric History ID alone;
- carry stable opaque/fingerprinted target identity from the original History target;
- do not serialize raw command text in the marker;
- restore remaps only History ID and preserves the opaque identity;
- missing identity fails closed;
- current target command identity is revalidated before replacement;
- blank URL is acceptable only under an authorized command-replacement contract.

**Do not start Finding B until Finding A P0/P1/P2 closure is complete.**

---

# Remediation-discovered follow-ups relevant to F1

These are real issues retained for later work. They do **not** increase the original
22-defect baseline count unless explicitly promoted.

## REMEDIATION-FOLLOWUP-DOWNLOAD-TERMINAL-RECOVERY-01

**State:** Discovered  
**Severity:** P2 candidate  
**Ownership:** cross-cutting Download terminal repository recovery  
**Current F1 impact:** pre-existing; non-blocking for Finding A

### Failure path

For `TargetMissing` / target-deleted completion, the terminal repository API performs
Download deletion and linked low-quality terminalization transactionally. If that terminal
repository transaction fails, worker fallback attempts to preserve the Download as Error.
If that fallback `dao.update(...)` also fails, the error can be logged and swallowed before
the branch returns. Even when the fallback Download Error write succeeds, startup
reconciliation may reconstruct a nonterminal child as generic `FAILED` instead of the
authoritative `SKIPPED / HISTORY_TARGET_DELETED` result.

### Required eventual result

- terminal repository failure recovery must preserve the authoritative terminal
  disposition;
- fallback write failure must not permit stale running state + handled completion;
- `TargetMissing` must not silently become generic failure semantics;
- linked child/parent reconstruction must retain the intended terminal reason.

## REMEDIATION-FOLLOWUP-HISTORY-POSTCOMMIT-01

**State:** Discovered  
**Severity:** P2 candidate  
**Current F1 impact:** pre-existing; non-blocking

A History replacement can commit successfully and a later automatic-keyword or ancillary
step can throw inside the same broad History error region, causing Download/ledger failure
even though authoritative History already contains the replacement.

Required eventual result: establish an explicit post-commit barrier and represent later
ancillary failures as warnings/follow-up failures rather than an uncommitted replacement
failure.

## BUG-OUTPUT-01-FOLLOWUP-01

**State:** Discovered  
**Severity:** P2 candidate  
**Ownership:** F2 / `BUG-OUTPUT-01`  
**Current F1 impact:** out of scope for Finding A

Reauthorize/stage ownership immediately before hard-sub fallback mutates previous History
media. An earlier authorized snapshot can become stale before `burnSubtitlesInPlace(...)`.

## REMEDIATION-FOLLOWUP-HISTORY-DELETE-01

**State:** Discovered  
**Severity:** P2 candidate  
**Ownership:** cross-cutting History filesystem deletion  
**Current F1 impact:** pre-existing; non-blocking

The retained-reference set is snapshotted before filesystem deletion. A concurrent History
row can begin referencing a candidate after that snapshot and before actual deletion.

## BUG-BACKUP-01-FOLLOWUP-01

**State:** Discovered  
**Severity:** P3  
**Topic:** forced replacement failure can lose created-output count.

## BUG-BACKUP-01-FOLLOWUP-02

**State:** Discovered  
**Severity:** P3  
**Topic:** Download mismatch persistence and linked low-quality FAILED reason are not one
atomic durable transition.

## BUG-BACKUP-01-FOLLOWUP-03

**State:** Discovered  
**Severity:** P3  
**Topic:** later notification failure can degrade the in-memory mismatch outcome to
`UNKNOWN` even though the precise mismatch is already durable.

## Verification follow-up — restore-to-worker integration

**State:** Verification gap  
**Severity:** non-blocking unless production evidence changes the classification

Marker remapping has unit/Room coverage, but end-to-end restore-to-worker coverage is still
needed for reset/merge, ID collision, missing target, regular marker, quality marker, and
worker authorization enforcement.

## Verification follow-up — quality-cleanup actual wiring

**State:** Verification gap

Policy tests cover `Incomplete`/`Failed` semantics, but direct fault injection should still
exercise the actual chain:

```text
gateway
→ HistoryFileDeletionEngine
→ deleteValidatedReplacementPaths
→ deleteRejectedQualityReplacementOutputs
→ QualityReplacementValidationException
→ worker outer catch
→ surviving-output revalidation
→ terminal DB/ledger outcome
```

---

# v3 mandatory review gate for F1 and later findings

For every authoritative terminal branch, complete the following matrix before declaring
P0/P1/P2 CLEAN:

| Fault point | Required proof |
|---|---|
| first terminal DB write succeeds | exact durable status/code/stage |
| first terminal DB write throws | propagation/recovery; no stale running success |
| linked ledger write throws | Download/child/parent reconciliation |
| notification/logging throws | authoritative state remains unchanged |
| recovery DB write throws | final local/WorkManager outcome is honest |
| process death after first durable write | startup reconstruction preserves semantics |

Minimum branches for F1:

- SourceMismatch;
- TypeMismatch;
- TargetMissing;
- Authorized + cleanup incomplete/failed;
- committed success + post-commit ancillary failure;
- cancellation.

A matrix cell may be `N/A` only with an explicit reason.

---

# Baseline remediation inventory

The authoritative baseline remains **22 defects**. Follow-ups above are not added to this
count.

| Order | ID | Priority | Mode | Status |
|---:|---|---|---|---|
| F1 | `BUG-BACKUP-01` | P0 | `DIRECT_LUNA_IMPLEMENTATION` | **Partial / NOT CLEAN** |
| F2 | `BUG-OUTPUT-01` | P0 | `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA` | Open |
| F3 | `BUG-OBSERVE-01` | P0 | `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA` | Open |
| F4 | `BUG-BACKUP-04` | P1 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F5 | `BUG-BACKUP-02` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F6 | `BUG-BACKUP-06` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F7 | `BUG-BACKUP-08` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F8 | `BUG-BACKUP-05` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F9 | `BUG-BACKUP-07` | P2 | `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA` | Open |
| F10 | `BUG-CLEANUP-01` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F11 | `BUG-BACKUP-03` | P0 | `SOL_EXTRA_HIGH_PLAN_THEN_LUNA` | Open |
| F12 | `BUG-KEYWORD-01` | P1 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F13 | `BUG-METADATA-02` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F14 | `BUG-METADATA-01` | P1 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F15 | `BUG-DATE-01` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F16 | `BUG-DATE-02` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F17 | `BUG-HISTORY-01` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F18 | `BUG-KEYWORD-02` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F19 | `BUG-DUPLICATE-01` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F20 | `BUG-LOCALADD-01` | P2 | `DIRECT_LUNA_IMPLEMENTATION` | Open |
| F21 | `BUG-PLAYER-01` | P2 | `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA` | Open |
| F22 | `BUG-QUEUE-01` | P3 | `DIRECT_LUNA_IMPLEMENTATION` | Open |

`BUG-BACKUP-09` remains excluded as a false positive.

---

# Exact next actions

1. Fix only the current Finding A mismatch terminal-persistence P2.
2. Add focused first-write/recovery fault-injection coverage required by checklist v3.
3. Commit and push that review fix separately.
4. Run Luna `/review` over the full Finding A scope.
5. User supplies the Luna result and explicit new HEAD.
6. Independently re-review only through that reported HEAD using the v3 matrix.
7. If Finding A is P0/P1/P2 clean and the verification gate is satisfied, begin Finding B.
8. After Finding B is separately committed/pushed, perform full F1 Luna review.
9. Once F1 is Luna-clean, proceed to F2.
10. Sol High final review remains a full-remediation close-out step, not an F1-only step.

---

# Evidence and document authority

- Primary remediation order and baseline:  
  `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md`
- Review escape-prevention gate:  
  `remediation-review-checklist-v3.md`
- Code evidence snapshot for this update:  
  `b13f58e8d63f0d6ee85e2bf13e21a3f59d2de11c`
- Remediation-discovered production issues should also be mirrored in:  
  `docs/codex/REMEDIATION_FOLLOWUPS.md`

This file is a status/task registry. It does not itself authorize implementation of a
follow-up outside the current F1 review-fix boundary.

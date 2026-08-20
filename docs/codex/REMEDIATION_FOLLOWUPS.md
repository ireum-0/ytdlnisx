# Remediation-discovered follow-ups

This file records correctness concerns discovered while remediating or reviewing
the 22 baseline defects in `docs/codex/TASKS.md`.

These entries are **not additional members of the original 22-defect baseline**
and must not change the baseline defect count or remediation order unless they
are explicitly promoted.  “Out of scope for the current defect” does not mean
“not a real problem”: confirmed or credible follow-ups stay recorded until they
are implemented, disproven, or deliberately retired.

States used here:

- **Discovered:** the failure path is credible/confirmed enough to retain, but it
  is not scheduled as the current remediation item.
- **Deferred:** intentionally postponed after review/design.
- **Promoted:** assigned to an active baseline defect or an explicitly scheduled
  remediation item.
- **Implemented:** corrected and verified.

Severity marked as **candidate** means the failure path is real enough to retain
but final priority should be re-evaluated when that item becomes active.

## BUG-BACKUP-01-FOLLOWUP-01 — Preserve created output count in forced failures

**State:** Discovered  
**Severity:** P3  
**Discovered during:** BUG-BACKUP-01 Finding A remediation  
**Ownership:** Download outcome diagnostics  
**Current remediation impact:** Non-blocking for Finding A

Forced History-replacement failure outcomes can lose the already-created output
count even though output creation occurred before the terminal failure.  This is
a diagnostic/accounting inconsistency, not authority to treat the operation as a
success.

**Required eventual result:** preserve the created-output count in forced failure
outcomes without weakening FINAL_FAILURE semantics or enabling generic partial
success.

## BUG-OUTPUT-01-FOLLOWUP-01 — Revalidate History authority before hard-sub mutation

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A review  
**Ownership:** BUG-OUTPUT-01 / hard-sub provenance and mutation ownership  
**Current remediation impact:** Out of scope for Finding A; retain for F2

`DownloadWorker.resolvePreviousHistoryMediaPaths()` authorizes the current
History target and then returns paths from that snapshot.  Hard-sub mutation can
occur later, outside the authorization transaction.  The History target can
change between authorization and the destructive in-place media mutation.

**Why it matters:** a previously authorized file can become stale authority
before mutation.  The final History replacement reauthorization does not undo an
in-place filesystem mutation that already happened.

**Relevant code:**

- `DownloadWorker.resolvePreviousHistoryMediaPaths(...)`
- post-move hard-sub fallback / `burnSubtitlesInPlace(...)`
- History replacement authorization in `HistoryKeywordAssignmentRepository`

**Required eventual result:** require operation-owned/proven output identity and
revalidate mutation authority immediately before destructive hard-sub mutation,
or otherwise stage the mutation so a stale History authorization cannot alter
live previous media.

## REMEDIATION-FOLLOWUP-HISTORY-DELETE-01 — Close the live-reference snapshot-to-delete TOCTOU

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A review  
**Ownership:** Cross-cutting History filesystem deletion  
**Current remediation impact:** Pre-existing; non-blocking for Finding A

History replacement/deletion cleanup obtains `HistoryDao.getDeletionReferenceRecords()`
and filters candidate files against that snapshot before filesystem deletion.
The reference query and filesystem delete are not one atomic ownership protocol.
Another History row can begin referencing a candidate file after the reference
snapshot but before `HistoryFileDeletionEngine.execute(...)` deletes it.

The existing deletion engine correctly handles canonical/raw/content reference
keys and alias protection; this follow-up is specifically the **time-of-check to
time-of-delete** window for newly created references.

**Relevant code:**

- `DownloadWorker.deleteValidatedReplacementPaths(...)`
- `HistoryDao.getDeletionReferenceRecords()`
- `HistoryFileDeletionEngine.excludeTargetsReferencedBy(...)`
- `HistoryFileDeletionEngine.execute(...)`

**Required eventual result:** define a deletion protocol that cannot authorize a
filesystem delete from a stale live-reference snapshot.  The solution may need a
DB-side ownership/lease/tombstone protocol or a final reference check coordinated
with deletion.

## REMEDIATION-FOLLOWUP-HISTORY-POSTCOMMIT-01 — Do not reclassify a committed History replacement as failed

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A review  
**Ownership:** History post-commit / automatic-keyword integration  
**Current remediation impact:** Pre-existing; non-blocking for Finding A

A History replacement can commit successfully, after which
`AutomaticKeywordRuleEngine.applyToHistory(...)` or another post-commit step can
throw inside the same broad History error region.  The catch can then preserve
the Download row as Error and transition a linked low-quality child to FAILED
with `HISTORY_WRITE_FAILED`, even though the authoritative History replacement
already committed.

**Why it matters:** durable History state says replacement succeeded while the
queue/ledger recovery state can say the operation failed.

**Relevant code:**

- successful History replacement branch in `DownloadWorker`
- `AutomaticKeywordRuleEngine.applyToHistory(...)`
- broad History error catch / low-quality failure transition

**Required eventual result:** establish an explicit History commit barrier.
Failures after the replacement commit must be represented as post-commit
warnings/follow-up failures and must not reclassify the already-committed History
mutation as an uncommitted replacement failure.

## BUG-BACKUP-01-FOLLOWUP-02 — Make mismatch persistence and low-quality failure durable together

**State:** Discovered  
**Severity:** P3  
**Discovered during:** BUG-BACKUP-01 Finding A review  
**Ownership:** BUG-BACKUP-01 recovery durability  
**Current remediation impact:** Non-blocking for Finding A

The Download Error row with the distinct History mismatch code is persisted
separately from the linked low-quality child transition to FAILED.  Process death
or a secondary ledger failure between those writes can leave the Download row
with the authoritative mismatch while the child must be reconciled later and may
lack the same reason code.

**Required eventual result:** persist the authoritative Download mismatch and
linked low-quality terminal reason atomically where practical, or make startup
reconciliation deterministically reconstruct the same mismatch reason.

## BUG-BACKUP-01-FOLLOWUP-03 — Preserve terminal mismatch diagnostics across post-persistence notification failure

**State:** Discovered  
**Severity:** P3  
**Discovered during:** BUG-BACKUP-01 Finding A review  
**Ownership:** BUG-BACKUP-01 outcome/notification diagnostics  
**Current remediation impact:** Non-blocking for Finding A

After durable Download Error + distinct mismatch persistence and low-quality
FAILED state, a later error-notification failure can still cause the outermost
unexpected path to replace the in-memory `downloadOutcome` with `UNKNOWN`.
Current durable recovery state remains the distinct History mismatch, so this is
not a P2 state-integrity failure, but immediate terminal logging/reporting can be
less precise than the persisted state.

**Required eventual result:** once an authoritative terminal issue is durably
persisted, later notification/reporting failures must not replace the in-memory
terminal issue; they may only append a non-authoritative notification diagnostic.

## Review checklist retained from Finding A misses

The following checks should be applied to future remediation reviews even when a
problem is ultimately assigned outside the current defect:

1. **First authoritative observation:** identify the first source/type/ownership
   decision and ensure later reauthorization cannot silently replace it.
2. **Semantic preservation:** flag typed decisions reduced to Boolean, null,
   empty collections, or generic exceptions when that loses terminal meaning.
3. **Persistence barrier:** enumerate every throwable operation between an
   authoritative decision and durable Download/ledger persistence.
4. **Post-commit barrier:** once a durable mutation commits, verify later
   ancillary failures cannot reclassify it as uncommitted failure.
5. **Outer-catch reinterpretation:** follow inner failures through every generic
   success/partial-success/retry/UNKNOWN/cancel path.
6. **Created-output interaction:** file existence must never outrank an
   authoritative authorization failure.
7. **Terminal repository API:** trace queue deletion and linked child/parent
   ledger state, not only the Worker-local outcome.
8. **Filesystem authority:** verify authority remains valid at the actual
   read/mutate/delete point, not only when a path was first obtained.
9. **Reference protection:** check both alias/canonical identity and concurrency
   between reference snapshot and deletion.
10. **Retry/reconfigure/recovery:** inspect alternate UI, bulk, restore, and
    startup paths for policy bypass.
11. **Restore authority:** validate the origin of remapped IDs, not only marker
    parsing/remapping itself.
12. **Attribution last:** retain real defects even when they are pre-existing,
    out of current scope, or P3; classify ownership after correctness is
    established.

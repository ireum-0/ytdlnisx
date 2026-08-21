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

## BUG-BACKUP-01-FOLLOWUP-03 — Preserve terminal failure diagnostics across post-persistence notification failure

**State:** Discovered  
**Severity:** P3  
**Discovered during:** BUG-BACKUP-01 Finding A review  
**Ownership:** Download outcome / notification diagnostics  
**Current remediation impact:** Non-blocking for Finding A

The original form of this follow-up concerned an authoritative
SourceMismatch/TypeMismatch being replaced in-memory by `UNKNOWN` after a later
notification failure.  The mismatch-specific case is now protected by the
attempt-local authoritative mismatch carrier introduced by
`fa1156be49d8ca6527948d1d7cffe0ad4f98cd18`.

A broader non-mismatch diagnostic defect remains.  After an ordinary terminal
failure has already persisted the Download Error row and linked FAILED reason, a
later exception from `createDownloadErrored(...)` or another non-authoritative
notification/reporting step can reach the outer unexpected catch while
`historyReplacementFailureIssue == null`.  That catch can replace the in-memory
`DownloadOutcome` with a newly classified `UNKNOWN` even though the durable
Download row and linked reason still contain the original terminal issue.
WorkManager can still return handled success, so immediate logging/reporting can
disagree with the already-persisted authoritative failure semantics.

This is a diagnostic/outcome-consistency defect rather than a new destructive
authority path: the persisted terminal state is not reverted by the notification
failure, and the mismatch path itself remains separately protected.

**Required eventual result:** once any authoritative terminal issue has been
durably persisted, later notification/logging/reporting failures must not replace
the in-memory terminal issue or `DownloadOutcome`; they may only append a
non-authoritative ancillary diagnostic.  Add focused coverage for an ordinary
non-mismatch terminal failure followed by notification failure, while retaining
mismatch-specific regression coverage.

## REMEDIATION-FOLLOWUP-WORKER-CANCELLATION-01 — Isolate item-local cancellation from worker-global cancellation

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A remediation review at `963ba9a936f4f3570a815bb00e39e00cd0124ca9`  
**Ownership:** Cross-cutting DownloadWorker concurrency / `BUG-CANCEL-01` adjacent  
**Current remediation impact:** Current-change regression; also blocks Finding A while present

`0251d144a0b38af463049e6d507c64ddfa82ae51` replaced the shared child
`coroutineScope` with `runDownloadItemsIndependently(...)` so a non-cancellation
failure in one item no longer immediately cancels its siblings.  The helper,
however, treats **every** child `CancellationException` as worker-global
cancellation by calling `scopeJob.cancel(cancelled)`.

Production item-local pause/cancel paths do not imply worker-global cancellation.
`pauseDownload(id)` persists only that row as `Paused` and destroys only that
item's process; yt-dlp execution can then convert the row-local `Paused` or
`Cancelled` state into a `CancellationException`.  With concurrent downloads,
that exception now cancels the supervisor scope and unrelated sibling children.
Those siblings can exit through cancellation while their rows remain
`Active`/`PostProcessing`, and `cleanupStoppedWorker()` is not guaranteed because
an item-local cancellation does not itself prove `DownloadWorker.isStopped`.

This is broader than History replacement: pausing or cancelling an ordinary
concurrent download can affect unrelated ordinary work.

**Required eventual result:** distinguish item-local cancellation from
worker/parent-global cancellation using authoritative state/ownership rather
than exception type alone.  An item-local `Paused`/`Cancelled` transition must
stop only that item and allow siblings to continue.  A genuine WorkManager or
parent cancellation must still cancel the whole batch.  Add production-wiring
coverage with `concurrent_downloads >= 2` for single-item pause, single-item
cancel, worker-global cancel, and an unrelated sibling that must not be stranded
as `Active`/`PostProcessing`.

## REMEDIATION-FOLLOWUP-DOWNLOAD-STATE-CAS-01 — Require expected-state ownership before manual requeue/resume

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A adjacent-path review  
**Ownership:** Cross-cutting Download queue state transitions  
**Current remediation impact:** Pre-existing / non-blocking for Finding A

Several user-triggered queue transitions update by Download ID without proving
that the row is still in the state that authorized the action.  In particular,
`DownloadDao.reQueueDownloadItems(...)` can write `Queued` for an ID regardless
of whether the current row is still `Paused`, and
`resetScheduleTimeForItems(...)` can write `Queued` without requiring the row to
still be `Scheduled`.  Both callers start a new DownloadWorker when the update
reports any affected row.

This creates a production race with stale UI/notification actions.  A Resume
action can be authorized from an earlier paused snapshot, then run after the
row has already become `Active`; the unconditional update can demote that live
row back to `Queued` while its original yt-dlp/post-processing owner continues.
Because `startDownloadWorker(...)` enqueues an independent work request and the
DB no longer advertises the original row as `Active`, another worker can select
the same Download ID and start overlapping work.  The scheduled "download now"
path has the same race if a scheduled row is claimed by a worker between the UI
snapshot and `resetScheduleTimeForItems(...)`.

`PauseDownloadNotificationReceiver` exposes an additional concrete path: its
`finally` block always creates a Resume notification even when the authoritative
`dao.update(Paused)` throws.  In that failure case the process-destruction calls
are skipped, so the original download can still be running while the UI advertises
Resume; a later raw resume can then rewrite that live row to `Queued` if the DB
has recovered.

**Required eventual result:** make user state transitions compare-and-set the
expected current state (`Paused -> Queued`, `Scheduled -> Queued`, and analogous
manual transitions) and start new work only after that exact transition succeeds.
A stale/double-delivered intent must be a no-op rather than demoting
`Active`/`PostProcessing`.  Show a Resume action only after durable pause
persistence succeeds.  Add deterministic stale-intent races for Resume and
scheduled "download now", plus pause-persistence failure coverage proving that
no live process can be paired with a falsely requeued row or duplicate worker.

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

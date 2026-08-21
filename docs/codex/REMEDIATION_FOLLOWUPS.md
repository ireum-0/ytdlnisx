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

## BUG-BACKUP-01-FOLLOWUP-04 — Make the authoritative mismatch barrier monotonic and crash-durable

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A v4 review at `963ba9a936f4f3570a815bb00e39e00cd0124ca9`  
**Ownership:** BUG-BACKUP-01 Finding A mismatch durability / queue-state concurrency  
**Current remediation impact:** Blocking Finding A

The current fix reconstructs a persisted SourceMismatch/TypeMismatch from
`Download.lastIssueCode` and correctly blocks ordinary retry/reconfigure paths.
That barrier is not yet monotonic against all concurrent state transitions or
process death.

Three concrete windows remain:

1. **Recovery skipped after a concurrent state change.**  If the first terminal
   mismatch `dao.update(Error + mismatch)` fails, the outer recovery writes the
   mismatch only while the latest row is `Active` or `PostProcessing`.  If a
   concurrent pause changes the row to `Paused` first, `recoveryResult` remains
   null.  `unrecoverableHistoryReplacementPersistenceFailure(...)` treats null
   as no unrecoverable mismatch persistence failure, so the worker can finish
   without ever durably recording the authoritative mismatch.  A later resume
   can then continue the privileged marker without the barrier.

2. **A persisted barrier can be overwritten by a stale full-row writer.**
   `PauseDownloadNotificationReceiver` reads a `DownloadItem`, changes only its
   in-memory status to `Paused`, and later performs full-row `dao.update(item)`.
   If the worker persists a mismatch Error between that read and write, the stale
   pause object can overwrite the newer mismatch issue fields.  The worker claim
   path has the same structural risk: multiple independently enqueued
   `DownloadWorker`s observe Room queue snapshots, and selection marks a snapshot
   `Active` with full-row `dao.updateMultiple(...)` rather than a status/ownership
   CAS.  A delayed stale queued snapshot can therefore overwrite a newer Error +
   mismatch record after another worker has terminalized the same ID.

3. **The first authoritative mismatch is not crash-durable until a separate
   Download write succeeds.**  History authorization/replacement returns
   SourceMismatch/TypeMismatch in one Room transaction, while the Download Error
   carrier is written afterward.  Process death in that gap leaves the durable
   row without the mismatch barrier.  Subsequent recovery/pause/resume/requeue
   can therefore revisit the same privileged marker as if no authoritative
   mismatch had been established.

These are Finding A issues because the same History replacement operation can
lose a previously authoritative refusal and later be reauthorized from mutable
or newly observed state.  The problem is not merely queue liveness or UI
consistency.

**Required eventual result:** make the mismatch barrier monotonic for the
lifetime of the privileged History-replacement operation.  Stale/full-row queue
writers must not be able to clear it; worker claim should use an expected-state
ownership transition that preserves newer issue/marker fields; terminal recovery
must not treat a skipped write as successful preservation when an authoritative
mismatch exists; and the design must define a crash-durable carrier or immutable
operation identity so process death between mismatch observation and terminal
queue persistence cannot reopen destructive authority.  Preserve `Paused` or
`Cancelled` user intent without erasing the mismatch semantic barrier.  Add
fault/race coverage for first-write failure + concurrent pause, stale pause
full-row write after mismatch persistence, stale multi-worker queue claim after
mismatch persistence, and process death/restart before the first mismatch carrier
write.

## BUG-BACKUP-01-FOLLOWUP-05 — Preserve hard-sub authorization failures as authoritative History semantics

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A v4 review at `c64ddbc26a41d4bba8010d9e30f5ffb24b2336da`  
**Ownership:** BUG-BACKUP-01 Finding A / hard-sub previous-media authorization propagation  
**Current remediation impact:** Blocking Finding A

`DownloadWorker.resolvePreviousHistoryMediaPaths(...)` now invokes the same
source/type/current-target authorization required by Finding A before exposing
previous History media.  However, the helper returns the authorized snapshot only
for `Authorized` and collapses `TargetMissing`, `SourceMismatch`, and
`TypeMismatch` alike to `emptyList()`.

That loses the first authoritative semantic decision.  In the pre-move and
post-move hard-sub fallback paths, an authorization mismatch can therefore be
followed by a generic "no media" / hard-sub `IOException` before final History
replacement is reached.  Because no `historyReplacementFailureIssue` is
established, the generic failure can be persisted instead of the exact
SourceMismatch/TypeMismatch, and retry/reconfigure logic may later treat the same
privileged marker as an ordinary failure.  If execution does continue far enough
to authorize again, a changed target can also replace the first refusal with a
later `Authorized` result.  `TargetMissing` is similarly prevented from becoming
the required `TARGET_DELETED` semantic at this first authoritative observation.

This is distinct from `BUG-OUTPUT-01-FOLLOWUP-01`: that follow-up owns the
**Authorized -> later destructive mutation** TOCTOU.  This entry owns loss of a
**non-authorized result itself** before any previous-media authority is granted.
The pre-F1 implementation read the History row directly by numeric ID; Finding A
introduced typed source/type authorization here, so preserving those typed
non-authorized results is part of the current remediation contract.

**Required eventual result:** return or propagate a typed previous-media
authorization result instead of reducing every refusal to an empty path list.
The first SourceMismatch/TypeMismatch must immediately establish the same
monotonic authoritative mismatch barrier used by final replacement, and later
hard-sub errors, output recovery, or reauthorization must not downgrade or
replace it.  TargetMissing must remain distinguishable and enter the defined
`TARGET_DELETED` semantics.  Add focused pre-move and post-move hard-sub tests
where source/type changes before previous-media fallback, including a generic
hard-sub failure before final History replacement and a later target change that
must not reauthorize the already-refused privileged operation.

## BUG-BACKUP-01-FOLLOWUP-06 — Bind low-quality selection to immutable History source/type identity

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A v4 re-review at `c64ddbc26a41d4bba8010d9e30f5ffb24b2336da`  
**Ownership:** BUG-BACKUP-01 Finding A / low-quality replacement operation identity  
**Current remediation impact:** Blocking Finding A

The low-quality replacement flow discovers and presents a candidate from a
specific live `HistoryItem`, but the durable `LowQualityRedownloadItem` records
only the numeric `historyId` plus quality/selection state.  It does not retain the
source URL identity or compatible media type that made that History row the
intended replacement target when the user selected it.

During `PREPARING`, `LowQualityRedownloadWorker` therefore re-loads the current
History row by `ledgerItem.historyId`, performs qualification and metadata lookup
against the row's **current** `url`/type, and constructs the replacement Download
from that new snapshot.  A normal History metadata update can keep the same
History ID while changing the URL.  If that happens after scan/selection but
before preparation, the operation can silently rebind from the originally
selected source A to a different source B.  The resulting Download then carries
B as its expected source together with `history-redownload:<same id>`, so the
later DownloadWorker source/type check can authorize B and replace/delete media
for B even though the user-selected low-quality operation was established for A.

This remains production-reachable across process death because the low-quality
ledger has no durable source/type carrier to reconstruct the original intended
target after restart.  It is also distinct from `BUG-LOWQUALITY-01`, which owns
Download/low-quality terminal atomicity and exact terminal-reason durability;
this entry owns **privileged operation identity before the child Download is
created**.

**Required eventual result:** bind every selected low-quality replacement to an
immutable, crash-durable intended History identity that includes the source
identity and compatible media type established for the selected candidate.
`PREPARING` must compare the current live History target against that identity
before metadata lookup or child Download creation.  `TargetMissing` must remain
fail-closed with its defined target-deleted/skip semantics, and source/type drift
must become the corresponding non-authorized outcome rather than rebind the same
privileged operation to the new row contents.  Do not create or retain a History
replacement marker for a changed target unless the original privileged operation
has been explicitly and irrevocably abandoned in favor of a normal unprivileged
download.  Add focused scan -> selection -> History URL/type change -> prepare
coverage, plus process-death/restart coverage proving that a selected operation
cannot rebind to a different source while preserving the same numeric History ID.

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
13. **Durable operation identity:** when a user selection, coordinator ledger,
    retry token, or marker authorizes later privileged work, persist the source /
    type identity that established that authority.  Re-loading a mutable row by
    numeric ID and validating only its new current fields is not proof that the
    original operation still targets the same object.

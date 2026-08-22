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
however, treated every child `CancellationException` as worker-global
cancellation.

`3046ef449e2880ad80a8ddc4dfbcc28a0749e1a1` partially corrected that behavior by
introducing an execution-token keyed `DownloadCancellationRegistry`.  Direct
per-item Pause/Cancel, including rapid Pause -> Resume where the durable row has
already changed again before the old child sees cancellation, can now retain its
item-local origin without cancelling unrelated siblings.

A production gap remains at the authorized review state
`f1a9dbc1dc73c4c176c3ed9830b31fbb896a324b`.  Low-quality operation cancellation
and terminal coordinator failure call `LowQualityRedownloadRepository` paths that
write linked active Downloads directly to `Cancelled` through
`DownloadDao.cancelLinkedDownloads(...)`.  Those transitions do not record the
linked rows' `(downloadId, executionId)` in `DownloadCancellationRegistry` before
the manager/worker destroys yt-dlp and post-processing.  The resulting child
`CancellationException` is therefore not recognized as item-local by
`DownloadWorker`, so it is reclassified as worker-global cancellation and can
cancel unrelated sibling downloads in the same DownloadWorker batch.

This is broader than History replacement: cancelling one low-quality operation
may legitimately stop its own linked Downloads, but it must not stop unrelated
ordinary or unrelated replacement downloads that merely share the same worker.

**Required eventual result:** every production path that intentionally stops only
a Download item or a bounded linked set must persist/record a stable
execution-attempt cancellation cause before or atomically with the row-local
state transition and before process destruction.  Direct Pause/Cancel,
low-quality operation cancellation, low-quality coordinator-failure cancellation,
and any equivalent linked/bulk local cancellation must all remain item-local from
the DownloadWorker batch's perspective.  Genuine WorkManager/parent cancellation
must still cancel the whole batch.  Add production-wiring coverage with
`concurrent_downloads >= 2` proving low-quality linked cancellation and
coordinator failure do not cancel or strand an unrelated sibling, while global
worker cancellation still does.

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
pause persistence throws.  In that failure case the process-destruction calls
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

The original cross-attempt fix reconstructed a persisted SourceMismatch/TypeMismatch
from `Download.lastIssueCode` and blocked ordinary retry/reconfigure paths, but
that carrier was not monotonic against all concurrent state transitions or
process death.  Review identified recovery-skipped-after-pause, stale full-row
writer, stale multi-worker claim, and process-death-before-Download-write windows.

`3046ef449e2880ad80a8ddc4dfbcc28a0749e1a1` substantially closes the direct
forms of those windows with a dedicated `history_replacement_barriers` table,
transactional mismatch-barrier creation, execution-owned worker claims, guarded
full-row writes, and barrier-aware exceptional/startup recovery.  The remaining
Finding A blockers are tracked separately below where the active attempt fails
to adopt a barrier created mid-attempt, where the pre-child low-quality operation
identity is not durable, and where execution-attempt ownership does not yet guard
all privileged/terminal side effects.

**Required eventual result:** retain the monotonic barrier for the full lifetime
of the privileged operation and keep all stale/full-row/claim/recovery paths
fail-closed.  Verification must include process-death/restart, first-write
failure plus concurrent Pause/Cancel, stale worker claim, zero-row cleanup, and
legacy/migration behavior; table-existence-only migration coverage is not enough.

## BUG-BACKUP-01-FOLLOWUP-05 — Preserve hard-sub authorization failures as authoritative History semantics

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A v4 review at `c64ddbc26a41d4bba8010d9e30f5ffb24b2336da`  
**Ownership:** BUG-BACKUP-01 Finding A / hard-sub previous-media authorization propagation  
**Current remediation impact:** Blocking Finding A

`DownloadWorker.resolvePreviousHistoryMediaPaths(...)` invokes the same
source/type/current-target authorization required by Finding A before exposing
previous History media.  At the current authorized state the helper now passes
replacement Download/operation identity, so SourceMismatch/TypeMismatch can
create the crash-durable barrier even when first observed mid-attempt.  However,
the helper still returns the authorized snapshot only for `Authorized` and
collapses `TargetMissing`, `SourceMismatch`, and `TypeMismatch` alike to
`emptyList()`.

The new durable barrier therefore does not automatically update the already
running child's `historyReplacementFailureIssue`, which was initialized only at
child start.  A mid-attempt SourceMismatch/TypeMismatch can create the barrier,
return `emptyList()`, and then be followed by a generic hard-sub failure.  Generic
Error/UNKNOWN writes can be rejected by the new barrier guard while outer
recovery still reasons as though no authoritative mismatch was established,
allowing terminal/liveness inconsistency.  `TargetMissing` remains even more
directly lossy because it has no mismatch barrier and can still be reduced to a
generic hard-sub/no-media failure instead of the required `TARGET_DELETED`
semantic.

This is distinct from `BUG-OUTPUT-01-FOLLOWUP-01`: that follow-up owns the
**Authorized -> later destructive mutation** TOCTOU.  This entry owns propagation
of a **non-authorized result itself** into the active attempt's terminal state
machine.

**Required eventual result:** return or propagate a typed previous-media
authorization result instead of reducing refusals to an empty path list.  The
first SourceMismatch/TypeMismatch must immediately establish and be adopted as
the active attempt's monotonic authoritative issue even when the barrier appears
mid-attempt; later hard-sub errors, output recovery, or reauthorization must not
downgrade or replace it.  TargetMissing must remain distinguishable and enter the
defined `TARGET_DELETED` semantics.  Add focused pre-move and post-move hard-sub
tests including barrier-created-after-child-start, generic failure after refusal,
and target-missing-before-final-replacement cases.

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

## BUG-BACKUP-01-FOLLOWUP-07 — Require execution-attempt ownership for privileged mutation and terminal completion

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** BUG-BACKUP-01 Finding A re-review at `f1a9dbc1dc73c4c176c3ed9830b31fbb896a324b`  
**Ownership:** BUG-BACKUP-01 Finding A / execution-attempt ownership  
**Current remediation impact:** Blocking Finding A

`3046ef449e2880ad80a8ddc4dfbcc28a0749e1a1` adds an `executionId` token and uses
it for worker claims, metadata writes, failure persistence, and exceptional
requeue.  Privileged History mutation and successful terminal repository actions,
however, are still authorized without that execution token.

A concrete Pause -> rapid Resume race remains.  Old attempt E1 can finish yt-dlp
or reach late post-processing before the Pause process destruction takes effect.
Pause records E1's item-local cancellation and changes the row to `Paused`; Resume
then returns the same Download row to `Queued`, and a new worker claims it as E2
with a new `executionId`.  If E1 never receives a `CancellationException`, its
`shouldStopForUserRequest()` only sees the row's new `Active` status and does not
verify that the current `executionId` is still E1.  E1 can therefore continue
into `replaceHistoryPreservingAssignmentsAuthorizedBlocking(...)`, whose
Download-backed authorization receives Download ID and operation ID but no
execution token.

The same missing ownership proof exists after the History phase.  The normal
success/target-deleted path calls `DownloadRepository.completeAndDelete(id)` or
`completeHistoryTargetDeletedAndDelete(id)`.  Those APIs can terminalize a linked
low-quality child, delete the History mismatch barrier, and delete the Download
row solely by numeric Download ID.  A stale E1 can therefore mutate History and
then delete E2's live queue row while E2 owns/runs the newer attempt.  Other
terminal side effects that use only ID/current status, such as membership parking,
must be audited for the same stale-attempt hazard.

**Required eventual result:** execution-attempt ownership must guard the actual
privileged commit and every terminal side effect that can mutate/delete state for
the running Download.  For Download-backed History replacement, verify the
expected `executionId` in the same Room transaction that validates and mutates
the History target.  Success delete, target-deleted delete, linked-ledger terminal
transition, membership parking, barrier deletion, and equivalent terminal APIs
must either prove the expected execution token or prove that no newer attempt can
own the row.  An old attempt that loses ownership must stop without mutating
History, deleting the newer Download row, or terminalizing state owned by the
newer attempt.  Add a deterministic E1 Pause -> Resume -> E2 claim race after
E1's transfer completes but before History commit, and prove E1 cannot mutate
History or terminalize/delete E2.

## REMEDIATION-FOLLOWUP-WORKER-OWNERSHIP-01 — Do not retain dead static ownership after cleanup persistence failure

**State:** Discovered  
**Severity:** P2 candidate  
**Discovered during:** DownloadWorker ownership re-review at `f1a9dbc1dc73c4c176c3ed9830b31fbb896a324b`  
**Ownership:** Cross-cutting DownloadWorker liveness / ownership reconciliation  
**Current remediation impact:** Current-change regression; blocks CLEAN while present

`DownloadWorker.ownedDownloadIds` is a process-static ID set used to exclude
apparently live rows from stale-worker cleanup.  In the current exceptional/global
cleanup, rows are removed from that static set only after the cleanup path proves
them `releasedIds`.  If the DB requeue/cleanup write itself throws, the worker
still exits and its per-worker ownership maps are cleared, but the affected ID can
remain in `ownedDownloadIds`.

That stale static marker no longer represents a live worker.  A later
`cleanupStoppedWorker(includeStaleRows = true)` filters `Active`/`PostProcessing`
rows whose IDs remain in `ownedDownloadIds`, so the dead row can be hidden from
same-process stale-row recovery.  For an ordinary download with no History
mismatch barrier, `recoverDurableMismatchRowsAtStartup()` also does not apply.
The ghost `Active` row can then continue to consume a concurrency slot and block
queued work until process restart even though its original worker and native
processes are gone.

This is a regression from the earlier cleanup behavior, which removed the whole
active ID set from the static ownership set after the worker's cleanup attempt,
even when durable requeue failed.  The new execution-aware design correctly
needs to avoid releasing a newer attempt's ownership, but an unrecoverable DB
cleanup failure must not leave a dead old attempt represented as a live owner
forever.

**Required eventual result:** process-static ownership must model a live exact
execution attempt, not merely a Download ID whose cleanup persistence failed.
When a worker/attempt exits, its live-owner marker must be released even if the
DB requeue write failed, while preserving any distinct newer attempt's ownership.
Persist or reconstruct enough execution-owner state that a later worker in the
same process can identify and reconcile `Active`/`PostProcessing` rows whose
owner no longer exists.  Add a deterministic test that injects an ordinary
cleanup requeue DB failure, lets the worker exit, then starts another worker in
the same process and proves the stale row is detected/recovered rather than
permanently consuming a concurrency slot.  Also prove cleanup from E1 cannot
release or requeue a live E2 token for the same Download ID.

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
14. **Execution-attempt ownership:** once a Download can be reclaimed with a new
    execution token, every old child must prove it still owns that exact token at
    privileged History mutation and terminal repository boundaries, not only at
    queue claim or failure persistence.
15. **Live-owner lifecycle:** a cleanup persistence failure may leave a durable
    row unresolved, but it must not leave an exited attempt represented forever
    as a live process-static owner that suppresses later stale-row recovery.

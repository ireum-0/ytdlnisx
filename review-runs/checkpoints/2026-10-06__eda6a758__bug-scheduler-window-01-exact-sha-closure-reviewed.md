# BUG-SCHEDULER-WINDOW-01 — published exact-SHA closure review

Date: 2026-10-06

record_kind: IMPLEMENTATION_COMPLETION_REVIEW
record_status: FINAL
manual_review_run: NO

finding: BUG-SCHEDULER-WINDOW-01
severity_before_closure: P2
implementation_base: db29f63ce169176b4c8ade4cec01f66cc0307ec8
implementation_final_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
implementation_branch: checkpoint/pre-baseline-review
review_parent_sha: 801cfe4ba1d6525c045e6fdf2afa3c205fe9c003
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d

## History / scope integrity

GitHub compare:
- relation: db29f63 -> eda6a758 is direct forward ancestry;
- commit count: 1;
- commit: eda6a7589af3a19a97eb38e869b47dabaf74388b
  "fix(scheduler): use circular minute windows and normalized alarms";
- changed files: exactly four;
  - app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt
  - app/src/main/java/com/ireum/ytdl/work/ScheduledDownloadWindow.kt
  - app/src/test/java/com/ireum/ytdl/work/ScheduledDownloadWindowTest.kt
  - app/src/androidTest/java/com/ireum/ytdl/work/ScheduledDownloadWindowProductionWiringTest.kt
- unrelated expansion: NONE_FOUND;
- history rewrite/non-forward update: NONE_FOUND;
- remote implementation branch head at review: eda6a7589af3a19a97eb38e869b47dabaf74388b.

The three production consumers themselves are unchanged across the range:
- DownloadViewModel.kt blob:
  9c817cec48f124782256cb8be4bc3d8385d218bc before/after;
- DownloadWorker.kt blob:
  c0a772d926ec8b33fb9588f4cc7b305304965f82 before/after;
- ObserveSourceWorker.kt blob:
  3d06dcd55896f77c82c7b8fc8899815ab3c4ea35 before/after.

They all continue to consume AlarmScheduler.isDuringTheScheduledTime(), so one corrected predicate
propagates to all three production paths without divergent copies.

## Independent source-semantic review

### Circular-day membership

ScheduledDownloadWindow parses the configured start/end into minute-of-day integers.

Membership:
- start <= end:
  current minute must be in start..end;
- start > end:
  current minute must be >= start OR <= end.

This gives one coherent circular-day model for:
- ordinary same-day windows;
- overnight windows across midnight;
- overnight windows ending after noon;
- exact 00:00 / 23:59 boundaries.

Boundary minutes are inclusive.

Seconds/milliseconds do not alter membership inside the configured minute. This matches the
minute-granularity preference contract and removes the previous current-hour/adjusted-end-hour logic.

### Equal boundary contract

start == end selects exactly that one minute.

This is explicit in source semantics rather than an accidental branch effect:
the same-day interval becomes minute in start..end with equal endpoints.

### Boundary scheduling

nextStart()/nextEnd() delegate to one nextBoundary() implementation.

The returned Calendar:
- clones the supplied current Calendar;
- preserves zone/date context;
- sets exact configured hour/minute;
- sets SECOND=0;
- sets MILLISECOND=0;
- advances one calendar day only when the target minute-of-day is earlier than the current
  minute-of-day.

The old asymmetry/typo where end setup did not reliably normalize its own seconds is removed.

The same normalized millis are used both for exact-alarm publication and durable scheduler handoff
notBeforeAt values through AlarmScheduler.scheduleWithinOrdinaryMutation().

### Production propagation

DownloadViewModel.queueDownloads():
- scheduler enabled + outside window -> persist queued items and schedule;
- otherwise -> start worker.

DownloadWorker:
- when scheduler enabled and no special runnable/recovery ownership remains, outside-window state
  can stop the worker.

ObserveSourceWorker:
- both membership-requeue and ordinary queue-publication paths use the same
  AlarmScheduler.isDuringTheScheduledTime() decision before schedule-vs-start.

No independent start/end arithmetic remains in those consumers.

## Test evidence

Reported implementation execution:
- focused Android scheduler gate: 3 PASS / 0 FAIL;
- exact-final-SHA verification:
  - 45 JVM PASS;
  - 24 Android PASS;
  - 0 failures;
  - 0 skips;
- worktree clean;
- index empty;
- normal fast-forward publication;
- remote equality verified.

The sealed local report was reported at:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-one-wave-20261006-8d4e677c/BUG_SCHEDULER_WINDOW_01_PUBLISHED_EXACT_SHA_CLOSURE_REPORT.md

The local report is not GitHub-hosted and was not independently opened by this reviewer.
Its reported counts are supporting execution evidence, not a substitute for the source-semantic review
above.

Committed deterministic coverage independently inspected includes:
- same-day inside/outside;
- exact start/end;
- same-hour minute bounds;
- overnight before midnight;
- overnight after midnight;
- overnight afternoon end;
- 00:00 / 23:59;
- equal start/end;
- seconds/milliseconds membership;
- normalized boundary calendars;
- next-day rollover;
- current boundary-minute behavior;
- timezone/calendar preservation;
- exhaustive all-1440-minute checks across representative windows;
- real AlarmScheduler persisted-preference wiring;
- durable start/end handoff timestamp equality with published normalized alarm timestamps.

## Independent verdict

BUG_SCHEDULER_WINDOW_01_STATUS=FIXED_CLOSED
SOURCE_SEMANTICS=VERIFIED
SCOPE=VERIFIED_NARROW
HISTORY_INTEGRITY=VERIFIED_FORWARD_ONLY
PUBLICATION=VERIFIED_AT_REMOTE_HEAD
EXACT_FINAL_SHA=eda6a7589af3a19a97eb38e869b47dabaf74388b
SAME_ROOT_RESIDUAL=NONE_FOUND
NEW_ROOT_FROM_THIS_CHANGE=NONE_FOUND
REPOSITORY_WIDE_CLEAN=NO

Canonical repository-wide state after this closure:
- P0: 0
- P1: 1
- P2: 11
- BUG-SCHEDULER-WINDOW-01 removed from open roots
- active download blockers:
  - BUG-FORMAT-BG-01
  - BUG-FORMAT-BG-02
  - BUG-FORMAT-BG-03
  - BUG-FORMAT-BG-04
  - BUG-FORMAT-BG-05
  - BUG-INCOGNITO-01
- active download blocker count: 6
- PO Token remains PAUSED until all six active download blockers close.

Repository-wide CLEAN remains revoked. Closing this root does not supersede the other open findings or
the prior full-manual review results.

## Next governed action

Use the already-recorded correction-boundary checkpoint:
review-runs/checkpoints/2026-10-06__db29f63__remaining-download-blockers-correction-boundaries.md

Revalidate its BG-01/BG-02 producer-handoff contract only against the bounded db29f63..eda6a758 delta.
Because that delta touches scheduler-only files, unchanged format-background source blobs may be reused
as exact proof.

Next implementation wave:
FMT-PRODUCER = BUG-FORMAT-BG-01 + BUG-FORMAT-BG-02

Canonical roots remain independent even when implemented in one wave.

INDEPENDENT_REVIEW_REQUIRED=YES

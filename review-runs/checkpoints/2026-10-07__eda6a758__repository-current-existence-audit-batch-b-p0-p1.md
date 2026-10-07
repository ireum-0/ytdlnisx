# Repository current-existence audit — batch B — historical P0/P1 roots

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 3e4c68c9d4e4382f9342d0d0f6fb1d5f9b25a955
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## VERIFIED_CLOSED / CURRENTLY NOT REPRODUCED

### BUG-BACKUP-01
Current disposition: VERIFIED_CLOSED.
Evidence chain:
- historical F1 / Finding-A closure is sealed in evidence/FINDING_A_CLOSURE_2026-09-03_70437F76.md;
- Finding A is recorded CLEAN with zero P1/P2 blockers;
- later known-good/final-heavy review did not reopen the root;
- db29f63..eda6a758 changes only scheduler production files.

### BUG-OBSERVE-01
Current disposition: VERIFIED_CLOSED.
Evidence:
- exact FIXED-CLOSED checkpoint at 9c5191c3;
- typed AUTHORITATIVE/PARTIAL/FAILED source authority and production-boundary WorkManager/Room evidence;
- later Observe reviews preserved the closure;
- current post-KGB delta does not touch Observe source authority.

### BUG-OUTPUT-01
Current disposition: VERIFIED_CLOSED_CURRENT_PATH_ABSENT.
Evidence:
- exact-basis review at 4ef990e0 established the historical recency-based privileged recovery path was absent
  and ordered DO NOT PROMOTE;
- current eda6a758 DownloadWorker still contains no recoverPathsFromDirectory symbol;
- privileged output handling still uses DownloadOutputProvenance;
- current lastModified use inspected here is diagnostic/logging context rather than primary output
  authority;
- current post-KGB delta does not touch Download output provenance.

This is not classified as a historical false positive. The historical defect record is preserved; the
current unsafe path is simply not present.

### BUG-HISTORY-02
Current disposition: VERIFIED_CLOSED_CURRENT_PATH_ABSENT.
Evidence:
- exact 90afaec1 regression revalidation found the historical retained-reference TOCTOU still not
  reproduced;
- current HistoryRepository deleteRecords/deleteAllRecords/updateDownloadPath use
  HistoryReferenceMutationCoordinator;
- current HistoryKeywordAssignmentRepository insertHistory/restoreHistory also participate in the same
  relationship mutation domain;
- current post-KGB delta does not touch these production files.

### BUG-BACKUP-04
Current disposition: VERIFIED_CLOSED.
Evidence:
- exact c67ecb66 closure records CLEAN/CLOSED after same-second staging identity residual closure;
- subsequent backup waves retained the invocation-local publication contract;
- final known-good review did not reopen it;
- current post-KGB delta is scheduler-only.

### BUG-KEYWORD-01
Current disposition: VERIFIED_CLOSED.
Evidence:
- exact 4ef990e0 closure records CLEAN/FIXED-CLOSED;
- baseline/discovery remains gated by typed source authority rather than item count;
- later automatic-keyword work preserved this root while fixing distinct handoff ownership;
- current post-KGB delta is scheduler-only.

### BUG-METADATA-01
Current disposition: VERIFIED_CLOSED.
Evidence:
- exact 9edd3e23 closure records CLEAN/FIXED-CLOSED;
- full-row metadata publication was replaced by narrow, source/execution-guarded publication;
- later reviews did not reopen the historical stale-full-row root;
- current post-KGB delta is scheduler-only.

### BUG-TERMINAL-02
Current disposition: VERIFIED_CLOSED_CURRENT_PATH_ABSENT.
Evidence:
- exact 4ef990e0 broader-registry revalidation found the historical cross-table numeric task-identity root
  not reproduced;
- current TerminalDownloadWorker still uses YtdlpProcessIdentity.terminal(...);
- current CancelTerminalNotificationReceiver uses TerminalCancellationCoordinator and terminalDao, not
  ordinary Download cancel authority;
- current foreground entry is awaited through suspending setForeground(...);
- current post-KGB delta is scheduler-only.

The older low-severity notification-ID collision candidate is a distinct candidate and does not reopen
the historical cross-record mutation root.

## Batch result

roots_audited: 8
verified_closed_or_currently_not_reproduced: 8
verified_open: 0
reopened: 0
not_verified: 0

Cumulative repository lineage progress after batches A+B:
- candidate IDs: 136
- audited: 23
- verified closed/currently not reproduced: 23
- current download canonical count remains P0=0/P1=0/P2=8.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.

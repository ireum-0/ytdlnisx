# BUG-UPDATER-04 recovery diagnostic preparation complete — single-method rerun authorized

review_parent_sha: fa9206d5e1a48784ede11f986eae87ab8237c477
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_committed_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_committed_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_committed_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
DIAGNOSTIC_PREPARATION_ACCEPTED
ONE_SINGLE_METHOD_DIAGNOSTIC_RERUN_AUTHORIZED
FULL_FOCUSED_SET_NOT_AUTHORIZED
FULL_CLASS_NOT_AUTHORIZED
PUSH_NOT_AUTHORIZED
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted implementation-agent preparation evidence:
- only YtdlpRuntimeAuthorityProductionWiringTest.kt was changed for read-only BEFORE/AFTER diagnostics around laterUpdaterProgressAfterExactMutationRecovery;
- the existing single recoverGeneration(...) call count was preserved;
- the original success condition was preserved;
- complete debug AndroidTest compilation PASS;
- git diff --check PASS;
- device tests executed: 0;
- production edits: 0;
- commits/pushes: 0;
- exact committed candidate 05c1fc2ed53531da6935f93470df93028bd799f3 remained preserved;
- both indexes and all prior failure-evidence records remained preserved.

Preparation report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-diagnostic-prep-66e69c5c029b4595b55061ae0a49317c/REPORT.md

Evidence confidence:
- diagnostic-harness mechanics are implementation-agent evidence because the AndroidTest overlay is still local-only/uncommitted;
- compilation and diff-check support safe execution of the prepared diagnostic harness;
- this authorization does not treat the harness change as independently reviewed production source and does not close any execution or source gate.

Authorized next execution:
Run exactly once:
com.ireum.ytdl.util.YtdlpRuntimeAuthorityProductionWiringTest#laterUpdaterProgressAfterExactMutationRecovery

Use:
- exact production commit 05c1fc2ed53531da6935f93470df93028bd799f3;
- the prepared local AndroidTest-only diagnostic overlay;
- the already-authorized ignored local.properties environment;
- the same authorized real SM-A546E / arm64-v8a / API 36 target.

Required semantics:
- exactly one intended test starts and finishes;
- exactly one existing recoverGeneration(...) invocation occurs in the test body;
- do not add or execute any second destructive recovery call;
- preserve complete primary assertion diagnostics before teardown can obscure them;
- if PASS, STOP after reporting the diagnostic observations; do not infer FIXED/CLOSED and do not proceed to the 5-test set/full class;
- if FAIL, preserve the complete enriched BEFORE/AFTER diagnostic message plus teardown outcome and STOP;
- if 0 tests execute or device/infrastructure blocks execution, classify as infrastructure/no semantic result and STOP;
- do not rerun unchanged regardless of outcome.

No edit, commit, push, publication, full focused-set run, or full-class run is authorized in this execution pass.

The known debug-package collision risk with the user's personal com.ireum.ytdl installation remains acknowledged; this single diagnostic device run is within the user's explicit decision to continue current BUG-UPDATER-04 verification. The separate debug package-isolation follow-up remains mandatory after this wave.

INDEPENDENT EXECUTION: NOT EXECUTED

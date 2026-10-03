# BUG-UPDATER-04 exact-SHA focused runtime failure — recovery cause not verified

review_parent_sha: 4b0d62302e6e24e19c35d36593746bc9854aa77a
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_committed_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_committed_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_committed_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
FOCUSED_EXACT_SHA_GATE_FAILED_VALIDLY
SAME_ROOT_CLOSURE_FAILURE_EVIDENCE
RECOVERY_CAUSE_NOT_VERIFIED
FULL_CLASS_NOT_AUTHORIZED
PUSH_NOT_AUTHORIZED
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted implementation-agent result:
- exact committed candidate remained 05c1fc2ed53531da6935f93470df93028bd799f3;
- candidate/tree/environment/index/tracked/cached identities remained unchanged;
- focused exact-SHA gate executed exactly 5 intended tests;
- result: 4 PASS / 1 FAIL;
- failing test: laterUpdaterProgressAfterExactMutationRecovery;
- failing assertion at line 555 observed recoverGeneration(...) == false;
- supplied diagnostics establish a live native mutation generation and a blocked reader at the failure boundary;
- teardown also failed to recover that same exact generation;
- no rerun, correction, new commit, full-class execution, push, or publication occurred.

Evidence report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-restored-runtime-3602ae325dee4a25bcf04ce79893b737/REPORT.md

Reviewer classification:
- This is not an infrastructure-only stop: an intended focused test executed and produced a semantic failure.
- It is also not enough to conclude a production recovery defect, because recoverGeneration(...) can fail closed for multiple causes and the supplied evidence does not identify which branch occurred.
- The failure is within the existing BUG-UPDATER-04 recovery/progress contract, so canonical root/count does not change.
- The exact committed candidate remains non-publishable.
- Do not rerun unchanged merely to seek green.
- Do not run the full runtime-authority class while the focused gate is red.
- Do not push 05c1fc2ed53531da6935f93470df93028bd799f3.

Relevant exact-remote recovery semantics at base 256a5cf5:
- recoverGeneration(...) returns false for unreadable known/candidate markers;
- returns false when marker enumeration or /proc generation scanning is unavailable;
- returns false when recoverDetailed/recoverSelector cannot prove exact quiescence;
- exact selector recovery may remain unresolved if the generation survives SIGTERM/SIGKILL observation or the final scan cannot prove absence;
- therefore the Boolean alone does not classify cause.

Narrow diagnostic authorization:
- inspect the exact local committed candidate and the preserved failure report first;
- prefer ANDROIDTEST-ONLY read-only diagnostics around the existing single recoverGeneration call;
- preserve the same live mutation generation, blocked-reader precondition, exact token/process identity, and single recovery call;
- capture enough before/after state to distinguish at least:
  1. marker absent/present/readable/malformed/unreadable;
  2. marker processId/generation/state when readable;
  3. exact generation visibility before recovery and immediately after false;
  4. whether the generation is still live, disappeared, or cannot be observed;
  5. whether marker enumeration/observation is unavailable;
  6. whether a newer/different generation owns the marker;
  7. blocked reader state remains fail-closed until recovery is actually proven.
- use existing read-only production observations where possible.
- do not add sleeps to manufacture success.
- do not call recoverGeneration or another destructive recovery API a second time for diagnosis.
- do not clear/delete/terminate marker/process state before diagnostics are captured.
- preserve the original success assertion after diagnostic capture.
- if existing APIs are insufficient and a production diagnostic hook would be required, STOP and report the exact missing observation; production diagnostic-hook changes are not authorized in this diagnostic pass.

Authorized implementation change scope:
ANDROIDTEST_ONLY_DIAGNOSTIC_INSTRUMENTATION
PRODUCTION_EDIT_AUTHORIZED=NO
BEHAVIORAL_TEST_EXPECTATION_CHANGE_AUTHORIZED=NO
COMMIT_AUTHORIZED=NO
PUSH_AUTHORIZED=NO

Verification for diagnostic instrumentation:
- compile complete debug AndroidTest Kotlin/source;
- git diff --check;
- do not run device tests in the instrumentation-preparation pass;
- preserve exact candidate commit and all prior failure evidence;
- stop for reviewer authorization of one exact single-method rerun after diagnostics are prepared.

Post-wave debug-package isolation remains required separately and must not be mixed into this BUG-UPDATER-04 diagnostic pass.

INDEPENDENT EXECUTION: NOT EXECUTED

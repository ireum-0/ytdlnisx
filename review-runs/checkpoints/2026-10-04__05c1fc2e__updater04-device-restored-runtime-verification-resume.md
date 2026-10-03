# BUG-UPDATER-04 exact-SHA verification — device restored, runtime gates may resume

review_parent_sha: 821a2b6d42a1b7d3614abf00e5496d33ce9b6157
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_committed_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_committed_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_committed_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
DEVICE_RECOVERY_CONTINUATION_AUTHORIZED
EXACT_SHA_RUNTIME_GATES_PENDING
PUSH_STILL_CONDITIONAL
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted stop-report facts:
- local committed candidate remains 05c1fc2ed53531da6935f93470df93028bd799f3;
- tree remains d675b3b1bb2aa4e8570e0113d525b9582c3c367a;
- parent remains 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- authorized local.properties copy passed byte-for-byte verification and remained ignored, unstaged, and uncommitted;
- configuration, compilation, and APK packaging completed before the prior infrastructure stop;
- intended SM-A546E disappeared from ADB during APK installation;
- focused gate executed 0 tests and produced no semantic result;
- full YtdlpRuntimeAuthorityProductionWiringTest was not run;
- no push/publication/source edit/new commit/retry/history change occurred;
- preserved evidence report: C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-env-exact-sha-fb9a52e58a644902b075d1182f817453/REPORT.md

User subsequently reported that the device is powered on/restored.

Reviewer interpretation:
- prior stop remains INFRASTRUCTURE_ONLY;
- the exact committed candidate and ignored environment precondition remain the verification basis;
- no compilation or packaging rerun is required solely because of device reconnection if candidate/environment identity remains unchanged;
- runtime verification must resume from the previously unexecuted focused five-test gate;
- no FIXED/CLOSED/CLEAN state may be inferred until exact-SHA runtime closure and independent exact-source completion review finish.

Required continuation:
1. non-mutatingly verify isolated worktree HEAD/tree exactly match the candidate above;
2. verify tracked and cached diffs are empty;
3. verify isolated local.properties still matches the preserved source hash, remains ignored, unstaged, and uncommitted;
4. verify ADB target is the same authorized SM-A546E, arm64-v8a, API 36;
5. run the exact focused five-test BUG-UPDATER-04 gate and require 5/5 PASS;
6. only if focused is green, run full YtdlpRuntimeAuthorityProductionWiringTest and require 12/12 PASS;
7. run git diff --check;
8. fresh-check remote checkpoint/pre-baseline-review immediately before push and require it still equals 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
9. prove 05c1fc2ed53531da6935f93470df93028bd799f3 is a strict forward descendant;
10. push by normal fast-forward only;
11. verify remote implementation HEAD equals exactly 05c1fc2ed53531da6935f93470df93028bd799f3 and ahead/behind is 0/0;
12. stop for independent exact-source completion review.

Any candidate/environment identity mismatch, wrong/ambiguous device, valid test failure, zero-test execution, infrastructure blocker, destination movement, non-fast-forward condition, or protected/history risk is a hard stop.

No source/test/repository-config edit, new commit, re-staging, index mutation, or history rewrite is authorized.

INDEPENDENT EXECUTION: NOT EXECUTED

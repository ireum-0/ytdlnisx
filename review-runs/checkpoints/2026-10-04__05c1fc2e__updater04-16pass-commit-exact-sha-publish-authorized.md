# BUG-UPDATER-04 full sixteen-test dirty-overlay gate PASS — logical commit, exact-SHA closure, and FF publication authorized

review_parent_sha: 394b846a17c3045ce7b2d8ce7f0b9553391378bd
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_parent_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_parent_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_parent_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
DIRTY_OVERLAY_FOCUSED_GATE_PASS
DIRTY_OVERLAY_FULL_CLASS_GATE_PASS
LOGICAL_CORRECTION_COMMIT_AUTHORIZED
EXACT_COMMITTED_SHA_FOCUSED_AND_BROAD_CLOSURE_AUTHORIZED
NORMAL_FAST_FORWARD_PUBLICATION_AUTHORIZED_IF_ALL_GATES_PASS
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted dirty-overlay runtime evidence:
- exact worktree: D:/AndroidStudioProjects/ytdlnisx-f11/build/sol-remediation-20260930;
- parent HEAD/tree/parent remain:
  05c1fc2ed53531da6935f93470df93028bd799f3
  d675b3b1bb2aa4e8570e0113d525b9582c3c367a
  256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- exactly two unstaged correction files remain and cached/staged diff is empty;
- exact physical correction hashes remain:
  YoutubeDLCompat.kt = 098a834d565a87c1203380098abd117768ff38b97f76bd4827dcca95e7bc436b
  YtdlpRuntimeAuthorityProductionWiringTest.kt = a249a3b7d2327bf22d714948756b497db8def043d13badc6a3d90f7125da6810;
- focused correction gate: exactly 4 executions, 4 PASS / 0 FAIL / 0 skipped;
- broader class gate: exactly 16 executions, 16 PASS / 0 FAIL / 0 skipped;
- no setup or teardown failures reported in the broader class gate;
- no retries, source/test/config/applicationId edits, staging, commits, pushes, history rewrites, or evidence overwrites occurred;
- protected evidence remained preserved.

Full class runtime report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-full-sixteen-run-ce3d2184d1044204963f9a4cd7508377/RUNTIME_GATE_REPORT.md

Historical evidence rule:
The earlier laterUpdaterProgressAfterExactMutationRecovery false-recovery cause remains NOT_VERIFIED. Current green execution does not prove that earlier event flaky or establish its historical cause.

## Authorized logical correction commit

Create exactly one new forward commit whose parent is exactly:
05c1fc2ed53531da6935f93470df93028bd799f3

Stage exactly these two whole files and no other path:
1. app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompat.kt
   physical pre-stage SHA-256:
   098a834d565a87c1203380098abd117768ff38b97f76bd4827dcca95e7bc436b
2. app/src/androidTest/java/com/ireum/ytdl/util/YtdlpRuntimeAuthorityProductionWiringTest.kt
   physical pre-stage SHA-256:
   a249a3b7d2327bf22d714948756b497db8def043d13badc6a3d90f7125da6810

Before commit:
- verify HEAD is exactly 05c1fc2ed53531da6935f93470df93028bd799f3;
- verify staged/cached diff is empty;
- verify the worktree differs from HEAD in exactly those two paths;
- verify the two physical hashes exactly match above;
- stage those two whole paths only;
- verify staged name-status contains exactly those two paths;
- verify staged content matches the already-reviewed correction diff capsule;
- no patch/hunk staging.

Recommended commit message:
fix: align anonymous mutation recovery identity

After commit:
- verify new commit parent is exactly 05c1fc2ed53531da6935f93470df93028bd799f3;
- verify commit path set is exactly the two authorized paths;
- verify no behavior-relevant source/test/config worktree changes remain;
- do not amend/rebase/squash/rewrite.

## Exact committed-SHA closure gate

On the exact newly created clean committed SHA X:

1. record full SHA X and tree;
2. compile:
   - :app:compileDebugKotlin
   - :app:compileDebugAndroidTestKotlin
   - :app:compileDebugAndroidTestJavaWithJavac
   using the existing authorized environment;
3. run git diff --check for the committed correction range;
4. on the same authorized real SM-A546E / arm64-v8a / API 36, run exactly the four focused correction methods in one invocation;
   require exactly 4 PASS / 0 FAIL / 0 skipped;
5. only after 4/4 PASS, run exactly one complete
   com.ireum.ytdl.util.YtdlpRuntimeAuthorityProductionWiringTest
   invocation;
   require exactly 16 PASS / 0 FAIL / 0 skipped;
6. any compile/diff/test failure or zero-test/infrastructure failure is a hard stop;
7. do not retry unchanged failures;
8. no source/test/config edit after commit or during exact-SHA verification.

## Publication gate

Only after all exact-SHA gates above pass:

- fresh-check remote checkpoint/pre-baseline-review immediately before push;
- require it still equals 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- prove X is a strict fast-forward descendant through:
  256a5cf507b54adcca0342b82ddaf6e2d75a684e
  -> 05c1fc2ed53531da6935f93470df93028bd799f3
  -> X;
- push X to checkpoint/pre-baseline-review by normal fast-forward only;
- no force push;
- if destination moved, divergence is observed, or fast-forward cannot be proven, STOP and do not retry;
- after successful push, verify remote implementation HEAD equals X exactly and ahead/behind is 0/0;
- do not create any additional implementation commit after X.

After successful publication, stop for independent completion review. Do not claim FIXED/CLOSED/CLEAN before that review.

Known debug package collision risk remains acknowledged for this current wave. Separate debug applicationId isolation remains mandatory and separate.

INDEPENDENT EXECUTION: NOT EXECUTED

# BUG-UPDATER-04 correction preparation complete — exact correction diff review required

review_parent_sha: 88b4ff04ac6089b9b620b8953a55151e06158c43
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_parent_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_parent_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_parent_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
CORRECTION_PREPARATION_REPORTED_COMPLETE
EXACT_DIFF_INDEPENDENT_REVIEW_REQUIRED
RUNTIME_BEHAVIOR_NOT_VERIFIED
DEVICE_TEST_NOT_AUTHORIZED
COMMIT_NOT_AUTHORIZED
PUSH_NOT_AUTHORIZED
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted preparation report:
- worktree: D:/AndroidStudioProjects/ytdlnisx-f11/build/sol-remediation-20260930;
- exactly two files reported modified and unstaged:
  1. app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompat.kt
  2. app/src/androidTest/java/com/ireum/ytdl/util/YtdlpRuntimeAuthorityProductionWiringTest.kt
- reported production shape: classify once before anonymous identity selection; anonymous mutators use mutation:, ordinary anonymous requests use consumer:, explicit caller IDs remain unchanged;
- reported test shape: four new regressions for identities, retained-debt recovery, and later progress; all twelve pre-existing test bodies remain unchanged;
- production Kotlin compile PASS;
- complete AndroidTest Kotlin and Java compilation PASS;
- git diff --check PASS;
- no tests executed;
- no commit/push/config/applicationId/history mutation;
- protected state/evidence reportedly preserved.

Preparation report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-anonymous-identity-correction-ce4ad5b8897749daa18bdb2d67e651ba/REPORT.md

Reviewer decision:
Compilation establishes buildability only. Before any runtime test authorization, independently inspect the exact two-file correction diff against parent candidate 05c1fc2ed and prove:
1. requestRequiresMutation is evaluated once before anonymous identity creation;
2. anonymous mutator -> mutation: identity;
3. anonymous ordinary -> consumer: identity;
4. explicit caller processId remains unchanged;
5. the same chosen identity flows through runtimeAdmissions, prepare, native environment, process registry, finalization and cancellation/revocation;
6. executeUnderMutation remains separate and unchanged in semantics;
7. no recovery selector broadening or second durable store was introduced;
8. reader fencing/publication validation remains unchanged;
9. all four new regression tests are production-faithful and do not weaken or rewrite the twelve existing test bodies;
10. no unrelated scope expansion exists.

Because the correction diff is local-only, the next action is evidence persistence, not test execution.

Required next action:
Persist a reviewer-readable exact correction-diff capsule to the private review branch. Include:
- git diff --no-ext-diff --unified=80 or equivalent complete diff for the two files only;
- exact physical SHA-256 and candidate-parent Git blob identity for both files;
- line-numbered post-edit excerpts covering changed production code and all four new tests;
- proof all twelve existing test bodies are byte-identical or exact hash-equivalent to their pre-correction versions;
- reported compile/diff-check results and report provenance;
- confirmation no other worktree/index/config/history mutation.

No device tests, commit, push, or further source edits are authorized during evidence persistence.

Historical focused recovery failure remains NOT_VERIFIED.
Canonical BUG-UPDATER-04 liveness defect remains PROVEN and OPEN P2.

Separate debug applicationId isolation remains mandatory and must not be mixed into this correction.

INDEPENDENT EXECUTION: NOT EXECUTED

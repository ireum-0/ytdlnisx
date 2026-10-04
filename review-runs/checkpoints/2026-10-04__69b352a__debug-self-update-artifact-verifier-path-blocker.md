# Debug/test package isolation self-update correction — artifact verifier path blocker

review_parent_sha: 0393d13469c8aaec4587afe740a2c200aedea075
implementation_remote_sha: 69b352a18ae580d4de5fd49f0a20d3e66d3d5966
scope: DEBUG_TEST_PACKAGE_ISOLATION_SELF_UPDATE_RESIDUAL_CORRECTION
canonical_count_change: 0

Disposition:
- narrow debug self-update guard was prepared locally in the preserved isolated worktree;
- production compilation PASS;
- complete AndroidTest compilation PASS;
- debug APK assembly PASS;
- debug AndroidTest APK assembly PASS;
- git diff --check PASS;
- artifact verifier did not reach APK inspection because it referenced prior environment evidence from the wrong directory;
- artifact identity proof for this correction therefore remains NOT_VERIFIED;
- no semantic/runtime failure occurred;
- no staging, commit, push, device install, or connected test occurred.

Classification:
ENVIRONMENT/VERIFIER_PATH_BLOCKER
NO_NEW_SOURCE_FINDING
NO_RETRY_OF_SEMANTIC_FAILURE

Continuation authorization:
- preserve the exact current source/config candidate; make no new semantic edits;
- first verify worktree status/diff and relevant file hashes still match the stopped candidate report;
- reuse the already-passed compile/assemble/diff-check evidence only if candidate bytes are unchanged;
- locate the APK/merged-manifest artifacts produced by the current self-update-correction run, not the prior environment-recovery run;
- rerun only the artifact identity verifier/inspection needed to prove:
  release/default app = com.ireum.ytdl;
  debug app = com.ireum.ytdl.debug;
  AndroidTest package distinct;
  instrumentation target = com.ireum.ytdl.debug;
  debug FileProvider authority follows com.ireum.ytdl.debug;
  debug shortcuts target com.ireum.ytdl.debug;
- do not regenerate or overwrite prior evidence merely to make a path match.

If artifact proof passes with unchanged candidate:
- stage only the authorized self-update residual correction and directly attributable regression coverage, if any;
- create one logical forward checkpoint commit;
- fresh-check checkpoint/pre-baseline-review;
- require strict forward ancestry from 69b352a18ae580d4de5fd49f0a20d3e66d3d5966;
- normal fast-forward push only;
- verify remote equality;
- stop for independent review.

If artifact proof fails semantically, candidate bytes differ, expected current-run artifacts are missing/corrupt, or ancestry moves incompatibly: preserve evidence and stop with no push.

Device install/connected test remain unauthorized.

INDEPENDENT EXECUTION: NOT EXECUTED

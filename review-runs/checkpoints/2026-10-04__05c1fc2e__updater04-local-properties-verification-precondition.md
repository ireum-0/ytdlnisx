# BUG-UPDATER-04 exact-SHA verification infrastructure precondition — local.properties authorized

review_parent_sha: da4a8889ff300f2f25bdac2b8a930cf10caa7a75
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_committed_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_committed_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_committed_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
LOCAL_PROPERTIES_PRECONDITION_AUTHORIZED
EXACT_SHA_VERIFICATION_MAY_RESUME
PUSH_STILL_CONDITIONAL
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted implementation-agent stop-report facts:
- one authorized BUG-UPDATER-04 commit was created directly from the preserved normalized staged index;
- local commit SHA: 05c1fc2ed53531da6935f93470df93028bd799f3;
- tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a;
- parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- all eleven committed blobs matched the preserved staged snapshot;
- no content edit or re-staging occurred;
- isolated verification stopped before semantic execution because Gradle configuration required local.properties and that ignored local file was absent;
- focused gate executed 0 tests and produced no semantic result;
- full runtime-authority class, final exact-SHA gate, push, and publication were not run;
- local commit/worktrees/index snapshots/failure evidence were preserved.

Evidence report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-committed-sha-866a7720377442cb9d7acbf87d21cb70/REPORT.md

Repository policy evidence:
- repository .gitignore contains /local.properties;
- repository .gitattributes is absent at the implementation base;
- therefore local.properties is an ignored local build-environment file, not part of the committed source candidate.

## Authorized environment-only recovery

The implementation agent may satisfy the isolated worktree's missing local.properties precondition by copying the exact existing local.properties from the preserved primary working environment into the isolated verification worktree, byte-for-byte.

This authorization is limited to environment setup:
- source must be the existing local.properties already used by the preserved primary worktree/build environment;
- destination must be the isolated exact-SHA verification worktree;
- verify destination local.properties is ignored by Git;
- record SHA-256 of source and destination and require equality;
- do not modify the file's contents;
- do not change core.autocrlf, .gitattributes, Gradle source/config files, SDK versions, dependencies, or repository-tracked files;
- do not stage or commit local.properties;
- do not use a different arbitrary SDK/config file if the preserved source local.properties is missing or unreadable.

A clean exact-SHA verification worktree may contain this ignored environment file. Source identity remains exact when tracked index/worktree diffs are empty and HEAD is the target commit.

## Required continuation

1. Re-establish or create a clean isolated worktree at exactly 05c1fc2ed53531da6935f93470df93028bd799f3.
2. Copy the preserved primary worktree local.properties byte-for-byte into that isolated worktree.
3. Prove source and destination local.properties SHA-256 match and Git ignores the destination.
4. Prove isolated HEAD is exactly 05c1fc2ed53531da6935f93470df93028bd799f3 and both tracked working-tree diff and staged diff are empty.
5. Verify the intended SM-A546E / arm64-v8a / API 36 device is available.
6. Run the exact focused five-test BUG-UPDATER-04 gate. Require 5/5 PASS.
7. Only then run full YtdlpRuntimeAuthorityProductionWiringTest. Require 12/12 PASS.
8. Run git diff --check for the exact commit/tree.
9. Only after all gates pass, fresh-check checkpoint/pre-baseline-review still equals 256a5cf507b54adcca0342b82ddaf6e2d75a684e.
10. Prove 05c1fc2ed53531da6935f93470df93028bd799f3 is a strict forward descendant and push by normal fast-forward only.
11. Verify remote checkpoint/pre-baseline-review equals exactly 05c1fc2ed53531da6935f93470df93028bd799f3 and ahead/behind is 0/0.
12. Stop for independent exact-source completion review.

Any missing/mismatched local.properties source, tracked worktree mutation, valid test failure, infrastructure blocker, destination movement, non-fast-forward condition, or history/protected-state risk is a hard stop.

No source/test/repository config content edit is authorized.

INDEPENDENT EXECUTION: NOT EXECUTED

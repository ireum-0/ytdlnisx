# BUG-UPDATER-04 staged-hash reconciliation — normalized index accepted

review_parent_sha: ce44eabd9696ae9bdf6d723e96f2d771036d93f2
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
STAGED_HASH_RECONCILIATION_ACCEPTED
PRESERVE_EXISTING_INDEX
COMMIT_FROM_EXISTING_STAGED_INDEX_AUTHORIZED_AFTER_IDENTITY_RECHECK
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted stop-report facts:
- exactly the 11 reviewer-authorized manifest paths are staged: 9 modifications + 2 additions;
- no unstaged candidate diff remains;
- working-file bytes remain unchanged from the explicit manifest;
- 2 staged content hashes equal the raw working-file SHA-256 values;
- 9 staged content hashes differ only because local core.autocrlf=true normalized CRLF working-tree bytes to LF for the Git index;
- for each of those 9 paths, the staged blob content equals the corresponding working file after **only** CRLF->LF conversion;
- staged index was preserved after the mandatory hash-gate stop;
- no commit, build, test, push, publication, source edit, or index repair occurred after the stop;
- remote implementation/review/private refs remained at the expected values.

Evidence report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-explicit-manifest-f703d8f51c7140d8a19007b4521377b2/REPORT.md

Repository observation:
- no repository .gitattributes file exists at implementation HEAD, so no repo-level attribute rule contradicts the reported core.autocrlf normalization.

## Reconciliation rule

The previous manifest SHA-256 values are **working-tree byte identities**. They are not required to equal Git index blob bytes when Git's clean conversion normalizes line endings.

For commit identity:
- the already-staged index is the authoritative candidate;
- preserve it exactly;
- do not unstage/re-stage merely to make raw-file SHA-256 values match;
- do not change core.autocrlf or introduce .gitattributes;
- do not rewrite working files to LF;
- do not perform hunk staging.

The line-ending transformation is accepted only because the supplied evidence establishes a pure CRLF->LF conversion for every staged mismatch, with no other byte/content change.

## Mandatory recheck before commit

Before creating the commit, verify non-mutatingly that:
1. HEAD remains 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
2. the staged path set is exactly the same 11 manifest paths;
3. no unstaged candidate diff exists;
4. working-file SHA-256 values still equal the explicit-manifest values from checkpoint ce44eabd...;
5. the current staged per-path blob OIDs/content hashes and staged tree identity exactly match the staged index snapshot recorded in the evidence report;
6. for the 9 normalized paths, staged content still equals current working content after only CRLF->LF conversion;
7. for the 2 non-normalized paths, staged content still equals current working content exactly;
8. no additional staged path/index mutation exists.

If any check differs, STOP. Do not repair/re-stage.

## Authorized continuation

If all rechecks pass:
- create one logical commit directly from the preserved staged index;
- parent must be exactly 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- recommended message: `fix: fence unresolved runtime mutation debt`;
- do not amend.

Then:
- run the final 5-test focused set and full 12-test YtdlpRuntimeAuthorityProductionWiringTest from a clean isolated worktree at the exact committed SHA;
- require 5/5 and 12/12 respectively;
- run git diff --check;
- only after all exact-SHA gates pass, fresh-check remote checkpoint/pre-baseline-review still equals the expected base;
- push by normal fast-forward only;
- verify final remote implementation HEAD equals the exact tested SHA;
- stop for independent exact-source completion review.

Any staged-tree/blob mismatch, valid test failure, infrastructure blocker, destination movement, non-fast-forward condition, or history/protected-state risk is a hard stop.

INDEPENDENT EXECUTION: NOT EXECUTED

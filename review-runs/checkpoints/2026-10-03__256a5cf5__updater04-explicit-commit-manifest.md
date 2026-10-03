# BUG-UPDATER-04 commit composition manifest — all 11 current dirty paths belong to the logical candidate

review_parent_sha: 5b4656ed1957b700b5c84559e51005394e19939d
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
COMMIT_COMPOSITION_AUTHORIZED
ONE_COMBINED_LOGICAL_COMMIT
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

## Why the composition ambiguity is resolved

The composition-evidence run recovered temporal provenance:
- 16 tracked hunks pre-existed the durable-mutation-debt continuation;
- 8 tracked hunks are continuation-only;
- 2 tracked hunks are mixed inherited + continuation content;
- both untracked files pre-existed the continuation and were then extended by it.

Temporal `inherited` does not mean unrelated/protected-outside-root.

Earlier canonical review commit d75385877d238b0d8dd29b3b70630c2eddd1b3af explicitly treated the then-current BUG-UPDATER-04 dirty candidate as the **entire dirty range** against base 256a5cf5, required enumeration/review of every dirty production and test file, required classification of the readiness/direct-role diagnostic test edits, and allowed one combined forward commit or at most production + test commits.

The later durable-mutation-debt work was explicitly a same-root continuation of that existing protected BUG-UPDATER-04 dirty candidate and modified only a subset of its files. No later canonical checkpoint removed any inherited path/hunk from the BUG-UPDATER-04 candidate.

Therefore:
- the pre-continuation inherited hunks are prior portions of the same BUG-UPDATER-04 logical candidate;
- the continuation-only hunks are same-root residual corrections;
- the mixed hunks contain only earlier same-root candidate content plus later same-root residual correction;
- both untracked files are same-root candidate files whose earlier and later content must stay together;
- hunk-level staging is neither required nor desirable for this logical commit.

## Authorized commit manifest — exact current whole-file state

Include the exact currently verified content of all 11 paths below, and no other repository path:

1. app/src/main/java/com/ireum/ytdl/App.kt
   SHA-256: 5308eb3bb7c119fc47d29f70e56bf86f76617090b9f143b57f55b21467ec6773
   tracked modified; include whole current file diff (F1.H1).

2. app/src/main/java/com/ireum/ytdl/util/UpdateUtil.kt
   SHA-256: d40421f8e286674e356272a9533fc3b580c341b6a7f08c64bdc90ffd7c8a91cd
   tracked modified; include whole current file diff (F2.H1-H3).

3. app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompat.kt
   SHA-256: 0df9d483f49795af8f031a3002f7c643313fdfd5e642a440613981d2ee7d3395
   tracked modified; include whole current file diff (F3.H1-H6, including mixed F3.H3).

4. app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YtdlpNativeProcessBarrier.kt
   SHA-256: 9eb29785a396fda8ab0a9509232888dd61f608a51a7cec84a2d560c109b9d57c
   tracked modified; include whole current file diff (F4.H1-H5, including mixed F4.H5).

5. app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YtdlpRuntimeAuthority.kt
   SHA-256: 0717d7506a19ca379593ec8eeb64a011f5223ed8564f82c8255d5fc1eb123677
   untracked; include the complete current file.

6. app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YTDLPUtil.kt
   SHA-256: 19e04f337518004e4dbd54ec1d1c847e25a66bfb551cbcd611977e63b1937df6
   tracked modified; include whole current file diff (F6.H1-H6).

7. app/src/main/java/com/ireum/ytdl/util/runtime/RuntimeDiagnostics.kt
   SHA-256: 421179bd37647feedf9f95e00a0b4128b70088f2d9015e5ce409a1ac03ccb783
   tracked modified; include whole current file diff (F7.H1).

8. app/src/androidTest/java/com/ireum/ytdl/util/YtdlpRuntimeAuthorityProductionWiringTest.kt
   SHA-256: 02b6f69e9ef4ece5b5551cb71701f4b4fb8625109fa3fa81bf02bc3f1aa41da3
   untracked; include the complete current file, including inherited fixtures/tests, the five later tests, and accepted diagnostic/reset wiring.

9. app/src/androidTest/java/com/ireum/ytdl/util/UpdateUtilProductionWiringTest.kt
   SHA-256: 146237418ea9111f686b6aba50e0275b6e7d1ddbcb7d7826ffe5def7f94dd02d
   tracked modified; include whole current file diff (F9.H1).

10. app/src/androidTest/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompatProcessCommandProductionTest.kt
    SHA-256: cfc01a7afbd159e04f766f58826849b67a21deb61582583d2375e7c45c816ade
    tracked modified; include whole current file diff (F10.H1).

11. app/src/androidTest/java/com/ireum/ytdl/util/extractors/ytdlp/YtdlpNativeProcessBarrierStateMachineTest.kt
    SHA-256: 77991286048f1ea5a2345b24b2ad94a2a3444b049f71f193a3ad294b4115f39a
    tracked modified; include whole current file diff (F11.H1-H2), including the reviewer-authorized read-only direct-role diagnostic instrumentation. The historical false-return evidence remains NOT_VERIFIED; inclusion of diagnostics does not reinterpret that evidence.

## Excluded protected-state inventory

Within the current Git worktree dirty inventory, there is **no excluded dirty path or hunk among these 11 items**.

Excluded from the logical commit:
- every repository path not listed in the 11-path manifest above;
- any future/new dirty content whose byte hash differs from the manifest;
- any index entry not produced by staging exactly these manifest paths;
- local evidence reports under C:/Users/dh2/AppData/Local/Temp/...;
- any unrelated external worktree, stash, generated/ignored build artifact, or state not part of the 11-path manifest.

The current empty index is a required starting condition.

## Commit shape

Use exactly one combined logical commit.

Rationale:
- the earlier canonical review already treated the entire dirty range as one BUG-UPDATER-04 candidate;
- the shared runtime authority, native barrier, updater integration, ordinary consumers, and production-wiring regressions form one cross-file semantic contract;
- the durable-mutation-debt continuation is a same-root residual correction, not a separate finding;
- splitting inherited/continuation or production/test content now would require reconstructing artificial intermediate states that were not the verified candidate.

Recommended commit message:
`fix: fence unresolved runtime mutation debt`

## Next action

1. verify all 11 current SHA-256 values exactly match this manifest and the index is empty;
2. stage exactly these 11 whole paths; no patch/hunk staging is required;
3. verify staged name-status contains exactly these 11 paths and no others;
4. verify staged content hashes equal the manifest;
5. create one commit whose parent is exactly 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
6. verify the commit contains exactly the 11 manifest paths and no other path;
7. run final closure-grade focused 5-test set and full YtdlpRuntimeAuthorityProductionWiringTest from a clean isolated tree at the exact committed SHA on the authorized SM-A546E;
8. run git diff --check for the exact committed SHA;
9. only after all exact-SHA gates pass, fresh-check remote checkpoint/pre-baseline-review still equals 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
10. push the tested SHA by normal fast-forward only and verify the remote implementation HEAD equals that exact tested SHA;
11. stop for independent exact-source completion review.

Any hash mismatch, non-empty unexpected index, path-set mismatch, valid test failure, destination movement, non-fast-forward condition, or history/protected-state risk is a hard stop.

No content edit is authorized during this commit/final-SHA/push continuation.

INDEPENDENT EXECUTION: NOT EXECUTED

# BUG-HISTORY-05 — adf2f34 exact-SHA completion review

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO

review_parent_sha: 8b28c4b239995cf6bb2227af14836bfad16a35b4
implementation_parent_sha: d510848904af427ee4a837f791e779791fdd23e0
implementation_sha: adf2f347ce9e20ec9f9376cf94053694353c9961
overall_verdict: BUG_HISTORY_05_CLOSED
canonical_p0: 0
canonical_p1: 0
canonical_p2: 0
canonical_count_change: -1
canonical_open_roots: NONE
clean_review_basis_advance: NO
repository_wide_clean_claim: NO
independent_execution: NOT_EXECUTED

## History and scope

d510848904af427ee4a837f791e779791fdd23e0..adf2f347ce9e20ec9f9376cf94053694353c9961
is one normal forward commit:
- ahead: 1
- behind: 0
- total commits: 1.

Changed files are exactly:
- app/src/main/java/com/ireum/ytdl/database/repository/HistoryKeywordAssignmentRepository.kt
- app/src/androidTest/java/com/ireum/ytdl/database/HistoryDuplicateIdentityProductionWiringTest.kt

PlaylistDao, schema, migrations, manifest, dependencies, build scripts, updater/restore/download/runtime production code and unrelated product paths are unchanged.

## Independent source-semantic review

HistoryKeywordAssignmentRepository.deleteDuplicateHistoryGroups() retains the existing BUG-HISTORY-04 authority model:
- candidate ids remain hints;
- current History rows are reread inside HistoryReferenceMutationCoordinator;
- the mutation remains in one Room transaction;
- current duplicate identity is recomputed;
- retained-row selection is unchanged;
- retained and duplicate identity are checked before relationship mutation;
- the final identity recheck remains immediately before destructive History deletion.

The BUG-HISTORY-05 correction is narrow:
- current playlist memberships of each still-valid duplicate are read inside the transaction;
- each membership is mapped to retained.id;
- the mapped PlaylistItemCrossRef rows are inserted before duplicate relationship retirement;
- existing composite identity/on-conflict behavior makes overlapping memberships idempotent;
- duplicate playlist refs are then retired by the existing path;
- keyword assignment transfer/materialization remains in the same transaction;
- History deletion remains last and is still guarded by the final identity recheck.

Across multiple valid duplicates the repeated transfer converges to the playlist-membership union on the retained History row.

No Playlist row semantics, duplicate identity rules, retained-row policy, or DAO contract was broadened.

## Regression-contract review

HistoryDuplicateIdentityProductionWiringTest now contains 16 @Test methods and 0 @Ignore.

The new/strengthened real-Room cases cover:
- retained-only A + duplicate-only B => retained A+B;
- shared + unique memberships => exact one-copy union;
- retained with no playlist;
- duplicate with no playlist;
- multiple duplicates with overlapping/disjoint memberships;
- idempotent repeated cleanup;
- stale URL/source candidate => no transfer/delete;
- stale type candidate => no transfer/delete;
- injected playlist-transfer INSERT failure => whole graph rollback;
- injected History-delete failure after provisional playlist/assignment union => whole graph rollback;
- in-transaction identity change after playlist transfer => final HISTORY-04 recheck aborts and rolls back;
- keyword-assignment materialization remains coupled to the same transaction.

The tests exercise real Room PlaylistItemCrossRef rows and the production deleteDuplicateHistoryGroups() entrypoint.

## Implementation-agent verification evidence

Reported for exact published SHA:
adf2f347ce9e20ec9f9376cf94053694353c9961

- production compilation: PASS;
- AndroidTest compilation: PASS;
- build/artifact proof: PASS;
- git diff check: PASS;
- focused pre-publication runtime:
  16 executed / 16 PASS / 0 FAIL / 0 skipped;
- exact published-SHA runtime:
  16 executed / 16 PASS / 0 FAIL / 0 skipped;
- remote equality: verified;
- worktree: clean;
- index: empty;
- prior protected evidence preserved;
- 7,348 prior records reported preserved;
- 1,497 new records reported sealed.

Storage infrastructure:
- authorized recovery reclaimed 273.2 MiB;
- the single install retry succeeded.

Sealed local report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-history05-storage-resume-20261005-5072cd44/BUG_HISTORY_05_IMPLEMENTATION_EXACT_SHA_REPORT.md

The local sealed report was not independently opened from GitHub. Runtime counts, detailed storage profile/root-cause classification and evidence-record totals are implementation-agent evidence unless separately persisted in GitHub.

The semantic closure does not depend on a claimed emulator-storage root cause. The repeated-storage harness issue remains infrastructure evidence only; no production finding is created from it.

## Disposition

BUG-UPDATER-02: CLOSED.
BUG-UPDATER-03: CLOSED.
BUG-HISTORY-05: CLOSED P2.

Canonical active-remediation blocker counts:
P0 = 0
P1 = 0
P2 = 0

Canonical open roots:
NONE

This checkpoint closes the last active remediation root. It does NOT declare repository-wide CLEAN and does NOT promote a Known-Good Baseline.

Per REVIEW_PROTOCOL section 17, after remediation scope becomes clean, the next governed action is the plan's final heavy verification and final high-effort independent review before authoritative ledger/baseline/tag closure.

CLEAN_REVIEW_BASIS remains unchanged pending that gate.

INDEPENDENT EXECUTION: NOT EXECUTED

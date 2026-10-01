# minSdk26 exact-final gate 12 missing-row classification

checkpoint_kind: REVIEWER_COMPLETION_STOP_CLASSIFICATION
review_parent_sha: 5ad8b550d863fc99ca3e83bb15fff0b2b8ec5cbd
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 73514f16dab256b974631257bf09d9e2da25000b
reported_local_candidate_parent: 43bcebf8d4f796ed6e6759ebe03b0e5925442444
reported_local_candidate_tree: e0455cdd7038a76fc325a080d091b2b06ac72bc8
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
canonical_root: BUG-DOWNLOAD-01
related_root: BUG-RESUME-01
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED
local_candidate_source_confidence: IMPLEMENTATION_AGENT_EVIDENCE_UNPUBLISHED

## Completion evidence received

The implementation agent reports one forward local commit on exact parent
43bcebf8d4f796ed6e6759ebe03b0e5925442444:

- child: 73514f16dab256b974631257bf09d9e2da25000b
- tree: e0455cdd7038a76fc325a080d091b2b06ac72bc8
- intended changed path only: app/build.gradle
- intended semantic change only: defaultConfig minSdk 24 -> 26
- protected candidate worktree: clean
- no publication

Reported focused/config evidence:

- minSdkVersion = 26
- targetSdkVersion = 36
- compileSdk = 36
- resume smoke PASS
- both compile gates PASS
- detached exact-SHA diff PASS

Reported exact-final union result:

- gates 1-11 PASS
- gate 12 executed 211 tests: 210 PASS, 1 FAIL
- zero skips/errors
- failed method: realWorkerRejectsAmbientRecentAndSameNameFiles
- expected terminal Download status Error; observed nullable status = null
- production admission/execution preconditions and exact disappearance cause remain NOT_VERIFIED
- gates 13-19 NOT_EXECUTED
- Complete-Wave NOT_EXECUTED
- API24/25_PROOF=NOT_APPLICABLE_MINSDK_26
- no API24/25 device was created or used

The local durable report path supplied by the implementation agent is preserved in the protected
worktree as:
build/remediation-agent/sol-stop-review/2026-10-01__73514f16__min-sdk-26-union-first-failure.json

## Authoritative GitHub facts

The remote implementation branch remains
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9. Therefore 73514f16 is local-only evidence and is not
promoted to the independently reviewed remote implementation basis.

Before the support-floor change, canonical review evidence for exact local candidate 43bcebf8
recorded:

- DownloadOutput 21/21 PASS
- detached exact-SHA diff PASS
- complete 19/19 union PASS with 242 PASS / 0 FAIL
- Complete-Wave Check PASS

The minSdk26 scope reconciliation authorized only app/build.gradle minSdk 24 -> 26 and prohibited
production Kotlin/Java, tests, manifests, dependency/plugin, targetSdk/compileSdk, ABI, version,
schema, and documentation changes.

The GitHub-authoritative remote version of DownloadOutputProductionWiringTest contains the same
semantic contract for realWorkerRejectsAmbientRecentAndSameNameFiles: insert a queued Download,
run the real DownloadWorker, require no History publication, require terminal Error on the Download
row, and preserve the ambient files. The exact local 73514f16 test/harness mechanics are not
GitHub-authoritative and are therefore not independently asserted here.

## Classification

CLASSIFICATION=BUG_DOWNLOAD01_EXACT_FINAL_GATE12_MISSING_ROW_CAUSAL_BOUNDARY_UNVERIFIED

This result is a same-root exact-final verification blocker for BUG-DOWNLOAD-01. It is not, on the
available evidence:

- a new canonical production root;
- a proven minSdk26 production regression;
- a proven test-harness defect;
- a zero-test infrastructure failure;
- evidence sufficient to alter canonical root counts.

Reasoning boundary:

1. the same broader DownloadOutput/union boundary passed on 43bcebf8;
2. the only reviewer-authorized product delta for 73514f16 is minSdk configuration;
3. the failing semantic test did execute, but the report does not prove that the intended target
   Download was claimed by the intended worker, that the target ytdlp hook was entered, that an exact
   execution/process owner existed, or which actor removed/failed to retain the row;
4. therefore the null row cannot safely be attributed to output-provenance production semantics, but
   it also cannot be dismissed as a flake;
5. an unchanged rerun merely to seek green is prohibited.

Canonical consequences:

- BUG-DOWNLOAD-01 remains OPEN_P2 with exact-final verification incomplete.
- BUG-RESUME-01 remains OPEN_P2 because the minSdk26 candidate has not completed the required
  exact-final union/Complete-Wave closure path.
- The user-authorized minSdk26 product-support decision remains in force.
- API24/25 proof is not required for an exact candidate whose committed build contract proves
  minSdk=26.
- canonical counts are unchanged.
- repository-wide CLEAN remains unsupported.

## Authorized bounded continuation

Authorize one bounded TEST-ONLY causal diagnostic wave rooted at exact local candidate
73514f16dab256b974631257bf09d9e2da25000b.

Initial changed-file boundary:

- app/src/androidTest/java/com/ireum/ytdl/database/DownloadOutputProductionWiringTest.kt only.

No production/config/manifest/dependency/schema/documentation edit is authorized.

The diagnostic must preserve the original semantic assertions and capture, before teardown can erase
state, enough exact evidence to classify the missing row:

- prove the inserted Download row and its exact id/status/operation identity immediately before
  enqueue;
- identify the exact WorkManager request created for the focused method and its state transitions;
- prove whether the target ytdlp test hook is entered and with which candidate Download id;
- capture the exact Download row, History row, execution/process owner, recovery carrier/disposition,
  and available finalization/cleanup observation state at the earliest decisive boundaries supported
  by existing test APIs/hooks;
- establish whether the row disappears before admission, during owned execution, or only during
  terminal/finalization cleanup;
- inspect and, when causally relevant, explicitly scope/restore mutable global queue/scheduler
  preferences already known to affect real-worker admission; do not invent a new production contract;
- preserve the ambient-file non-publication contract and all existing safety assertions.

One forward test-only diagnostic/contract commit is permitted on 73514f16. Rerun of the focused
method is permitted only after the test tree materially changes.

Same-root continuation envelope:

- If the bounded diagnostic conclusively proves a test-only precondition/observer/quiescence defect
  within this same file, a narrow test-only correction may be made forward while preserving the
  original first-failure evidence and strengthening rather than weakening the contract.
- Examples of admissible classes are exact-worker quiescence observation, stale-worker isolation,
  missing explicit mutable preference scoping, or an observer boundary that reads before the existing
  production finalization contract is actually complete. These examples are not presumed causes.
- If the focused method then PASSes with the intended production admission/execution preconditions
  proved, run the full DownloadOutput class, detached exact-SHA diff, then the complete canonical
  19-gate union from partition 1 on the exact final candidate, followed by Complete-Wave Check.
- Because the test tree changes, prior 73514f16 gate results are historical evidence only; the final
  union must be from partition 1.
- No API24/25 target is required or permitted for minSdk26.
- Publication remains unauthorized; stop after the required verification boundary for independent
  reviewer classification.

Mandatory stop:

- the diagnostic still cannot distinguish production from harness after one bounded pass;
- exact evidence proves the intended worker/execution owned the target and production deleted or
  lost the row contrary to the accepted terminal contract;
- any production/config/manifest/dependency/schema/documentation edit appears necessary;
- the correction would weaken/remove the ambient-output or Error-row assertions;
- another semantic root appears;
- protected state, history, refs, or first-failure evidence cannot be preserved;
- any focused/class/union/Complete-Wave gate fails or is incomplete outside the explicitly
  preauthorized same-root test-only envelope.

No source root is closed by this checkpoint.

# Reviewer clarification — per-finding test evidence, not blanket raw-log requirement

date: 2026-10-12
checkpoint_kind: REVIEWER_EXECUTION_EVIDENCE_THRESHOLD_CLARIFICATION
manual_review_run: NO
checkpoint_status: FINAL
review_expected_parent: 801a3704cfbb2018a28f78bf93a82932c04f5082
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
production_source_changed: NO
new_runtime_test_execution: NO
canonical_root_disposition_change: NONE
active_download_root_count_change: NONE

The independent source-semantic review in `2026-10-12__db339e1__reviewer-window01-restore01-independent-semantic-verification.md` stands: old WINDOW-01 and RESTORE-01 failure paths were NOT_REPRODUCED on exact `db339e1`. Executable evidence was reported but NOT independently executed by the reviewer.

**Correction of an overstrict reviewer instruction:** `REVIEW_PROTOCOL.md` Section 16.3 does NOT require fetching, hashing and retransmitting every raw XML or log file when an independently assessable existing test result/report already establishes exact committed/published SHA parity, actual named affected test/class nonzero execution, sound semantic preconditions, no unexplained failure, and governing focused/broader gate coverage. Agent-reported results may be used as **execution evidence**, but never relabelled as independent reviewer execution.

The accepted independent completion checkpoint `2026-10-12__db339e1__bug-scheduler-01-accepted-terminal-independent-closure.md` documents an agent-reported **152 PASS/0 FAIL/0 SKIP**, 57 JVM and 95 Android, on exact published `db339e1`, including seven window/END-owner tests and source-bound Reset / E1->E2 settings-Restore tests. This is substantive evidence, not a zero-value summary. However this public checkpoint itself does not enumerate a per-root precise table of every test method executed, the original command/exit and per-class affected test result; root-specific closure still requires reviewer semantic sufficiency assessment, not blind arithmetic on 152.

Because prior WINDOW-01 was falsely closed when a test suite passed while production final-effect composition was wrong, independent SOURCE+production/test semantics remain central, and source review was performed already. The correct decision procedure is to first use **existing GitHub source and existing test report**; where they establish Section 16.3 and relevant checklist obligations, CLOSE the individual root without requesting a redundant raw-log collection or test rerun. Request only the precise missing execution identity/result if genuinely needed; a new test run is a last resort. The reviewer, not any implementation/evidence-collection agent, makes root disposition decisions.

The previously persisted `ytdlnisx/prompts/2026-10-12_GPT61_SOL_WINDOW01_RESTORE01_EXACT_SHA_CLOSURE_EVIDENCE.md` overdelegates closure readiness and broad runtime execution and MUST be superseded BEFORE launch. Likewise `...WINDOW01_RESTORE01_SEALED_RAW_TEST_EVIDENCE_ONLY.md` remains historical and must not automatically require full raw-log transfer. Neither is the active next-step prompt. Record reviewer ownership and no active agent in private handoff. No source/test/production artifact, canonical finding count or required gate has been changed by this clarification.

verdict_window: SOURCE_OLD_FAILURE_NOT_REPRODUCED; EXECUTION_REPORT_PRESENT; EXACT_PER_ROOT_GATE_ASSESSMENT_NOT_FINALIZED
verdict_restore: SOURCE_OLD_FAILURE_NOT_REPRODUCED; EXECUTION_REPORT_PRESENT; EXACT_PER_ROOT_GATE_ASSESSMENT_NOT_FINALIZED
next_action: REVIEWER_EVALUATE_EXISTING_AGENT_REPORTED_EXACT_SHA_SPECIFIC_TEST_SCOPE_AND_CLOSURE_WITHOUT_BLANKET_LOG_DEMAND
INDEPENDENT EXECUTION: NOT EXECUTED

# V39 reviewer scope decision: nullable chapter converter dependency

checkpoint_kind: OWNER_REQUESTED_NEXT_PROMPT_SCOPE_DECISION
checkpoint_status: FINAL
manual_review_run: NO
review_start_parent: a501b21b1119817eec0a47c974d1ad858fd9c8c9
implementation_sha: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v38_report_blob: 077dc8bba101871f05de759bd644e1a0bf6e2f96
v38_independent_review_blob: 892aa0646b2e8338f8a0c181266ce50e22a24a4b
confirmed_root_id: BUG-RESULT-NULL-CHAPTER-CONVERTER-01
confirmed_root_state: OPEN
confirmed_root_priority: P2
inventory_disposition: ADOPTED_INTO_ACTIVE_INVENTORY
active_scope: DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
previous_active_open_p2: 7
new_active_open_p2: 8
existing_sql_trigger_root: BUG-FORMAT-AUTHORITY-TRIGGER-01
existing_sql_trigger_root_state: OPEN
clean: NO
implementation_started: NO

## Evidence and decision

At exact implementation X the declared `ResultItem.chapters` is nullable. An actual `ResultDao.insert` of `chapters=null` writes JSON null, and actual Room DAO readback invokes `Converters.stringToListOfChapters`, which unconditionally calls `toMutableList()` on a nullable Gson result. The V38 executed `FormatBatchHandoffProductionWiringTest#realLegacyWorkerCreatesRowlessIssueWithoutBorrowingH2` returned a JUnit NPE at Converters.kt:63 before H2 preparation. The original SQL1555 trigger conflict is not the cause. This is one distinct current production error, not a test-only malfunction or alias of the SQL authority root. Production source and failure were independently reviewed in the V38 FINAL checkpoint and archived V38 report.

V38 was intentionally stopped at its other-root boundary; the user subsequently explicitly requested the next implementation prompt (`프롬프트`) and then review, correction, saving and handoff retry. This is a new prospective permission to plan the narrow next converter remedy, not retrospective V38 execution permission. Previous review disposition deferred this same root because that distinct permission was absent. Adopt exactly this one root now into the already named active download remediation scope as OPEN P2; reconcile 7 to 8 in the authoritative active inventory and handoff. No second count for the same observed crash and no closure of the SQL root.

## Next bounded task

V39 may fix the null-chapter converter semantic contract, with direct converter/DAO readback/regression verification and the previously blocked Handoff method, preserving actual null as null where supported. Before changing the converter, trace supported producers, DB carriers (SQL NULL vs JSON null vs []), Room generated bindings, existing and historical rows, UI/History/Result/recovery consumers, and final outcomes. Do not change the test input to nonnull or conflate empty list with unknown/null just to pass. No schema/Room/dependency migration changes without new authorization.

Preserve the four exact uncommitted local V38 candidate files and current full hashes, worktree, index, all other dirty worktrees/stashes, historic seals, V38 original first failure and build evidence, and the owner-approved *single* historical missing JAR reference exception. Implemented changes still require focused Handoff 35-method partition, seven direct SQLite trigger regressions, six genuine migrations, normal forward-only publication of reviewed code, and same-wave exact published SHA 197-method original V26 gate. Any new blocker requires genuine first failure and report; all outcomes require independent append-only private Full Report. Never relaunch V38 or infer the implementation agent has started.

# Owner decision after V37: prospective single-file evidence disposition

checkpoint_status: FINAL
manual_review_run: NO
decision_type: USER_AUTHORIZED_PROSPECTIVE_SINGLE_FILE_REFERENCE
review_start_parent: f7a80cb1a3d983408ebb386ab11fd3cd0f7bea40
implementation_sha: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v37_report_blob: f1bea5854e7be47eebdd8182414cae9a706e79a6
prior_final_review_blob: 22e3e908237361a2e7e43a8719f8fb5c6523b934
canonical_root: BUG-FORMAT-AUTHORITY-TRIGGER-01
canonical_root_status: OPEN
unresolved_p2: 7
implementation_agent_started: NO

## Decision

After the V37 evidence-disposition proposal and explanation, the user explicitly instructed `그냥 진행해`. This records the owner's decision to proceed within the precisely scoped prospective one-file reference treatment, **not** a statement that the original historical file survived continuously. The past V35/V36 absence remains unchanged, and this decision is effective only for subsequent work.

Historic reference: V35 final evidence manifest SHA256 `0519f9ade0e95df9119a4ba2ffa0065eca55e000a4a520e250354ede9a1c3848`, missing relative entry `room-runtime-classes.jar`, expected size `606703`, expected SHA256 `8f8c6e21001aeba03baa15928353d9994b5fbad2c09c88701e1fcdc67eb3f02d`.

Separate present-day reference: Room 2.8.4 `room-runtime-android` cached AAR, SHA256 `3293575082024fa0a92b081c4e2d0609f14b5c109635ec227bc509db141a4e20`, ZIP member `classes.jar`, streamed uncompressed size `606703`, SHA256 `8f8c6e21001aeba03baa15928353d9994b5fbad2c09c88701e1fcdc67eb3f02d`. This proves the reported current byte identity, not independent pre-loss custody.

Scope: the old missing-path observation and sealed V35/V36/V37 reports are immutable. Do not replace or re-seal historic files or label the missing original as recovered. A new future verifier may record this *one exact* historical absent item as an owner-approved exception after independently verifying every digest above and the integrity of all remaining original protected items. Other evidence, source, indexes, dirty worktrees, schema snapshots, APKs and stashes remain protected without any exception. Any new mismatch stops. No original file path mutation, broader evidence waiver, or retroactive PASS is permitted.

This decision authorizes reviewer preparation of a new narrowly scoped persisted implementation prompt following protocol preflight and handoff readback. It does not itself launch an implementation agent, change SQLite bug status, prove V35 code, or satisfy any focused/six/197 Android execution. Implementation tip remains `57d8d8f1b4993d72177c12ac705f7f2137fa92a1`; the root remains OPEN and unresolved P2 count 7.

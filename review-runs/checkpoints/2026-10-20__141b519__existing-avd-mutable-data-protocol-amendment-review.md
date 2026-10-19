# Protocol maintenance: reusable existing AVD and mutable app test data — independent second pass

checkpoint_status: FINAL_PROTOCOL_MAINTENANCE_REVIEW
manual_review_run: NO
review_parent: 5fa1cbced553f09b782420b94a292b6c17c8f7ff
implementation_pinned: 141b5199592369ab953f52c55ebdb72ba300f1a4
protocol_commit: de606d3b95ee0c185d11a9f976d2c12221bb5428
protocol_parent: 11ba88d59cd5ea58fc53faba3e908e23d3898076
protocol_blob_before: 6fdb93a3325579b18ec66b1b0c9c9d7aa4bdbb1b
protocol_blob_after: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
protocol_path: ytdlnisx/REVIEW_PROTOCOL.md
protocol_scope: TWO_ADDITIVE_FUTURE_WAVE_RULES_ONLY
existing_agent_execution_authorized: NO
v50_consumed_final_unchanged: YES
device_test_executed_by_reviewer: NO
implementation_source_changed: NO
active_canonical_p2_unresolved: 8
clean: NO
independent_execution: NOT_EXECUTED

## Purpose and narrow amendment

The user requested relaxing excessive protocol restrictions and specifically accepted changing existing emulator app data while wishing to keep the existing emulator. This is authorization to review and revise stable protocol, not blanket permission for agent startup, device destructive actions, or changes to stale V50 task controls.

The normal sole-parent private commit `de606d3b95ee0c185d11a9f976d2c12221bb5428` changes only `ytdlnisx/REVIEW_PROTOCOL.md` and appends exactly two paragraphs (four added lines by GitHub diff, zero removed). Section 3.2 now distinguishes the **explicitly authorized, precisely identified existing emulator with mutable app/test data** from protected other devices, host state and evidence; forbids universal requirements for a fresh AVD, zero guest NIC/socket activity and idle unrelated background jobs absent a concrete material reason. Section 16.2.1 distinguishes initial focused tests from *post-failure finite-inventory* continuation and requires only reachable shared-carrier contamination checks while preserving failed-result evidence and exact-SHA closure requirements.

## Independent maintenance review

Read back the exact protocol blob `67a7de97b31c8e44ecb524c1902c9e09fce9f678` and verified the inserted text, unchanged previous content after removing the insertions, parent and forward ancestry. Compared Sections 3.2, 3.2.1, 7.1, 16.2.1, 16.3, 20–21; Master Plan at `plan/remediation` (`8491528b730abea17de22013bca4288e1549a39e`, blob `507a97c1455793b272298e29f37b945f4cfb55d7`) and `REVIEW_CHECKLIST_V6_OPERATIONAL.md` (blob `7b553328dfcd9941d783658f49ecb16c71b98c56`). No contrary mandate for a *universal first-test fresh AVD/zero NIC proof* found in those inspected governing scopes; this does not revise immutable historical governance pins or override execution gates for any reviewed SHA.

The existing `NEXT_CHAT.md` blob `c1cb892a416bcce6cdb5a6cf2fc240cafa5c3645` contains `CURRENT_EXPECTED_PROTOCOL_BLOB_SUMMARY` and `EXPECTED_PROTOCOL_BLOB` pointing to **old** `6fdb93a...`, and still claims the **consumed V50** task `PERSISTED_NOT_STARTED` with old new-AVD-only device control and `IMPLEMENTATION_AGENT_CURRENTLY_WORKING=NO`. The V50 intent/claim/independent FINAL and subsequent separate evidence-preservation commit supersede that stale task. The protocol amendment deliberately changes **neither** `NEXT_CHAT.md` nor V50's immutable prompt/start records, source, plan, ledger or tests. This is a **material handoff protocol-identity mismatch for future bootstrapping**, not an authorization to launch or retrofit V50.

## Next required boundary

Before a new implementation/testing wave, the independent reviewer must separately reconcile this stale execution capsule and exact protocol identity under §§20–21 with fresh GitHub facts and appropriate task/user authorization; the new agent prompt must explicitly permit the chosen existing AVD and scoped in-AVD test-data changes. No old V50 task relaunch, no source editing from review-only work, no silent expansion to wipe, factory reset, unrelated app data, host networking or other devices. Keep genuine method/test causal risks and exact committed-SHA final execution gates.

This maintenance checkpoint records a stable future policy and an unresolved dynamic handoff mismatch; it does **not** declare current V50 execution controls launch-ready, ROOT CLOSED or CLEAN.

# Clean-AVD verification route — owner-approved discard of guest application data

date: 2026-10-16 KST
checkpoint_kind: POST_V29_OWNER_DEVICE_DATA_DISPOSAL_AUTHORIZATION_AND_EXECUTION_PLANNING
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_start_parent: 838b89d3c399e45c8faf454449a8ab06295064c8
implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
local_only_X: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
local_only_X_tree: f2adb2f34ee679517df672f8ecfea7fc889c7c11
applicable_protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v29_report_commit: 5e65e2440b4d07bebe14dc5f44b9216c70d8403c
v29_report_blob: 01a5f251c03c9ac7efcb576bc50bcecc7397a779
v29_independent_review_commit: 838b89d3c399e45c8faf454449a8ab06295064c8
v29_independent_review_blob: 12d5a8229462a9185dae7f463bb3ed6a7105f1ed
active_P2_unresolved: 6
canonical_root_count_delta: 0
published_implementation_change: NONE
clean_basis_advancement: NONE
independent_execution: NOT_EXECUTED

## Owner authorization and precise limit

Following V29's final STOP, the owner expressly confirmed in this conversation on 2026-10-16 that **all existing data in the current emulator may be discarded**, including its debug app data/settings, and invited checking only if useful. This new prospective authority **supersedes prior no-wipe requirements for guest user data in precisely `Medium_Phone_API_36.1` at `emulator-5554`**, after freshly verifying its identity and actual ownership. It does not retroactively alter V26–V29 execution scopes, invalidate their historical STOP evidence, or authorize overwriting any other AVD, Windows local source, original source/schema snapshots, physical/decoded evidence, protected dirty roots/worktrees/stashes, old APKs, log files, Android SDK/global settings, repositories or immutable reports. No wipe/reset has been executed by this reviewer; only future guarded execution is approved.

User disposal consent does not prove that the currently installed application's live SQLite schema is valid. V29 G2 remains NOT_VERIFIED historically; do not claim the live upgrade passed, and do not install exact X into the old populated guest to bypass that gate. The owner is explicitly retiring that **specific old live-data upgrade scenario** in favor of a clean-AVD test scenario, not declaring the old data verified, restored, backed up, or migrated.

## Feasible bounded route

The already-sealed original 17 physical/decoded snapshots, authentic original Room64 schema and migration source, forward Room65 and generated KSP artifacts, local clean committed X and signed exact-X app/test APKs are **reported** by V26/V29 as preserved *outside* guest data. Independently verify their location/integrity/protection before any irreversible wipe. Their existence supports conducting actual instrumented `MigrationTestHelper` tests seeded with authentic old 61/63/64 schemas on a clean guest; a clean-device install alone is NOT a migration PASS. Those fixtures cannot substitute for the discarded device's exact private historical state, and preserving that observation is not required after this explicit owner choice.

Next reviewer-authorized scope may use a SINGLE cautious clean-data reset of only the verified named test AVD, with a controlled graceful stop if necessary and fresh offline identity/no-other-instance proof, a cold boot expressly preventing stale Quick Boot snapshot restoration, and clean guest/package/store preflight. It must check Windows disk free space and device/data partition capacity before mutation and abort if insufficient rather than deleting protected caches. Its single-use destructive action must be audited for actual target, command, exit, process ownership, and postcondition; no blind retries, no reset of other AVDs, no restore from old Quick Boot, no permission changes. If any local preservation or AVD identity cannot be proven, STOP before mutation with Full Report.

After a verified clean guest, explicitly restore the previously authorized narrow X exact-SHA execution chain only when applicable gates pass: verify exact remote db339e1 plus local-only X parent/tree and signed app/test APKs; install only the exact matched X pair under authorized scope; execute the six real `FormatBatchMigrationProductionWiringTest` methods (old source schemas, Room64→65, journal/reopen/recovery); after their actual PASS and another guarded forward-only destination-HEAD check, normal FF publication of X if it remains compatible, followed automatically by the 19-class/197-method Android gate on the **same** exact committed published X SHA. Preserve first failure and original evidence; no greenwashing, skipping, fake old-writer seeds, source/test/config rewrite, silent pruning of roots, automatic baseline or CLEAN. If new semantically material mismatch occurs, STOP with correct phase classifications and private append-only Full Report, do not expand the wave to unrelated fixes.

This is a material owner-authorization/bounded execution-route checkpoint, NOT a retrospective reclassification of V29 or a completion review of unpublished X. Implementation source review remains reviewer-owned after publication; the reviewer has not independently run tests, inspected X as authoritative source, approved root closure or changed any canonical severity/root count.

INDEPENDENT EXECUTION: NOT EXECUTED

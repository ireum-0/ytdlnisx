# V45 Independent Completion — Exact-SHA host BUILD PASS, device entry NOT ADMITTED

review_date_kst: 2026-10-19
checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_parent: 4bcb6a41a286f760542121510e59d4cb22a55f38
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
reviewed_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
reviewed_implementation_range: 141b5199592369ab953f52c55ebdb72ba300f1a4..141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_delta_in_v45: NONE
verdict: BUILD_PASS_AGENT_EXECUTED_DEVICE_NOT_ADMITTED_NO_RUNTIME_TEST_EVIDENCE
new_semantic_root: NONE
canonical_root_count_delta: 0
canonical_unresolved_p2: 8_UNCHANGED
clean: NO
independent_execution: NOT_EXECUTED
v45_report_commit: f51812d7d7b2d79f5b0eab17b661e16601714a69
v45_report_blob: f482457ab5ef1940dcbcb132ceda4a8ff8524a8d
v45_manifest_blob: 3f751eb95b1d1217e7b66ef9b638d2fe37d6a501
v45_task_instance: V45_CHAINED_HOST_DEVICE_DIAG_20261019_141B5199
v45_prompt_origin: 26423537e6b6e827c8b841e5b4bc8045deef3737
v45_prompt_blob: fac342bb6e286ef4c80fb41008642fab421b202c
v45_intent_commit: 2883d492fc423d854c234139c0cd980fdf5b990f
v45_intent_blob: eba819a65582475120aff29836da99a173fcaf3b
v45_claim_commit: 6310b60b44f6ee191a322438b4bef30d348ec783
v45_claim_blob: 27fffd6ea87c2aa2344006ef7133f598d0f56e30
protocol_blob: 6fdb93a3325579b18ec66b1b0c9c9d7aa4bdbb1b
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f

## Independent verdict and immutable GitHub evidence

The exact remote implementation tip `checkpoint/pre-baseline-review` is unchanged at `141b5199592369ab953f52c55ebdb72ba300f1a4` / tree `4903b4ef3949e15d2f2967e474805f54e3385a0e`. No V45 implementation commit, production/test/config edit, or new source-semantic range exists. Prior V43 independent source review and V44 STOP checkpoint remain the applicable semantic basis. V45 supplied new build/runtime-admission evidence, not a new correctness repair.

The private branch moved normally from launch-ready `653b2905cb4eb19f686233e7e6c8db66b52c0407` to `f51812d7d7b2d79f5b0eab17b661e16601714a69`: GitHub compare shows ahead3/behind0 and exactly four added paths: the task-specific intent, claim, Full Report and method TSV. Commit parent chain was independently established as
`653b2905` -> intent `2883d492` -> claim `6310b60b` -> final report/TSV `f51812d7`. Each intermediate commit adds only its authorized path; final commit adds only the two new report paths. Fresh readback confirms prompt blob, one-shot task ID, protocol/base, identical claimant in intent/claim, reported receipts and exact archive report/manifest Git blobs. These consumed receipts and matching STOPPED Full Report permanently supersede `NEXT_CHAT.md`'s stale `PERSISTED_NOT_STARTED` for V45. Never bootstrap or relaunch V45.

## Successful but bounded host proof

Agent's immutable report contains the native Gradle command `gradlew.bat --offline --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -Pytdlnisx.testAbi=x86_64`, native cmd PID 6700, exit 0, stdout `BUILD SUCCESSFUL in 5m 56s`, 75 actionable tasks (38 executed/37 cached), and named Kotlin tasks. `:app:compileDebugKotlin` was FROM-CACHE; `:app:compileDebugAndroidTestKotlin` executed and completed, so the exact published V43 diagnostic test *compiled*, but production Kotlin was not freshly compiled. The build and APK inspection were AGENT_EXECUTED / GitHub-REPORT-ATTESTED, never reviewer-reproduced. A compiler result is not runtime correctness.

The report identifies app APK SHA256 `22ad507d569c264888d78558b59b5478fbfb82ec0fe975951db8c021a0a29e52` and AndroidTest APK SHA256 `03460a4d8b066d818e276ec691307c5ab03094a98ab6a26247df8a4bc386a65d`. Agent performed aapt/apksigner verification for matching application/runner target and certificate SHA256 `403c3c085f6e9fd68e0a93549b657a13903fa50b051dcfed50aef43324aa8713`; DEX class-definition evidence names `DownloadFormatNotificationAuthorityProductionWiringTest`. These are agent-executed local artifact observations, not independently downloaded/rehashed APK bytes.

Reported eleven device metadata/installed-hash queries were read-only, with no installation, app startup or tests. The installed app was reported byte-identical to newly built app; installed test APK is the older protected V39 artifact, not this new V43-test-diagnostic build. Therefore exact current instrumentation of the new diagnostics did not occur. No assertion/callback success, runtime interaction, APK install safety or changed test logic is established.

## Source-grounded device admission stop

Exact published source inspection corroborates why app entry could carry effects: `app/src/main/AndroidManifest.xml` names `.App`; `app/src/main/java/com/ireum/ytdl/App.kt` onCreate starts Restore recovery and dispatches scheduler, download, cleanup, terminal publication, handoff and cancellation recovery actors before any test fixture's apparent in-memory isolation is enough to establish globally clean durable state. `RestoreTransactionCoordinator.kt` RestoreGate reads a durable operation store and preserves unresolved admission as DEFERRED. The target AndroidTest's `@Before` accesses application Context, clears static worker-owner test registries and constructs its own in-memory Room fixture. Thus an in-memory database or absent app PID cannot alone exclude effects from Application/startup, stored owners or independent recovery actors.

The V45 report says no qualified safe *current* observation established durable owner IDs, predecessor completion, relevant quiescence, non-interference or durable fencing before package replacement/Application entry. This is a **missing positive safety/admission proof**, not affirmative evidence that a corrupt, pending or unsafe owner exists and not a new repository-global hazard. Holding dependent install and test stages is consistent with the existing risk boundary. Do not automatically prescribe intrusive DB reads, app startup, uninstall, global cleanup or security/permission changes to manufacture admission. A next reviewer-governed scoped proof path, if independently authorized, must establish a safe isolated target or source-grounded non-interference/fence, with exact installed app/test artifact provenance, before any device effect.

## Frozen inventory and preserved canonical disposition

The new 197-row V45 TSV has exactly the same first 14 columns, byte-equivalent line values and order as the V43 Git blob `5e8d5b7beffd0602a76fe69e3fd31aed290bcc71`. There are 197 distinct selectors and partitions P1=48, P2=20, P3=49, P4=76, P5=4. V39 historical truth remains 46 PASS, 1 FAIL, 150 NOT_REACHED. V45 reports zero executed methods; all 197 entries are NOT_REACHED (46 historical PASS excluded, one historical FAIL under changed diagnostics plus 150 original not reached), no actual JUnit PASS/FAIL/ERROR or runner SKIP. This was independently crosschecked from exact GitHub files, not inferred merely from report totals.

The original V39 10-second timeout's runtime cause and current job/actor completion remain NOT_VERIFIED; V43's newly compiled observation callback has not executed. No finding or root is opened, closed, waived or recounted solely from build success or missing device admission. Prior canonical eight unresolved P2 remain unchanged; repository CLEAN=NO, CLEAN_REVIEW_BASIS revoked. Protected work, local Windows/SDK/Gradle side effects, candidate cleanliness and original input evidence seals are AGENT-REPORTED and cannot be independently examined from GitHub; exact remote source nonadvancement and normal append-only private publication are independently verified.

## Review retrospective and next boundary

V45 corrected the earlier workflow weakness: it diagnosed an initial native invocation/path issue, used a safe alternate diagnostic strategy, made a genuine offline build, and automatically continued to APK/source and device admission rather than halting because a helper failed. The exact *cause* of V44's old PowerShell stall remains unproved, and successful V45 path admission must not be retroactively assigned to that old helper. The new limiting uncertainty is not compiler/toolchain but externally preserved durable runtime ownership on the existing device.

Next independently reviewable proof: qualified non-mutating scoped runtime-owner/Restore/WorkManager/terminal-handoff current-state confirmation or a separately approved isolated/fenced device target whose startup and installation effects cannot consume or overwrite protected work. Favor actual finite positive ownership or non-interference evidence rather than treating lack of global emptiness or inaccessible DB as automatic STOP for unrelated independently safe work. Only then could exact new test APK be safely installed and eligible fixed-inventory tests selected per §16.2.1, while protecting the original failed method's no-unchanged-retry rule.

This is a reviewer-only V45 completion checkpoint. No NEW agent task, prompt, device authorization or private handoff-routing change follows merely from this review (§§6,8.2,21). Archived matching STOPPED V45 report overrides its stale launch flags. Separate user authorization would be required for a successor wave. No new lens DEEP mark or manual `3` run is claimed.

INDEPENDENT EXECUTION: NOT EXECUTED

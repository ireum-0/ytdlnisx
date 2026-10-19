# V51 existing-AVD Android verification — independent completion review FINAL

checkpoint_status: FINAL
checkpoint_kind: INDEPENDENT_IMPLEMENTATION_AGENT_COMPLETION_REVIEW
manual_review_run: NO
review_parent: d03e886e42725932563e1e1fc7c1310c9722cdaa
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
private_report_commit: 5edd13f60326708d1b0d53fd471f8b6d757a3260
private_report_parent: b9261de429d0b5da2903b527ddea8b0b88860b65
private_report_blob: d3bae9df3edf1ff50f07f5ed49112afba6a11d1c
private_method_tsv_blob: cbfb3e99f54be9cbbc654b3be2ef6c0455bd37a7
protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
task_instance_id: V51_EXISTING_AVD_FOCUSED_ANDROID_20261020_141B5199
task_consumed: YES
device_test_evidence: 54_PASS_2_FAIL_94_NOT_REACHED_OF_ORIGINAL_150
gate_result: FAILED_AND_INCOMPLETE
new_confirmed_production_semantic_roots: 0
root_cause_of_two_fails: NOT_VERIFIED
active_canonical_p2_unresolved: 8
clean: NO
independent_execution: NOT_EXECUTED

## Independent verdict

V51 progressed materially beyond earlier preboot waves. It is a verification-only task, with exact source `141b5199592369ab953f52c55ebdb72ba300f1a4` unchanged, no implementation commit and no test/config edit. The agent reports verified emulator `Medium_Phone_API_36.1 / emulator-5554`, exact original app and test APK pair, one update of the test package, and 56 separate AndroidJUnitRunner invocations on 56 distinct original previously unexecuted selectors. Its first prescribed focused P1 test passed. The V51 agent reports **54 JUnit PASS, 2 actual JUnit FAIL, 94 NOT_REACHED** among the original 150 V39 NOT_REACHED; the recorded gate is **FAILED AND INCOMPLETE**, NOT CLOSED/CLEAN. Reviewer did not independently execute device methods or receive the 545 original local files and raw runner logs, so runtime counts, AVD identity/installation and on-host protection attestation are agent-reported, independently checked here for GitHub manifest coherence, not independent device execution.

GitHub confirms `ireum-0/private:ytdlnisx-review` tip `5edd13f60326708d1b0d53fd471f8b6d757a3260`, a sole-parent normal forward report-only commit from `b9261de429d0b5da2903b527ddea8b0b88860b65`, adding ONLY `ytdlnisx/reports/2026-10-20__V51_EXISTING_AVD_FOCUSED_ANDROID__v51-existing-avd-20261019T1616088721990Z__FULL_REPORT.md` and `ytdlnisx/reports/2026-10-20__V51_EXISTING_AVD_FOCUSED_ANDROID__v51-existing-avd-20261019T1616088721990Z__METHOD_RESULTS.tsv`; expected blobs match direct readback. Earlier immutable intent `b54b1291f2e398d7a0464cda7a343c5445cbfcad` and claim `b9261de429d0b5da2903b527ddea8b0b88860b65` have matching immutable task/prompt/origin/claimant, one-parent normal forward ancestry; the unique V51 task instance is **consumed and must not be relaunched**. The unchanged `NEXT_CHAT.md` still claims `PERSISTED_NOT_STARTED` and is superseded by verified intent/claim plus final report. Do not return old V51 bootstrap, or modify launch-control fields as an incidental review-only action.

## Manifest audit: independent exact-file computation

The GitHub-published V51 TSV has **197 rows, 112 columns**, with **all prior 96 V50 columns and every original row/field identical** to immutable V50 blob `e384e1de70e833c0957d370acb9cdf29f5be3a4b`. The newly added 16 V51 columns have:
- 54 rows `PASS`, `v51_executed=1`, all originally V39 `NOT_REACHED`;
- 2 rows `FAIL`, `v51_executed=1`, original ordinal 115 and 116;
- 94 rows `NOT_REACHED`, `v51_executed=0`, originally V39 `NOT_REACHED`;
- 46 prior V39 `PASS` and one prior V39 `FAIL` are intentionally **current-wave NOT_REACHED, executed=0**, and are excluded from V51's 150 eligible-method denominator.
Thus full 197-row `v51_status` totals 54 PASS, 2 FAIL, 141 NOT_REACHED; 141=94 current eligible but unexecuted + 47 historical exclusions. The reported exact method/ordinal map and first errors agree with V51 TSV fields. Neither adb native exit 0 nor a historical pass was recoded as a fresh JUnit PASS. No invented skip or automatic failed-method retry is evidenced by the archived manifest; the exact host invocation/retry chronology remains agent evidence unless original raw host records are supplied.

## Failure analysis against exact current production and test sources

Both failed selectors are in `app/src/androidTest/java/com/ireum/ytdl/work/DownloadProducerSemanticFingerprintProductionTest.kt`, blob `eb2e4928419d13e350120266b6b76c4018f8ed1c`:

1. Original ordinal 115, `generatedConfigSemanticsReachTheProductionFingerprintBoundary`, first error `AssertionError: Values should be different. Actual: null` at test line **73**. Source line 72 compares `first.fingerprint` and `changed.fingerprint` and did **not** fail; line 73 compares `first.request.getArguments("--config-locations")?.singleOrNull()` with the corresponding `changed` value. Both observed values are null. Production `YTDLPUtil.kt` blob `7229e7116ccf45495a7e14db21cf8c180d35a9d7` lines 2758–2767 creates and writes a real unique config file and attaches the config option to `ytDlRequest` via `addCommands(tmp)`; test access through `getArguments()` may be a mismatched observer for such commands. The external `YoutubeDLRequest.getArguments` behavior has NOT been independently traced here, so the nulls prove only the specific test observer's output, **not** absence of the generated config, and not a confirmed production defect. The fingerprint difference (preceding assert passed) is positive evidence that different format content reached some fingerprint boundary; a complete production-correctness verdict requires verifying the relevant exact producer/carrier/consumer.

2. Original ordinal 116, `generatedConfigFilenameIsRuntimeOnlyForIdenticalEffectiveSemantics`, test line **89** actual fingerprint inequality. Expected `799c131f42b02e2d1aa2f11806ec39034acde57fc6fead7022ef9bceefbf1405`; observed `85d5063a1eefab49c2e98b54a916434c1dcb107dc77433a60f24995ee9462e5e`. The fixture calls `fingerprintFor(item)` then `fingerprintFor(item.copy(id = item.id + 1L))`, and each `fingerprintFor` chooses a new UUID staging directory. Production `DownloadProducerSemanticFingerprint.kt` blob `03c4001a2ca3b9ef61343165919ef0d65551c231` lines 24–76 normalizes command/runtime-path tokens, effective config, output plan and publication semantics before SHA256; `YtdlpProducerSemanticSnapshot.kt` blob `5b23787b8c8c4d9cb61ea076ecbb8d1faf8ffd99` holds effective generated config and runtime paths. `YTDLPUtil.kt` materializes config at lines 2758–2789. These establish intended boundary and possible value carriers, **not** that the two inputs really had identical normalized effective semantics at runtime. The Full Report explicitly says raw relevant request/config/Room payload bytes were not captured before fixture teardown. Without actual before/after generated config, parsed tokens, normalized outer/effective payload, output-plan and fingerprint preimage, it is **NOT_VERIFIED** whether this is a real production normalization defect, a test assumption/config drift, or another shared carrier. A third subtitle semantic fingerprint test passed, which does not override either failure.

No concrete confirmed new production incorrect-effect chain is established solely by the two assertions. They share a fingerprint/request semantic domain but whether they share one root or are independent failures is **NOT_VERIFIED**. Preserve both actual test failures as evidence; do not create two canonical P2 roots, automatically change production, weaken assertions, switch to fake observers, or rerun unchanged failed tests to seek green. Next diagnostic contract: trace how `addCommands` is observed by `getArguments`; record (from already available records if possible) actual generated request/config, effective snapshot contents, runtimePaths, normalizer inputs and output plan for the two cases; identify earliest divergent producer/carrier and first incorrect final effect. Any test instrumentation or source edit is a separate authorization boundary and would need a distinct new committed-SHA execution gate.

## Unexecuted tests and scoped authority boundary

The Full Report maps 93 remaining selectors to ten fixtures with live `WorkManager.getInstance(context).cancelAllWork()` in `@Before` or `@After`, and original ordinal 105 to real scheduler END `cancelAllWorkByTag("download")` through `CancelScheduledDownloadWorker`. Independent source sampling confirms `ObserveSourceGenerationOwnershipProductionWiringTest.kt` blob `45a3d21f0feb5b529e6cf368d3fbd7cd6bb1e478` lines 45/66, `F11HandoffCarrierMutationAdmissionProductionWiringTest.kt` blob `dedeac97797c98bcb851aeae34a74ae9764923eb` lines 73/105, `BackupResetTransactionProductionWiringTest.kt` blob `17b72af677f5e53eb1ff5e0a3917a7f6eb77c554` lines 80/97, and `CancelScheduledDownloadWorker.kt` blob `eac2ed1080e87940065d3c0844b0af460b240bbc` line 65. Exact current V51 prompt barred global or broad work cancellations, even though ordinary mutable target-app data was authorized. No evidence authorizes treating those broad effects as fixture-scoped merely because app test data may change. The agent correctly recorded affected methods NOT_REACHED rather than SKIP/PASS. Full 94-selector effect mapping across all ten fixtures is agent-attested; independently sampled concrete violating production/test calls support the class of boundary, not a complete independent 94-line path audit.

A future *separately authorized* test plan may either isolate cancellation to proven fixture-owned work, or explicitly authorize existing-AVD app-wide cancellation if its effect on nonfixture app work and test truth is understood and accepted; neither is granted retroactively by this report. No new AVD, host network proof, old V50 replay or global reset is required just to investigate these source boundaries off-device.

## Verdict and next governed action

- The V51 runtime evidence gate is **FAILED** due to 2 genuine assertion failures and **INCOMPLETE** due to 94 unexecuted eligible methods. V51 task completed/consumed, not still waiting to launch.
- No source/test/config changes; no 8-P2 canonical root closure; `CLEAN=NO`; no confirmed new root or attribution and no automatic P2 recount.
- The next reviewer-owned action is evidence-based classification of the shared fingerprint test/producer boundary, preserving both failures, and a narrow plan for future affected focused verification; independently assess the scope of broad WorkManager cancellation before any next device-method authorization. A further agent task, prompt, handoff routing transition or source mutation requires separate authorization, exact GitHub preflight and its own unique instance. Do not reproduce/reissue V51.
- This checkpoint is the independent final assessment of this **verification-only agent result**, not a manual-`3` run and not full repository CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED

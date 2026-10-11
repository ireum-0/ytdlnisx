# BUG-SCHEDULER-01 — unpublished local verification and host recovery, out-of-band transcript reconciliation

date: 2026-10-11
record_kind: INDEPENDENT_REVIEW_EVIDENCE_RECONCILIATION
record_status: FINAL
manual_review_run: NO
review_parent_sha: 0ab05a60df535d0aef683cd1a77c66fca1da9a20
implementation_pinned_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
canonical_root: BUG-SCHEDULER-01 EXISTING OPEN P2 SAME_ROOT
new_canonical_root_count: 0
canonical_count_delta: 0
semantic_closure: NOT_VERIFIED
source_publication: NOT_PUBLISHED
independent_device_execution: NOT_EXECUTED

## Provenance

The user supplied a full, historical exported Codex conversation transcript reaching October 11. This checkpoint reconciles previously missing *agent-reported* Windows-local execution and protected-candidate observations with the *independently fetched* GitHub state and previous source finding. No remote evidence is invented for the local candidate. Local Windows files, original sealed manifests, device APK binary hashes and non-remote diff are not accessible through this GitHub-only independent review. The original transcript is supplied in the current user conversation; no large logs, personal identifiers or secret-bearing evidence are uploaded to GitHub.

Remote implementation branch remains exactly c34b8aa57e01803c9960e4ad873d1ed5b68e019c; this publication still has the confirmed accepted scheduler START/END FAILED/CANCELLED -> generic deleteAccepted fallthrough documented in the October 8 checkpoint. Earlier historical accepted-terminal runtime/harness results cannot close this remote residual until a new exact source SHA is published and reviewed.

## Missing Windows-local success evidence now recovered from transcript

1. The *previously assigned* ordinary non-elevated Gradle 8.13/JBR 21.0.8 one-shot `gradlew.bat --offline --no-daemon --version` probe **did execute exactly once and exited 0**, with empty stderr and zero restricting SIDs. Agent's sealed report is described as `BUG_SCHEDULER_01_NORMAL_DH2_WRAPPER_STARTUP_PROBE_REPORT.md` (2026-10-08). Its historical execution status is COMPLETED_PASS, not PERSISTED_NOT_STARTED. This does NOT establish historical WinError 5 cause or all future Gradle launches.
2. The accepted-terminal unpublished six-line production candidate was **actually built and its original 12 authored focused regressions executed 12/12 PASS**. In the subsequent local prepublication verification, additional reported Android partitions passed: scheduler handoff controls 9/9, corrected real WorkManager 3/3, external authority 12/12, settings/Restore 20/20; scheduler window 4/5 PASS and one FAIL due to exact-alarm capability refusal at the after-window queue assertion. These are distinct local-candidate runtime results, not the published exact-source closure.
3. After bounded host diagnosis, scheduler-window JVM controls passed 16/16 and settings-validation JVM controls passed 6/6. The interim preserved totals were 82 PASS / 1 unresolved window FAIL. Another ordinary-mutation Android method (`schedulerOrdinaryCarrierMutationCompletesBeforeRestorePublication`) later passed 1/1, yielding historical 83 PASS / 1 unresolved prior FAIL. Do not aggregate across time/trees as a single 138-case exact final SHA gate.
4. A test-harness failure in `F11HandoffCarrierMutationAdmissionProductionWiringTest.resetWins()` was independently *reported* from local diagnostic probes: an unrelated automatic-keyword reconciler consumed a global ordinary-mutation test hook and held publication authority, preventing the intended Reset interleaving. The local correction restricts hook consumption to the exact synchronous writer thread, keeping original assertions/order/timeout. Post-correction compilation passed, and six JVM controls passed; subsequent attempted corrected Reset launches repeatedly failed at Windows process creation before Android assertion, so those attempts did not establish corrected Reset acceptance.
5. The latest October 11 Codex alpha.20 standalone CLI plus Windows helpers successfully executed read-only `Get-Location` and exactly **one** installed `emulator-5554` `schedulerResetWinsBeforeOrdinaryCarrierMutation` instrumentation: exact test name observed; `OK (1 test)`, elapsed 4.68s, adb process exit 0; no sandbox error. This proves that local tool/ADB/test execution path for that installed APK at that moment. The selected device differs from previous `emulator-5560`, and the transcript does not independently bind that installed APK to the unpublished latest five-file candidate SHA/tree. Do NOT silently convert this one PASS into exact-candidate source/harness or exact-final-SHA closure. Historic `WinError 5` causation remains NOT_VERIFIED.

## Later protected local-candidate identity reported

The initial October 8 protected candidate had implementation HEAD c34b8aa57e01803c9960e4ad873d1ed5b68e019c, tracked dirty tree `df2d52c20c6928bf74b7d1c4f230483189b5aa5b`, three unstaged files and empty index. The recorded local candidate moved *forward as an uncommitted diff*, without moving its committed HEAD, by adding test-only regression/harness correction. Latest repeated agent reports prior to October 11 record:

- committed HEAD: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
- committed tree: `ec679bdb43c600946d2d2dbab0e130ad213ba423`
- latest tracked dirty-tree: `5fbe5eb5c02a02f8bf1ebbd703bebc340d3d125e`
- exactly FIVE tracked unstaged paths; empty logical staging index
- modified paths:
  - `app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/RealWorkManagerHandoffProductionTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/WorkManagerHandoffProductionTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/F11SchedulerSettingsTransitionProductionWiringTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/F11HandoffCarrierMutationAdmissionProductionWiringTest.kt`
- five separately protected worktrees are retained, plus four untracked unknown-origin literal `%SystemDrive%` Windows cache-like files totaling 989,728 bytes; their preservation and safe classification remain required.
- A historical *index-file byte hash* discrepancy was observed but all 1,404 index entries then matched HEAD and staging was empty. It was not attributed to any writer; it was never authorization to reset the index.

These Windows observations are not independently re-read here; the next implementation agent must check current local identity before any write and STOP on unexplained mismatch. The old three-file/12-unexecuted handoff snapshot is superseded as a current local status, but remains immutable historical evidence.

## Host launch forensics and distinctions

Historic Windows native launch error 5 intermittently blocked the intended Android tests; preserved Procmon evidence records an adb.exe process that exited `0xC0000022`, contemporaneous Teruten-related remote-thread creation and T_Prevent.dll execution, then a .NET WinError 5 report. This establishes chronology/activity, NOT the component that initiated termination or an access-denial decision; hypotheses remain unproven. No security-control disabling, ACL alteration, lock/cache deletion, or process termination is authorized by this checkpoint. The corrected Codex alpha.20 environment's success is a present execution-path pass, not retroactive proof of root cause or permanence.

## Current decision / remaining work

BUG-SCHEDULER-01 remains OPEN P2. The exact source-meaning correction appears in an UNPUBLISHED local draft that has preliminary passing tests but incomplete window/Reset and exact-new-SHA comprehensive closure. The Windows records supersede these stale machine handoff assertions: `CURRENT_BUG_SCHEDULER_01_LOCAL_PATHS=3_UNSTAGED_INDEX_EMPTY`, `CURRENT_BUG_SCHEDULER_01_LOCAL_REGRESSIONS=12_METHODS_UNEXECUTED`, and `CURRENT_NORMAL_DH2_WRAPPER_PROMPT_STATUS=PERSISTED_LAUNCH_READY_NO_EXECUTION_REPORTED`. Do not silently treat the one installed Reset PASS as final source correctness.

Next: reconcile current local five-file diff, protected registry/index/cache and APK/source identity; reuse supported preliminary 12/12 and 22/22 evidence as evidence, not as new-final-SHA gating; review accepted-terminal production/DAO/Restore consumers, correct only same root; obtain required exact-committed-candidate focused/affected execution on verified device, including the still-open window and corrected Reset contract; then normal forward-only publication (with fresh destination check) and full governing exact-final-SHA union/independent review. An environment or protected-state mismatch is a reportable STOP, not a justification to discard local work. No source, test, caches, production branch, ledger, or immutable past evidence were modified by the reviewer.

INDEPENDENT EXECUTION: NOT EXECUTED

# BUG-UPDATER-04 direct-role diagnostic correction complete — single-method rerun authorized

review_parent_sha: b64d049a306a0cb737e9384f7f9b84afbda58323
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Implementation-agent report accepted:
- changed only YtdlpNativeProcessBarrierStateMachineTest.kt;
- added read-only diagnostics before/after exactly one recoverDownloadExecution call;
- original success assertions preserved;
- AndroidTest Kotlin compile PASS;
- git diff --check PASS;
- protected dirty files/history/evidence preserved;
- no device test, production edit, commit, or publication;
- HEAD remains 256a5cf507b54adcca0342b82ddaf6e2d75a684e.

Prior result remains:
- combined native gate 13 PASS / 1 FAIL;
- exact cause NOT_VERIFIED.

Reviewer decision:
Authorize exactly one ARM64 operator rerun of only:
com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrierStateMachineTest#directNativeRoleMarkerUsesTheSameExactGenerationRecoveryCarrier

Use the same corrected dirty candidate.
Do not run the full 14-test combined gate.
Do not rerun unchanged after this single-method result.
Preserve the complete failure message if it fails; it now contains pre/post diagnostics.

No source edit, commit, push, or publication is authorized.

BUG-UPDATER-04 remains OPEN P2.

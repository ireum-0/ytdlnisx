# Remediation tooling

These Windows PowerShell wrappers standardize caller-supplied remediation checks. They do not choose findings, infer test scope, classify a test failure as a production or harness defect, judge review-tip compatibility, or declare the repository CLEAN.

## Evidence

Each invocation creates a unique directory under:

    build/remediation-agent/<candidate-sha>/<run-id>/

The directory must be Git-ignored. JSON records bind to the exact committed SHA and tree. Exact-source checks also require no tracked changes and no non-ignored untracked files; Git-ignored evidence/build outputs and an ignored `local.properties` in the source worktree remain allowed. Blocked checks report a bounded untracked-path summary and never remove or alter those files. Raw command stdout/stderr remain separate log files. A new invocation gets a new directory so a retry cannot overwrite the first failure.

Normal exact-source verification creates a detached Git worktree at `build/remediation-worktrees/<run-id>` and runs each requested Gradle or diff gate there. The short, ignored path keeps long repository filenames within Windows path limits; the worktree is bound to the unique verification run ID in its evidence. Its HEAD/tree and clean tracked/non-ignored state are recorded before gates, after each gate, and after the last gate. Gradle's ignored build/test outputs stay in that materialization; wrapper logs and JSON stay in the separate run evidence directory. The wrapper retains the materialization and its Git worktree registration; it never removes or resets it. An ignored source-worktree `local.properties` is not inspected, copied, or serialized, and does not participate in the tracked candidate tree. SDK configuration for the isolated execution tree must therefore be available through the normal host environment.

Do not put credentials in arguments. Preflight checks local.properties ignore/tracking status without reading its contents.

## Preflight-Wave.ps1

Inputs are explicit. The wrapper verifies the implementation remote ref, local HEAD and optional parent, exact worktree state, protected primary HEAD, named protected stash objects, local.properties status, optional review ancestry, and optional connected-device health.

Connected preflight requires an explicit ADB path and serial. It runs bounded ADB-list, shell, boot-complete, and PackageManager probes. A listed device is not treated as healthy unless those probes pass. Optional AVD identity/config observations are read-only.

Example invocation shape:

    .\Preflight-Wave.ps1 -RepoPath $repo -RemoteName origin -ImplementationRef $implementationRef -ExpectedRemoteSha $remoteBase -ExpectedLocalSha $candidate -ExpectedParentSha $parent -ProtectedPrimaryPath $primary -ProtectedPrimarySha $primarySha -ProtectedStashObjects $stashIds -RecordedReviewTip $reviewTip -ReviewRef $reviewRef -RequireReviewAncestry

A forward review-tip movement is reported mechanically. The wrapper does not decide semantic compatibility.

## Invoke-Verification.ps1

The caller supplies each connected test class, JVM test class, compile task, and whether to run git diff --check. No class or task is inferred. Normal exact-source mode accepts only the resolved repository-local `gradlew.bat` and executes that canonical wrapper from the exact candidate materialization; a supplied alternate launcher is rejected before any gate starts. Synthetic fake launchers remain available only with `ToolingDemoMode`, whose evidence is marked `tooling_demo`.

Connected classes execute serially, one class-filtered Gradle invocation at a time. Android instrumentation classes use the Android runner class property; JVM classes use Gradle test filters.

Before each connected invocation the wrapper requires healthy ADB shell, boot-complete, and PackageManager probes. It periodically samples those probes and guest PSI while Gradle runs. A failed health preflight blocks the Gradle invocation. A failed connected gate with zero tests and infrastructure evidence opens a circuit breaker for later connected partitions. Recovery is never automatic. An authorized retry is a new invocation that references the preserved infrastructure evidence, supplies a recovery authorization note, and passes health preflight.

Independent JVM/compile/diff checks after an infrastructure event run only when the caller explicitly supplies ContinueIndependentAfterInfrastructureFailure. A test failure is recorded without assigning a production-versus-harness cause. PSI or process CPU alone cannot open the circuit breaker.

Gradle daemon reuse is the default. SingleUseDaemon explicitly adds the single-use daemon option. Gate timing is estimated from task, install, instrumentation, and result markers; the estimate and source logs are retained.

For a synthetic wrapper demonstration only, ToolingDemoMode accepts a fake Gradle/ADB executable and a relative result directory name under that run's evidence directory. The wrapper passes its resolved path to the child as `YTDLNISX_REMEDIATION_DEMO_RESULT_ROOT`. Such records are marked tooling_demo and Complete-Wave.ps1 will not accept them as exact-source verification evidence.

Run `Test-ExecutionLifetimeProvenance.ps1` for the combined synchronized tooling acceptance matrix. It temporarily mutates and restores a disposable fixture's tracked input while a gate is active, then verifies the gate read the committed value from its independent candidate materialization. It also exercises the pre-Push local-HEAD, destination-ref, and review-ref races; the after-observation `B -> C -> X` target-update race; exact-object conditional push and already-pushed controls; tracked/untracked rejection; ignored output and local.properties allowance; canonical/demo launcher policy; and completion's provenance binding. It does not run Android instrumentation.

Example invocation shape:

    .\Invoke-Verification.ps1 -RepoPath $repo -ExpectedSha $candidate -ConnectedTestClass $connectedClasses -JvmTestClass $jvmClasses -CompileTask $compileTasks -RunDiffCheck -AdbPath $adb -DeviceSerial $serial

Recovery retries require the exact prior infrastructure evidence, a non-empty caller authorization note attesting to material recovery, the exact same single connected class, and a passing bounded device-health preflight. Each retry creates a new run directory, preserving the original failed attempt.

## Device and stall evidence

The health record includes serial/state, bounded shell and PackageManager latency, sys.boot_completed, model/build/AVD identity, start/end time samples, and device timezone. UTC is the machine-correlation axis. Raw device wall time is preserved exactly; derived device UTC and Asia/Seoul values are separate fields.

During a diagnosed stall, bounded diagnostics preserve CPU, I/O, and memory PSI or mark them unavailable; recent raw logcat; slow system_server, ANR, binder/service-manager, and package-install signals; a guest process snapshot; and a host snapshot of emulator/QEMU/ADB processes, available memory, and coarse disk queue data when available. A probable guest-wide stall is a diagnostic signal only, not a root-cause verdict. No AVD is wiped, created, deleted, restarted, or reconfigured by these wrappers.

The watchdog keeps sampling after a diagnostic snapshot. It captures the bounded guest/host bundle after a failed health probe, sustained elevated PSI across samples, or materially slow shell/PackageManager probes. PSI or latency triggers diagnostics only; the infrastructure circuit breaker still requires a failed bounded health probe or a zero-test invocation with an infrastructure marker.

The periodic watchdog also captures bounded instrumentation-presence output. Stall diagnostics include raw guest `top` lines and target-process CPU fields when the device's top output exposes a parseable CPU column.

## Complete-Wave.ps1

Default behavior is Check mode. It validates exact HEAD/tree, no tracked changes or non-ignored untracked files, verification evidence binding, execution-lifetime provenance for the exact candidate materialization and every passing gate, caller-required PASS gates, expected remote base, review-tip ancestry, and the normal fast-forward relation. Ignored evidence and build outputs remain allowed. Evidence output is the only write in Check mode.

Push mode requires the explicit Push switch. Immediately before a write it re-reads local HEAD/tree/worktree state, the review ref and its recorded-tip ancestry/forward acknowledgement, then reads the destination implementation ref last. It blocks if local HEAD moved from the tested SHA or either ref is outside the completion contract. Before publication it independently proves the accepted old destination is an ancestor of the tested SHA. The update uses the full immutable TestedSha as its source and an exact-ref, exact-old-SHA `--force-with-lease=refs/heads/<implementation-ref>:<accepted-old-sha>` compare-and-swap guard, as authorized by REVIEW_PROTOCOL Section 1.1. The lease is only used with the proven fast-forward relation; it cannot authorize a non-forward update. If the destination changed after the accepted observation, the lease rejects the update and completion stops without retry or reconciliation, preserving the competing writer's tip. No plain `--force`, implicit/tracking-ref lease, leading-plus refspec, amend, rebase, squash, reset, or rewrite path is available. After a successful update it re-reads the implementation ref and requires exact equality with the tested SHA and 0/0 ahead/behind. A forward review tip remains caller-owned and requires an explicit compatibility acknowledgement naming the exact live review-tip SHA.

Example invocation shape:

    .\Complete-Wave.ps1 -RepoPath $repo -RemoteName origin -ImplementationRef $implementationRef -ExpectedRemoteBaseSha $remoteBase -TestedSha $candidate -VerificationEvidencePath $verificationJson -RequiredGateIds $requiredGateIds -RecordedReviewTip $reviewTip -ReviewRef $reviewRef
    .\Complete-Wave.ps1 -RepoPath $repo -RemoteName origin -ImplementationRef $implementationRef -ExpectedRemoteBaseSha $remoteBase -TestedSha $candidate -VerificationEvidencePath $verificationJson -RequiredGateIds $requiredGateIds -RecordedReviewTip $reviewTip -ReviewRef $reviewRef -Push

Run acceptance demos against synthetic fixtures; never use an unfiltered connected instrumentation suite to validate this tooling.

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepoPath,
    [Parameter(Mandatory)][string]$ExpectedSha,
    [string]$ExpectedParentSha,
    [string[]]$ConnectedTestClass = @(),
    [string[]]$JvmTestClass = @(),
    [string[]]$CompileTask = @(),
    [switch]$RunDiffCheck,
    [string]$GradlePath,
    [string]$AdbPath,
    [string]$DeviceSerial,
    [ValidateRange(1, 600)][int]$ProbeTimeoutSeconds = 10,
    [ValidateRange(0.1, 600)][double]$MaxShellLatencySeconds = 8,
    [ValidateRange(0.1, 600)][double]$MaxPackageManagerLatencySeconds = 15,
    [ValidateRange(1, 600)][int]$DeviceWatchdogIntervalSeconds = 30,
    [ValidateRange(1, 86400)][int]$GateTimeoutSeconds = 14400,
    [switch]$SingleUseDaemon,
    [switch]$ContinueIndependentAfterInfrastructureFailure,
    [string]$PriorInfrastructureEvidence,
    [string]$RecoveryAuthorization,
    [switch]$ToolingDemoMode,
    [string]$DemoResultRoot,
    [string]$EvidenceRoot
)

. (Join-Path $PSScriptRoot 'Remediation.Common.ps1')

function Assert-RemediationClassScope {
    param([string[]]$Classes, [string]$Label)
    $seen = @{}
    foreach ($className in $Classes) {
        if ($className -notmatch '^[A-Za-z0-9_.$*?]+$') {
            throw "$Label contains unsupported class-filter characters: $className"
        }
        if ($seen.ContainsKey($className)) {
            throw "$Label contains a duplicate class filter: $className"
        }
        $seen[$className] = $true
    }
}

function Get-VerificationLogPaths {
    param($ProcessResult)
    return @($ProcessResult.stdoutPath, $ProcessResult.stderrPath)
}

function Read-PriorInfrastructureEvidence {
    param(
        [string]$Path,
        [string]$RepoFull,
        [string]$CandidateSha,
        [string]$CandidateTree,
        [string]$ExpectedClass
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'A prior infrastructure evidence file is required for a recovery retry.'
    }
    $full = (Resolve-Path -LiteralPath $Path).Path
    $root = [System.IO.Path]::GetFullPath((Join-Path $RepoFull 'build\remediation-agent')).TrimEnd('\') + '\'
    if (-not $full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Prior infrastructure evidence must be under ignored build/remediation-agent output.'
    }
    $prior = Get-Content -LiteralPath $full -Raw | ConvertFrom-Json
    if ($prior.candidateSha -ne $CandidateSha -or $prior.candidateTree -ne $CandidateTree) {
        throw 'Prior failure evidence does not bind to the unchanged exact candidate SHA/tree.'
    }
    if ($prior.requestedTestClass -ne $ExpectedClass -or -not $prior.zeroTests) {
        throw 'Prior evidence is not a zero-test infrastructure event for the exact requested class.'
    }
    if ($prior.eventKind -notin @('zero_test_infrastructure_failure', 'connected_health_preflight_failure')) {
        throw 'Prior evidence is not an authorized infrastructure circuit-breaker event.'
    }
    return [pscustomobject]@{ path = $full; evidence = $prior }
}

function New-VerificationGateRecord {
    param(
        [string]$GateId,
        [string]$Kind,
        [string]$RequestedClass,
        [string]$RequestedTask,
        [string]$Status,
        [string]$Command,
        [string[]]$Arguments,
        [string]$CandidateSha,
        [string]$CandidateTree,
        [string]$StartedUtc,
        [string]$StartedKorea,
        [string]$EndedUtc,
        [string]$EndedKorea,
        [int]$ExitCode,
        [bool]$TimedOut,
        $TestSummary,
        [string[]]$InfrastructureSignals,
        [string]$InfrastructureStatus,
        $PhaseMap,
        [string[]]$LogPaths,
        [string]$FailureEvidencePath,
        $DeviceHealth,
        [string]$ErrorText
    )
    return [pscustomobject][ordered]@{
        gateId = $GateId
        kind = $Kind
        requestedTestClass = $RequestedClass
        requestedTask = $RequestedTask
        status = $Status
        command = $Command
        arguments = @($Arguments)
        candidateSha = $CandidateSha
        candidateTree = $CandidateTree
        startedUtc = $StartedUtc
        startedKorea = $StartedKorea
        endedUtc = $EndedUtc
        endedKorea = $EndedKorea
        exitCode = $ExitCode
        timedOut = $TimedOut
        instrumentationStarted = $(if ($null -ne $TestSummary) { $TestSummary.instrumentationStarted } else { $null })
        executedTests = $(if ($null -ne $TestSummary) { $TestSummary.executedTests } else { $null })
        skippedTests = $(if ($null -ne $TestSummary) { $TestSummary.skippedTests } else { $null })
        failureCount = $(if ($null -ne $TestSummary) { $TestSummary.failureCount } else { $null })
        errorCount = $(if ($null -ne $TestSummary) { $TestSummary.errorCount } else { $null })
        outOfScopeTestCaseCount = $(if ($null -ne $TestSummary) { $TestSummary.outOfScopeTestCaseCount } else { 0 })
        testCountBasis = $(if ($null -ne $TestSummary) { $TestSummary.countBasis } else { 'not_a_test_gate' })
        infrastructureEvidenceStatus = $InfrastructureStatus
        infrastructureSignals = @($InfrastructureSignals)
        semanticCauseClassification = 'not_performed'
        deviceHealth = $DeviceHealth
        phaseDurationsSeconds = $PhaseMap
        logPaths = @($LogPaths)
        failureEvidencePath = $FailureEvidencePath
        error = $ErrorText
    }
}

function New-ExactCandidateExecutionTree {
    param(
        [Parameter(Mandatory)][string]$SourceRepoPath,
        [Parameter(Mandatory)][string]$CandidateSha,
        [Parameter(Mandatory)][string]$CandidateTree,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$LogDirectory
    )
    $materializationRunId = Split-Path -Leaf $EvidenceDirectory
    $materializationRoot = [System.IO.Path]::GetFullPath((Join-Path $SourceRepoPath 'build\remediation-worktrees'))
    $executionPath = [System.IO.Path]::GetFullPath((Join-Path $materializationRoot $materializationRunId))
    if (Test-Path -LiteralPath $executionPath) {
        throw "Exact candidate materialization path already exists: $executionPath"
    }
    New-Item -ItemType Directory -Path $materializationRoot -Force | Out-Null
    $created = Invoke-RemediationGit -RepoPath $SourceRepoPath -ArgumentList @('worktree', 'add', '--detach', $executionPath, $CandidateSha) -LogDirectory $LogDirectory -Name 'git-create-exact-candidate-worktree' -TimeoutSeconds 300
    if ($created.timedOut -or $created.exitCode -ne 0) {
        throw "Unable to create the exact candidate execution worktree (exit $($created.exitCode)); the source worktree was left unchanged."
    }
    $materializedHead = Get-RemediationHead -RepoPath $executionPath -LogDirectory $LogDirectory
    $materializedTree = Get-RemediationTreeSha -RepoPath $executionPath -CommitSha $materializedHead -LogDirectory $LogDirectory
    $materializedState = Get-RemediationTrackedTreeState -RepoPath $executionPath -CandidateSha $CandidateSha -LogDirectory $LogDirectory
    $identityPass = ($materializedHead -eq $CandidateSha -and $materializedTree -eq $CandidateTree -and $materializedState.clean)
    $record = [pscustomobject][ordered]@{
        contract = 'exact_candidate_execution_lifetime_v1'
        mechanism = 'git_detached_candidate_worktree'
        lifecycle = 'retained_in_ignored_run_scoped_worktree_no_cleanup'
        materializationRunId = $materializationRunId
        sourceRepositoryPath = [System.IO.Path]::GetFullPath($SourceRepoPath)
        materializationPath = $executionPath
        materializationCreation = [pscustomobject][ordered]@{
            command = $created.command
            exitCode = [int]$created.exitCode
            timedOut = [bool]$created.timedOut
            startedUtc = $created.startUtc
            endedUtc = $created.endUtc
            stdoutPath = $created.stdoutPath
            stderrPath = $created.stderrPath
        }
        candidateSha = $CandidateSha
        candidateTree = $CandidateTree
        materializedHead = $materializedHead
        materializedTree = $materializedTree
        stateBeforeGates = $materializedState
        identityPass = [bool]$identityPass
        sourceWorktreeUsedForGateExecution = $false
        allowedWritableOutputs = 'Git-ignored outputs within the materialization; wrapper evidence remains outside it.'
        localProperties = 'Not inspected, copied, or serialized by the wrapper; ignored source-worktree file remains outside the candidate tree.'
        createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    }
    if (-not $identityPass) {
        Write-RemediationJson -Path (Join-Path $EvidenceDirectory 'execution-lifetime.json') -Value $record
        throw 'Exact candidate materialization failed HEAD/tree/clean-state verification; no verification gate was started.'
    }
    return $record
}

$repoFull = (Resolve-Path -LiteralPath $RepoPath).Path
if (($ConnectedTestClass.Count + $JvmTestClass.Count + $CompileTask.Count + [int][bool]$RunDiffCheck) -eq 0) {
    throw 'No caller-supplied verification scope was provided.'
}
if ($ExpectedSha -notmatch '^[0-9a-fA-F]{40,64}$') {
    throw 'ExpectedSha must be a full hexadecimal Git object id.'
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedParentSha) -and $ExpectedParentSha -notmatch '^[0-9a-fA-F]{40,64}$') {
    throw 'ExpectedParentSha must be a full hexadecimal Git object id when supplied.'
}
Assert-RemediationClassScope -Classes $ConnectedTestClass -Label 'ConnectedTestClass'
Assert-RemediationClassScope -Classes $JvmTestClass -Label 'JvmTestClass'
if ($ConnectedTestClass.Count -gt 0 -and ([string]::IsNullOrWhiteSpace($AdbPath) -or [string]::IsNullOrWhiteSpace($DeviceSerial))) {
    throw 'Connected gates require explicit AdbPath and DeviceSerial values.'
}
if ((-not [string]::IsNullOrWhiteSpace($PriorInfrastructureEvidence)) -xor (-not [string]::IsNullOrWhiteSpace($RecoveryAuthorization))) {
    throw 'Recovery retry requires both PriorInfrastructureEvidence and a non-empty RecoveryAuthorization note.'
}
if (-not [string]::IsNullOrWhiteSpace($PriorInfrastructureEvidence) -and $ConnectedTestClass.Count -ne 1) {
    throw 'A recovery retry must supply exactly the prior connected class and no additional connected partitions.'
}
foreach ($task in $CompileTask) {
    if ($task -notmatch '^:?[A-Za-z0-9_:]+$' -or $task -notmatch '(^|:)compile[A-Za-z0-9]*$') {
        throw "CompileTask is outside the compile-task contract: $task"
    }
}
$canonicalGradlePath = Join-Path $repoFull 'gradlew.bat'
if ([string]::IsNullOrWhiteSpace($GradlePath)) {
    if (-not (Test-Path -LiteralPath $canonicalGradlePath -PathType Leaf)) {
        throw "Repository-local Gradle wrapper does not exist: $canonicalGradlePath"
    }
    $canonicalGradlePath = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $canonicalGradlePath).Path)
    $GradlePath = $canonicalGradlePath
} elseif ($ToolingDemoMode) {
    if (-not (Test-Path -LiteralPath $GradlePath -PathType Leaf)) {
        throw "Gradle wrapper does not exist: $GradlePath"
    }
    $GradlePath = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $GradlePath).Path)
} else {
    if (-not (Test-Path -LiteralPath $canonicalGradlePath -PathType Leaf)) {
        throw "Repository-local Gradle wrapper does not exist: $canonicalGradlePath"
    }
    $canonicalGradlePath = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $canonicalGradlePath).Path)
    if (-not (Test-Path -LiteralPath $GradlePath -PathType Leaf)) {
        throw "Normal exact-source mode accepts only the repository-local gradlew.bat; supplied path does not exist: $GradlePath"
    }
    $suppliedGradlePath = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $GradlePath).Path)
    if (-not [string]::Equals($suppliedGradlePath, $canonicalGradlePath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Normal exact-source mode accepts only the repository-local gradlew.bat: $canonicalGradlePath"
    }
    $GradlePath = $canonicalGradlePath
}
if ($ToolingDemoMode -and [string]::IsNullOrWhiteSpace($DemoResultRoot)) {
    throw 'ToolingDemoMode requires a relative DemoResultRoot under the invocation evidence directory.'
}

$runDirectoryInfo = New-RemediationRunDirectory -RepoPath $repoFull -CandidateSha $ExpectedSha -EvidenceRoot $EvidenceRoot
$runDirectory = $runDirectoryInfo.evidenceDirectory
$logDirectory = Join-Path $runDirectory 'logs'
$verificationStarted = Get-RemediationUtcNow
$head = Get-RemediationHead -RepoPath $repoFull -LogDirectory $runDirectory
if ($head -ne $ExpectedSha) {
    throw "Expected candidate $ExpectedSha, but HEAD is $head."
}
$treeSha = Get-RemediationTreeSha -RepoPath $repoFull -CommitSha $head -LogDirectory $runDirectory
$treeState = Get-RemediationTrackedTreeState -RepoPath $repoFull -CandidateSha $head -LogDirectory $runDirectory
Write-RemediationJson -Path (Join-Path $runDirectory 'worktree-state.json') -Value $treeState
if (-not $treeState.clean) {
    $untrackedSummary = @($treeState.untrackedStatus) -join '; '
    throw "Verification refuses tracked changes or non-ignored untracked inputs. Tracked status: $($treeState.trackedStatus); untracked: $untrackedSummary"
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedParentSha)) {
    $parent = (Get-RemediationGitText -RepoPath $repoFull -ArgumentList @('rev-parse', ($head + '^')) -LogDirectory $runDirectory -Name 'git-parent').stdoutSample.Trim()
    if ($parent -ne $ExpectedParentSha) {
        throw "Expected parent $ExpectedParentSha, but HEAD parent is $parent."
    }
}
$executionRepoFull = $repoFull
$executionGradlePath = $GradlePath
$executionLifetime = $null
if (-not $ToolingDemoMode) {
    $executionLifetime = New-ExactCandidateExecutionTree -SourceRepoPath $repoFull -CandidateSha $head -CandidateTree $treeSha -EvidenceDirectory $runDirectory -LogDirectory $logDirectory
    $executionRepoFull = $executionLifetime.materializationPath
    $executionGradlePath = Join-Path $executionRepoFull 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $executionGradlePath -PathType Leaf)) {
        throw 'The exact candidate materialization does not contain its repository-local gradlew.bat; no verification gate was started.'
    }
    $executionGradlePath = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $executionGradlePath).Path)
    $executionLifetime | Add-Member -NotePropertyName requestedCanonicalLauncherPath -NotePropertyValue $GradlePath
    $executionLifetime | Add-Member -NotePropertyName executedCanonicalLauncherPath -NotePropertyValue $executionGradlePath
    $executionLifetime | Add-Member -NotePropertyName launcherPolicy -NotePropertyValue 'normal_mode_repository_local_gradlew_bat_from_exact_candidate_materialization'
    Write-RemediationJson -Path (Join-Path $runDirectory 'execution-lifetime.json') -Value $executionLifetime
} else {
    $executionLifetime = [pscustomobject][ordered]@{
        mechanism = 'not_applicable_tooling_demo'
        lifecycle = 'demo_only'
        candidateSha = $head
        candidateTree = $treeSha
        sourceWorktreeUsedForGateExecution = $true
        launcherPolicy = 'caller_supplied_demo_launcher_only'
    }
}
if ($ToolingDemoMode) {
    if ([System.IO.Path]::IsPathRooted($DemoResultRoot) -or $DemoResultRoot -match '(^|[\\/])\.\.([\\/]|$)') {
        throw 'DemoResultRoot must be a relative path without parent traversal.'
    }
    $demoFull = [System.IO.Path]::GetFullPath((Join-Path $runDirectory $DemoResultRoot))
    $runFull = [System.IO.Path]::GetFullPath($runDirectory).TrimEnd('\') + '\'
    if (-not $demoFull.StartsWith($runFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'DemoResultRoot must be inside this run evidence directory.'
    }
    New-Item -ItemType Directory -Path $demoFull -Force | Out-Null
}

$priorRecovery = $null
if (-not [string]::IsNullOrWhiteSpace($PriorInfrastructureEvidence)) {
    $priorRecovery = Read-PriorInfrastructureEvidence -Path $PriorInfrastructureEvidence -RepoFull $repoFull -CandidateSha $head -CandidateTree $treeSha -ExpectedClass $ConnectedTestClass[0]
}

$gateSpecs = New-Object System.Collections.Generic.List[object]
foreach ($className in $ConnectedTestClass) {
    $gateSpecs.Add([pscustomobject]@{ kind = 'connected'; requestedClass = $className; task = ':app:connectedDebugAndroidTest'; gateId = 'connected:' + $className })
}
foreach ($className in $JvmTestClass) {
    $gateSpecs.Add([pscustomobject]@{ kind = 'jvm'; requestedClass = $className; task = ':app:testDebugUnitTest'; gateId = 'jvm:' + $className })
}
foreach ($task in $CompileTask) {
    $gateSpecs.Add([pscustomobject]@{ kind = 'compile'; requestedClass = $null; task = $task; gateId = 'compile:' + $task })
}
if ($RunDiffCheck) {
    $gateSpecs.Add([pscustomobject]@{ kind = 'diff'; requestedClass = $null; task = 'git diff --check'; gateId = 'git_diff_check' })
}

$gateResults = New-Object System.Collections.Generic.List[object]
$gateExecutionRecords = New-Object System.Collections.Generic.List[object]
$phaseResults = New-Object System.Collections.Generic.List[object]
$deviceHealthHistory = New-Object System.Collections.Generic.List[object]
$pressureHistory = New-Object System.Collections.Generic.List[object]
$firstInfrastructureFailurePath = $null
$circuitBreakerOpen = $false
$haltAll = $false
$infrastructureFailureSeen = $false
$recoveryHealthPassed = $false
$evidenceKind = $(if ($ToolingDemoMode) { 'tooling_demo' } else { 'exact_source_verification' })
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

foreach ($gate in $gateSpecs) {
    if ($haltAll) { break }
    if ($gate.kind -eq 'connected' -and $circuitBreakerOpen) { continue }
    if ($gate.kind -ne 'connected' -and $infrastructureFailureSeen -and -not $ContinueIndependentAfterInfrastructureFailure) { break }

    $gateStart = Get-RemediationUtcNow
    $deviceHealth = $null
    $gateStartCorrelation = $null
    $gateEndCorrelation = $null
    $preflightFailure = $false
    $gateFailureEvidencePath = $null
    $failurePath = $null
    $testSummary = $null
    $infraSignals = @()
    $infraStatus = 'not_applicable'
    $phaseDurations = @{}
    $watch = [ordered]@{
        lastPulseUtc = $gateStart
        currentPhase = 'gradle_startup_configuration'
        nextDeviceProbeUtc = $gateStart.AddSeconds($DeviceWatchdogIntervalSeconds)
        lastDeviceSample = $null
        hardDeviceFailure = $false
        diagnosticCaptured = $false
        hardFailureDiagnosticCaptured = $false
        samples = (New-Object System.Collections.Generic.List[object])
        pressureSamples = (New-Object System.Collections.Generic.List[object])
        diagnostic = $null
    }

    if ($gate.kind -eq 'connected') {
        $deviceDirectory = Join-Path (Join-Path $runDirectory 'device-health') ([Regex]::Replace($gate.gateId, '[^A-Za-z0-9_.-]', '_'))
        New-Item -ItemType Directory -Path $deviceDirectory -Force | Out-Null
        try {
            $deviceHealth = Get-RemediationDeviceHealth -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $deviceDirectory -ProbeTimeoutSeconds $ProbeTimeoutSeconds -MaxShellLatencySeconds $MaxShellLatencySeconds -MaxPackageManagerLatencySeconds $MaxPackageManagerLatencySeconds
        } catch {
            $deviceHealth = [pscustomobject][ordered]@{ serial = $DeviceSerial; healthy = $false; hardFailure = $true; error = $_.Exception.Message; probes = @() }
        }
        $deviceHealthHistory.Add($deviceHealth)
        Write-RemediationJson -Path (Join-Path $runDirectory 'device-health.json') -Value @($deviceHealthHistory.ToArray())
        if ($deviceHealth.PSObject.Properties.Name -notcontains 'correlationStart') { $deviceHealth | Add-Member -NotePropertyName correlationStart -NotePropertyValue $null }; if ($deviceHealth.PSObject.Properties.Name -notcontains 'correlationEnd') { $deviceHealth | Add-Member -NotePropertyName correlationEnd -NotePropertyValue $null }; if ($deviceHealth.PSObject.Properties.Name -notcontains 'deviceIdentity') { $deviceHealth | Add-Member -NotePropertyName deviceIdentity -NotePropertyValue $null }; if ($null -ne $deviceHealth.correlationStart) {
            $gateStartCorrelation = $deviceHealth.correlationStart
            Write-RemediationJson -Path (Join-Path $runDirectory 'time-correlation-start.json') -Value $gateStartCorrelation
        }
        if (-not $deviceHealth.healthy) {
            $preflightFailure = $true
            $circuitBreakerOpen = $true
            $infrastructureFailureSeen = $true
            $infraStatus = 'bounded_device_health_preflight_failed'
            $preflightDiagnostic = $null
            $preflightDiagnosticError = $null
            $preflightDiagnosticPath = Join-Path $runDirectory 'stall-diagnostics\device-pressure.json'
            try {
                $preflightDiagnostic = Capture-RemediationGuestStallDiagnostics -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -EvidenceDirectory $runDirectory -NamePrefix ([Regex]::Replace($gate.gateId, '[^A-Za-z0-9_.-]', '_') + '-preflight') -WatchSample $deviceHealth -PreviousPressureSamples @() -AdbTimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
            } catch {
                $preflightDiagnosticError = $_.Exception.Message
                $hostSnapshot = Get-RemediationHostSnapshot -AvdName $(if ($null -ne $deviceHealth.deviceIdentity) { [string]$deviceHealth.deviceIdentity.avdName } else { '' })
                $preflightDiagnosticPath = Join-Path $runDirectory 'stall-diagnostics\preflight-host-snapshot.json'
                Write-RemediationJson -Path $preflightDiagnosticPath -Value $hostSnapshot
            }
            $zeroFailure = [pscustomobject][ordered]@{
                schemaVersion = 1
                candidateSha = $head
                candidateTree = $treeSha
                gateId = $gate.gateId
                requestedTestClass = $gate.requestedClass
                eventKind = 'connected_health_preflight_failure'
                zeroTests = $true
                gradleStarted = $false
                deviceHealth = $deviceHealth
                diagnosticOnly = $true
                diagnosticPath = $preflightDiagnosticPath
                diagnosticCaptureError = $preflightDiagnosticError
                createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
                nextConnectedGateAllowedWithoutMaterialRecovery = $false
                semanticCauseClassification = 'not_performed'
            }
            $gateFailureEvidencePath = Join-Path $runDirectory 'infra-failure.json'; $failurePath = $gateFailureEvidencePath
            Write-RemediationJson -Path $gateFailureEvidencePath -Value $zeroFailure
            $firstInfrastructureFailurePath = $gateFailureEvidencePath
            $blockedEnd = Get-RemediationUtcNow
            $gateResults.Add((New-VerificationGateRecord -GateId $gate.gateId -Kind $gate.kind -RequestedClass $gate.requestedClass -RequestedTask $gate.task -Status 'BLOCKED_DEVICE_HEALTH' -Command '' -Arguments @() -CandidateSha $head -CandidateTree $treeSha -StartedUtc (Format-RemediationUtc $gateStart) -StartedKorea (Format-RemediationKoreaTime $gateStart) -EndedUtc (Format-RemediationUtc $blockedEnd) -EndedKorea (Format-RemediationKoreaTime $blockedEnd) -ExitCode -1 -TimedOut $false -TestSummary ([pscustomobject]@{ instrumentationStarted = $false; executedTests = 0; skippedTests = 0; failureCount = 0; errorCount = 0; outOfScopeTestCaseCount = 0; countBasis = 'gradle_not_started_due_health' }) -InfrastructureSignals @('preflight_health_failed') -InfrastructureStatus $infraStatus -PhaseMap (ConvertTo-RemediationPhaseMap $phaseDurations) -LogPaths @() -FailureEvidencePath $gateFailureEvidencePath -DeviceHealth $deviceHealth -ErrorText 'Connected Gradle task was not started because bounded device health checks failed.'))
            if (-not $ContinueIndependentAfterInfrastructureFailure) { $haltAll = $true }
            continue
        }
        if ($null -ne $priorRecovery) {
            $recoveryHealthPassed = $true
        }
        $gateStart = Get-RemediationUtcNow
    }

    $executionStartUtc = $null
    $executionEndUtc = $null
    $executionStarted = $false
    $arguments = @($gate.task)
    if ($gate.kind -eq 'connected') {
        $arguments += ('-Pandroid.testInstrumentationRunnerArguments.class=' + $gate.requestedClass)
    } elseif ($gate.kind -eq 'jvm') {
        $arguments += ('--tests=' + $gate.requestedClass)
    }
    if ($gate.kind -ne 'diff') {
        $arguments += '--console=plain'
        if ($SingleUseDaemon) { $arguments += '--no-daemon' }
    }
    $processResult = $null
    $errorText = $null
    if ($gate.kind -eq 'diff') {
        $executionStartUtc = Get-RemediationUtcNow
        $executionStarted = $true
        $processResult = Invoke-RemediationGit -RepoPath $executionRepoFull -ArgumentList @('diff', '--check') -LogDirectory $logDirectory -Name 'git-diff-check' -TimeoutSeconds 300
        $executionEndUtc = Get-RemediationUtcNow
    } else {
        $pulse = {
            param($runningProcess)
            $now = Get-RemediationUtcNow
            $elapsed = [Math]::Max(0, ($now - $watch.lastPulseUtc).TotalSeconds)
            $phase = $watch.currentPhase
            if ($watch.Contains('stdoutPath')) {
                $phase = Get-RemediationGradlePhase -StdoutPath $watch.stdoutPath -StderrPath $watch.stderrPath
            }
            $phaseDurations[$watch.currentPhase] = [double]($phaseDurations[$watch.currentPhase]) + $elapsed
            $watch.currentPhase = $phase
            $watch.lastPulseUtc = $now
            if ($gate.kind -eq 'connected' -and $now -ge $watch.nextDeviceProbeUtc) {
                $sampleDirectory = Join-Path (Join-Path $runDirectory 'watchdog') ([Regex]::Replace($gate.gateId, '[^A-Za-z0-9_.-]', '_'))
                New-Item -ItemType Directory -Path $sampleDirectory -Force | Out-Null
                try {
                    $sample = Get-RemediationDeviceHealth -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $sampleDirectory -ProbeTimeoutSeconds $ProbeTimeoutSeconds -MaxShellLatencySeconds $MaxShellLatencySeconds -MaxPackageManagerLatencySeconds $MaxPackageManagerLatencySeconds -Quick
                } catch {
                    $sample = [pscustomobject][ordered]@{ serial = $DeviceSerial; healthy = $false; hardFailure = $true; shellLatencySeconds = $null; packageManagerLatencySeconds = $null; probes = @(); deviceIdentity = $deviceHealth.deviceIdentity; error = $_.Exception.Message }
                }
                try {
                    $instrumentationPresence = Get-RemediationInstrumentationPresence -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $sampleDirectory -Name 'watchdog-instrumentation-presence' -TimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
                } catch {
                    $instrumentationPresence = [pscustomobject][ordered]@{ available = $false; observed = $false; observationBasis = 'bounded dumpsys activity instrumentation output'; matchingLines = @(); stdoutPath = $null; stderrPath = $null; timedOut = $false; exitCode = $null; durationSeconds = $null; error = $_.Exception.Message }
                }
                $sampleRecord = [ordered]@{
                    sampledUtc = Format-RemediationUtc $now
                    sampledKorea = Format-RemediationKoreaTime $now
                    hardFailure = [bool]$sample.hardFailure
                    healthy = [bool]$sample.healthy
                    shellLatencySeconds = $sample.shellLatencySeconds
                    packageManagerLatencySeconds = $sample.packageManagerLatencySeconds
                    probes = $sample.probes
                    instrumentationProcessPresence = $instrumentationPresence
                    deviceIdentity = $deviceHealth.deviceIdentity
                    phase = $phase
                }
                $watch.lastDeviceSample = $sample
                $pressure = Get-RemediationPressureSample -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $sampleDirectory -NamePrefix 'watchdog' -TimeoutSeconds ([Math]::Min(5, $ProbeTimeoutSeconds))
                $watch.pressureSamples.Add([pscustomobject][ordered]@{
                    sampledUtc = Format-RemediationUtc $now
                    sampledKorea = Format-RemediationKoreaTime $now
                    pressure = $pressure
                })
                $deviceHealthHistory.Add($sample); Write-RemediationJson -Path (Join-Path $runDirectory 'device-health.json') -Value @($deviceHealthHistory.ToArray())
                $pressureHistory.Add($watch.pressureSamples[$watch.pressureSamples.Count - 1]); Write-RemediationJson -Path (Join-Path $runDirectory 'device-pressure.json') -Value @($watch.pressureSamples.ToArray())
                $watch.nextDeviceProbeUtc = (Get-RemediationUtcNow).AddSeconds($DeviceWatchdogIntervalSeconds)
                $currentPressureKinds = New-Object System.Collections.Generic.List[string]
                foreach ($pressureKind in $pressure) {
                    if ($pressureKind.available -and @($pressureKind.parsed | Where-Object { $null -ne $_.avg10 -and [double]$_.avg10 -ge 20 }).Count -gt 0) {
                        $currentPressureKinds.Add([string]$pressureKind.kind)
                    }
                }
                $previousPressureKinds = New-Object System.Collections.Generic.List[string]
                $priorSampleCount = [Math]::Max(0, $watch.pressureSamples.Count - 1)
                $priorPressureRecords = @($watch.pressureSamples.ToArray() | Select-Object -First $priorSampleCount)
                foreach ($priorRecord in $priorPressureRecords) {
                    foreach ($pressureKind in @($priorRecord.pressure)) {
                        if ($pressureKind.available -and @($pressureKind.parsed | Where-Object { $null -ne $_.avg10 -and [double]$_.avg10 -ge 20 }).Count -gt 0) {
                            $previousPressureKinds.Add([string]$pressureKind.kind)
                        }
                    }
                }
                $persistentPressureKinds = @($currentPressureKinds.ToArray() | Where-Object { $previousPressureKinds.Contains($_) } | Select-Object -Unique)
                $latencyWarning = (($null -ne $sample.shellLatencySeconds -and [double]$sample.shellLatencySeconds -ge 2) -or ($null -ne $sample.packageManagerLatencySeconds -and [double]$sample.packageManagerLatencySeconds -ge 3))
                $diagnosticTrigger = [ordered]@{
                    boundedHealthFailure = [bool]$sample.hardFailure
                    persistentPressureKinds = $persistentPressureKinds
                    elevatedProbeLatency = [bool]$latencyWarning
                }
                if ($sample.hardFailure -or $persistentPressureKinds.Count -gt 0 -or $latencyWarning) {
                    if ($sample.hardFailure) { $watch.hardDeviceFailure = $true }
                    $captureForNewHardFailure = ($sample.hardFailure -and -not $watch.hardFailureDiagnosticCaptured)
                    if (-not $watch.diagnosticCaptured -or $captureForNewHardFailure) {
                        $watch.diagnosticCaptured = $true
                        if ($sample.hardFailure) { $watch.hardFailureDiagnosticCaptured = $true }
                        try {
                            $watch.diagnostic = Capture-RemediationGuestStallDiagnostics -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -EvidenceDirectory $runDirectory -NamePrefix ([Regex]::Replace($gate.gateId, '[^A-Za-z0-9_.-]', '_') + $(if ($sample.hardFailure) { '-health-failure' } else { '-stall-signal' })) -WatchSample $sample -PreviousPressureSamples $priorPressureRecords -AdbTimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
                        } catch {
                            $watch.diagnosticError = $_.Exception.Message
                        }
                    }
                }
                $sampleRecord.diagnosticTrigger = $diagnosticTrigger
                $watch.samples.Add([pscustomobject]$sampleRecord)
            }
        }.GetNewClosure()
        $startHook = {
            param($startedProcess)
            $watch.stdoutPath = $startedProcess.stdoutPath
            $watch.stderrPath = $startedProcess.stderrPath
        }.GetNewClosure()
        try {
            $watch.lastPulseUtc = $gateStart
            $demoEnvironmentName = 'YTDLNISX_REMEDIATION_DEMO_RESULT_ROOT'
            $hadPreviousDemoEnvironment = Test-Path -LiteralPath ('Env:' + $demoEnvironmentName)
            $previousDemoEnvironment = [Environment]::GetEnvironmentVariable($demoEnvironmentName, 'Process')
            try {
                if ($ToolingDemoMode) { [Environment]::SetEnvironmentVariable($demoEnvironmentName, $demoFull, 'Process') }
                $executionStartUtc = Get-RemediationUtcNow
                $executionStarted = $true
                $processResult = Invoke-RemediationProcess -FilePath $executionGradlePath -ArgumentList $arguments -WorkingDirectory $executionRepoFull -LogDirectory $logDirectory -Name ([Regex]::Replace($gate.gateId, '[^A-Za-z0-9_.-]', '_')) -TimeoutSeconds $GateTimeoutSeconds -PollIntervalSeconds 1 -OnStart $startHook -OnPulse $pulse -KillProcessTreeOnTimeout
                $executionEndUtc = Get-RemediationUtcNow
            } finally {
                if ($ToolingDemoMode) {
                    if ($hadPreviousDemoEnvironment) { [Environment]::SetEnvironmentVariable($demoEnvironmentName, $previousDemoEnvironment, 'Process') }
                    else { [Environment]::SetEnvironmentVariable($demoEnvironmentName, $null, 'Process') }
                }
            }
            $now = Get-RemediationUtcNow
            $phase = Get-RemediationGradlePhase -StdoutPath $processResult.stdoutPath -StderrPath $processResult.stderrPath
            $phaseDurations[$watch.currentPhase] = [double]($phaseDurations[$watch.currentPhase]) + [Math]::Max(0, ($now - $watch.lastPulseUtc).TotalSeconds)
            $watch.currentPhase = $phase
            if ($gate.kind -eq 'connected') {
                $endHealthDirectory = Join-Path (Join-Path $runDirectory 'watchdog') 'gate-end'
                New-Item -ItemType Directory -Path $endHealthDirectory -Force | Out-Null
                try {
                    $endHealth = Get-RemediationDeviceHealth -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $endHealthDirectory -ProbeTimeoutSeconds $ProbeTimeoutSeconds -MaxShellLatencySeconds $MaxShellLatencySeconds -MaxPackageManagerLatencySeconds $MaxPackageManagerLatencySeconds -Quick
                } catch {
                    $endHealth = [pscustomobject][ordered]@{ serial = $DeviceSerial; healthy = $false; hardFailure = $true; shellLatencySeconds = $null; packageManagerLatencySeconds = $null; probes = @(); deviceIdentity = $deviceHealth.deviceIdentity; error = $_.Exception.Message }
                }
                $deviceHealthHistory.Add($endHealth)
                $endSampleUtc = Get-RemediationUtcNow
                $endSampleRecord = [pscustomobject][ordered]@{
                    sampledUtc = Format-RemediationUtc $endSampleUtc
                    sampledKorea = Format-RemediationKoreaTime $endSampleUtc
                    hardFailure = [bool]$endHealth.hardFailure
                    healthy = [bool]$endHealth.healthy
                    shellLatencySeconds = $endHealth.shellLatencySeconds
                    packageManagerLatencySeconds = $endHealth.packageManagerLatencySeconds
                    probes = $endHealth.probes
                    phase = $phase
                    boundary = 'gate_end'
                }
                $watch.samples.Add($endSampleRecord)
                Write-RemediationJson -Path (Join-Path $runDirectory 'device-health.json') -Value @($deviceHealthHistory.ToArray())
                Write-RemediationJson -Path (Join-Path $runDirectory 'device-health-watchdog.json') -Value @($watch.samples.ToArray())
                if ($endHealth.hardFailure) {
                    $watch.hardDeviceFailure = $true
                    if (-not $watch.diagnosticCaptured) {
                        $watch.diagnosticCaptured = $true
                        $watch.diagnostic = Capture-RemediationGuestStallDiagnostics -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -EvidenceDirectory $runDirectory -NamePrefix 'gate-end' -WatchSample $endHealth -PreviousPressureSamples @($watch.pressureSamples.ToArray()) -AdbTimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
                    }
                }
                $gateEndCorrelation = Get-RemediationTimeCorrelationSample -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory (Join-Path $runDirectory 'time-correlation') -NamePrefix 'gate-end' -TimeoutSeconds ([Math]::Min(5, $ProbeTimeoutSeconds))
                Write-RemediationJson -Path (Join-Path $runDirectory 'time-correlation-end.json') -Value $gateEndCorrelation
            }
        } catch {
            $errorText = $_.Exception.Message
            if ($executionStarted) { $executionEndUtc = Get-RemediationUtcNow }
            $phaseDurations[$watch.currentPhase] = [double]($phaseDurations[$watch.currentPhase]) + [Math]::Max(0, ((Get-RemediationUtcNow) - $watch.lastPulseUtc).TotalSeconds)
        }
    }

    $ended = Get-RemediationUtcNow
    $logPaths = @()
    if ($null -ne $processResult) {
        $logPaths = @($processResult.stdoutPath, $processResult.stderrPath)
    }
    if ($gate.kind -eq 'connected' -or $gate.kind -eq 'jvm') {
        $roots = @()
        if ($ToolingDemoMode) {
            $roots = @($demoFull)
        } else {
            $roots = @(
                (Join-Path $executionRepoFull 'app\build\test-results'),
                (Join-Path $executionRepoFull 'app\build\outputs\androidTest-results\connected')
            )
        }
        $testSummary = Get-RemediationTestResultSummary -ResultRoots $roots -ExpectedClass $gate.requestedClass -StartedUtc $gateStart -LogPaths $logPaths
    }
    if ($gate.kind -eq 'connected') {
        $infraSignals = @(Get-RemediationInfrastructureSignals -LogPaths $logPaths)
        if ($infraSignals.Count -gt 0) { $infraStatus = 'marker_observed' }
        elseif ($watch.hardDeviceFailure) { $infraStatus = 'bounded_health_probe_failed' }
        else { $infraStatus = 'no_infrastructure_marker_observed' }
    }
    $status = 'PASS'
    $failurePath = $null
    $exitCode = $(if ($null -ne $processResult) { [int]$processResult.exitCode } else { -1 })
    $timedOut = ($null -ne $processResult -and [bool]$processResult.timedOut)
    if ($null -ne $errorText) {
        $status = 'FAILED_TO_START_OR_EXECUTE'
    } elseif ($timedOut) {
        $status = 'TIMED_OUT'
    } elseif ($null -eq $processResult -or $processResult.exitCode -ne 0) {
        $status = 'FAILED_EXIT_CODE'
    } elseif ($null -ne $testSummary) {
        if (-not $testSummary.scopeMatches) { $status = 'FAILED_SCOPE_MISMATCH' }
        elseif ($null -eq $testSummary.executedTests) { $status = 'FAILED_TEST_COUNT_UNVERIFIED' }
        elseif ($testSummary.executedTests -le 0) { $status = 'FAILED_ZERO_TESTS' }
        elseif ($testSummary.failureCount -gt 0 -or $testSummary.errorCount -gt 0) { $status = 'FAILED_TESTS' }
    }

    $zeroTests = ($null -ne $testSummary -and $testSummary.executedTests -eq 0)
    $hasInfrastructureEvidence = ($infraSignals.Count -gt 0 -or $watch.hardDeviceFailure)
    if ($gate.kind -eq 'connected' -and $exitCode -ne 0 -and $zeroTests -and $hasInfrastructureEvidence) {
        $circuitBreakerOpen = $true
        $infrastructureFailureSeen = $true
        $status = 'ZERO_TEST_INFRASTRUCTURE_FAILURE'
        $failurePath = Join-Path $runDirectory 'infra-failure.json'
        $infraEvidence = [pscustomobject][ordered]@{
            schemaVersion = 1
            candidateSha = $head
            candidateTree = $treeSha
            gateId = $gate.gateId
            requestedTestClass = $gate.requestedClass
            eventKind = 'zero_test_infrastructure_failure'
            zeroTests = $true
            instrumentationStarted = [bool]$testSummary.instrumentationStarted
            executedTests = $testSummary.executedTests
            infrastructureSignals = @($infraSignals)
            watchdogHealthFailure = [bool]$watch.hardDeviceFailure
            watchdogSamples = @($watch.samples.ToArray())
            diagnosticPath = $(if ($null -ne $watch.diagnostic) { Join-Path $runDirectory 'stall-diagnostics\device-pressure.json' } else { $null })
            originalStdoutPath = $processResult.stdoutPath
            originalStderrPath = $processResult.stderrPath
            createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
            nextConnectedGateAllowedWithoutMaterialRecovery = $false
            semanticCauseClassification = 'not_performed'
        }
        Write-RemediationJson -Path $failurePath -Value $infraEvidence
        $firstInfrastructureFailurePath = $failurePath
    } elseif ($gate.kind -eq 'connected' -and $status -ne 'PASS' -and $watch.hardDeviceFailure -and $zeroTests) {
        $circuitBreakerOpen = $true
        $infrastructureFailureSeen = $true
        $failurePath = Join-Path $runDirectory 'infra-failure.json'
        $infraEvidence = [pscustomobject][ordered]@{
            schemaVersion = 1
            candidateSha = $head
            candidateTree = $treeSha
            gateId = $gate.gateId
            requestedTestClass = $gate.requestedClass
            eventKind = 'zero_test_infrastructure_failure'
            zeroTests = $true
            executedTests = 0
            infrastructureSignals = @($infraSignals)
            watchdogHealthFailure = $true
            originalStdoutPath = $(if ($null -ne $processResult) { $processResult.stdoutPath } else { $null })
            originalStderrPath = $(if ($null -ne $processResult) { $processResult.stderrPath } else { $null })
            createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
            nextConnectedGateAllowedWithoutMaterialRecovery = $false
            semanticCauseClassification = 'not_performed'
        }
        Write-RemediationJson -Path $failurePath -Value $infraEvidence
        $firstInfrastructureFailurePath = $failurePath
    }
    if ($gate.kind -eq 'connected' -and $status -ne 'PASS' -and $status -ne 'ZERO_TEST_INFRASTRUCTURE_FAILURE') {
        $haltAll = $true
    }
    if ($gate.kind -eq 'jvm' -and $status -ne 'PASS') { $haltAll = $true }
    if ($gate.kind -eq 'compile' -and ($null -eq $processResult -or $processResult.exitCode -ne 0 -or $timedOut)) { $status = 'FAILED_COMPILE_GATE'; $haltAll = $true }
    if ($gate.kind -eq 'diff' -and ($null -eq $processResult -or $processResult.exitCode -ne 0 -or $timedOut)) { $status = 'FAILED_DIFF_CHECK'; $haltAll = $true }

    $afterState = Get-RemediationTrackedTreeState -RepoPath $executionRepoFull -CandidateSha $head -LogDirectory $logDirectory
    if (-not $afterState.clean) {
        $haltAll = $true
        $status = $(if ($ToolingDemoMode) { 'FAILED_TRACKED_TREE_CHANGED' } else { 'FAILED_EXECUTION_TREE_CHANGED' })
        $errorText = 'The gate execution tree changed tracked or non-ignored inputs; no cleanup was attempted.'
    }
    $executionGateRecord = [pscustomobject][ordered]@{
        gateId = $gate.gateId
        candidateSha = $head
        candidateTree = $treeSha
        mechanism = $executionLifetime.mechanism
        status = $(if (-not $executionStarted) { 'not_started' } elseif ($status -eq 'PASS') { 'PASS' } else { 'FAIL' })
        provenancePass = [bool](-not $ToolingDemoMode -and $executionStarted -and $status -eq 'PASS' -and $executionLifetime.identityPass -and $afterState.clean)
        materializationPath = $(if ($ToolingDemoMode) { $null } else { $executionRepoFull })
        workingDirectory = $(if ($executionStarted) { $executionRepoFull } else { $null })
        launcherPath = $(if ($executionStarted -and $gate.kind -ne 'diff') { $executionGradlePath } else { $null })
        launcherPolicy = $executionLifetime.launcherPolicy
        sourceWorktreeUsedForGateExecution = [bool]$ToolingDemoMode
        startedUtc = $(if ($null -ne $executionStartUtc) { Format-RemediationUtc $executionStartUtc } else { $null })
        endedUtc = $(if ($null -ne $executionEndUtc) { Format-RemediationUtc $executionEndUtc } else { $null })
        materializationStateAfterGate = $afterState
    }
    $gateExecutionRecords.Add($executionGateRecord)
    $gateRecord = New-VerificationGateRecord -GateId $gate.gateId -Kind $gate.kind -RequestedClass $gate.requestedClass -RequestedTask $gate.task -Status $status -Command $(if ($null -ne $processResult) { $processResult.command } else { '' }) -Arguments $arguments -CandidateSha $head -CandidateTree $treeSha -StartedUtc (Format-RemediationUtc $gateStart) -StartedKorea (Format-RemediationKoreaTime $gateStart) -EndedUtc (Format-RemediationUtc $ended) -EndedKorea (Format-RemediationKoreaTime $ended) -ExitCode $exitCode -TimedOut $timedOut -TestSummary $testSummary -InfrastructureSignals $infraSignals -InfrastructureStatus $infraStatus -PhaseMap (ConvertTo-RemediationPhaseMap $phaseDurations) -LogPaths $logPaths -FailureEvidencePath $failurePath -DeviceHealth $deviceHealth -ErrorText $errorText
    $gateRecord | Add-Member -NotePropertyName executionLifetime -NotePropertyValue $executionGateRecord
    $gateResults.Add($gateRecord)
    $phaseResults.Add([pscustomobject][ordered]@{
        gateId = $gate.gateId
        startedUtc = Format-RemediationUtc $gateStart
        startedKorea = Format-RemediationKoreaTime $gateStart
        endedUtc = Format-RemediationUtc $ended
        endedKorea = Format-RemediationKoreaTime $ended
        totalSeconds = [Math]::Round(($ended - $gateStart).TotalSeconds, 3)
        phaseDurationsSeconds = $gateRecord.phaseDurationsSeconds
        phaseAttribution = 'poll-based estimate from Gradle task/install/instrumentation/result markers'
        instrumentationStarted = $gateRecord.instrumentationStarted
        executedTests = $gateRecord.executedTests
        failureCount = $gateRecord.failureCount
        infrastructureEvidenceStatus = $gateRecord.infrastructureEvidenceStatus
        stdoutPath = $(if ($null -ne $processResult) { $processResult.stdoutPath } else { $null })
        stderrPath = $(if ($null -ne $processResult) { $processResult.stderrPath } else { $null })
    })
    if ($status -ne 'PASS' -and $gate.kind -ne 'connected') { $haltAll = $true }
}

$passed = (@($gateResults | Where-Object { $_.status -ne 'PASS' }).Count -eq 0 -and $gateResults.Count -eq $gateSpecs.Count)
if (-not $ToolingDemoMode) {
    $materializationFinalState = Get-RemediationTrackedTreeState -RepoPath $executionRepoFull -CandidateSha $head -LogDirectory $logDirectory
    $executionLifetime | Add-Member -NotePropertyName materializationStateAfterAllGates -NotePropertyValue $materializationFinalState
    $executionLifetime | Add-Member -NotePropertyName gates -NotePropertyValue @($gateExecutionRecords.ToArray())
    $executionLifetime | Add-Member -NotePropertyName startedUtc -NotePropertyValue (Format-RemediationUtc $verificationStarted)
    $executionLifetime | Add-Member -NotePropertyName endedUtc -NotePropertyValue (Format-RemediationUtc (Get-RemediationUtcNow))
    $lifetimePass = ($executionLifetime.identityPass -and $materializationFinalState.clean -and $gateExecutionRecords.Count -eq $gateSpecs.Count -and @($gateExecutionRecords | Where-Object { -not $_.provenancePass }).Count -eq 0)
    $executionLifetime | Add-Member -NotePropertyName status -NotePropertyValue $(if ($lifetimePass) { 'PASS' } else { 'FAIL' })
    if (-not $lifetimePass) { $passed = $false }
} else {
    $executionLifetime | Add-Member -NotePropertyName gates -NotePropertyValue @($gateExecutionRecords.ToArray())
    $executionLifetime | Add-Member -NotePropertyName status -NotePropertyValue 'NOT_APPLICABLE'
}
$overall = $(if ($passed) { 'PASS' } elseif ($circuitBreakerOpen) { 'BLOCKED_BY_INFRASTRUCTURE_CIRCUIT_BREAKER' } else { 'FAIL_OR_INCOMPLETE' })
$verificationEnded = Get-RemediationUtcNow
$verification = [pscustomobject][ordered]@{
    schemaVersion = 1
    evidenceKind = $evidenceKind
    runId = $runDirectoryInfo.runId
    status = $overall
    startedUtc = Format-RemediationUtc $verificationStarted
    startedKorea = Format-RemediationKoreaTime $verificationStarted
    endedUtc = Format-RemediationUtc $verificationEnded
    endedKorea = Format-RemediationKoreaTime $verificationEnded
    candidateSha = $head
    candidateTree = $treeSha
    trackedTreeCleanBefore = $treeState.clean
    executionLifetime = $executionLifetime
    expectedParentSha = $ExpectedParentSha
    scope = [pscustomobject][ordered]@{
        connectedTestClasses = @($ConnectedTestClass)
        jvmTestClasses = @($JvmTestClass)
        compileTasks = @($CompileTask)
        diffCheck = [bool]$RunDiffCheck
        gateOrder = @($gateSpecs | ForEach-Object { $_.gateId })
        scopeSource = 'explicit command parameters only'
    }
    daemonPolicy = $(if ($SingleUseDaemon) { 'single_use_no_daemon_explicit' } else { 'normal_daemon_reuse_default' })
    recovery = [pscustomobject]@{
        priorInfrastructureEvidence = $(if ($null -ne $priorRecovery) { $priorRecovery.path } else { $null })
        callerAttestedMaterialRecovery = $(if ($null -ne $priorRecovery) { $RecoveryAuthorization } else { $null })
        currentHealthPassed = $recoveryHealthPassed
    }
    gates = @($gateResults.ToArray())
    circuitBreaker = [pscustomobject]@{
        open = $circuitBreakerOpen
        firstInfrastructureFailurePath = $firstInfrastructureFailurePath
        remainingConnectedPartitionsRun = $false
        independentChecksAuthorizedByCaller = [bool]$ContinueIndependentAfterInfrastructureFailure
        semanticClassificationPerformed = $false
    }
    deviceHealthHistory = @($deviceHealthHistory.ToArray())
    phaseTimings = @($phaseResults.ToArray())
    evidenceDirectory = $runDirectory
    scopeWidening = $false
    semanticVerdict = 'not_provided_by_verification_tool'
    cleanVerdict = 'not_provided_by_verification_tool'
}
Write-RemediationJson -Path (Join-Path $runDirectory 'execution-lifetime.json') -Value $executionLifetime
Write-RemediationJson -Path (Join-Path $runDirectory 'verification.json') -Value $verification
Write-RemediationJson -Path (Join-Path $runDirectory 'timings.json') -Value @($phaseResults.ToArray())
if ($null -ne $firstInfrastructureFailurePath) {
    Write-Output ('INFRA_FAILURE_JSON=' + $firstInfrastructureFailurePath)
}
Write-Output ('VERIFICATION_STATUS=' + $overall)
Write-Output ('VERIFICATION_JSON=' + (Join-Path $runDirectory 'verification.json'))
Write-Output ('EVIDENCE_DIRECTORY=' + $runDirectory)
if (-not $passed) { exit 1 }

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

function Get-VerificationArtifactToken {
    param([Parameter(Mandatory)][string]$GateId)
    $readablePrefix = [Regex]::Replace($GateId, '[^A-Za-z0-9_.-]', '_')
    if ($readablePrefix.Length -gt 8) { $readablePrefix = $readablePrefix.Substring(0, 8) }
    if ([string]::IsNullOrWhiteSpace($readablePrefix)) { $readablePrefix = 'gate' }
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digestBytes = $hasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($GateId))
    } finally {
        $hasher.Dispose()
    }
    $digest = ([System.BitConverter]::ToString($digestBytes)).Replace('-', '').ToLowerInvariant().Substring(0, 32)
    return $readablePrefix + '-' + $digest
}

$script:demoFinalizationSerializationFailureInjected = $false

function Write-VerificationFinalizationJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    $requestedFailureArtifact = [Environment]::GetEnvironmentVariable('YTDLNISX_REMEDIATION_DEMO_FINALIZATION_SERIALIZATION_FAIL_ONCE')
    $artifactName = [System.IO.Path]::GetFileName($Path)
    if ($ToolingDemoMode -and -not $script:demoFinalizationSerializationFailureInjected -and
        $requestedFailureArtifact -ceq $artifactName -and
        $artifactName -in @('verification.json', 'execution-lifetime.json', 'timings.json')) {
        $script:demoFinalizationSerializationFailureInjected = $true
        throw "Injected ToolingDemoMode report serialization failure for $artifactName."
    }
    Write-RemediationJson -Path $Path -Value $Value
}

function Get-VerificationEvidencePathBudget {
    param(
        [Parameter(Mandatory)][string]$RepositoryFullPath,
        [Parameter(Mandatory)][string]$CandidateSha,
        [string]$EvidenceRoot
    )
    $evidenceBase = if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
        Join-Path $RepositoryFullPath ('build\remediation-agent\' + $CandidateSha)
    } else {
        [System.IO.Path]::GetFullPath($EvidenceRoot)
    }
    $evidenceBaseFull = [System.IO.Path]::GetFullPath($evidenceBase).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    $token = 'x' * 41
    $stamp = 'x' * 10
    $processLogSuffix = '-' + $stamp + '.stdout.log'
    $relativePathTemplates = @(
        ('g\' + $token + $processLogSuffix),
        ('g\git-local-properties-ignore-before-bootstrap' + $processLogSuffix),
        ('h\time-start-device-epoch' + $processLogSuffix),
        ('w\watchdog-instrumentation-presence' + $processLogSuffix),
        ('t\' + $token + '.s.tmp-' + ('x' * 32)),
        ('t\' + $token + '\s-device-epoch' + $processLogSuffix),
        ('t\' + $token + '\e-device-epoch' + $processLogSuffix),
        ('stall-diagnostics\d-capture-pressure-psi-memory' + $processLogSuffix),
        ('stall-diagnostics\d-bounded-logcat' + $processLogSuffix),
        ('stall-diagnostics\d-guest-top' + $processLogSuffix),
        ('stall-diagnostics\d-instrumentation-presence' + $processLogSuffix),
        ('verification.json.tmp-' + ('x' * 32))
    )
    $maximumRelativeSuffixLength = 0
    $maximumRelativeSuffix = ''
    foreach ($template in $relativePathTemplates) {
        $length = ([string]$template).Length
        if ($length -gt $maximumRelativeSuffixLength) {
            $maximumRelativeSuffixLength = $length
            $maximumRelativeSuffix = [string]$template
        }
    }
    $runIdMaximumLength = 28
    $maximumPathLength = 259
    $requiredMaximumPathLength = $evidenceBaseFull.Length + 1 + $runIdMaximumLength + 1 + $maximumRelativeSuffixLength
    $rootComponents = @($evidenceBaseFull.Split([System.IO.Path]::DirectorySeparatorChar) | Where-Object { -not [string]::IsNullOrEmpty($_) })
    $longestRootComponentLength = 0
    foreach ($component in $rootComponents) { $longestRootComponentLength = [Math]::Max($longestRootComponentLength, ([string]$component).Length) }
    return [pscustomobject][ordered]@{
        policy = 'whole-evidence-path-budget-windows-max-path-259-v1'
        evidenceRoot = $evidenceBaseFull
        evidenceRootLength = $evidenceBaseFull.Length
        runIdMaximumLength = $runIdMaximumLength
        maximumRelativeArtifactSuffixLength = $maximumRelativeSuffixLength
        maximumRelativeArtifactSuffix = $maximumRelativeSuffix
        requiredMaximumPathLength = $requiredMaximumPathLength
        maximumSupportedPathLength = $maximumPathLength
        longestEvidenceRootComponentLength = $longestRootComponentLength
        maximumSupportedComponentLength = 255
        accepted = ($requiredMaximumPathLength -le $maximumPathLength -and $longestRootComponentLength -le 255)
    }
}

function Get-VerificationEvidencePathExceptionDetails {
    param([Parameter(Mandatory)][System.Exception]$Exception)
    $exceptionTypes = New-Object System.Collections.Generic.List[string]
    $messages = New-Object System.Collections.Generic.List[string]
    $current = $Exception
    $depth = 0
    while ($null -ne $current -and $depth -lt 16) {
        $exceptionTypes.Add($current.GetType().FullName)
        if (-not [string]::IsNullOrWhiteSpace($current.Message)) { $messages.Add($current.Message) }
        $current = $current.InnerException
        $depth++
    }
    $messageText = $messages -join [Environment]::NewLine
    $pathType = @($exceptionTypes.ToArray() | Where-Object { $_ -in @('System.IO.PathTooLongException', 'System.IO.DirectoryNotFoundException') }).Count -gt 0
    $pathMessage = ($messageText -match '(?i)(could not find a part of the path|path.{0,40}(too long|invalid)|file.?name.{0,40}too long)')
    return [pscustomobject][ordered]@{
        isEvidencePathFailure = [bool]($pathType -or $pathMessage)
        classification = $(if ($pathType -or $pathMessage) { 'tooling_evidence_path_materialization_failure' } else { 'not_evidence_path_failure' })
        exceptionTypes = @($exceptionTypes.ToArray())
        message = $messageText
    }
}

function Get-DetachedLocalPropertiesState {
    param(
        [Parameter(Mandatory)][string]$MaterializationPath,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$Name
    )
    $path = Join-Path $MaterializationPath 'local.properties'
    $exists = Test-Path -LiteralPath $path -PathType Leaf
    $byteCount = $null
    if ($exists) { $byteCount = [long](Get-Item -LiteralPath $path).Length }
    $ignoreResult = Invoke-RemediationGit -RepoPath $MaterializationPath -ArgumentList @('check-ignore', '--quiet', '--', 'local.properties') -LogDirectory $LogDirectory -Name $Name -TimeoutSeconds 30
    return [pscustomobject][ordered]@{
        exists = [bool]$exists
        byteCount = $byteCount
        ignored = [bool](-not $ignoreResult.timedOut -and $ignoreResult.exitCode -eq 0)
        ignoreCommand = $ignoreResult.command
        ignoreExitCode = [int]$ignoreResult.exitCode
        ignoreTimedOut = [bool]$ignoreResult.timedOut
    }
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
    if ($prior.eventKind -notin @('zero_test_infrastructure_failure', 'connected_health_preflight_failure', 'tooling_evidence_path_bootstrap_failure')) {
        throw 'Prior evidence is not an authorized infrastructure recovery event.'
    }
    return [pscustomobject]@{ path = $full; evidence = $prior }
}

function New-VerificationGateRecord {
    param(
        [string]$GateId,
        [string]$ArtifactToken,
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
        artifactToken = $ArtifactToken
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
    $preBootstrapIdentityPass = ($materializedHead -eq $CandidateSha -and $materializedTree -eq $CandidateTree -and $materializedState.clean)
    $sourcePolicy = [pscustomobject][ordered]@{
        policy = 'source_local_properties_never_observed_or_used_v1'
        inspected = $false
        read = $false
        copied = $false
        serialized = $false
        hashed = $false
        parsed = $false
        compared = $false
        derived = $false
    }
    $detachedPropertiesPath = Join-Path $executionPath 'local.properties'
    $detachedPropertiesInitiallyPresent = Test-Path -LiteralPath $detachedPropertiesPath -PathType Leaf
    $initialIgnoreProof = $null
    $bootstrapCreated = $false
    $bootstrapError = $null
    $initialPropertiesState = $null
    if ($preBootstrapIdentityPass) {
        $initialPropertiesState = Get-DetachedLocalPropertiesState -MaterializationPath $executionPath -LogDirectory $LogDirectory -Name 'git-local-properties-ignore-before-bootstrap'
        $initialIgnoreProof = $initialPropertiesState
    }
    if ($preBootstrapIdentityPass -and $initialPropertiesState.ignored -and -not $detachedPropertiesInitiallyPresent) {
        $emptyFileStream = $null
        try {
            $emptyFileStream = [System.IO.File]::Open($detachedPropertiesPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $emptyFileStream.Dispose()
            $emptyFileStream = $null
            $bootstrapCreated = $true
        } catch {
            $bootstrapError = $_.Exception.Message
        } finally {
            if ($null -ne $emptyFileStream) { $emptyFileStream.Dispose() }
        }
    } elseif (-not $preBootstrapIdentityPass) {
        $bootstrapError = 'The detached worktree failed its initial candidate identity/clean-state check.'
    } elseif (-not $initialPropertiesState.ignored) {
        $bootstrapError = 'The detached candidate does not prove local.properties is Git-ignored.'
    } elseif ($detachedPropertiesInitiallyPresent) {
        $bootstrapError = 'The unique detached run worktree already contains local.properties; refusing to inspect or replace it.'
    }
    $propertiesStateAfterBootstrap = Get-DetachedLocalPropertiesState -MaterializationPath $executionPath -LogDirectory $LogDirectory -Name 'git-local-properties-ignore-after-bootstrap'
    $postBootstrapHead = Get-RemediationHead -RepoPath $executionPath -LogDirectory $LogDirectory
    $postBootstrapTree = Get-RemediationTreeSha -RepoPath $executionPath -CommitSha $postBootstrapHead -LogDirectory $LogDirectory
    $postBootstrapState = Get-RemediationTrackedTreeState -RepoPath $executionPath -CandidateSha $CandidateSha -LogDirectory $LogDirectory
    $bootstrapPass = (
        $bootstrapCreated -and
        $propertiesStateAfterBootstrap.exists -and
        $propertiesStateAfterBootstrap.byteCount -eq 0 -and
        $propertiesStateAfterBootstrap.ignored -and
        $postBootstrapHead -eq $CandidateSha -and
        $postBootstrapTree -eq $CandidateTree -and
        $postBootstrapState.clean
    )
    if (-not $bootstrapPass -and [string]::IsNullOrWhiteSpace($bootstrapError)) {
        $bootstrapError = 'The detached local.properties bootstrap did not remain empty, ignored, and isolated from the exact candidate tree.'
    }
    $detachedBootstrap = [pscustomobject][ordered]@{
        policy = 'detached_empty_ignored_local_properties_bootstrap_v1'
        generated = [bool]$bootstrapCreated
        generatedEmpty = [bool]($bootstrapCreated -and $propertiesStateAfterBootstrap.exists -and $propertiesStateAfterBootstrap.byteCount -eq 0)
        byteCount = $propertiesStateAfterBootstrap.byteCount
        ignored = [bool]$propertiesStateAfterBootstrap.ignored
        beforeCreationIgnoreProof = $initialIgnoreProof
        afterCreationState = $propertiesStateAfterBootstrap
        materializationRunId = $materializationRunId
        materializationPath = $executionPath
        sourceValuesUsed = $false
        failure = $bootstrapError
    }
    $identityPass = ($preBootstrapIdentityPass -and $bootstrapPass -and $postBootstrapHead -eq $CandidateSha -and $postBootstrapTree -eq $CandidateTree -and $postBootstrapState.clean)
    $record = [pscustomobject][ordered]@{
        contract = 'exact_candidate_execution_lifetime_v2'
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
        stateAfterBootstrap = $postBootstrapState
        materializedHeadAfterBootstrap = $postBootstrapHead
        materializedTreeAfterBootstrap = $postBootstrapTree
        identityPass = [bool]$identityPass
        sourceWorktreeUsedForGateExecution = $false
        allowedWritableOutputs = 'Git-ignored outputs within the materialization; wrapper evidence remains outside it.'
        sourceLocalProperties = $sourcePolicy
        detachedLocalPropertiesBootstrap = $detachedBootstrap
        createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    }
    if (-not $identityPass) {
        Write-RemediationJson -Path (Join-Path $EvidenceDirectory 'execution-lifetime.json') -Value $record
        throw "Exact candidate materialization failed candidate identity or detached empty local.properties bootstrap verification; no verification gate was started. $bootstrapError"
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
$gateArtifactTokenOwners = @{}
$gateArtifactTokens = New-Object System.Collections.Generic.List[object]
foreach ($gate in $gateSpecs) {
    $artifactToken = Get-VerificationArtifactToken -GateId $gate.gateId
    if ($gateArtifactTokenOwners.ContainsKey($artifactToken) -and $gateArtifactTokenOwners[$artifactToken] -ne $gate.gateId) {
        throw "Distinct semantic gate IDs produced the same bounded artifact token; refusing an ambiguous evidence path: $artifactToken"
    }
    $gateArtifactTokenOwners[$artifactToken] = $gate.gateId
    $gate | Add-Member -NotePropertyName artifactToken -NotePropertyValue $artifactToken -Force
    $gateArtifactTokens.Add([pscustomobject][ordered]@{ gateId = $gate.gateId; artifactToken = $artifactToken })
}

$evidencePathBudget = Get-VerificationEvidencePathBudget -RepositoryFullPath $repoFull -CandidateSha $ExpectedSha -EvidenceRoot $EvidenceRoot
if (-not $evidencePathBudget.accepted) {
    $repoPrefix = [System.IO.Path]::GetFullPath($repoFull).TrimEnd('\') + '\'
    if (-not $evidencePathBudget.evidenceRoot.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence path must remain inside the implementation repository.'
    }
    $requestedRelativeRoot = $evidencePathBudget.evidenceRoot.Substring($repoPrefix.Length).Replace('\', '/')
    $bootstrapLogs = Join-Path $env:TEMP 'ytdlnisx-remediation-bootstrap'
    $ignoreCheck = Invoke-RemediationGit -RepoPath $repoFull -ArgumentList @('check-ignore', '--quiet', '--', $requestedRelativeRoot) -LogDirectory $bootstrapLogs -Name 'evidence-path-budget-ignore-check'
    if ($ignoreCheck.timedOut -or $ignoreCheck.exitCode -ne 0) {
        throw "Evidence path is not ignored by Git: $requestedRelativeRoot"
    }
    $rejectionRoot = Join-Path $repoFull 'build\remediation-agent\path-budget-failures'
    $rejectionRun = New-RemediationRunDirectory -RepoPath $repoFull -CandidateSha $ExpectedSha -EvidenceRoot $rejectionRoot
    $rejectionLogDirectory = Join-Path $rejectionRun.evidenceDirectory 'g'
    $rejectionStarted = Get-RemediationUtcNow
    $observedHead = Get-RemediationHead -RepoPath $repoFull -LogDirectory $rejectionLogDirectory
    $observedTree = Get-RemediationTreeSha -RepoPath $repoFull -CommitSha $observedHead -LogDirectory $rejectionLogDirectory
    $observedTreeState = Get-RemediationTrackedTreeState -RepoPath $repoFull -CandidateSha $observedHead -LogDirectory $rejectionLogDirectory
    $rejectionPath = Join-Path $rejectionRun.evidenceDirectory 'infra-failure.json'
    $pathBudgetFailure = [pscustomobject][ordered]@{
        schemaVersion = 1
        candidateSha = $observedHead
        candidateTree = $observedTree
        expectedCandidateSha = $ExpectedSha
        eventKind = 'tooling_evidence_path_bootstrap_failure'
        failureClassification = 'tooling_evidence_path_budget_rejected'
        bootstrapStage = 'evidence_path_budget_preflight'
        zeroTests = $true
        gradleStarted = $false
        deviceProbeStarted = $false
        probeNotStartedEvidence = 'The computed maximum evidence path exceeded the supported Windows path contract before the requested run directory was created; no ADB/device-health or Gradle process was launched.'
        deviceHealthObserved = $false
        deviceHealthFailureObserved = $false
        instrumentationStarted = $false
        executedTests = 0
        failureCount = 0
        errorCount = 0
        evidencePathBudget = $evidencePathBudget
        scope = [pscustomobject][ordered]@{
            connectedTestClasses = @($ConnectedTestClass)
            jvmTestClasses = @($JvmTestClass)
            compileTasks = @($CompileTask)
            diffCheck = [bool]$RunDiffCheck
            gateOrder = @($gateSpecs | ForEach-Object { $_.gateId })
            gateArtifactTokens = @($gateArtifactTokens.ToArray())
        }
        actualCandidateMatchedRequest = ($observedHead -eq $ExpectedSha)
        trackedTreeCleanBefore = $observedTreeState.clean
        error = "EvidenceRoot requires a maximum path of $($evidencePathBudget.requiredMaximumPathLength) characters; supported maximum is $($evidencePathBudget.maximumSupportedPathLength), with longest root component $($evidencePathBudget.longestEvidenceRootComponentLength) characters."
        createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    }
    Write-RemediationJson -Path $rejectionPath -Value $pathBudgetFailure
    $rejectionEnded = Get-RemediationUtcNow
    $rejectionVerification = [pscustomobject][ordered]@{
        schemaVersion = 1
        evidenceKind = $(if ($ToolingDemoMode) { 'tooling_demo' } else { 'exact_source_verification' })
        runId = $rejectionRun.runId
        status = 'BLOCKED_BY_TOOLING_EVIDENCE_PATH_BUDGET'
        startedUtc = Format-RemediationUtc $rejectionStarted
        startedKorea = Format-RemediationKoreaTime $rejectionStarted
        endedUtc = Format-RemediationUtc $rejectionEnded
        endedKorea = Format-RemediationKoreaTime $rejectionEnded
        candidateSha = $observedHead
        candidateTree = $observedTree
        trackedTreeCleanBefore = $observedTreeState.clean
        expectedCandidateSha = $ExpectedSha
        expectedParentSha = $ExpectedParentSha
        evidencePathBudget = $evidencePathBudget
        scope = [pscustomobject][ordered]@{
            connectedTestClasses = @($ConnectedTestClass)
            jvmTestClasses = @($JvmTestClass)
            compileTasks = @($CompileTask)
            diffCheck = [bool]$RunDiffCheck
            gateOrder = @($gateSpecs | ForEach-Object { $_.gateId })
            artifactTokenPolicy = 'sanitized-prefix-8-plus-lowercase-sha256-128-v1'
            artifactTokenMaximumLength = 41
            gateArtifactTokens = @($gateArtifactTokens.ToArray())
            scopeSource = 'explicit command parameters only'
        }
        gates = @()
        toolingInfrastructureBootstrap = [pscustomobject][ordered]@{
            failed = $true
            failurePath = $rejectionPath
            deviceHealthFailureObserved = $false
            deviceProbeStarted = $false
            gradleStarted = $false
            zeroTests = $true
        }
        evidenceDirectory = $rejectionRun.evidenceDirectory
        scopeWidening = $false
        semanticVerdict = 'not_performed'
        cleanVerdict = 'not_provided_by_verification_tool'
    }
    Write-RemediationJson -Path (Join-Path $rejectionRun.evidenceDirectory 'verification.json') -Value $rejectionVerification
    Write-Output 'VERIFICATION_STATUS=BLOCKED_BY_TOOLING_EVIDENCE_PATH_BUDGET'
    Write-Output ('INFRA_FAILURE_JSON=' + $rejectionPath)
    Write-Output ('VERIFICATION_JSON=' + (Join-Path $rejectionRun.evidenceDirectory 'verification.json'))
    Write-Output ('EVIDENCE_DIRECTORY=' + $rejectionRun.evidenceDirectory)
    exit 1
}

$runDirectoryInfo = New-RemediationRunDirectory -RepoPath $repoFull -CandidateSha $ExpectedSha -EvidenceRoot $EvidenceRoot
$runDirectory = $runDirectoryInfo.evidenceDirectory
$logDirectory = Join-Path $runDirectory 'g'
$deviceLogDirectory = Join-Path $runDirectory 'h'
$watchdogLogDirectory = Join-Path $runDirectory 'w'
$timeCorrelationLogDirectory = Join-Path $runDirectory 't'
$evidencePathBudget | Add-Member -NotePropertyName actualRunDirectoryLength -NotePropertyValue $runDirectory.Length -Force
$evidencePathBudget | Add-Member -NotePropertyName actualMaximumPathLength -NotePropertyValue ($runDirectory.Length + 1 + [int]$evidencePathBudget.maximumRelativeArtifactSuffixLength) -Force
if ($runDirectory.Length + 1 + [int]$evidencePathBudget.maximumRelativeArtifactSuffixLength -gt [int]$evidencePathBudget.maximumSupportedPathLength) {
    throw 'The materialized run directory exceeded the preflight whole-path evidence budget; no verification gate was started.'
}
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

$gateResults = New-Object System.Collections.Generic.List[object]
$gateExecutionRecords = New-Object System.Collections.Generic.List[object]
$phaseResults = New-Object System.Collections.Generic.List[object]
$deviceHealthHistory = New-Object System.Collections.Generic.List[object]
$pressureHistory = New-Object System.Collections.Generic.List[object]
$firstInfrastructureFailurePath = $null
$circuitBreakerOpen = $false
$toolingInfrastructureFailureSeen = $false
$toolingInfrastructureFailurePath = $null
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
    $demoDiagnosticErrorGateId = $null
    if ($ToolingDemoMode) {
        $demoDiagnosticErrorGateId = [Environment]::GetEnvironmentVariable('YTDLNISX_REMEDIATION_DEMO_DIAGNOSTIC_ERROR_GATE_ID')
    }
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
        diagnosticError = $null
    }

    if ($gate.kind -eq 'connected') {
        $deviceDirectory = $deviceLogDirectory
        $gateCorrelationDirectory = Join-Path (Join-Path $runDirectory 't') $gate.artifactToken
        $deviceProbeLogsBefore = @()
        if (Test-Path -LiteralPath $deviceDirectory -PathType Container) {
            $deviceProbeLogsBefore = @(Get-ChildItem -LiteralPath $deviceDirectory -Filter 'adb-devices-*.stdout.log' -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        }
        $deviceHealthCollectionError = $null
        $deviceHealthCollectionStage = 'device_health_directory_materialization'
        $deviceHealthCollectionExceptionType = $null
        $deviceHealthCollectionExceptionDetails = $null
        try {
            New-Item -ItemType Directory -Path $deviceDirectory -Force -ErrorAction Stop | Out-Null
            New-Item -ItemType Directory -Path $gateCorrelationDirectory -Force -ErrorAction Stop | Out-Null
            $deviceHealthCollectionStage = 'adb_device_health_preflight'
            $deviceHealth = Get-RemediationDeviceHealth -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $deviceDirectory -ProbeTimeoutSeconds $ProbeTimeoutSeconds -MaxShellLatencySeconds $MaxShellLatencySeconds -MaxPackageManagerLatencySeconds $MaxPackageManagerLatencySeconds
        } catch {
            $deviceHealthCollectionError = $_.Exception.Message
            $deviceHealthCollectionExceptionType = $_.Exception.GetType().FullName
            $deviceHealthCollectionExceptionDetails = Get-VerificationEvidencePathExceptionDetails -Exception $_.Exception
        }
        if ($null -ne $deviceHealthCollectionError) {
            $deviceProbeLogs = @()
            if (Test-Path -LiteralPath $deviceDirectory -PathType Container) {
                $deviceProbeLogs = @(Get-ChildItem -LiteralPath $deviceDirectory -Filter 'adb-devices-*.stdout.log' -File -ErrorAction SilentlyContinue | Where-Object { $deviceProbeLogsBefore -notcontains $_.FullName } | ForEach-Object { $_.FullName })
            }
            $deviceProbeStarted = $null
            if ($deviceProbeLogs.Count -gt 0) { $deviceProbeStarted = $true }
            elseif ($deviceHealthCollectionStage -eq 'device_health_directory_materialization') { $deviceProbeStarted = $false }
            $artifactPathFailure = ($deviceHealthCollectionStage -eq 'device_health_directory_materialization' -or $deviceHealthCollectionExceptionDetails.isEvidencePathFailure)
            $preProbePathFailure = ($artifactPathFailure -and $deviceProbeStarted -eq $false)
            $bootstrapStage = $(if ($preProbePathFailure) { 'evidence_path_materialization_before_adb_probe' } elseif ($artifactPathFailure) { 'evidence_path_materialization_during_probe_capture' } elseif ($deviceProbeStarted -eq $true) { 'device_health_probe_collection' } else { 'adb_probe_start_or_collection' })
            $bootstrapEventKind = $(if ($artifactPathFailure) { 'tooling_evidence_path_bootstrap_failure' } elseif ($deviceProbeStarted -eq $true) { 'device_health_probe_collection_failure' } else { 'adb_probe_start_failure' })
            $failurePath = Join-Path $runDirectory 'infra-failure.json'
            $bootstrapFailure = [pscustomobject][ordered]@{
                schemaVersion = 1
                candidateSha = $head
                candidateTree = $treeSha
                gateId = $gate.gateId
                artifactToken = $gate.artifactToken
                requestedTestClass = $gate.requestedClass
                eventKind = $bootstrapEventKind
                failureClassification = $(if ($artifactPathFailure) { 'tooling_infrastructure_bootstrap' } elseif ($deviceProbeStarted -eq $true) { 'device_health_probe_collection_error' } else { 'adb_probe_start_failure' })
                bootstrapStage = $bootstrapStage
                failingStage = $deviceHealthCollectionStage
                exceptionType = $deviceHealthCollectionExceptionType
                exceptionTypes = $(if ($null -ne $deviceHealthCollectionExceptionDetails) { @($deviceHealthCollectionExceptionDetails.exceptionTypes) } else { @() })
                pathFailureClassification = $(if ($null -ne $deviceHealthCollectionExceptionDetails) { $deviceHealthCollectionExceptionDetails.classification } else { $null })
                zeroTests = $true
                gradleStarted = $false
                deviceProbeStarted = $deviceProbeStarted
                probeNotStartedEvidence = $(if ($deviceProbeStarted -eq $false) { 'The evidence-path directory could not be materialized before invoking the ADB health probe.' } elseif ($deviceProbeStarted -eq $true) { $null } elseif ($artifactPathFailure) { 'A wrapped evidence FileStream path-materialization failure was classified; the process-start boundary may have been crossed, so probe start is unknown and no device-health verdict is assigned.' } else { 'No adb-devices stdout log was materialized; ADB process start was not independently confirmed.' })
                deviceHealthObserved = $false
                deviceHealthFailureObserved = $false
                deviceProbeLogs = @($deviceProbeLogs)
                evidencePathBudget = $evidencePathBudget
                probes = @()
                error = $deviceHealthCollectionError
                createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
                semanticCauseClassification = 'not_performed'
            }
            Write-RemediationJson -Path $failurePath -Value $bootstrapFailure
            $toolingInfrastructureFailureSeen = $true
            $toolingInfrastructureFailurePath = $failurePath
            $firstInfrastructureFailurePath = $failurePath
            $infrastructureFailureSeen = $true
            $haltAll = $true
            $blockedEnd = Get-RemediationUtcNow
            $bootstrapTestSummary = [pscustomobject]@{ instrumentationStarted = $false; executedTests = 0; skippedTests = 0; failureCount = 0; errorCount = 0; outOfScopeTestCaseCount = 0; countBasis = 'adb_preflight_bootstrap_failure' }
            $gateRecord = New-VerificationGateRecord -GateId $gate.gateId -ArtifactToken $gate.artifactToken -Kind $gate.kind -RequestedClass $gate.requestedClass -RequestedTask $gate.task -Status 'BLOCKED_TOOLING_INFRASTRUCTURE_BOOTSTRAP' -Command '' -Arguments @() -CandidateSha $head -CandidateTree $treeSha -StartedUtc (Format-RemediationUtc $gateStart) -StartedKorea (Format-RemediationKoreaTime $gateStart) -EndedUtc (Format-RemediationUtc $blockedEnd) -EndedKorea (Format-RemediationKoreaTime $blockedEnd) -ExitCode -1 -TimedOut $false -TestSummary $bootstrapTestSummary -InfrastructureSignals @($bootstrapEventKind) -InfrastructureStatus $bootstrapStage -PhaseMap (ConvertTo-RemediationPhaseMap $phaseDurations) -LogPaths @($deviceProbeLogs) -FailureEvidencePath $failurePath -DeviceHealth $null -ErrorText $deviceHealthCollectionError
            $gateExecutionRecord = [pscustomobject][ordered]@{
                gateId = $gate.gateId
                artifactToken = $gate.artifactToken
                candidateSha = $head
                candidateTree = $treeSha
                mechanism = $executionLifetime.mechanism
                status = 'not_started'
                provenancePass = $false
                materializationPath = $(if ($ToolingDemoMode) { $null } else { $executionRepoFull })
                workingDirectory = $null
                launcherPath = $null
                launcherPolicy = $executionLifetime.launcherPolicy
                sourceLocalProperties = $(if ($ToolingDemoMode) { $null } else { $executionLifetime.sourceLocalProperties })
                detachedLocalPropertiesBootstrap = $(if ($ToolingDemoMode) { $null } else { $executionLifetime.detachedLocalPropertiesBootstrap })
                detachedLocalPropertiesBootstrapAfterGate = $null
                detachedLocalPropertiesBootstrapPass = $false
                sourceWorktreeUsedForGateExecution = [bool]$ToolingDemoMode
                startedUtc = $null
                endedUtc = $null
                materializationStateAfterGate = $null
            }
            $gateExecutionRecords.Add($gateExecutionRecord)
            $gateRecord | Add-Member -NotePropertyName executionLifetime -NotePropertyValue $gateExecutionRecord
            $gateResults.Add($gateRecord)
            $phaseResults.Add([pscustomobject][ordered]@{
                gateId = $gate.gateId
                artifactToken = $gate.artifactToken
                startedUtc = Format-RemediationUtc $gateStart
                startedKorea = Format-RemediationKoreaTime $gateStart
                endedUtc = Format-RemediationUtc $blockedEnd
                endedKorea = Format-RemediationKoreaTime $blockedEnd
                totalSeconds = [Math]::Round(($blockedEnd - $gateStart).TotalSeconds, 3)
                phaseDurationsSeconds = $gateRecord.phaseDurationsSeconds
                phaseAttribution = 'device health bootstrap failed before Gradle execution'
                instrumentationStarted = $false
                executedTests = 0
                failureCount = 0
                infrastructureEvidenceStatus = $bootstrapStage
                stdoutPath = $null
                stderrPath = $null
            })
            continue
        }
        $deviceHealth | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
        $deviceHealth | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
        $deviceHealthHistory.Add($deviceHealth)
        Write-RemediationJson -Path (Join-Path $runDirectory 'device-health.json') -Value @($deviceHealthHistory.ToArray())
        if ($deviceHealth.PSObject.Properties.Name -notcontains 'correlationStart') { $deviceHealth | Add-Member -NotePropertyName correlationStart -NotePropertyValue $null }; if ($deviceHealth.PSObject.Properties.Name -notcontains 'correlationEnd') { $deviceHealth | Add-Member -NotePropertyName correlationEnd -NotePropertyValue $null }; if ($deviceHealth.PSObject.Properties.Name -notcontains 'deviceIdentity') { $deviceHealth | Add-Member -NotePropertyName deviceIdentity -NotePropertyValue $null }; if ($null -ne $deviceHealth.correlationStart) {
            $gateStartCorrelation = $deviceHealth.correlationStart
            $gateStartCorrelation | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
            $gateStartCorrelation | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
            $gateStartCorrelation | Add-Member -NotePropertyName boundary -NotePropertyValue 'gate_start' -Force
            $gateStartCorrelationPath = Join-Path (Join-Path $runDirectory 't') ($gate.artifactToken + '.s')
            $gateStartCorrelation | Add-Member -NotePropertyName evidenceJsonPath -NotePropertyValue $gateStartCorrelationPath -Force
            Write-RemediationJson -Path $gateStartCorrelationPath -Value $gateStartCorrelation
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
                $preflightDiagnostic = Capture-RemediationGuestStallDiagnostics -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -EvidenceDirectory $runDirectory -NamePrefix 'p' -WatchSample $deviceHealth -PreviousPressureSamples @() -AdbTimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
                $preflightDiagnostic | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                $preflightDiagnostic | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                $preflightDiagnostic | Add-Member -NotePropertyName capturePhase -NotePropertyValue 'connected_preflight' -Force
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
                artifactToken = $gate.artifactToken
                requestedTestClass = $gate.requestedClass
                eventKind = 'connected_health_preflight_failure'
                zeroTests = $true
                gradleStarted = $false
                deviceHealth = $deviceHealth
                diagnosticOnly = $true
                diagnosticPath = $preflightDiagnosticPath
                diagnosticCapture = $preflightDiagnostic
                diagnosticCaptureError = $preflightDiagnosticError
                createdUtc = Format-RemediationUtc (Get-RemediationUtcNow)
                nextConnectedGateAllowedWithoutMaterialRecovery = $false
                semanticCauseClassification = 'not_performed'
            }
            $gateFailureEvidencePath = Join-Path $runDirectory 'infra-failure.json'; $failurePath = $gateFailureEvidencePath
            Write-RemediationJson -Path $gateFailureEvidencePath -Value $zeroFailure
            $firstInfrastructureFailurePath = $gateFailureEvidencePath
            $blockedEnd = Get-RemediationUtcNow
            $gateResults.Add((New-VerificationGateRecord -GateId $gate.gateId -ArtifactToken $gate.artifactToken -Kind $gate.kind -RequestedClass $gate.requestedClass -RequestedTask $gate.task -Status 'BLOCKED_DEVICE_HEALTH' -Command '' -Arguments @() -CandidateSha $head -CandidateTree $treeSha -StartedUtc (Format-RemediationUtc $gateStart) -StartedKorea (Format-RemediationKoreaTime $gateStart) -EndedUtc (Format-RemediationUtc $blockedEnd) -EndedKorea (Format-RemediationKoreaTime $blockedEnd) -ExitCode -1 -TimedOut $false -TestSummary ([pscustomobject]@{ instrumentationStarted = $false; executedTests = 0; skippedTests = 0; failureCount = 0; errorCount = 0; outOfScopeTestCaseCount = 0; countBasis = 'gradle_not_started_due_health' }) -InfrastructureSignals @('preflight_health_failed') -InfrastructureStatus $infraStatus -PhaseMap (ConvertTo-RemediationPhaseMap $phaseDurations) -LogPaths @() -FailureEvidencePath $gateFailureEvidencePath -DeviceHealth $deviceHealth -ErrorText 'Connected Gradle task was not started because bounded device health checks failed.'))
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
                $sampleDirectory = $watchdogLogDirectory
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
                    gateId = $gate.gateId
                    artifactToken = $gate.artifactToken
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
                $pressure = @(Get-RemediationPressureSample -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $sampleDirectory -NamePrefix 'watchdog' -TimeoutSeconds ([Math]::Min(5, $ProbeTimeoutSeconds)))
                foreach ($pressureRecord in $pressure) {
                    $pressureRecord | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                    $pressureRecord | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                    $pressureRecord | Add-Member -NotePropertyName samplePhase -NotePropertyValue 'watchdog' -Force
                }
                $sample | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                $sample | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                $watch.pressureSamples.Add([pscustomobject][ordered]@{
                    sampledUtc = Format-RemediationUtc $now
                    sampledKorea = Format-RemediationKoreaTime $now
                    gateId = $gate.gateId
                    artifactToken = $gate.artifactToken
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
                    demoDiagnosticErrorRequested = [bool]($ToolingDemoMode -and $demoDiagnosticErrorGateId -ceq $gate.gateId)
                }
                $forceDemoDiagnosticError = ($ToolingDemoMode -and $demoDiagnosticErrorGateId -ceq $gate.gateId -and -not $watch.diagnosticCaptured)
                if ($sample.hardFailure -or $persistentPressureKinds.Count -gt 0 -or $latencyWarning -or $forceDemoDiagnosticError) {
                    if ($sample.hardFailure) { $watch.hardDeviceFailure = $true }
                    $captureForNewHardFailure = ($sample.hardFailure -and -not $watch.hardFailureDiagnosticCaptured)
                    if (-not $watch.diagnosticCaptured -or $captureForNewHardFailure) {
                        $watch.diagnosticCaptured = $true
                        if ($sample.hardFailure) { $watch.hardFailureDiagnosticCaptured = $true }
                        try {
                            if ($forceDemoDiagnosticError) { throw "Injected ToolingDemoMode diagnostic capture failure for $($gate.gateId)." }
                            $watch.diagnostic = Capture-RemediationGuestStallDiagnostics -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -EvidenceDirectory $runDirectory -NamePrefix 'd' -WatchSample $sample -PreviousPressureSamples $priorPressureRecords -AdbTimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
                            $watch.diagnostic | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                            $watch.diagnostic | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                            $watch.diagnostic | Add-Member -NotePropertyName capturePhase -NotePropertyValue $(if ($sample.hardFailure) { 'watchdog_health_failure' } else { 'watchdog_stall_signal' }) -Force
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
                $processResult = Invoke-RemediationProcess -FilePath $executionGradlePath -ArgumentList $arguments -WorkingDirectory $executionRepoFull -LogDirectory $logDirectory -Name $gate.artifactToken -TimeoutSeconds $GateTimeoutSeconds -PollIntervalSeconds 1 -OnStart $startHook -OnPulse $pulse -KillProcessTreeOnTimeout
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
                $endHealthDirectory = $watchdogLogDirectory
                New-Item -ItemType Directory -Path $endHealthDirectory -Force | Out-Null
                try {
                    $endHealth = Get-RemediationDeviceHealth -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $endHealthDirectory -ProbeTimeoutSeconds $ProbeTimeoutSeconds -MaxShellLatencySeconds $MaxShellLatencySeconds -MaxPackageManagerLatencySeconds $MaxPackageManagerLatencySeconds -Quick
                } catch {
                    $endHealth = [pscustomobject][ordered]@{ serial = $DeviceSerial; healthy = $false; hardFailure = $true; shellLatencySeconds = $null; packageManagerLatencySeconds = $null; probes = @(); deviceIdentity = $deviceHealth.deviceIdentity; error = $_.Exception.Message }
                }
                $endHealth | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                $endHealth | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                $endHealth | Add-Member -NotePropertyName samplePhase -NotePropertyValue 'gate_end' -Force
                $deviceHealthHistory.Add($endHealth)
                $endSampleUtc = Get-RemediationUtcNow
                $endSampleRecord = [pscustomobject][ordered]@{
                    sampledUtc = Format-RemediationUtc $endSampleUtc
                    sampledKorea = Format-RemediationKoreaTime $endSampleUtc
                    gateId = $gate.gateId
                    artifactToken = $gate.artifactToken
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
                        try {
                            $watch.diagnostic = Capture-RemediationGuestStallDiagnostics -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -EvidenceDirectory $runDirectory -NamePrefix 'e' -WatchSample $endHealth -PreviousPressureSamples @($watch.pressureSamples.ToArray()) -AdbTimeoutSeconds ([Math]::Min(8, $ProbeTimeoutSeconds))
                            $watch.diagnostic | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                            $watch.diagnostic | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                            $watch.diagnostic | Add-Member -NotePropertyName capturePhase -NotePropertyValue 'gate_end_health_failure' -Force
                        } catch {
                            $watch.diagnosticError = $_.Exception.Message
                        }
                    }
                }
                $gateEndCorrelation = Get-RemediationTimeCorrelationSample -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $gateCorrelationDirectory -NamePrefix 'e' -TimeoutSeconds ([Math]::Min(5, $ProbeTimeoutSeconds))
                $gateEndCorrelation | Add-Member -NotePropertyName gateId -NotePropertyValue $gate.gateId -Force
                $gateEndCorrelation | Add-Member -NotePropertyName artifactToken -NotePropertyValue $gate.artifactToken -Force
                $gateEndCorrelation | Add-Member -NotePropertyName boundary -NotePropertyValue 'gate_end' -Force
                $gateEndCorrelationPath = Join-Path (Join-Path $runDirectory 't') ($gate.artifactToken + '.e')
                $gateEndCorrelation | Add-Member -NotePropertyName evidenceJsonPath -NotePropertyValue $gateEndCorrelationPath -Force
                Write-RemediationJson -Path $gateEndCorrelationPath -Value $gateEndCorrelation
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
            artifactToken = $gate.artifactToken
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
            artifactToken = $gate.artifactToken
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
    $bootstrapAfterGate = $null
    $bootstrapAfterGatePass = [bool]$ToolingDemoMode
    if (-not $ToolingDemoMode) {
        $bootstrapAfterGate = Get-DetachedLocalPropertiesState -MaterializationPath $executionRepoFull -LogDirectory $logDirectory -Name 'git-local-properties-after-gate'
        $bootstrapAfterGatePass = ($bootstrapAfterGate.exists -and $bootstrapAfterGate.byteCount -eq 0 -and $bootstrapAfterGate.ignored)
        if (-not $bootstrapAfterGatePass) {
            $haltAll = $true
            $status = 'FAILED_EXECUTION_LOCAL_PROPERTIES_BOOTSTRAP_CHANGED'
            $errorText = 'The detached local.properties bootstrap was missing, non-empty, or no longer Git-ignored after gate execution.'
        }
    }
    if (-not $afterState.clean) {
        $haltAll = $true
        $status = $(if ($ToolingDemoMode) { 'FAILED_TRACKED_TREE_CHANGED' } else { 'FAILED_EXECUTION_TREE_CHANGED' })
        $errorText = 'The gate execution tree changed tracked or non-ignored inputs; no cleanup was attempted.'
    }
    $executionGateRecord = [pscustomobject][ordered]@{
        gateId = $gate.gateId
        artifactToken = $gate.artifactToken
        candidateSha = $head
        candidateTree = $treeSha
        mechanism = $executionLifetime.mechanism
        status = $(if (-not $executionStarted) { 'not_started' } elseif ($status -eq 'PASS') { 'PASS' } else { 'FAIL' })
        provenancePass = [bool](-not $ToolingDemoMode -and $executionStarted -and $status -eq 'PASS' -and $executionLifetime.identityPass -and $afterState.clean -and $bootstrapAfterGatePass)
        materializationPath = $(if ($ToolingDemoMode) { $null } else { $executionRepoFull })
        workingDirectory = $(if ($executionStarted) { $executionRepoFull } else { $null })
        launcherPath = $(if ($executionStarted -and $gate.kind -ne 'diff') { $executionGradlePath } else { $null })
        launcherPolicy = $executionLifetime.launcherPolicy
        sourceLocalProperties = $(if ($ToolingDemoMode) { $null } else { $executionLifetime.sourceLocalProperties })
        detachedLocalPropertiesBootstrap = $(if ($ToolingDemoMode) { $null } else { $executionLifetime.detachedLocalPropertiesBootstrap })
        detachedLocalPropertiesBootstrapAfterGate = $bootstrapAfterGate
        detachedLocalPropertiesBootstrapPass = [bool]$bootstrapAfterGatePass
        sourceWorktreeUsedForGateExecution = [bool]$ToolingDemoMode
        startedUtc = $(if ($null -ne $executionStartUtc) { Format-RemediationUtc $executionStartUtc } else { $null })
        endedUtc = $(if ($null -ne $executionEndUtc) { Format-RemediationUtc $executionEndUtc } else { $null })
        materializationStateAfterGate = $afterState
    }
    $gateExecutionRecords.Add($executionGateRecord)
    $gateRecord = New-VerificationGateRecord -GateId $gate.gateId -ArtifactToken $gate.artifactToken -Kind $gate.kind -RequestedClass $gate.requestedClass -RequestedTask $gate.task -Status $status -Command $(if ($null -ne $processResult) { $processResult.command } else { '' }) -Arguments $arguments -CandidateSha $head -CandidateTree $treeSha -StartedUtc (Format-RemediationUtc $gateStart) -StartedKorea (Format-RemediationKoreaTime $gateStart) -EndedUtc (Format-RemediationUtc $ended) -EndedKorea (Format-RemediationKoreaTime $ended) -ExitCode $exitCode -TimedOut $timedOut -TestSummary $testSummary -InfrastructureSignals $infraSignals -InfrastructureStatus $infraStatus -PhaseMap (ConvertTo-RemediationPhaseMap $phaseDurations) -LogPaths $logPaths -FailureEvidencePath $failurePath -DeviceHealth $deviceHealth -ErrorText $errorText
    $gateRecord | Add-Member -NotePropertyName executionLifetime -NotePropertyValue $executionGateRecord
    if ($gate.kind -eq 'connected') {
        $gateRecord | Add-Member -NotePropertyName watchdogSamples -NotePropertyValue @($watch.samples.ToArray())
        $gateRecord | Add-Member -NotePropertyName watchdogPressureSamples -NotePropertyValue @($watch.pressureSamples.ToArray())
        $gateRecord | Add-Member -NotePropertyName stallDiagnostic -NotePropertyValue $watch.diagnostic
        $gateRecord | Add-Member -NotePropertyName stallDiagnosticError -NotePropertyValue $watch.diagnosticError
        $gateRecord | Add-Member -NotePropertyName gateCorrelationEvidenceDirectory -NotePropertyValue $gateCorrelationDirectory
    }
    $gateResults.Add($gateRecord)
    $phaseResults.Add([pscustomobject][ordered]@{
        gateId = $gate.gateId
        artifactToken = $gate.artifactToken
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
    $propertiesFinalState = Get-DetachedLocalPropertiesState -MaterializationPath $executionRepoFull -LogDirectory $logDirectory -Name 'git-local-properties-ignore-after-all-gates'
    $propertiesFinalPass = ($propertiesFinalState.exists -and $propertiesFinalState.byteCount -eq 0 -and $propertiesFinalState.ignored)
    $executionLifetime | Add-Member -NotePropertyName materializationStateAfterAllGates -NotePropertyValue $materializationFinalState
    $executionLifetime | Add-Member -NotePropertyName detachedLocalPropertiesBootstrapAfterAllGates -NotePropertyValue $propertiesFinalState
    $executionLifetime | Add-Member -NotePropertyName gates -NotePropertyValue @($gateExecutionRecords.ToArray())
    $executionLifetime | Add-Member -NotePropertyName startedUtc -NotePropertyValue (Format-RemediationUtc $verificationStarted)
    $executionLifetime | Add-Member -NotePropertyName endedUtc -NotePropertyValue (Format-RemediationUtc (Get-RemediationUtcNow))
    $lifetimePass = ($executionLifetime.identityPass -and $materializationFinalState.clean -and $propertiesFinalPass -and $gateExecutionRecords.Count -eq $gateSpecs.Count -and @($gateExecutionRecords | Where-Object { -not $_.provenancePass }).Count -eq 0)
    $executionLifetime | Add-Member -NotePropertyName status -NotePropertyValue $(if ($lifetimePass) { 'PASS' } else { 'FAIL' })
    if (-not $lifetimePass) { $passed = $false }
} else {
    $executionLifetime | Add-Member -NotePropertyName gates -NotePropertyValue @($gateExecutionRecords.ToArray())
    $executionLifetime | Add-Member -NotePropertyName status -NotePropertyValue 'NOT_APPLICABLE'
}
$overall = $(if ($passed) { 'PASS' } elseif ($circuitBreakerOpen) { 'BLOCKED_BY_INFRASTRUCTURE_CIRCUIT_BREAKER' } elseif ($toolingInfrastructureFailureSeen) { 'BLOCKED_BY_TOOLING_INFRASTRUCTURE' } else { 'FAIL_OR_INCOMPLETE' })
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
    evidencePathBudget = $evidencePathBudget
    executionLifetime = $executionLifetime
    expectedParentSha = $ExpectedParentSha
    scope = [pscustomobject][ordered]@{
        connectedTestClasses = @($ConnectedTestClass)
        jvmTestClasses = @($JvmTestClass)
        compileTasks = @($CompileTask)
        diffCheck = [bool]$RunDiffCheck
        gateOrder = @($gateSpecs | ForEach-Object { $_.gateId })
        artifactTokenPolicy = 'sanitized-prefix-8-plus-lowercase-sha256-128-v1'
        artifactTokenMaximumLength = 41
        gateArtifactTokens = @($gateArtifactTokens.ToArray())
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
    toolingInfrastructureBootstrap = [pscustomobject][ordered]@{
        failed = [bool]$toolingInfrastructureFailureSeen
        failurePath = $toolingInfrastructureFailurePath
        deviceHealthFailureObserved = $false
    }
    deviceHealthHistory = @($deviceHealthHistory.ToArray())
    phaseTimings = @($phaseResults.ToArray())
    evidenceDirectory = $runDirectory
    scopeWidening = $false
    semanticVerdict = 'not_provided_by_verification_tool'
    cleanVerdict = 'not_provided_by_verification_tool'
}
$finalizationRecord = [ordered]@{
    status = 'PASS'
    underlyingVerificationStatus = $overall
    underlyingExecutionLifetimeStatus = $executionLifetime.status
    artifactPaths = [ordered]@{
        verification = (Join-Path $runDirectory 'verification.json')
        executionLifetime = (Join-Path $runDirectory 'execution-lifetime.json')
        timings = (Join-Path $runDirectory 'timings.json')
    }
    errors = @()
    recoveryErrors = @()
}
$executionLifetime | Add-Member -NotePropertyName reportFinalization -NotePropertyValue $finalizationRecord -Force
$verification | Add-Member -NotePropertyName finalization -NotePropertyValue $finalizationRecord -Force

$finalizationErrors = New-Object System.Collections.Generic.List[object]
try {
    Write-VerificationFinalizationJson -Path (Join-Path $runDirectory 'execution-lifetime.json') -Value $executionLifetime
} catch {
    $finalizationErrors.Add([pscustomobject]@{ artifact = 'execution-lifetime.json'; phase = 'initial'; message = $_.Exception.Message })
}
try {
    Write-VerificationFinalizationJson -Path (Join-Path $runDirectory 'timings.json') -Value @($phaseResults.ToArray())
} catch {
    $finalizationErrors.Add([pscustomobject]@{ artifact = 'timings.json'; phase = 'initial'; message = $_.Exception.Message })
}
if ($finalizationErrors.Count -eq 0) {
    try {
        Write-VerificationFinalizationJson -Path (Join-Path $runDirectory 'verification.json') -Value $verification
    } catch {
        $finalizationErrors.Add([pscustomobject]@{ artifact = 'verification.json'; phase = 'initial'; message = $_.Exception.Message })
    }
}

if ($finalizationErrors.Count -gt 0) {
    $passed = $false
    $overall = 'FAILED_REPORT_FINALIZATION'
    $verification.status = $overall
    $finalizationFailureEnded = Get-RemediationUtcNow
    $verification.endedUtc = Format-RemediationUtc $finalizationFailureEnded
    $verification.endedKorea = Format-RemediationKoreaTime $finalizationFailureEnded
    $executionLifetime.status = $overall
    $finalizationRecord.status = 'FAIL'
    $finalizationRecord.errors = @($finalizationErrors.ToArray())

    $recoveryErrors = New-Object System.Collections.Generic.List[object]
    try {
        Write-VerificationFinalizationJson -Path (Join-Path $runDirectory 'execution-lifetime.json') -Value $executionLifetime
    } catch {
        $recoveryErrors.Add([pscustomobject]@{ artifact = 'execution-lifetime.json'; phase = 'failure_recovery'; message = $_.Exception.Message })
    }
    try {
        Write-VerificationFinalizationJson -Path (Join-Path $runDirectory 'timings.json') -Value @($phaseResults.ToArray())
    } catch {
        $recoveryErrors.Add([pscustomobject]@{ artifact = 'timings.json'; phase = 'failure_recovery'; message = $_.Exception.Message })
    }
    $finalizationRecord.recoveryErrors = @($recoveryErrors.ToArray())
    $finalizationRecord.errors = @($finalizationErrors.ToArray() + $recoveryErrors.ToArray())
    try {
        Write-VerificationFinalizationJson -Path (Join-Path $runDirectory 'verification.json') -Value $verification
    } catch {
        $recoveryErrors.Add([pscustomobject]@{ artifact = 'verification.json'; phase = 'failure_recovery'; message = $_.Exception.Message })
        $finalizationRecord.recoveryErrors = @($recoveryErrors.ToArray())
        $finalizationRecord.errors = @($finalizationErrors.ToArray() + $recoveryErrors.ToArray())
        try {
            $fallbackText = @(
                'REPORT_FINALIZATION_STATUS=FAILED'
                ('UNDERLYING_VERIFICATION_STATUS=' + $finalizationRecord.underlyingVerificationStatus)
                ('CANDIDATE_SHA=' + $head)
                ('EVIDENCE_DIRECTORY=' + $runDirectory)
                (($finalizationRecord.errors | ForEach-Object { $_.artifact + ':' + $_.phase + ':' + $_.message }) -join [Environment]::NewLine)
            ) -join [Environment]::NewLine
            [System.IO.File]::WriteAllText((Join-Path $runDirectory 'finalization-error.txt'), $fallbackText, (New-Object System.Text.UTF8Encoding($false)))
        } catch {
            Write-Error -Message ("Report finalization failed and its fallback evidence could not be written: " + $_.Exception.Message) -ErrorAction Continue
        }
    }
}
if ($null -ne $firstInfrastructureFailurePath) {
    Write-Output ('INFRA_FAILURE_JSON=' + $firstInfrastructureFailurePath)
}
Write-Output ('VERIFICATION_STATUS=' + $overall)
Write-Output ('VERIFICATION_JSON=' + (Join-Path $runDirectory 'verification.json'))
Write-Output ('EVIDENCE_DIRECTORY=' + $runDirectory)
if (-not $passed) { exit 1 }

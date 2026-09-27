[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepoPath,
    [Parameter(Mandatory)][string]$RemoteName,
    [Parameter(Mandatory)][string]$ImplementationRef,
    [Parameter(Mandatory)][string]$ExpectedRemoteSha,
    [Parameter(Mandatory)][string]$ExpectedLocalSha,
    [string]$ExpectedParentSha,
    [Parameter(Mandatory)][string]$ProtectedPrimaryPath,
    [Parameter(Mandatory)][string]$ProtectedPrimarySha,
    [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$ProtectedStashObjects,
    [string]$RecordedReviewTip,
    [string]$ReviewRef,
    [switch]$RequireReviewAncestry,
    [string]$AdbPath,
    [string]$DeviceSerial,
    [switch]$CheckConnectedDevice,
    [ValidateRange(1, 600)][int]$ProbeTimeoutSeconds = 10,
    [ValidateRange(0.1, 600)][double]$MaxShellLatencySeconds = 8,
    [ValidateRange(0.1, 600)][double]$MaxPackageManagerLatencySeconds = 15,
    [string]$EvidenceRoot
)

. (Join-Path $PSScriptRoot 'Remediation.Common.ps1')

foreach ($inputSha in @(
    @{ name = 'ExpectedRemoteSha'; value = $ExpectedRemoteSha },
    @{ name = 'ExpectedLocalSha'; value = $ExpectedLocalSha },
    @{ name = 'ProtectedPrimarySha'; value = $ProtectedPrimarySha },
    @{ name = 'ExpectedParentSha'; value = $ExpectedParentSha },
    @{ name = 'RecordedReviewTip'; value = $RecordedReviewTip }
)) {
    if (-not [string]::IsNullOrWhiteSpace([string]$inputSha.value) -and [string]$inputSha.value -notmatch '^[0-9a-fA-F]{40,64}$') {
        throw "$($inputSha.name) must be a full hexadecimal Git object id when supplied."
    }
}
foreach ($stashSha in $ProtectedStashObjects) {
    if ($stashSha -notmatch '^[0-9a-fA-F]{40,64}$') { throw "Protected stash object must be a full hexadecimal Git object id: $stashSha" }
}
if ((-not [string]::IsNullOrWhiteSpace($RecordedReviewTip)) -xor (-not [string]::IsNullOrWhiteSpace($ReviewRef))) {
    throw 'RecordedReviewTip and ReviewRef must either both be supplied or both be omitted.'
}

$repoFull = (Resolve-Path -LiteralPath $RepoPath).Path
$expectedDirectory = New-RemediationRunDirectory -RepoPath $repoFull -CandidateSha $ExpectedLocalSha -EvidenceRoot $EvidenceRoot
$runDirectory = $expectedDirectory.evidenceDirectory
$preflightStarted = Get-RemediationUtcNow
$checks = New-Object System.Collections.Generic.List[object]
$mismatches = New-Object System.Collections.Generic.List[string]
$observedHead = $null
$treeSha = $null

try {
    if (-not (Test-Path -LiteralPath (Join-Path $repoFull '.git'))) {
        throw "Implementation path is not a Git worktree: $repoFull"
    }
    $observedHead = Get-RemediationHead -RepoPath $repoFull -LogDirectory $runDirectory
    $treeSha = Get-RemediationTreeSha -RepoPath $repoFull -CommitSha $observedHead -LogDirectory $runDirectory
    $checks.Add([pscustomobject][ordered]@{
        name = 'local_head'
        expected = $ExpectedLocalSha
        observed = $observedHead
        pass = ($observedHead -eq $ExpectedLocalSha)
    })
    if ($observedHead -ne $ExpectedLocalSha) { $mismatches.Add("local HEAD expected $ExpectedLocalSha but was $observedHead") }

    if (-not [string]::IsNullOrWhiteSpace($ExpectedParentSha)) {
        $parent = (Get-RemediationGitText -RepoPath $repoFull -ArgumentList @('rev-parse', ($observedHead + '^')) -LogDirectory $runDirectory -Name 'git-parent').stdoutSample.Trim()
        $parentPass = ($parent -eq $ExpectedParentSha)
        $checks.Add([pscustomobject][ordered]@{ name = 'local_parent'; expected = $ExpectedParentSha; observed = $parent; pass = $parentPass })
        if (-not $parentPass) { $mismatches.Add("parent expected $ExpectedParentSha but was $parent") }
    }

    $tracked = Get-RemediationTrackedTreeState -RepoPath $repoFull -CandidateSha $observedHead -LogDirectory $runDirectory
    $checks.Add([pscustomobject][ordered]@{
        name = 'tracked_tree'
        clean = $tracked.clean
        trackedStatus = $tracked.trackedStatus
        untrackedPresent = $tracked.untrackedPresent
        untrackedCount = $tracked.untrackedCount
        untrackedStatus = @($tracked.untrackedStatus)
        untrackedListingTruncated = $tracked.untrackedListingTruncated
        pass = $tracked.clean
    })
    if (-not $tracked.clean) { $mismatches.Add('implementation worktree has tracked changes or non-ignored untracked files') }

    $remoteSha = Get-RemediationRemoteRefSha -RepoPath $repoFull -RemoteName $RemoteName -BranchName $ImplementationRef -LogDirectory $runDirectory
    $remotePass = ($remoteSha -eq $ExpectedRemoteSha)
    $checks.Add([pscustomobject][ordered]@{ name = 'implementation_remote'; remote = $RemoteName; ref = $ImplementationRef; expected = $ExpectedRemoteSha; observed = $remoteSha; pass = $remotePass })
    if (-not $remotePass) { $mismatches.Add("implementation ref expected $ExpectedRemoteSha but was $remoteSha") }

    $primaryHead = Get-RemediationHead -RepoPath $ProtectedPrimaryPath -LogDirectory $runDirectory
    $primaryPass = ($primaryHead -eq $ProtectedPrimarySha)
    $checks.Add([pscustomobject][ordered]@{ name = 'protected_primary_head'; expected = $ProtectedPrimarySha; observed = $primaryHead; pass = $primaryPass })
    if (-not $primaryPass) { $mismatches.Add("protected primary HEAD expected $ProtectedPrimarySha but was $primaryHead") }

    $stashResults = New-Object System.Collections.Generic.List[object]
    foreach ($stashSha in $ProtectedStashObjects) {
        $stashCheck = Invoke-RemediationGit -RepoPath $ProtectedPrimaryPath -ArgumentList @('cat-file', '-e', ($stashSha + '^{commit}')) -LogDirectory $runDirectory -Name 'git-protected-object'
        $present = (-not $stashCheck.timedOut -and $stashCheck.exitCode -eq 0)
        $stashResults.Add([pscustomobject][ordered]@{ sha = $stashSha; present = $present })
        if (-not $present) { $mismatches.Add("protected stash object missing: $stashSha") }
    }
    $missingStashes = @($stashResults | Where-Object { -not $_.present }); $checks.Add([pscustomobject][ordered]@{ name = 'protected_stash_objects'; objects = @($stashResults.ToArray()); pass = ($missingStashes.Count -eq 0) })

    $localPropertiesPath = Join-Path $repoFull 'local.properties'
    $ignoreCheck = Invoke-RemediationGit -RepoPath $repoFull -ArgumentList @('check-ignore', '--quiet', '--', 'local.properties') -LogDirectory $runDirectory -Name 'git-local-properties-ignore'
    $trackedCheck = Invoke-RemediationGit -RepoPath $repoFull -ArgumentList @('ls-files', '--error-unmatch', '--', 'local.properties') -LogDirectory $runDirectory -Name 'git-local-properties-tracked'
    $localPropertiesSafe = ($ignoreCheck.exitCode -eq 0 -and $trackedCheck.exitCode -eq 1)
    $checks.Add([pscustomobject][ordered]@{
        name = 'local_properties'
        exists = (Test-Path -LiteralPath $localPropertiesPath -PathType Leaf)
        ignored = ($ignoreCheck.exitCode -eq 0)
        tracked = ($trackedCheck.exitCode -eq 0)
        contentRead = $false
        pass = $localPropertiesSafe
    })
    if (-not $localPropertiesSafe) { $mismatches.Add('local.properties is not both ignored and untracked') }

    $reviewResult = [ordered]@{ requested = $false; recordedTip = $RecordedReviewTip; liveTip = $null; relation = 'not_requested'; ancestryPass = $null; semanticCompatibility = 'not_assessed_by_tool' }
    if (-not [string]::IsNullOrWhiteSpace($RecordedReviewTip) -or -not [string]::IsNullOrWhiteSpace($ReviewRef)) {
        if ([string]::IsNullOrWhiteSpace($RecordedReviewTip) -or [string]::IsNullOrWhiteSpace($ReviewRef)) {
            $reviewResult.relation = 'incomplete_inputs'
            if ($RequireReviewAncestry) { $mismatches.Add('review ancestry requires both a recorded tip and a live review ref') }
        } else {
            $reviewResult.requested = $true
            $liveReviewSha = Get-RemediationRemoteRefSha -RepoPath $repoFull -RemoteName $RemoteName -BranchName $ReviewRef -LogDirectory $runDirectory
            $reviewResult.liveTip = $liveReviewSha
            if ($liveReviewSha -eq $RecordedReviewTip) {
                $reviewResult.relation = 'identical'
                $reviewResult.ancestryPass = $true
            } else {
                try {
                    $isAncestor = Test-RemediationGitAncestor -RepoPath $repoFull -AncestorSha $RecordedReviewTip -DescendantSha $liveReviewSha -LogDirectory $runDirectory
                    $reviewResult.ancestryPass = $isAncestor
                    $reviewResult.relation = $(if ($isAncestor) { 'forward_moved' } else { 'diverged_or_behind' })
                } catch {
                    $reviewResult.relation = 'ancestry_unavailable'
                    $reviewResult.ancestryError = $_.Exception.Message
                    $reviewResult.ancestryPass = $false
                }
            }
            if ($RequireReviewAncestry -and -not $reviewResult.ancestryPass) {
                $mismatches.Add("live review ref $ReviewRef does not prove recorded tip $RecordedReviewTip is an ancestor")
            }
        }
    }
    $checks.Add([pscustomobject][ordered]@{ name = 'review_tip'; result = [pscustomobject]$reviewResult; pass = (-not $RequireReviewAncestry -or [bool]$reviewResult.ancestryPass) })

    $deviceResult = $null
    if ($CheckConnectedDevice) {
        if ([string]::IsNullOrWhiteSpace($AdbPath) -or [string]::IsNullOrWhiteSpace($DeviceSerial)) {
            $deviceResult = [pscustomobject][ordered]@{ healthy = $false; hardFailure = $true; error = 'AdbPath and DeviceSerial are required for connected-device preflight'; probes = @() }
        } else {
            $deviceResult = Get-RemediationDeviceHealth -RepoPath $repoFull -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory (Join-Path $runDirectory 'device-health') -ProbeTimeoutSeconds $ProbeTimeoutSeconds -MaxShellLatencySeconds $MaxShellLatencySeconds -MaxPackageManagerLatencySeconds $MaxPackageManagerLatencySeconds
        }
        $checks.Add([pscustomobject][ordered]@{ name = 'connected_device'; result = $deviceResult; pass = [bool]$deviceResult.healthy })
        if (-not $deviceResult.healthy) { $mismatches.Add('connected-device readiness preflight failed') }
        Write-RemediationJson -Path (Join-Path $runDirectory 'device-health.json') -Value $deviceResult
        if ($null -ne $deviceResult.correlationStart) { Write-RemediationJson -Path (Join-Path $runDirectory 'time-correlation-start.json') -Value $deviceResult.correlationStart }
        if ($null -ne $deviceResult.correlationEnd) { Write-RemediationJson -Path (Join-Path $runDirectory 'time-correlation-end.json') -Value $deviceResult.correlationEnd }
    }

    $status = $(if ($mismatches.Count -eq 0) { 'PASS' } else { 'FAIL' })
    $preflightEnded = Get-RemediationUtcNow
    $evidence = [pscustomobject][ordered]@{
        schemaVersion = 1
        evidenceKind = 'preflight'
        runId = $expectedDirectory.runId
        status = $status
        startedUtc = Format-RemediationUtc $preflightStarted
        startedKorea = Format-RemediationKoreaTime $preflightStarted
        endedUtc = Format-RemediationUtc $preflightEnded
        endedKorea = Format-RemediationKoreaTime $preflightEnded
        candidateSha = $observedHead
        candidateTree = $treeSha
        expectedLocalSha = $ExpectedLocalSha
        remote = [pscustomobject]@{ name = $RemoteName; implementationRef = $ImplementationRef; expectedSha = $ExpectedRemoteSha; observedSha = $remoteSha }
        reviewTip = [pscustomobject]$reviewResult
        trackedTreeClean = [bool]$tracked.clean
        checks = @($checks.ToArray())
        mismatches = @($mismatches.ToArray())
        protectedState = [pscustomobject]@{
            primaryPath = $ProtectedPrimaryPath
            primaryHead = $primaryHead
            primaryHeadUnchanged = $primaryPass
            stashObjects = @($stashResults.ToArray())
            localPropertiesContentRead = $false
        }
        connectedDevice = $deviceResult
        evidenceDirectory = $runDirectory
        semanticVerdict = 'not_provided_by_preflight_tool'
        cleanVerdict = 'not_provided_by_preflight_tool'
    }
    Write-RemediationJson -Path (Join-Path $runDirectory 'preflight.json') -Value $evidence
    Write-Output ('PREFLIGHT_STATUS=' + $status)
    Write-Output ('PREFLIGHT_JSON=' + (Join-Path $runDirectory 'preflight.json'))
    if ($mismatches.Count -gt 0) {
        foreach ($mismatch in $mismatches) { Write-Output ('MISMATCH=' + $mismatch) }
        exit 1
    }
} catch {
    $mismatches.Add($_.Exception.Message)
    $preflightEnded = Get-RemediationUtcNow
    $failure = [pscustomobject][ordered]@{
        schemaVersion = 1
        evidenceKind = 'preflight'
        runId = $expectedDirectory.runId
        status = 'FAIL'
        startedUtc = Format-RemediationUtc $preflightStarted
        startedKorea = Format-RemediationKoreaTime $preflightStarted
        endedUtc = Format-RemediationUtc $preflightEnded
        endedKorea = Format-RemediationKoreaTime $preflightEnded
        candidateSha = $observedHead
        candidateTree = $treeSha
        checks = @($checks.ToArray())
        mismatches = @($mismatches.ToArray())
        evidenceDirectory = $runDirectory
        error = $_.Exception.Message
        semanticVerdict = 'not_provided_by_preflight_tool'
        cleanVerdict = 'not_provided_by_preflight_tool'
    }
    Write-RemediationJson -Path (Join-Path $runDirectory 'preflight.json') -Value $failure
    Write-Output ('PREFLIGHT_STATUS=FAIL')
    Write-Output ('PREFLIGHT_JSON=' + (Join-Path $runDirectory 'preflight.json'))
    Write-Output ('MISMATCH=' + $_.Exception.Message)
    exit 1
}

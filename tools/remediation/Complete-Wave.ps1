[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepoPath,
    [Parameter(Mandatory)][string]$RemoteName,
    [Parameter(Mandatory)][string]$ImplementationRef,
    [Parameter(Mandatory)][string]$ExpectedRemoteBaseSha,
    [Parameter(Mandatory)][string]$TestedSha,
    [Parameter(Mandatory)][string]$VerificationEvidencePath,
    [Parameter(Mandatory)][string[]]$RequiredGateIds,
    [Parameter(Mandatory)][string]$RecordedReviewTip,
    [Parameter(Mandatory)][string]$ReviewRef,
    [string]$AcceptanceEvidencePath,
    [string[]]$RequiredAcceptanceCheckIds = @(),
    [string]$ForwardReviewAdvanceAcknowledgement,
    [switch]$Push,
    [string]$EvidenceRoot
)

. (Join-Path $PSScriptRoot 'Remediation.Common.ps1')

function Assert-IgnoredEvidencePath {
    param([string]$RepoFull, [string]$Path, [string]$LogDirectory, [string]$Name)
    $full = (Resolve-Path -LiteralPath $Path).Path
    $root = [System.IO.Path]::GetFullPath((Join-Path $RepoFull 'build\remediation-agent')).TrimEnd('\') + '\'
    if (-not $full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name must be under ignored build/remediation-agent output."
    }
    return $full
}

function Get-RemediationAheadBehind {
    param([string]$RepoFull, [string]$LeftSha, [string]$RightSha, [string]$LogDirectory)
    $result = Get-RemediationGitText -RepoPath $RepoFull -ArgumentList @('rev-list', '--left-right', '--count', ($LeftSha + '...' + $RightSha)) -LogDirectory $LogDirectory -Name 'git-ahead-behind'
    $match = [Regex]::Match($result.stdoutSample.Trim(), '^\s*(\d+)\s+(\d+)\s*$')
    if (-not $match.Success) { throw 'Git ahead/behind result was ambiguous.' }
    return [pscustomobject]@{
        leftOnly = [int]$match.Groups[1].Value
        rightOnly = [int]$match.Groups[2].Value
    }
}

function Test-ExactSourceExecutionLifetime {
    param(
        [Parameter(Mandatory)]$Verification,
        [Parameter(Mandatory)][string]$VerificationPath,
        [Parameter(Mandatory)][string]$RepoFull,
        [Parameter(Mandatory)][string]$CandidateSha,
        [Parameter(Mandatory)][string]$CandidateTree
    )
    $failures = New-Object System.Collections.Generic.List[string]
    $proof = $Verification.executionLifetime
    if ($null -eq $proof) {
        $failures.Add('verification has no execution-lifetime provenance object')
    } else {
        $evidenceDirectory = [System.IO.Path]::GetFullPath((Split-Path -Parent $VerificationPath)).TrimEnd('\')
        $evidenceRunId = Split-Path -Leaf $evidenceDirectory
        $expectedMaterializationRoot = Join-Path $RepoFull 'build\remediation-worktrees'
        $expectedMaterialization = [System.IO.Path]::GetFullPath((Join-Path $expectedMaterializationRoot $evidenceRunId))
        $actualMaterialization = ''
        try { $actualMaterialization = [System.IO.Path]::GetFullPath([string]$proof.materializationPath) } catch {}
        $recordedSourceRoot = ''
        try { $recordedSourceRoot = [System.IO.Path]::GetFullPath([string]$proof.sourceRepositoryPath) } catch {}
        $expectedSourceLauncher = [System.IO.Path]::GetFullPath((Join-Path $RepoFull 'gradlew.bat'))
        $expectedExecutionLauncher = [System.IO.Path]::GetFullPath((Join-Path $expectedMaterialization 'gradlew.bat'))
        $requestedLauncher = ''
        $executedLauncher = ''
        try { $requestedLauncher = [System.IO.Path]::GetFullPath([string]$proof.requestedCanonicalLauncherPath) } catch {}
        try { $executedLauncher = [System.IO.Path]::GetFullPath([string]$proof.executedCanonicalLauncherPath) } catch {}
        if ($proof.contract -ne 'exact_candidate_execution_lifetime_v1') { $failures.Add('execution-lifetime contract identifier is missing or unsupported') }
        if ($proof.mechanism -ne 'git_detached_candidate_worktree') { $failures.Add('execution-lifetime mechanism is not the detached exact-candidate worktree') }
        if ($proof.lifecycle -ne 'retained_in_ignored_run_scoped_worktree_no_cleanup') { $failures.Add('execution-lifetime materialization lifecycle or no-cleanup policy is absent') }
        if ($proof.materializationRunId -ne $evidenceRunId) { $failures.Add('execution-lifetime worktree is not bound to the verification evidence run ID') }
        if ($proof.allowedWritableOutputs -ne 'Git-ignored outputs within the materialization; wrapper evidence remains outside it.') { $failures.Add('execution-lifetime allowed writable output policy is absent or unsupported') }
        if ($proof.localProperties -ne 'Not inspected, copied, or serialized by the wrapper; ignored source-worktree file remains outside the candidate tree.') { $failures.Add('execution-lifetime local.properties non-exposure policy is absent or unsupported') }
        if ($proof.sourceWorktreeUsedForGateExecution -ne $false) { $failures.Add('execution-lifetime proof permits gate execution from the mutable source worktree') }
        if ($proof.status -ne 'PASS') { $failures.Add('execution-lifetime proof did not PASS') }
        if (-not [string]::Equals($recordedSourceRoot, [System.IO.Path]::GetFullPath($RepoFull), [System.StringComparison]::OrdinalIgnoreCase)) { $failures.Add('execution-lifetime source repository does not match the completion repository') }
        if ($proof.candidateSha -ne $CandidateSha -or $proof.materializedHead -ne $CandidateSha) { $failures.Add('materialized HEAD does not bind to the tested candidate SHA') }
        if ($proof.candidateTree -ne $CandidateTree -or $proof.materializedTree -ne $CandidateTree) { $failures.Add('materialized tree does not bind to the tested candidate tree') }
        if (-not $proof.identityPass -or -not $proof.stateBeforeGates.clean -or -not $proof.materializationStateAfterAllGates.clean) { $failures.Add('materialization identity or clean boundary did not PASS') }
        if ($null -eq $proof.materializationCreation -or $proof.materializationCreation.exitCode -ne 0 -or $proof.materializationCreation.timedOut -ne $false -or [string]::IsNullOrWhiteSpace([string]$proof.materializationCreation.command)) { $failures.Add('exact candidate worktree creation evidence is incomplete') }
        if ($proof.sourceWorktreeUsedForGateExecution -ne $false) { $failures.Add('gate execution was not isolated from the source worktree') }
        if (-not [string]::Equals($actualMaterialization, $expectedMaterialization, [System.StringComparison]::OrdinalIgnoreCase)) { $failures.Add('materialization path is not the run-owned ignored worktree directory') }
        if (-not (Test-Path -LiteralPath $expectedMaterialization -PathType Container)) { $failures.Add('run-owned exact worktree materialization is unavailable') }
        if (-not [string]::Equals($requestedLauncher, $expectedSourceLauncher, [System.StringComparison]::OrdinalIgnoreCase)) { $failures.Add('requested normal launcher is not the source repository canonical gradlew.bat') }
        if (-not [string]::Equals($executedLauncher, $expectedExecutionLauncher, [System.StringComparison]::OrdinalIgnoreCase)) { $failures.Add('executed normal launcher is not the materialized canonical gradlew.bat') }
        if ($proof.launcherPolicy -ne 'normal_mode_repository_local_gradlew_bat_from_exact_candidate_materialization') { $failures.Add('normal canonical-launcher policy is absent') }

        $verificationGates = @($Verification.gates)
        $lifetimeGates = @($proof.gates)
        if ($verificationGates.Count -eq 0 -or $lifetimeGates.Count -ne $verificationGates.Count) {
            $failures.Add('execution-lifetime records do not cover every verification gate')
        } else {
            foreach ($gate in $verificationGates) {
                $matches = @($lifetimeGates | Where-Object { $_.gateId -eq $gate.gateId })
                if ($matches.Count -ne 1) {
                    $failures.Add("execution-lifetime record is missing or duplicated for gate $($gate.gateId)")
                    continue
                }
                $gateProof = $gate.executionLifetime
                $lifetimeGate = $matches[0]
                $gatePass = (
                    $gate.status -eq 'PASS' -and
                    $gate.candidateSha -eq $CandidateSha -and
                    $gate.candidateTree -eq $CandidateTree -and
                    $gateProof.provenancePass -eq $true -and
                    $gateProof.status -eq 'PASS' -and
                    $gateProof.mechanism -eq $proof.mechanism -and
                    $gateProof.launcherPolicy -eq $proof.launcherPolicy -and
                    $gateProof.candidateSha -eq $CandidateSha -and
                    $gateProof.candidateTree -eq $CandidateTree -and
                    $gateProof.materializationPath -eq $expectedMaterialization -and
                    $gateProof.workingDirectory -eq $expectedMaterialization -and
                    $gateProof.sourceWorktreeUsedForGateExecution -eq $false -and
                    $gateProof.materializationStateAfterGate.clean -eq $true -and
                    -not [string]::IsNullOrWhiteSpace([string]$gateProof.startedUtc) -and
                    -not [string]::IsNullOrWhiteSpace([string]$gateProof.endedUtc) -and
                    $lifetimeGate.provenancePass -eq $true -and
                    $lifetimeGate.status -eq 'PASS' -and
                    $lifetimeGate.mechanism -eq $proof.mechanism -and
                    $lifetimeGate.launcherPolicy -eq $proof.launcherPolicy -and
                    $lifetimeGate.candidateSha -eq $CandidateSha -and
                    $lifetimeGate.candidateTree -eq $CandidateTree -and
                    $lifetimeGate.materializationPath -eq $expectedMaterialization -and
                    $lifetimeGate.workingDirectory -eq $expectedMaterialization -and
                    $lifetimeGate.materializationStateAfterGate.clean -eq $true
                )
                if ($gate.kind -ne 'diff') {
                    $gatePass = $gatePass -and [string]::Equals([string]$gateProof.launcherPath, $expectedExecutionLauncher, [System.StringComparison]::OrdinalIgnoreCase)
                }
                if (-not $gatePass) { $failures.Add("exact execution-lifetime proof failed for gate $($gate.gateId)") }
            }
        }
    }
    return [pscustomobject]@{
        pass = ($failures.Count -eq 0)
        failures = @($failures.ToArray())
    }
}

function Get-FinalPushAuthority {
    param(
        [Parameter(Mandatory)][string]$RepoFull,
        [Parameter(Mandatory)][string]$RemoteName,
        [Parameter(Mandatory)][string]$ImplementationRef,
        [Parameter(Mandatory)][string]$ReviewRef,
        [Parameter(Mandatory)][string]$ExpectedRemoteBaseSha,
        [Parameter(Mandatory)][string]$InitialRemoteSha,
        [Parameter(Mandatory)][string]$TestedSha,
        [Parameter(Mandatory)][string]$CandidateTree,
        [Parameter(Mandatory)][string]$RecordedReviewTip,
        [string]$ForwardReviewAdvanceAcknowledgement,
        [Parameter(Mandatory)][string]$LogDirectory
    )
    $failures = New-Object System.Collections.Generic.List[string]
    $localHead = $null
    $localTree = $null
    $trackedState = $null
    $reviewLive = $null
    $reviewAncestor = $false
    $reviewRelation = 'unavailable'
    $reviewAcknowledgementPass = $false
    $destinationLive = $null
    $destinationState = 'unavailable'

    try {
        $localHead = Get-RemediationHead -RepoPath $RepoFull -LogDirectory $LogDirectory
        if ($localHead -ne $TestedSha) { $failures.Add("local HEAD moved before Push: expected $TestedSha, observed $localHead") }
        $localTree = Get-RemediationTreeSha -RepoPath $RepoFull -CommitSha $localHead -LogDirectory $LogDirectory
        if ($localTree -ne $CandidateTree) { $failures.Add("local candidate tree moved before Push: expected $CandidateTree, observed $localTree") }
    } catch {
        $failures.Add("unable to revalidate local candidate before Push: $($_.Exception.Message)")
    }
    try {
        $trackedState = Get-RemediationTrackedTreeState -RepoPath $RepoFull -CandidateSha $TestedSha -LogDirectory $LogDirectory
        if (-not $trackedState.clean) { $failures.Add('local worktree became tracked-dirty or gained non-ignored untracked inputs before Push') }
    } catch {
        $failures.Add("unable to revalidate local worktree state before Push: $($_.Exception.Message)")
    }

    try {
        $reviewLive = Get-RemediationRemoteRefSha -RepoPath $RepoFull -RemoteName $RemoteName -BranchName $ReviewRef -LogDirectory $LogDirectory
        if ($reviewLive -eq $RecordedReviewTip) {
            $reviewAncestor = $true
            $reviewRelation = 'identical'
        } else {
            $reviewAncestor = Test-RemediationGitAncestor -RepoPath $RepoFull -AncestorSha $RecordedReviewTip -DescendantSha $reviewLive -LogDirectory $LogDirectory
            $reviewRelation = $(if ($reviewAncestor) { 'forward_moved' } else { 'diverged_or_behind' })
        }
        $reviewAcknowledgementPass = (
            $reviewRelation -ne 'forward_moved' -or
            (-not [string]::IsNullOrWhiteSpace($ForwardReviewAdvanceAcknowledgement) -and
                $ForwardReviewAdvanceAcknowledgement -match [Regex]::Escape($reviewLive))
        )
        if (-not $reviewAncestor) { $failures.Add("recorded review tip $RecordedReviewTip is not an ancestor of the live pre-Push review tip $reviewLive") }
        elseif (-not $reviewAcknowledgementPass) { $failures.Add("forward review movement requires an acknowledgement naming the exact live review tip $reviewLive") }
    } catch {
        $reviewRelation = 'ancestry_unavailable'
        $reviewAncestor = $false
        $failures.Add("unable to revalidate review authority before Push: $($_.Exception.Message)")
    }

    # Read the destination last so no further authority lookup separates this
    # observation from the immutable-object push invocation.
    try {
        $destinationLive = Get-RemediationRemoteRefSha -RepoPath $RepoFull -RemoteName $RemoteName -BranchName $ImplementationRef -LogDirectory $LogDirectory
        if ($destinationLive -eq $TestedSha) {
            $destinationState = 'already_at_tested_sha'
        } elseif ($destinationLive -eq $ExpectedRemoteBaseSha) {
            $destinationState = 'expected_base'
        } else {
            $destinationState = 'moved_from_expected_base'
            $failures.Add("implementation ref moved before Push from the allowed exact states: observed $destinationLive")
        }
        if ($InitialRemoteSha -eq $TestedSha -and $destinationLive -ne $TestedSha) {
            $failures.Add("implementation ref changed after the earlier already-pushed observation: expected $TestedSha, observed $destinationLive")
        } elseif ($InitialRemoteSha -ne $ExpectedRemoteBaseSha -and $InitialRemoteSha -ne $TestedSha) {
            $failures.Add("earlier implementation ref observation was outside the authorized states: $InitialRemoteSha")
        }
    } catch {
        $failures.Add("unable to revalidate destination implementation ref before Push: $($_.Exception.Message)")
    }

    return [pscustomobject][ordered]@{
        contract = 'exact_tested_sha_push_authority_v1'
        observedUtc = Format-RemediationUtc (Get-RemediationUtcNow)
        testedSha = $TestedSha
        testedTree = $CandidateTree
        localHead = $localHead
        localTree = $localTree
        localTrackedTreeState = $trackedState
        destinationRef = $ImplementationRef
        destinationSha = $destinationLive
        destinationState = $destinationState
        reviewRef = $ReviewRef
        recordedReviewTip = $RecordedReviewTip
        liveReviewTip = $reviewLive
        reviewRelation = $reviewRelation
        recordedReviewTipIsAncestor = [bool]$reviewAncestor
        forwardReviewAcknowledgement = $ForwardReviewAdvanceAcknowledgement
        forwardReviewAcknowledgementNamesLiveTip = [bool]$reviewAcknowledgementPass
        authorizedPushSourceObjectId = $(if ($failures.Count -eq 0 -and $destinationState -eq 'expected_base') { $TestedSha } else { $null })
        pass = ($failures.Count -eq 0)
        failures = @($failures.ToArray())
    }
}

if ($RequiredGateIds.Count -eq 0) { throw 'At least one exact required verification gate must be supplied.' }
foreach ($sha in @($ExpectedRemoteBaseSha, $TestedSha, $RecordedReviewTip)) {
    if ($sha -notmatch '^[0-9a-fA-F]{40,64}$') { throw "Invalid full Git SHA input: $sha" }
}
$repoFull=(Resolve-Path -LiteralPath $RepoPath).Path
$runInfo=New-RemediationRunDirectory -RepoPath $repoFull -CandidateSha $TestedSha -EvidenceRoot $EvidenceRoot
$runDirectory=$runInfo.evidenceDirectory
$completionStarted=Get-RemediationUtcNow
$checks=New-Object System.Collections.Generic.List[object]
$errors=New-Object System.Collections.Generic.List[string]
$head=$null
$tree=$null
$remoteBefore=$null
$reviewLive=$null
$verification=$null
$acceptance=$null
$pushAttempted=$false
$pushResult=$null
$prePushAuthority=$null
$pushSourceObjectId=$null
$remoteAfter=$null
$aheadBehind=$null
$reviewAncestor=$false
$reviewRelation='not_checked'
$implementationState='not_checked'
$status='CHECK_FAILED'

try {
    $head=Get-RemediationHead -RepoPath $repoFull -LogDirectory $runDirectory
    $tree=Get-RemediationTreeSha -RepoPath $repoFull -CommitSha $head -LogDirectory $runDirectory
    $tracked=Get-RemediationTrackedTreeState -RepoPath $repoFull -CandidateSha $head -LogDirectory $runDirectory
    $headPass=($head -eq $TestedSha)
    $checks.Add([pscustomobject]@{ name='exact_head'; expected=$TestedSha; observed=$head; pass=$headPass })
    if(-not $headPass){$errors.Add("local HEAD expected $TestedSha but was $head")}
    $checks.Add([pscustomobject]@{
        name='tracked_tree'
        clean=$tracked.clean
        trackedStatus=$tracked.trackedStatus
        untrackedPresent=$tracked.untrackedPresent
        untrackedCount=$tracked.untrackedCount
        untrackedStatus=@($tracked.untrackedStatus)
        untrackedListingTruncated=$tracked.untrackedListingTruncated
        pass=$tracked.clean
    })
    if(-not $tracked.clean){$errors.Add('worktree has tracked changes or non-ignored untracked files at the tested SHA')}

    $verificationPath=Assert-IgnoredEvidencePath -RepoFull $repoFull -Path $VerificationEvidencePath -LogDirectory $runDirectory -Name 'VerificationEvidencePath'
    $verification=Get-Content -LiteralPath $verificationPath -Raw | ConvertFrom-Json
    $executionLifetimeCheck=Test-ExactSourceExecutionLifetime -Verification $verification -VerificationPath $verificationPath -RepoFull $repoFull -CandidateSha $TestedSha -CandidateTree $tree
    $executionLifetimePass=[bool]$executionLifetimeCheck.pass
    $checks.Add([pscustomobject]@{ name='execution_lifetime_provenance_binding'; contract='exact_candidate_execution_lifetime_v1'; pass=$executionLifetimePass; failures=@($executionLifetimeCheck.failures) })
    if(-not $executionLifetimePass){$errors.Add('verification evidence does not satisfy the execution-lifetime exact-tree provenance contract')}
    $verificationPass=($verification.evidenceKind -eq 'exact_source_verification' -and $verification.status -eq 'PASS' -and $verification.candidateSha -eq $TestedSha -and $verification.candidateTree -eq $tree -and $verification.scopeWidening -eq $false -and $executionLifetimePass)
    $checks.Add([pscustomobject]@{ name='verification_evidence_binding'; path=$verificationPath; evidenceKind=$verification.evidenceKind; status=$verification.status; candidateSha=$verification.candidateSha; candidateTree=$verification.candidateTree; pass=$verificationPass })
    if(-not $verificationPass){$errors.Add('verification evidence does not bind to a passing exact-source run for the tested SHA/tree')}
    foreach($gateId in $RequiredGateIds){
        $matches=@($verification.gates | Where-Object { $_.gateId -eq $gateId })
        $gatePass=($matches.Count -eq 1 -and $matches[0].status -eq 'PASS' -and $matches[0].candidateSha -eq $TestedSha -and $matches[0].candidateTree -eq $tree)
        $checks.Add([pscustomobject]@{ name='required_verification_gate'; gateId=$gateId; pass=$gatePass; observedStatus=$(if($matches.Count -eq 1){$matches[0].status}else{'missing_or_duplicate'}) })
        if(-not $gatePass){$errors.Add("required verification gate did not PASS exactly once: $gateId")}
    }

    if(-not [string]::IsNullOrWhiteSpace($AcceptanceEvidencePath)){
        $acceptancePath=Assert-IgnoredEvidencePath -RepoFull $repoFull -Path $AcceptanceEvidencePath -LogDirectory $runDirectory -Name 'AcceptanceEvidencePath'
        $acceptance=Get-Content -LiteralPath $acceptancePath -Raw | ConvertFrom-Json
        $acceptanceBinding=($acceptance.evidenceKind -eq 'tooling_acceptance' -and $acceptance.candidateSha -eq $TestedSha -and $acceptance.candidateTree -eq $tree)
        $checks.Add([pscustomobject]@{ name='acceptance_evidence_binding'; path=$acceptancePath; candidateSha=$acceptance.candidateSha; candidateTree=$acceptance.candidateTree; pass=$acceptanceBinding })
        if(-not $acceptanceBinding){$errors.Add('tooling acceptance evidence does not bind to the exact tested SHA/tree')}
        foreach($checkId in $RequiredAcceptanceCheckIds){
            $matches=@($acceptance.checks | Where-Object { $_.checkId -eq $checkId })
            $acceptancePass=($matches.Count -eq 1 -and $matches[0].status -eq 'PASS')
            $checks.Add([pscustomobject]@{ name='required_tooling_acceptance'; checkId=$checkId; pass=$acceptancePass; observedStatus=$(if($matches.Count -eq 1){$matches[0].status}else{'missing_or_duplicate'}) })
            if(-not $acceptancePass){$errors.Add("required tooling acceptance check did not PASS exactly once: $checkId")}
        }
    } elseif($RequiredAcceptanceCheckIds.Count -gt 0) {
        $errors.Add('required acceptance checks were supplied without AcceptanceEvidencePath')
    }

    $remoteBefore=Get-RemediationRemoteRefSha -RepoPath $repoFull -RemoteName $RemoteName -BranchName $ImplementationRef -LogDirectory $runDirectory
    $implementationState='expected_base'
    if($remoteBefore -eq $TestedSha){$implementationState='already_at_tested_sha'}
    elseif($remoteBefore -ne $ExpectedRemoteBaseSha){$implementationState='moved_from_expected_base'; $errors.Add("implementation ref moved from expected base $ExpectedRemoteBaseSha to $remoteBefore")}
    $checks.Add([pscustomobject]@{ name='implementation_ref_before'; remote=$RemoteName; ref=$ImplementationRef; expectedBase=$ExpectedRemoteBaseSha; observed=$remoteBefore; state=$implementationState; pass=($implementationState -in @('expected_base','already_at_tested_sha')) })
    $expectedBaseAncestor=Test-RemediationGitAncestor -RepoPath $repoFull -AncestorSha $ExpectedRemoteBaseSha -DescendantSha $TestedSha -LogDirectory $runDirectory
    $checks.Add([pscustomobject]@{ name='expected_base_ancestry'; base=$ExpectedRemoteBaseSha; candidate=$TestedSha; isAncestor=$expectedBaseAncestor; pass=$expectedBaseAncestor })
    if(-not $expectedBaseAncestor){$errors.Add('tested SHA is not a descendant of the recorded implementation base')}
    if($remoteBefore -eq $ExpectedRemoteBaseSha){
        $isFastForward=Test-RemediationGitAncestor -RepoPath $repoFull -AncestorSha $remoteBefore -DescendantSha $TestedSha -LogDirectory $runDirectory
        $aheadBehind=Get-RemediationAheadBehind -RepoFull $repoFull -LeftSha $remoteBefore -RightSha $TestedSha -LogDirectory $runDirectory
        $checks.Add([pscustomobject]@{ name='fast_forward_relation'; base=$remoteBefore; candidate=$TestedSha; isFastForward=$isFastForward; remoteOnly=$aheadBehind.leftOnly; candidateOnly=$aheadBehind.rightOnly; pass=$isFastForward })
        if(-not $isFastForward){$errors.Add('tested SHA is not a normal fast-forward descendant of the expected implementation base')}
    } else {
        $aheadBehind=Get-RemediationAheadBehind -RepoFull $repoFull -LeftSha $remoteBefore -RightSha $TestedSha -LogDirectory $runDirectory
    }

    $reviewLive=Get-RemediationRemoteRefSha -RepoPath $repoFull -RemoteName $RemoteName -BranchName $ReviewRef -LogDirectory $runDirectory
    $reviewRelation='identical'
    $reviewAncestor=$true
    if($reviewLive -ne $RecordedReviewTip){
        try {
            $reviewAncestor=Test-RemediationGitAncestor -RepoPath $repoFull -AncestorSha $RecordedReviewTip -DescendantSha $reviewLive -LogDirectory $runDirectory
            $reviewRelation=$(if($reviewAncestor){'forward_moved'}else{'diverged_or_behind'})
        } catch {
            $reviewAncestor=$false
            $reviewRelation='ancestry_unavailable'
        }
    }
    $ackNamesLiveReviewTip=($reviewRelation -ne 'forward_moved' -or (-not [string]::IsNullOrWhiteSpace($ForwardReviewAdvanceAcknowledgement) -and $ForwardReviewAdvanceAcknowledgement -match [Regex]::Escape($reviewLive)))
    $reviewPass=($reviewAncestor -and ($reviewRelation -eq 'identical' -or -not $Push -or $ackNamesLiveReviewTip))
    $checks.Add([pscustomobject]@{
        name='review_ref_relation'
        ref=$ReviewRef
        recordedTip=$RecordedReviewTip
        liveTip=$reviewLive
        relation=$reviewRelation
        recordedTipIsAncestor=[bool]$reviewAncestor
        semanticCompatibility='caller_or_reviewer_owned'
        forwardAdvanceAcknowledgement=$ForwardReviewAdvanceAcknowledgement
        forwardAdvanceAcknowledgementNamesLiveTip=[bool]$ackNamesLiveReviewTip
        pass=$reviewPass
    })
    if(-not $reviewAncestor){$errors.Add("recorded review tip $RecordedReviewTip is not proven in live review ancestry")}
    elseif($Push -and $reviewRelation -eq 'forward_moved' -and -not $ackNamesLiveReviewTip){
        $errors.Add('review tip moved forward; the explicit caller-owned compatibility acknowledgement must name the exact live tip SHA for Push mode')
    }

    if($errors.Count -ne 0){
        $status='CHECK_FAILED'
    } else {
        $status='CHECK_PASS'
    }
    if($Push -and $errors.Count -eq 0){
        $prePushAuthority=Get-FinalPushAuthority -RepoFull $repoFull -RemoteName $RemoteName -ImplementationRef $ImplementationRef -ReviewRef $ReviewRef -ExpectedRemoteBaseSha $ExpectedRemoteBaseSha -InitialRemoteSha $remoteBefore -TestedSha $TestedSha -CandidateTree $tree -RecordedReviewTip $RecordedReviewTip -ForwardReviewAdvanceAcknowledgement $ForwardReviewAdvanceAcknowledgement -LogDirectory (Join-Path $runDirectory 'pre-push-authority-logs')
        $checks.Add([pscustomobject]@{ name='just_in_time_push_authority'; contract=$prePushAuthority.contract; pass=$prePushAuthority.pass; localHead=$prePushAuthority.localHead; destinationSha=$prePushAuthority.destinationSha; reviewTip=$prePushAuthority.liveReviewTip; reviewRelation=$prePushAuthority.reviewRelation; pushSourceObjectId=$prePushAuthority.authorizedPushSourceObjectId; failures=@($prePushAuthority.failures) })
        foreach($failure in $prePushAuthority.failures){$errors.Add("pre-Push authority: $failure")}
        if(-not $prePushAuthority.pass){
            $status='PUSH_BLOCKED_BY_CHECKS'
        } elseif($prePushAuthority.destinationSha -eq $TestedSha){
            $remoteAfter=$prePushAuthority.destinationSha
            $aheadBehind=[pscustomobject]@{leftOnly=0;rightOnly=0}
            $status='ALREADY_PUSHED_EXACT_SHA'
        } else {
            $pushSourceObjectId=$TestedSha
            $pushRefspec=$pushSourceObjectId + ':refs/heads/' + $ImplementationRef
            $pushAttempted=$true
            $pushResult=Invoke-RemediationGit -RepoPath $repoFull -ArgumentList @('push', $RemoteName, $pushRefspec) -LogDirectory (Join-Path $runDirectory 'push-logs') -Name 'git-normal-fast-forward-push' -TimeoutSeconds 180
            if($pushResult.timedOut -or $pushResult.exitCode -ne 0){
                $status='PUSH_REJECTED_NO_RECONCILIATION'
                $errors.Add("normal fast-forward push of the exact tested object failed (exit $($pushResult.exitCode)); no reconciliation or retry was attempted")
            } else {
                $remoteAfter=Get-RemediationRemoteRefSha -RepoPath $repoFull -RemoteName $RemoteName -BranchName $ImplementationRef -LogDirectory $runDirectory
                $equal=($remoteAfter -eq $TestedSha)
                if($equal){
                    $aheadBehind=Get-RemediationAheadBehind -RepoFull $repoFull -LeftSha $TestedSha -RightSha $remoteAfter -LogDirectory $runDirectory
                    $sync=($aheadBehind.leftOnly -eq 0 -and $aheadBehind.rightOnly -eq 0)
                    if($sync){$status='PUSHED_AND_VERIFIED'}else{$status='POST_PUSH_AHEAD_BEHIND_MISMATCH';$errors.Add('post-push ahead/behind was not 0/0')}
                } else {
                    $status='POST_PUSH_REMOTE_MISMATCH'
                    $errors.Add("post-push remote SHA was $remoteAfter, expected $TestedSha")
                }
            }
        }
    } elseif($Push -and $errors.Count -gt 0) {
        $status='PUSH_BLOCKED_BY_CHECKS'
    }
} catch {
    $errors.Add($_.Exception.Message)
    if($Push){$status='PUSH_BLOCKED_BY_CHECKS'}else{$status='CHECK_FAILED'}
}

$completionEnded=Get-RemediationUtcNow
$finalize=[pscustomobject][ordered]@{
    schemaVersion=1
    evidenceKind='completion'
    runId=$runInfo.runId
    mode=$(if($Push){'push'}else{'check'})
    status=$status
    startedUtc=Format-RemediationUtc $completionStarted
    startedKorea=Format-RemediationKoreaTime $completionStarted
    endedUtc=Format-RemediationUtc $completionEnded
    endedKorea=Format-RemediationKoreaTime $completionEnded
    candidateSha=$head
    candidateTree=$tree
    expectedRemoteBaseSha=$ExpectedRemoteBaseSha
    implementationRef=[pscustomobject]@{remote=$RemoteName;name=$ImplementationRef;before=$remoteBefore;after=$remoteAfter}
    reviewRef=[pscustomobject]@{name=$ReviewRef;recordedTip=$RecordedReviewTip;liveTip=$reviewLive;relation=$(if($reviewLive -eq $RecordedReviewTip){'identical'}elseif($reviewAncestor){'forward_moved'}else{'diverged_or_behind'});semanticCompatibility='caller_or_reviewer_owned'}
    aheadBehind=$aheadBehind
    prePushAuthority=$prePushAuthority
    checks=@($checks.ToArray())
    errors=@($errors.ToArray())
    requiredGateIds=@($RequiredGateIds)
    requiredAcceptanceCheckIds=@($RequiredAcceptanceCheckIds)
    verificationEvidencePath=$VerificationEvidencePath
    acceptanceEvidencePath=$AcceptanceEvidencePath
    pushAttempted=$pushAttempted
    pushSourceObjectId=$pushSourceObjectId
    pushRefspec=$(if($pushAttempted){$pushSourceObjectId + ':refs/heads/' + $ImplementationRef}else{$null})
    pushCommand=$(if($pushAttempted){'git push ' + $RemoteName + ' ' + $pushSourceObjectId + ':refs/heads/' + $ImplementationRef}else{$null})
    pushExitCode=$(if($null -ne $pushResult){$pushResult.exitCode}else{$null})
    pushStdoutPath=$(if($null -ne $pushResult){$pushResult.stdoutPath}else{$null})
    pushStderrPath=$(if($null -ne $pushResult){$pushResult.stderrPath}else{$null})
    evidenceDirectory=$runDirectory
    forceOrRewritePathPresent=$false
    cleanVerdict='not_provided_by_completion_tool'
    semanticVerdict='not_provided_by_completion_tool'
}
Write-RemediationJson -Path (Join-Path $runDirectory 'finalize.json') -Value $finalize
Write-Output ('COMPLETION_STATUS=' + $status)
Write-Output ('FINALIZE_JSON=' + (Join-Path $runDirectory 'finalize.json'))
Write-Output ('EVIDENCE_DIRECTORY=' + $runDirectory)
if($errors.Count -gt 0){foreach($message in $errors){Write-Output ('BLOCK=' + $message)}}
if($status -notin @('CHECK_PASS','ALREADY_PUSHED_EXACT_SHA','PUSHED_AND_VERIFIED')){exit 1}

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
    $checks.Add([pscustomobject]@{ name='tracked_tree'; clean=$tracked.clean; trackedStatus=$tracked.trackedStatus; pass=$tracked.clean })
    if(-not $tracked.clean){$errors.Add('tracked worktree is not clean at the tested SHA')}

    $verificationPath=Assert-IgnoredEvidencePath -RepoFull $repoFull -Path $VerificationEvidencePath -LogDirectory $runDirectory -Name 'VerificationEvidencePath'
    $verification=Get-Content -LiteralPath $verificationPath -Raw | ConvertFrom-Json
    $verificationPass=($verification.evidenceKind -eq 'exact_source_verification' -and $verification.status -eq 'PASS' -and $verification.candidateSha -eq $TestedSha -and $verification.candidateTree -eq $tree -and $verification.scopeWidening -eq $false)
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
        if($remoteBefore -eq $TestedSha){
            $remoteAfter=$remoteBefore
            $aheadBehind=[pscustomobject]@{leftOnly=0;rightOnly=0}
            $status='ALREADY_PUSHED_EXACT_SHA'
        } else {
            $pushAttempted=$true
            $pushResult=Invoke-RemediationGit -RepoPath $repoFull -ArgumentList @('push', $RemoteName, ('HEAD:refs/heads/' + $ImplementationRef)) -LogDirectory (Join-Path $runDirectory 'push-logs') -Name 'git-normal-fast-forward-push' -TimeoutSeconds 180
            if($pushResult.timedOut -or $pushResult.exitCode -ne 0){
                $status='PUSH_REJECTED_NO_RECONCILIATION'
                $errors.Add("normal fast-forward push failed (exit $($pushResult.exitCode)); no reconciliation or retry was attempted")
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
    checks=@($checks.ToArray())
    errors=@($errors.ToArray())
    requiredGateIds=@($RequiredGateIds)
    requiredAcceptanceCheckIds=@($RequiredAcceptanceCheckIds)
    verificationEvidencePath=$VerificationEvidencePath
    acceptanceEvidencePath=$AcceptanceEvidencePath
    pushAttempted=$pushAttempted
    pushCommand=$(if($pushAttempted){'git push <remote> HEAD:refs/heads/<authorized-ref>'}else{$null})
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

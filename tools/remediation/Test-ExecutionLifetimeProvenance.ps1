[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepoPath,
    [Parameter(Mandatory)][string]$ExpectedSha
)

. (Join-Path $PSScriptRoot 'Remediation.Common.ps1')

function Invoke-ToolingGit {
    param([AllowNull()][string]$RepoPath, [Parameter(Mandatory)][string[]]$Arguments)
    $git = Resolve-RemediationGit
    $allArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($RepoPath)) { $allArguments += @('-C', $RepoPath) }
    $allArguments += $Arguments
    $output = @(& $git @allArguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Fixture git command failed ($exitCode): git $($allArguments -join ' ')`n$($output -join "`n")"
    }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Get-ToolingRemoteRefSha {
    param([Parameter(Mandatory)]$Fixture, [Parameter(Mandatory)][string]$Branch)
    $result = Invoke-ToolingGit -RepoPath $Fixture.path -Arguments @('ls-remote', '--exit-code', 'origin', ('refs/heads/' + $Branch))
    $match = [Regex]::Match($result, '(?m)^([0-9a-fA-F]{40,64})\s+refs/heads/')
    if (-not $match.Success) { throw "Fixture remote ref response was ambiguous for $Branch." }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Write-ToolingUtf8 {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][AllowEmptyString()][string]$Content)
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding($false)))
}

function New-ToolingFixture {
    param([Parameter(Mandatory)][string]$Name)
    $fixturePath = Join-Path $script:fixturesRoot $Name
    New-Item -ItemType Directory -Path $fixturePath -Force | Out-Null
    $hooksPath = Join-Path $fixturePath 'empty-hooks'
    New-Item -ItemType Directory -Path $hooksPath -Force | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('init', '--quiet', '--initial-branch=main') | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('config', 'user.name', 'YTDLnisX tooling acceptance') | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('config', 'user.email', 'tooling-acceptance@example.invalid') | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('config', 'core.hooksPath', $hooksPath) | Out-Null

    $baseline = "committed-candidate-value`n"
    Write-ToolingUtf8 -Path (Join-Path $fixturePath '.gitignore') -Content "build/`nlocal.properties`n"
    Write-ToolingUtf8 -Path (Join-Path $fixturePath 'behavior.cfg') -Content $baseline
    $fakeGradle = @'
$ErrorActionPreference = 'Stop'
$control = [Environment]::GetEnvironmentVariable('YTDLNISX_TOOLING_ACCEPTANCE_CONTROL_ROOT')
if (-not [string]::IsNullOrWhiteSpace($control)) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $control 'gate-started'), 'started', $encoding)
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not (Test-Path -LiteralPath (Join-Path $control 'mutation-active') -PathType Leaf)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Timed out waiting for the synchronized source mutation.' }
        Start-Sleep -Milliseconds 40
    }
}
$workingDirectory = (Get-Location).Path
$behaviorPath = Join-Path $workingDirectory 'behavior.cfg'
$behavior = [System.IO.File]::ReadAllText($behaviorPath)
if (-not [string]::IsNullOrWhiteSpace($control)) {
    $record = [pscustomobject]@{ behavior = $behavior; workingDirectory = $workingDirectory; behaviorPath = $behaviorPath }
    [System.IO.File]::WriteAllText((Join-Path $control 'consumed.json'), (ConvertTo-Json -InputObject $record -Compress), (New-Object System.Text.UTF8Encoding($false)))
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not (Test-Path -LiteralPath (Join-Path $control 'source-restored') -PathType Leaf)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Timed out waiting for the synchronized source restoration.' }
        Start-Sleep -Milliseconds 40
    }
}
Write-Output 'BUILD SUCCESSFUL'
exit 0
'@
    Write-ToolingUtf8 -Path (Join-Path $fixturePath 'fake-gradle.ps1') -Content $fakeGradle
    $batch = "@echo off`r`npowershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0fake-gradle.ps1`" %*`r`nexit /b %ERRORLEVEL%`r`n"
    Write-ToolingUtf8 -Path (Join-Path $fixturePath 'gradlew.bat') -Content $batch
    $alternate = @'
$marker = [Environment]::GetEnvironmentVariable('YTDLNISX_TOOLING_ACCEPTANCE_ALT_MARKER')
if (-not [string]::IsNullOrWhiteSpace($marker)) {
    [System.IO.File]::WriteAllText($marker, 'alternate-launched', (New-Object System.Text.UTF8Encoding($false)))
}
$demoRoot = [Environment]::GetEnvironmentVariable('YTDLNISX_REMEDIATION_DEMO_RESULT_ROOT')
if (-not [string]::IsNullOrWhiteSpace($demoRoot)) {
    New-Item -ItemType Directory -Path $demoRoot -Force | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $demoRoot 'fake-ran'), 'demo', (New-Object System.Text.UTF8Encoding($false)))
}
Write-Output 'BUILD SUCCESSFUL'
exit 0
'@
    Write-ToolingUtf8 -Path (Join-Path $fixturePath 'fake-alternate.ps1') -Content $alternate
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('add', '--all') | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('commit', '--quiet', '-m', 'synthetic remediation candidate') | Out-Null
    $sha = Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('rev-parse', 'HEAD')
    $tree = Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('rev-parse', 'HEAD^{tree}')

    $barePath = Join-Path $script:fixturesRoot ($Name + '-origin.git')
    Invoke-ToolingGit -RepoPath $null -Arguments @('init', '--quiet', '--bare', '--initial-branch=main', $barePath) | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('remote', 'add', 'origin', $barePath) | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('push', '--quiet', 'origin', 'HEAD:refs/heads/implementation') | Out-Null
    Invoke-ToolingGit -RepoPath $fixturePath -Arguments @('push', '--quiet', 'origin', 'HEAD:refs/heads/review') | Out-Null
    return [pscustomobject]@{
        path = $fixturePath
        sha = $sha
        tree = $tree
        baseline = $baseline
        behaviorPath = Join-Path $fixturePath 'behavior.cfg'
        alternatePath = Join-Path $fixturePath 'fake-alternate.ps1'
        evidenceRoot = Join-Path $fixturePath 'build\remediation-agent'
        remotePath = $barePath
    }
}

function New-PushCandidateFixture {
    param([Parameter(Mandatory)][string]$Name)
    $fixture = New-ToolingFixture -Name $Name
    $baseSha = $fixture.sha
    Invoke-ToolingGit -RepoPath $fixture.path -Arguments @('commit', '--quiet', '--allow-empty', '-m', 'synthetic exact push candidate') | Out-Null
    $fixture.sha = Invoke-ToolingGit -RepoPath $fixture.path -Arguments @('rev-parse', 'HEAD')
    $fixture.tree = Invoke-ToolingGit -RepoPath $fixture.path -Arguments @('rev-parse', 'HEAD^{tree}')
    $fixture | Add-Member -NotePropertyName expectedRemoteBaseSha -NotePropertyValue $baseSha
    $fixture | Add-Member -NotePropertyName recordedReviewTip -NotePropertyValue $baseSha
    return $fixture
}

function New-EmptyRaceChild {
    param([Parameter(Mandatory)]$Fixture, [Parameter(Mandatory)][string]$Message)
    Invoke-ToolingGit -RepoPath $Fixture.path -Arguments @('commit', '--quiet', '--allow-empty', '-m', $Message) | Out-Null
    return Invoke-ToolingGit -RepoPath $Fixture.path -Arguments @('rev-parse', 'HEAD')
}

function Start-ToolingPowerShell {
    param(
        [Parameter(Mandatory)][string]$ScriptPath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [hashtable]$Environment = @{}
    )
    $powerShell = Join-Path $PSHOME 'powershell.exe'
    $allArguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = $powerShell
    $processInfo.Arguments = (($allArguments | ForEach-Object { ConvertTo-WindowsProcessArgument -Value ([string]$_) }) -join ' ')
    $processInfo.WorkingDirectory = $WorkingDirectory
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo
    $previousEnvironment = @{}
    try {
        foreach ($name in $Environment.Keys) {
            $environmentName = [string]$name
            $previousEnvironment[$environmentName] = [System.Environment]::GetEnvironmentVariable($environmentName, 'Process')
            [System.Environment]::SetEnvironmentVariable($environmentName, [string]$Environment[$name], 'Process')
        }
        if (-not $process.Start()) { throw "Unable to start PowerShell acceptance child: $ScriptPath" }
    } finally {
        foreach ($name in $previousEnvironment.Keys) {
            [System.Environment]::SetEnvironmentVariable([string]$name, $previousEnvironment[$name], 'Process')
        }
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    return [pscustomobject]@{ process = $process; stdoutTask = $stdoutTask; stderrTask = $stderrTask; scriptPath = $ScriptPath }
}

function Complete-ToolingPowerShell {
    param([Parameter(Mandatory)]$Running, [ValidateRange(1, 600)][int]$TimeoutSeconds = 120)
    if (-not $Running.process.WaitForExit($TimeoutSeconds * 1000)) {
        try { Stop-RemediationProcessTree -Process $Running.process } catch {}
        [void]$Running.process.WaitForExit(15000)
        $stdout = $(if ($Running.stdoutTask.IsCompleted) { $Running.stdoutTask.Result } else { '' })
        $stderr = $(if ($Running.stderrTask.IsCompleted) { $Running.stderrTask.Result } else { '' })
        $Running.process.Dispose()
        throw "PowerShell acceptance child timed out after $TimeoutSeconds seconds.`n$stdout`n$stderr"
    }
    $Running.process.WaitForExit()
    $result = [pscustomobject]@{
        exitCode = [int]$Running.process.ExitCode
        stdout = [string]$Running.stdoutTask.Result
        stderr = [string]$Running.stderrTask.Result
    }
    $Running.process.Dispose()
    return $result
}

function New-GitInterceptionShim {
    param([Parameter(Mandatory)][string]$Name)
    $shimRoot = Join-Path $script:processEvidenceRoot ('git-shim-' + $Name + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $shimRoot -Force | Out-Null
    $shimPath = Join-Path $shimRoot 'git.exe'
    $source = @'
using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;

public static class YtdlnisxGitAcceptanceShim
{
    private static bool IsTargetRepository(string[] args, string expectedPath)
    {
        if (args.Length < 2 || args[0] != "-C") return false;
        return String.Equals(
            Path.GetFullPath(args[1]).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
            Path.GetFullPath(expectedPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
            StringComparison.OrdinalIgnoreCase);
    }

    private static int NextCount(string path)
    {
        int count = 0;
        if (File.Exists(path)) Int32.TryParse(File.ReadAllText(path), NumberStyles.Integer, CultureInfo.InvariantCulture, out count);
        count++;
        File.WriteAllText(path, count.ToString(CultureInfo.InvariantCulture), new UTF8Encoding(false));
        return count;
    }

    private static void WaitAtBoundary(string root, string name)
    {
        string marker = Path.Combine(root, name + ".waiting");
        string release = Path.Combine(root, name + ".release");
        File.WriteAllText(marker, "waiting", new UTF8Encoding(false));
        DateTime deadline = DateTime.UtcNow.AddSeconds(90);
        while (!File.Exists(release))
        {
            if (DateTime.UtcNow >= deadline) throw new TimeoutException("Acceptance race barrier timed out: " + name);
            Thread.Sleep(25);
        }
    }

    private static string QuoteArgument(string value)
    {
        if (value.Length > 0 && value.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0) return value;
        StringBuilder result = new StringBuilder();
        result.Append('"');
        int slashes = 0;
        for (int i = 0; i < value.Length; i++)
        {
            char current = value[i];
            if (current == '\\')
            {
                slashes++;
            }
            else if (current == '"')
            {
                result.Append('\\', slashes * 2 + 1);
                result.Append('"');
                slashes = 0;
            }
            else
            {
                result.Append('\\', slashes);
                slashes = 0;
                result.Append(current);
            }
        }
        result.Append('\\', slashes * 2);
        result.Append('"');
        return result.ToString();
    }

    private static string JoinArguments(string[] args)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < args.Length; i++)
        {
            if (i > 0) result.Append(' ');
            result.Append(QuoteArgument(args[i]));
        }
        return result.ToString();
    }

    public static int Main(string[] args)
    {
        try
        {
            string realGit = Environment.GetEnvironmentVariable("YTDLNISX_ACCEPTANCE_REAL_GIT");
            string root = Environment.GetEnvironmentVariable("YTDLNISX_ACCEPTANCE_SHIM_ROOT");
            string mode = Environment.GetEnvironmentVariable("YTDLNISX_ACCEPTANCE_SHIM_MODE");
            string repo = Environment.GetEnvironmentVariable("YTDLNISX_ACCEPTANCE_SHIM_REPO");
            if (String.IsNullOrWhiteSpace(realGit) || String.IsNullOrWhiteSpace(root) || String.IsNullOrWhiteSpace(repo))
                throw new InvalidOperationException("Git acceptance shim configuration is incomplete.");

            if (IsTargetRepository(args, repo) && args.Length >= 4 && args[2] == "rev-parse" && args[3] == "HEAD" && mode == "local-head")
            {
                if (NextCount(Path.Combine(root, "head.count")) == 2) WaitAtBoundary(root, "local-head");
            }

            if (IsTargetRepository(args, repo) && args.Length >= 6 && args[2] == "ls-remote")
            {
                string branch = args[args.Length - 1];
                if (mode == "destination" && branch == "refs/heads/implementation")
                {
                    if (NextCount(Path.Combine(root, "implementation.count")) == 2) WaitAtBoundary(root, "destination");
                }
                if (mode == "review" && branch == "refs/heads/review")
                {
                    if (NextCount(Path.Combine(root, "review.count")) == 2) WaitAtBoundary(root, "review");
                }
            }

            if (IsTargetRepository(args, repo) && args.Length >= 3 && args[2] == "push")
            {
                string pushLog = Environment.GetEnvironmentVariable("YTDLNISX_ACCEPTANCE_PUSH_LOG");
                if (!String.IsNullOrWhiteSpace(pushLog))
                    File.AppendAllText(pushLog, String.Join("\t", args) + Environment.NewLine, new UTF8Encoding(false));
            }

            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = realGit;
            start.Arguments = JoinArguments(args);
            start.WorkingDirectory = Environment.CurrentDirectory;
            start.UseShellExecute = false;
            using (Process process = Process.Start(start))
            {
                if (process == null) return 126;
                process.WaitForExit();
                return process.ExitCode;
            }
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine(exception.ToString());
            return 126;
        }
    }
}
'@
    Add-Type -TypeDefinition $source -OutputAssembly $shimPath -OutputType ConsoleApplication -ErrorAction Stop
    return [pscustomobject]@{ directory = $shimRoot; path = $shimPath; controlRoot = $shimRoot }
}

function Get-GitInterceptionEnvironment {
    param(
        [Parameter(Mandatory)]$Shim,
        [Parameter(Mandatory)][string]$Mode,
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][string]$ControlRoot,
        [Parameter(Mandatory)][string]$PushLogPath
    )
    return @{
        PATH = ($Shim.directory + ';' + $env:PATH)
        YTDLNISX_ACCEPTANCE_REAL_GIT = (Resolve-RemediationGit)
        YTDLNISX_ACCEPTANCE_SHIM_ROOT = $ControlRoot
        YTDLNISX_ACCEPTANCE_SHIM_MODE = $Mode
        YTDLNISX_ACCEPTANCE_SHIM_REPO = $Fixture.path
        YTDLNISX_ACCEPTANCE_PUSH_LOG = $PushLogPath
    }
}

function Wait-ToolingMarker {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Running, [ValidateRange(1, 120)][int]$TimeoutSeconds = 60)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        if ($Running.process.HasExited) {
            $result = Complete-ToolingPowerShell -Running $Running -TimeoutSeconds 5
            throw "Verification process exited before marker '$([System.IO.Path]::GetFileName($Path))' (exit $($result.exitCode)).`n$($result.stdout)`n$($result.stderr)"
        }
        if ([DateTime]::UtcNow -ge $deadline) { throw "Timed out waiting for acceptance marker: $Path" }
        Start-Sleep -Milliseconds 40
    }
}

function Save-ChildEvidence {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)]$Result)
    $safe = [Regex]::Replace($Name, '[^A-Za-z0-9_.-]', '_')
    Write-ToolingUtf8 -Path (Join-Path $script:processEvidenceRoot ($safe + '.stdout.log')) -Content ([string]$Result.stdout)
    Write-ToolingUtf8 -Path (Join-Path $script:processEvidenceRoot ($safe + '.stderr.log')) -Content ([string]$Result.stderr)
}

function Invoke-VerificationChild {
    param(
        [Parameter(Mandatory)]$Fixture,
        [string[]]$ExtraArguments = @(),
        [hashtable]$Environment = @{},
        [string]$Name = 'verification'
    )
    $arguments = @('-RepoPath', $Fixture.path, '-ExpectedSha', $Fixture.sha, '-CompileTask', ':app:compileDebugKotlin', '-GateTimeoutSeconds', '90', '-EvidenceRoot', $Fixture.evidenceRoot) + $ExtraArguments
    $running = Start-ToolingPowerShell -ScriptPath $script:verificationScript -Arguments $arguments -WorkingDirectory $Fixture.path -Environment $Environment
    $result = Complete-ToolingPowerShell -Running $running -TimeoutSeconds 150
    Save-ChildEvidence -Name $Name -Result $result
    return $result
}

function Get-VerificationJsonPath {
    param([Parameter(Mandatory)]$Result)
    $match = [Regex]::Match([string]$Result.stdout, '(?m)^VERIFICATION_JSON=(.+)$')
    if (-not $match.Success) { throw "Verification child did not emit a JSON evidence path.`n$($Result.stdout)`n$($Result.stderr)" }
    return $match.Groups[1].Value.Trim()
}

function Finish-CompleteChild {
    param([Parameter(Mandatory)]$Running, [Parameter(Mandatory)][string]$Name, [int]$TimeoutSeconds = 120)
    $result = Complete-ToolingPowerShell -Running $Running -TimeoutSeconds $TimeoutSeconds
    Save-ChildEvidence -Name $Name -Result $result
    $match = [Regex]::Match([string]$result.stdout, '(?m)^FINALIZE_JSON=(.+)$')
    $finalize = $null
    if ($match.Success -and (Test-Path -LiteralPath $match.Groups[1].Value.Trim() -PathType Leaf)) {
        $finalize = Get-Content -LiteralPath $match.Groups[1].Value.Trim() -Raw | ConvertFrom-Json
    }
    return [pscustomobject]@{ result = $result; finalize = $finalize }
}

function Invoke-CompleteChild {
    param(
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][string]$VerificationPath,
        [string]$Name = 'completion',
        [switch]$Push,
        [string]$ForwardReviewAdvanceAcknowledgement,
        [hashtable]$Environment = @{},
        [switch]$StartOnly
    )
    $gateId = 'compile::app:compileDebugKotlin'
    $expectedBase = $(if ($Fixture.PSObject.Properties.Name -contains 'expectedRemoteBaseSha') { $Fixture.expectedRemoteBaseSha } else { $Fixture.sha })
    $recordedReviewTip = $(if ($Fixture.PSObject.Properties.Name -contains 'recordedReviewTip') { $Fixture.recordedReviewTip } else { $Fixture.sha })
    $arguments = @(
        '-RepoPath', $Fixture.path,
        '-RemoteName', 'origin',
        '-ImplementationRef', 'implementation',
        '-ExpectedRemoteBaseSha', $expectedBase,
        '-TestedSha', $Fixture.sha,
        '-VerificationEvidencePath', $VerificationPath,
        '-RequiredGateIds', $gateId,
        '-RecordedReviewTip', $recordedReviewTip,
        '-ReviewRef', 'review'
    )
    if ($Push) { $arguments += '-Push' }
    if (-not [string]::IsNullOrWhiteSpace($ForwardReviewAdvanceAcknowledgement)) {
        $arguments += @('-ForwardReviewAdvanceAcknowledgement', $ForwardReviewAdvanceAcknowledgement)
    }
    $running = Start-ToolingPowerShell -ScriptPath $script:completeScript -Arguments $arguments -WorkingDirectory $Fixture.path -Environment $Environment
    if ($StartOnly) { return $running }
    return Finish-CompleteChild -Running $running -Name $Name
}

function Add-AcceptanceCheck {
    param([Parameter(Mandatory)][string]$CheckId, [Parameter(Mandatory)][bool]$Pass, [Parameter(Mandatory)][string]$Detail)
    $status = $(if ($Pass) { 'PASS' } else { 'FAIL' })
    $script:acceptanceEvidence.checks += [pscustomobject]@{ checkId = $CheckId; status = $status; detail = $Detail }
    if (-not $Pass) { $script:acceptanceEvidence.status = 'FAIL' } else { $script:acceptanceEvidence.status = 'IN_PROGRESS' }
    Write-RemediationJson -Path $script:acceptanceEvidencePath -Value $script:acceptanceEvidence
    if (-not $Pass) { throw "Tooling acceptance failed: $CheckId" }
}

$repoFull = (Resolve-Path -LiteralPath $RepoPath).Path
$head = Invoke-ToolingGit -RepoPath $repoFull -Arguments @('rev-parse', 'HEAD')
if ($head -ne $ExpectedSha) { throw "Acceptance ExpectedSha mismatch: expected $ExpectedSha, observed $head" }
$tree = Invoke-ToolingGit -RepoPath $repoFull -Arguments @('rev-parse', 'HEAD^{tree}')
$runId = (Get-RemediationUtcNow).ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture) + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $repoFull ('build\remediation-agent\tooling-acceptance\' + $runId)
$fixtureToken = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$script:fixturesRoot = Join-Path (Split-Path -Parent $repoFull) ('fx-' + $fixtureToken)
if (Test-Path -LiteralPath $script:fixturesRoot) { throw 'Unique short-path fixture root already exists; refusing to reuse it.' }
$script:processEvidenceRoot = Join-Path $runRoot 'process-logs'
$script:verificationScript = Join-Path $PSScriptRoot 'Invoke-Verification.ps1'
$script:completeScript = Join-Path $PSScriptRoot 'Complete-Wave.ps1'
$script:acceptanceEvidencePath = Join-Path $runRoot 'acceptance.json'
New-Item -ItemType Directory -Path $script:fixturesRoot,$script:processEvidenceRoot -Force | Out-Null
$script:acceptanceEvidence = [pscustomobject][ordered]@{
    schemaVersion = 1
    evidenceKind = 'tooling_acceptance'
    status = 'IN_PROGRESS'
    candidateSha = $head
    candidateTree = $tree
    startedUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    endedUtc = $null
    checks = @()
    evidenceDirectory = $runRoot
    firstFailure = $null
}
Write-RemediationJson -Path $script:acceptanceEvidencePath -Value $script:acceptanceEvidence

try {
    $script:gitShim = New-GitInterceptionShim -Name 'exact-push-authority'

    $transientFixture = New-ToolingFixture -Name 'transient-mutation'
    $controlRoot = Join-Path $runRoot 'transient-control'
    New-Item -ItemType Directory -Path $controlRoot -Force | Out-Null
    $running = Start-ToolingPowerShell -ScriptPath $script:verificationScript -Arguments @(
        '-RepoPath', $transientFixture.path,
        '-ExpectedSha', $transientFixture.sha,
        '-CompileTask', ':app:compileDebugKotlin',
        '-GateTimeoutSeconds', '90',
        '-EvidenceRoot', $transientFixture.evidenceRoot
    ) -WorkingDirectory $transientFixture.path -Environment @{ YTDLNISX_TOOLING_ACCEPTANCE_CONTROL_ROOT = $controlRoot }
    $sourceMutated = $false
    $transientResult = $null
    $consumed = $null
    try {
        Wait-ToolingMarker -Path (Join-Path $controlRoot 'gate-started') -Running $running -TimeoutSeconds 60
        Write-ToolingUtf8 -Path $transientFixture.behaviorPath -Content "transient-worktree-value`n"
        $sourceMutated = $true
        Write-ToolingUtf8 -Path (Join-Path $controlRoot 'mutation-active') -Content 'active'
        Wait-ToolingMarker -Path (Join-Path $controlRoot 'consumed.json') -Running $running -TimeoutSeconds 60
        $consumed = Get-Content -LiteralPath (Join-Path $controlRoot 'consumed.json') -Raw | ConvertFrom-Json
        Write-ToolingUtf8 -Path $transientFixture.behaviorPath -Content $transientFixture.baseline
        $sourceMutated = $false
        Write-ToolingUtf8 -Path (Join-Path $controlRoot 'source-restored') -Content 'restored'
        $transientResult = Complete-ToolingPowerShell -Running $running -TimeoutSeconds 120
        Save-ChildEvidence -Name 'transient-mutation-verification' -Result $transientResult
    } finally {
        if ($sourceMutated) { Write-ToolingUtf8 -Path $transientFixture.behaviorPath -Content $transientFixture.baseline }
        if (-not (Test-Path -LiteralPath (Join-Path $controlRoot 'mutation-active'))) { Write-ToolingUtf8 -Path (Join-Path $controlRoot 'mutation-active') -Content 'release' }
        if (-not (Test-Path -LiteralPath (Join-Path $controlRoot 'source-restored'))) { Write-ToolingUtf8 -Path (Join-Path $controlRoot 'source-restored') -Content 'release' }
        if (-not $running.process.HasExited) {
            try { $transientResult = Complete-ToolingPowerShell -Running $running -TimeoutSeconds 120 } catch {}
        }
    }
    if ($null -eq $transientResult) { throw 'Synchronized transient-mutation verification produced no process result.' }
    $transientEvidencePath = Get-VerificationJsonPath -Result $transientResult
    $transientEvidence = Get-Content -LiteralPath $transientEvidencePath -Raw | ConvertFrom-Json
    $sourceState = Get-RemediationTrackedTreeState -RepoPath $transientFixture.path -CandidateSha $transientFixture.sha -LogDirectory $processEvidenceRoot
    $executionRunId = Split-Path -Leaf $transientEvidence.evidenceDirectory
    $expectedMaterializationRoot = Join-Path $transientFixture.path 'build\remediation-worktrees'
    $expectedMaterialization = [System.IO.Path]::GetFullPath((Join-Path $expectedMaterializationRoot $executionRunId))
    $observedWorkingDirectory = [System.IO.Path]::GetFullPath([string]$consumed.workingDirectory)
    $transientPass = (
        $transientResult.exitCode -eq 0 -and
        $transientEvidence.status -eq 'PASS' -and
        $sourceState.clean -and
        ([string]$consumed.behavior).TrimEnd("`r", "`n") -eq 'committed-candidate-value' -and
        ([string]$consumed.behavior).TrimEnd("`r", "`n") -ne 'transient-worktree-value' -and
        [string]::Equals($observedWorkingDirectory, $expectedMaterialization, [System.StringComparison]::OrdinalIgnoreCase) -and
        $transientEvidence.executionLifetime.status -eq 'PASS' -and
        $transientEvidence.executionLifetime.candidateSha -eq $transientFixture.sha -and
        $transientEvidence.executionLifetime.candidateTree -eq $transientFixture.tree -and
        $transientEvidence.executionLifetime.materializationRunId -eq $executionRunId -and
        $transientEvidence.gates[0].executionLifetime.provenancePass -eq $true
    )
    Add-AcceptanceCheck -CheckId 'transient_behavior_relevant_mutation_isolated' -Pass $transientPass -Detail 'The gate started after exact-tree observation, consumed the committed value from its detached materialization while the source worktree contained a synchronized transient edit, and passed only after the source was restored.'

    $untrackedFixture = New-ToolingFixture -Name 'persistent-untracked'
    $untrackedPath = Join-Path $untrackedFixture.path 'persistent-input.cfg'
    Write-ToolingUtf8 -Path $untrackedPath -Content 'preserve-me'
    $untrackedResult = Invoke-VerificationChild -Fixture $untrackedFixture -Name 'persistent-untracked-rejection'
    $untrackedPass = ($untrackedResult.exitCode -ne 0 -and $untrackedResult.stderr -match 'non-ignored untracked inputs' -and (Test-Path -LiteralPath $untrackedPath -PathType Leaf))
    Add-AcceptanceCheck -CheckId 'persistent_nonignored_untracked_rejected' -Pass $untrackedPass -Detail 'The persistent untracked input was rejected before a gate and remained present.'

    $dirtyFixture = New-ToolingFixture -Name 'tracked-dirty'
    Write-ToolingUtf8 -Path $dirtyFixture.behaviorPath -Content "tracked-dirty-value`n"
    try {
        $dirtyResult = Invoke-VerificationChild -Fixture $dirtyFixture -Name 'tracked-dirty-rejection'
    } finally {
        Write-ToolingUtf8 -Path $dirtyFixture.behaviorPath -Content $dirtyFixture.baseline
    }
    $dirtyPass = ($dirtyResult.exitCode -ne 0 -and $dirtyResult.stderr -match 'refuses tracked changes')
    Add-AcceptanceCheck -CheckId 'tracked_dirty_rejected' -Pass $dirtyPass -Detail 'Tracked source changes remained an early exact-source blocker; fixture source was restored without reset or cleanup.'

    $controlFixture = New-ToolingFixture -Name 'ignored-and-local-properties'
    $ignoredOutput = Join-Path $controlFixture.path 'build\remediation-agent\preserved-output.txt'
    $localProperties = Join-Path $controlFixture.path 'local.properties'
    $localSentinel = 'synthetic-local-properties-content-never-emit'
    Write-ToolingUtf8 -Path $ignoredOutput -Content 'ignored-output-control'
    Write-ToolingUtf8 -Path $localProperties -Content ($localSentinel + "`n")
    $controlResult = Invoke-VerificationChild -Fixture $controlFixture -Name 'ignored-output-local-properties-control'
    $controlEvidencePath = Get-VerificationJsonPath -Result $controlResult
    $controlEvidence = Get-Content -LiteralPath $controlEvidencePath -Raw | ConvertFrom-Json
    $controlState = Get-RemediationTrackedTreeState -RepoPath $controlFixture.path -CandidateSha $controlFixture.sha -LogDirectory $processEvidenceRoot
    $evidenceFiles = @(Get-ChildItem -LiteralPath (Split-Path -Parent $controlEvidencePath) -File -Recurse -Force)
    $sentinelEmitted = $false
    foreach ($evidenceFile in $evidenceFiles) {
        $textExtensions = @('.json', '.log', '.txt', '.bat', '.ps1', '.cfg')
        if ($textExtensions -contains $evidenceFile.Extension.ToLowerInvariant()) {
            if ([System.IO.File]::ReadAllText($evidenceFile.FullName).Contains($localSentinel)) { $sentinelEmitted = $true; break }
        }
    }
    $controlPass = ($controlResult.exitCode -eq 0 -and $controlEvidence.status -eq 'PASS' -and $controlState.clean -and -not $sentinelEmitted -and (Test-Path -LiteralPath $ignoredOutput) -and (Test-Path -LiteralPath $localProperties))
    Add-AcceptanceCheck -CheckId 'ignored_output_and_local_properties_allowed_without_content_emission' -Pass $controlPass -Detail 'Ignored build output and ignored local.properties did not block the exact candidate gate; the synthetic local.properties sentinel was absent from verification evidence.'

    $launcherFixture = New-ToolingFixture -Name 'launcher-controls'
    $alternateMarker = Join-Path $runRoot 'normal-alternate-launched'
    $alternateNormal = Invoke-VerificationChild -Fixture $launcherFixture -ExtraArguments @('-GradlePath', $launcherFixture.alternatePath) -Environment @{ YTDLNISX_TOOLING_ACCEPTANCE_ALT_MARKER = $alternateMarker } -Name 'normal-alternate-launcher-rejection'
    $normalRejectPass = ($alternateNormal.exitCode -ne 0 -and $alternateNormal.stderr -match 'accepts only the repository-local gradlew.bat' -and -not (Test-Path -LiteralPath $alternateMarker))
    Add-AcceptanceCheck -CheckId 'normal_launcher_is_canonical_and_alternate_rejected' -Pass $normalRejectPass -Detail 'Normal exact-source mode rejected the alternate launcher before it ran; successful normal fixture gates use the repository-local gradlew.bat from the candidate materialization.'

    $demoFixture = New-ToolingFixture -Name 'demo-launcher'
    $demoResult = Invoke-VerificationChild -Fixture $demoFixture -ExtraArguments @('-GradlePath', $demoFixture.alternatePath, '-ToolingDemoMode', '-DemoResultRoot', 'demo-results') -Name 'demo-launcher-control'
    $demoEvidencePath = Get-VerificationJsonPath -Result $demoResult
    $demoEvidence = Get-Content -LiteralPath $demoEvidencePath -Raw | ConvertFrom-Json
    $demoComplete = Invoke-CompleteChild -Fixture $demoFixture -VerificationPath $demoEvidencePath -Name 'demo-completion-rejection'
    $demoPass = ($demoResult.exitCode -eq 0 -and $demoEvidence.evidenceKind -eq 'tooling_demo' -and $demoEvidence.status -eq 'PASS' -and $demoComplete.result.exitCode -ne 0 -and $null -ne $demoComplete.finalize -and $demoComplete.finalize.status -notin @('CHECK_PASS', 'PUSHED_AND_VERIFIED', 'ALREADY_PUSHED_EXACT_SHA'))
    Add-AcceptanceCheck -CheckId 'demo_launcher_remains_demo_only_and_completion_rejects_it' -Pass $demoPass -Detail 'The alternate fake launcher ran only in ToolingDemoMode, emitted tooling_demo evidence, and Complete-Wave rejected the result as exact-source verification.'

    $validComplete = Invoke-CompleteChild -Fixture $transientFixture -VerificationPath $transientEvidencePath -Name 'exact-lifetime-completion-acceptance'
    $validCompletePass = ($validComplete.result.exitCode -eq 0 -and $null -ne $validComplete.finalize -and $validComplete.finalize.status -eq 'CHECK_PASS')
    $missingProofPath = Join-Path (Split-Path -Parent $transientEvidencePath) 'verification-without-lifetime-proof.json'
    $withoutProof = Get-Content -LiteralPath $transientEvidencePath -Raw | ConvertFrom-Json
    $withoutProof.executionLifetime = $null
    Write-ToolingUtf8 -Path $missingProofPath -Content (ConvertTo-Json -InputObject $withoutProof -Depth 40)
    $invalidComplete = Invoke-CompleteChild -Fixture $transientFixture -VerificationPath $missingProofPath -Name 'missing-lifetime-completion-rejection'
    $invalidCompletePass = ($invalidComplete.result.exitCode -ne 0 -and $null -ne $invalidComplete.finalize -and $invalidComplete.finalize.status -notin @('CHECK_PASS', 'PUSHED_AND_VERIFIED', 'ALREADY_PUSHED_EXACT_SHA'))
    Add-AcceptanceCheck -CheckId 'completion_requires_exact_lifetime_provenance' -Pass ($validCompletePass -and $invalidCompletePass) -Detail 'Complete-Wave accepted exact passing lifetime evidence for its tested SHA/tree and rejected a copy with the lifetime proof removed.'

    $invalidPolicyPath = Join-Path (Split-Path -Parent $transientEvidencePath) 'verification-with-invalid-lifetime-policy.json'
    $invalidPolicyEvidence = Get-Content -LiteralPath $transientEvidencePath -Raw | ConvertFrom-Json
    $invalidPolicyEvidence.executionLifetime.allowedWritableOutputs = 'unrestricted'
    Write-ToolingUtf8 -Path $invalidPolicyPath -Content (ConvertTo-Json -InputObject $invalidPolicyEvidence -Depth 40)
    $invalidPolicyComplete = Invoke-CompleteChild -Fixture $transientFixture -VerificationPath $invalidPolicyPath -Name 'invalid-lifetime-policy-rejection'
    $invalidPolicyPass = ($invalidPolicyComplete.result.exitCode -ne 0 -and $null -ne $invalidPolicyComplete.finalize -and $invalidPolicyComplete.finalize.status -notin @('CHECK_PASS', 'PUSHED_AND_VERIFIED', 'ALREADY_PUSHED_EXACT_SHA'))
    Add-AcceptanceCheck -CheckId 'completion_binds_lifetime_output_and_cleanup_policy' -Pass $invalidPolicyPass -Detail 'Complete-Wave rejected execution-lifetime evidence whose allowed writable output policy was altered.'

    $localRaceFixture = New-PushCandidateFixture -Name 'push-local-head-race'
    $localRaceVerification = Invoke-VerificationChild -Fixture $localRaceFixture -Name 'push-local-head-race-verification'
    $localRaceVerificationPath = Get-VerificationJsonPath -Result $localRaceVerification
    $localRaceChildSha = New-EmptyRaceChild -Fixture $localRaceFixture -Message 'concurrent local writer child'
    Invoke-ToolingGit -RepoPath $localRaceFixture.path -Arguments @('update-ref', 'refs/heads/main', $localRaceFixture.sha) | Out-Null
    $localRaceControl = Join-Path $runRoot 'push-local-head-race-control'
    New-Item -ItemType Directory -Path $localRaceControl -Force | Out-Null
    $localRacePushLog = Join-Path $localRaceControl 'push-commands.log'
    $localRaceEnvironment = Get-GitInterceptionEnvironment -Shim $script:gitShim -Mode 'local-head' -Fixture $localRaceFixture -ControlRoot $localRaceControl -PushLogPath $localRacePushLog
    $localRaceRunning = Invoke-CompleteChild -Fixture $localRaceFixture -VerificationPath $localRaceVerificationPath -Name 'push-local-head-race-completion' -Push -Environment $localRaceEnvironment -StartOnly
    try {
        Wait-ToolingMarker -Path (Join-Path $localRaceControl 'local-head.waiting') -Running $localRaceRunning -TimeoutSeconds 60
        Invoke-ToolingGit -RepoPath $localRaceFixture.path -Arguments @('update-ref', 'refs/heads/main', $localRaceChildSha) | Out-Null
    } finally {
        Write-ToolingUtf8 -Path (Join-Path $localRaceControl 'local-head.release') -Content 'continue'
    }
    $localRaceCompletion = Finish-CompleteChild -Running $localRaceRunning -Name 'push-local-head-race-completion'
    $localRaceRemote = Get-ToolingRemoteRefSha -Fixture $localRaceFixture -Branch 'implementation'
    $localRaceFinalHead = Invoke-ToolingGit -RepoPath $localRaceFixture.path -Arguments @('rev-parse', 'HEAD')
    $localRacePushLogText = $(if (Test-Path -LiteralPath $localRacePushLog) { Get-Content -LiteralPath $localRacePushLog -Raw } else { '' })
    $localRacePass = (
        $localRaceCompletion.result.exitCode -ne 0 -and
        $null -ne $localRaceCompletion.finalize -and
        $localRaceCompletion.finalize.status -eq 'PUSH_BLOCKED_BY_CHECKS' -and
        $localRaceCompletion.finalize.pushAttempted -eq $false -and
        $localRaceCompletion.finalize.prePushAuthority.localHead -eq $localRaceChildSha -and
        $localRaceCompletion.finalize.prePushAuthority.pass -eq $false -and
        $localRaceFinalHead -eq $localRaceChildSha -and
        $localRaceRemote -eq $localRaceFixture.expectedRemoteBaseSha -and
        [string]::IsNullOrWhiteSpace($localRacePushLogText)
    )
    Add-AcceptanceCheck -CheckId 'push_local_head_race_blocks_unverified_child_before_mutation' -Pass $localRacePass -Detail 'A synchronized local ref writer advanced X to empty child Y before the final HEAD observation; completion failed before Push, preserved local Y, and left the implementation remote at its recorded base.'

    $destinationRaceFixture = New-PushCandidateFixture -Name 'push-destination-race'
    $destinationRaceVerification = Invoke-VerificationChild -Fixture $destinationRaceFixture -Name 'push-destination-race-verification'
    $destinationRaceVerificationPath = Get-VerificationJsonPath -Result $destinationRaceVerification
    $destinationRaceChildSha = New-EmptyRaceChild -Fixture $destinationRaceFixture -Message 'concurrent destination writer child'
    Invoke-ToolingGit -RepoPath $destinationRaceFixture.path -Arguments @('update-ref', 'refs/heads/main', $destinationRaceFixture.sha) | Out-Null
    Invoke-ToolingGit -RepoPath $destinationRaceFixture.path -Arguments @('push', '--quiet', 'origin', ($destinationRaceChildSha + ':refs/heads/race-seed')) | Out-Null
    $destinationRaceControl = Join-Path $runRoot 'push-destination-race-control'
    New-Item -ItemType Directory -Path $destinationRaceControl -Force | Out-Null
    $destinationRacePushLog = Join-Path $destinationRaceControl 'push-commands.log'
    $destinationRaceEnvironment = Get-GitInterceptionEnvironment -Shim $script:gitShim -Mode 'destination' -Fixture $destinationRaceFixture -ControlRoot $destinationRaceControl -PushLogPath $destinationRacePushLog
    $destinationRaceRunning = Invoke-CompleteChild -Fixture $destinationRaceFixture -VerificationPath $destinationRaceVerificationPath -Name 'push-destination-race-completion' -Push -Environment $destinationRaceEnvironment -StartOnly
    try {
        Wait-ToolingMarker -Path (Join-Path $destinationRaceControl 'destination.waiting') -Running $destinationRaceRunning -TimeoutSeconds 60
        Invoke-ToolingGit -RepoPath $destinationRaceFixture.path -Arguments @('push', '--quiet', 'origin', ($destinationRaceChildSha + ':refs/heads/implementation')) | Out-Null
    } finally {
        Write-ToolingUtf8 -Path (Join-Path $destinationRaceControl 'destination.release') -Content 'continue'
    }
    $destinationRaceCompletion = Finish-CompleteChild -Running $destinationRaceRunning -Name 'push-destination-race-completion'
    $destinationRaceRemote = Get-ToolingRemoteRefSha -Fixture $destinationRaceFixture -Branch 'implementation'
    $destinationRacePushLogText = $(if (Test-Path -LiteralPath $destinationRacePushLog) { Get-Content -LiteralPath $destinationRacePushLog -Raw } else { '' })
    $destinationRacePass = (
        $destinationRaceCompletion.result.exitCode -ne 0 -and
        $null -ne $destinationRaceCompletion.finalize -and
        $destinationRaceCompletion.finalize.status -eq 'PUSH_BLOCKED_BY_CHECKS' -and
        $destinationRaceCompletion.finalize.pushAttempted -eq $false -and
        $destinationRaceCompletion.finalize.prePushAuthority.destinationSha -eq $destinationRaceChildSha -and
        $destinationRaceCompletion.finalize.prePushAuthority.pass -eq $false -and
        $destinationRaceRemote -eq $destinationRaceChildSha -and
        [string]::IsNullOrWhiteSpace($destinationRacePushLogText)
    )
    Add-AcceptanceCheck -CheckId 'push_destination_race_is_reobserved_before_mutation' -Pass $destinationRacePass -Detail 'A synchronized concurrent writer advanced the destination after its initial observation; final authority re-read observed the incompatible tip and completion did not invoke Push.'

    $reviewRaceFixture = New-PushCandidateFixture -Name 'push-review-race'
    $reviewRaceVerification = Invoke-VerificationChild -Fixture $reviewRaceFixture -Name 'push-review-race-verification'
    $reviewRaceVerificationPath = Get-VerificationJsonPath -Result $reviewRaceVerification
    $reviewRaceChildSha = New-EmptyRaceChild -Fixture $reviewRaceFixture -Message 'concurrent review checkpoint child'
    Invoke-ToolingGit -RepoPath $reviewRaceFixture.path -Arguments @('update-ref', 'refs/heads/main', $reviewRaceFixture.sha) | Out-Null
    Invoke-ToolingGit -RepoPath $reviewRaceFixture.path -Arguments @('push', '--quiet', 'origin', ($reviewRaceChildSha + ':refs/heads/review-seed')) | Out-Null
    $reviewRaceControl = Join-Path $runRoot 'push-review-race-control'
    New-Item -ItemType Directory -Path $reviewRaceControl -Force | Out-Null
    $reviewRacePushLog = Join-Path $reviewRaceControl 'push-commands.log'
    $reviewRaceEnvironment = Get-GitInterceptionEnvironment -Shim $script:gitShim -Mode 'review' -Fixture $reviewRaceFixture -ControlRoot $reviewRaceControl -PushLogPath $reviewRacePushLog
    $reviewRaceRunning = Invoke-CompleteChild -Fixture $reviewRaceFixture -VerificationPath $reviewRaceVerificationPath -Name 'push-review-race-completion' -Push -Environment $reviewRaceEnvironment -StartOnly
    try {
        Wait-ToolingMarker -Path (Join-Path $reviewRaceControl 'review.waiting') -Running $reviewRaceRunning -TimeoutSeconds 60
        Invoke-ToolingGit -RepoPath $reviewRaceFixture.path -Arguments @('push', '--quiet', 'origin', ($reviewRaceChildSha + ':refs/heads/review')) | Out-Null
    } finally {
        Write-ToolingUtf8 -Path (Join-Path $reviewRaceControl 'review.release') -Content 'continue'
    }
    $reviewRaceCompletion = Finish-CompleteChild -Running $reviewRaceRunning -Name 'push-review-race-completion'
    $reviewRaceRemote = Get-ToolingRemoteRefSha -Fixture $reviewRaceFixture -Branch 'implementation'
    $reviewRacePushLogText = $(if (Test-Path -LiteralPath $reviewRacePushLog) { Get-Content -LiteralPath $reviewRacePushLog -Raw } else { '' })
    $reviewRacePass = (
        $reviewRaceCompletion.result.exitCode -ne 0 -and
        $null -ne $reviewRaceCompletion.finalize -and
        $reviewRaceCompletion.finalize.status -eq 'PUSH_BLOCKED_BY_CHECKS' -and
        $reviewRaceCompletion.finalize.pushAttempted -eq $false -and
        $reviewRaceCompletion.finalize.prePushAuthority.liveReviewTip -eq $reviewRaceChildSha -and
        $reviewRaceCompletion.finalize.prePushAuthority.reviewRelation -eq 'forward_moved' -and
        $reviewRaceCompletion.finalize.prePushAuthority.pass -eq $false -and
        $reviewRaceRemote -eq $reviewRaceFixture.expectedRemoteBaseSha -and
        [string]::IsNullOrWhiteSpace($reviewRacePushLogText)
    )
    Add-AcceptanceCheck -CheckId 'push_review_race_requires_current_forward_tip_acknowledgement' -Pass $reviewRacePass -Detail 'A synchronized forward review movement after the earlier check was reobserved at the final boundary; without an acknowledgement naming that live tip, completion failed before Push.'

    $reviewAckControl = Join-Path $runRoot 'push-review-forward-ack-control'
    New-Item -ItemType Directory -Path $reviewAckControl -Force | Out-Null
    $reviewAckPushLog = Join-Path $reviewAckControl 'push-commands.log'
    $reviewAckEnvironment = Get-GitInterceptionEnvironment -Shim $script:gitShim -Mode 'record' -Fixture $reviewRaceFixture -ControlRoot $reviewAckControl -PushLogPath $reviewAckPushLog
    $reviewAckCompletion = Invoke-CompleteChild -Fixture $reviewRaceFixture -VerificationPath $reviewRaceVerificationPath -Name 'push-review-forward-ack-completion' -Push -ForwardReviewAdvanceAcknowledgement $reviewRaceChildSha -Environment $reviewAckEnvironment
    $reviewAckPass = (
        $reviewAckCompletion.result.exitCode -eq 0 -and
        $null -ne $reviewAckCompletion.finalize -and
        $reviewAckCompletion.finalize.status -eq 'PUSHED_AND_VERIFIED' -and
        $reviewAckCompletion.finalize.prePushAuthority.reviewRelation -eq 'forward_moved' -and
        $reviewAckCompletion.finalize.prePushAuthority.forwardReviewAcknowledgementNamesLiveTip -eq $true -and
        $reviewAckCompletion.finalize.pushSourceObjectId -eq $reviewRaceFixture.sha -and
        (Get-ToolingRemoteRefSha -Fixture $reviewRaceFixture -Branch 'implementation') -eq $reviewRaceFixture.sha
    )
    Add-AcceptanceCheck -CheckId 'compatible_forward_review_acknowledgement_remains_allowed' -Pass $reviewAckPass -Detail 'A compatible forward review tip remained usable when the explicit acknowledgement named the exact live tip; Push published only the tested SHA.'

    $pushControlFixture = New-PushCandidateFixture -Name 'push-exact-sha-control'
    $pushControlVerification = Invoke-VerificationChild -Fixture $pushControlFixture -Name 'push-exact-sha-control-verification'
    $pushControlVerificationPath = Get-VerificationJsonPath -Result $pushControlVerification
    $pushControlRoot = Join-Path $runRoot 'push-exact-sha-control'
    New-Item -ItemType Directory -Path $pushControlRoot -Force | Out-Null
    $pushControlLog = Join-Path $pushControlRoot 'push-commands.log'
    $pushControlEnvironment = Get-GitInterceptionEnvironment -Shim $script:gitShim -Mode 'record' -Fixture $pushControlFixture -ControlRoot $pushControlRoot -PushLogPath $pushControlLog
    $pushControlCompletion = Invoke-CompleteChild -Fixture $pushControlFixture -VerificationPath $pushControlVerificationPath -Name 'push-exact-sha-control-completion' -Push -Environment $pushControlEnvironment
    $pushRecord = Get-Content -LiteralPath $pushControlLog -Raw
    $exactRefspec = $pushControlFixture.sha + ':refs/heads/implementation'
    $pushControlPass = (
        $pushControlCompletion.result.exitCode -eq 0 -and
        $null -ne $pushControlCompletion.finalize -and
        $pushControlCompletion.finalize.status -eq 'PUSHED_AND_VERIFIED' -and
        $pushControlCompletion.finalize.pushAttempted -eq $true -and
        $pushControlCompletion.finalize.pushSourceObjectId -eq $pushControlFixture.sha -and
        $pushControlCompletion.finalize.pushRefspec -eq $exactRefspec -and
        $pushRecord.Contains($exactRefspec) -and
        -not $pushRecord.Contains('HEAD:refs/heads/implementation') -and
        $pushControlCompletion.finalize.implementationRef.after -eq $pushControlFixture.sha -and
        $pushControlCompletion.finalize.aheadBehind.leftOnly -eq 0 -and
        $pushControlCompletion.finalize.aheadBehind.rightOnly -eq 0
    )
    Add-AcceptanceCheck -CheckId 'normal_push_uses_exact_tested_object_and_verifies_equality' -Pass $pushControlPass -Detail 'With unchanged exact candidate X and recorded destination base, normal fast-forward Push used X as its immutable refspec source and verified the remote at X with 0/0.'

    $pushCountBeforeAlready = @((Get-Content -LiteralPath $pushControlLog) | Where-Object { $_ -match '\tpush\t' }).Count
    $alreadyCompletion = Invoke-CompleteChild -Fixture $pushControlFixture -VerificationPath $pushControlVerificationPath -Name 'already-pushed-exact-sha-control-completion' -Push -Environment $pushControlEnvironment
    $pushCountAfterAlready = @((Get-Content -LiteralPath $pushControlLog) | Where-Object { $_ -match '\tpush\t' }).Count
    $alreadyPass = (
        $alreadyCompletion.result.exitCode -eq 0 -and
        $null -ne $alreadyCompletion.finalize -and
        $alreadyCompletion.finalize.status -eq 'ALREADY_PUSHED_EXACT_SHA' -and
        $alreadyCompletion.finalize.pushAttempted -eq $false -and
        $alreadyCompletion.finalize.implementationRef.after -eq $pushControlFixture.sha -and
        $pushCountAfterAlready -eq $pushCountBeforeAlready -and
        (Get-ToolingRemoteRefSha -Fixture $pushControlFixture -Branch 'implementation') -eq $pushControlFixture.sha
    )
    Add-AcceptanceCheck -CheckId 'already_pushed_exact_sha_is_non_destructive' -Pass $alreadyPass -Detail 'A second Push completion observed remote X already exact, reported the established already-pushed state, and issued no additional Push command.'

    $script:acceptanceEvidence.status = 'PASS'
    $script:acceptanceEvidence.endedUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    Write-RemediationJson -Path $script:acceptanceEvidencePath -Value $script:acceptanceEvidence
    Write-Output 'ACCEPTANCE_STATUS=PASS'
    Write-Output ('ACCEPTANCE_JSON=' + $script:acceptanceEvidencePath)
} catch {
    $failureRecord = $_
    $failureException = $failureRecord.Exception
    $failureInvocation = $failureRecord.InvocationInfo
    $script:acceptanceEvidence.status = 'FAIL'
    $script:acceptanceEvidence.firstFailure = [pscustomobject][ordered]@{
        message = $failureException.Message
        exceptionType = $failureException.GetType().FullName
        scriptStackTrace = $failureRecord.ScriptStackTrace
        scriptName = $(if ($null -ne $failureInvocation) { $failureInvocation.ScriptName } else { $null })
        lineNumber = $(if ($null -ne $failureInvocation) { $failureInvocation.ScriptLineNumber } else { $null })
        positionMessage = $(if ($null -ne $failureInvocation) { $failureInvocation.PositionMessage } else { $null })
        recordedUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    }
    if (@($script:acceptanceEvidence.checks | Where-Object { $_.status -eq 'FAIL' }).Count -eq 0) {
        $script:acceptanceEvidence.checks += [pscustomobject]@{ checkId = 'acceptance_harness_execution'; status = 'FAIL'; detail = $failureException.Message; scriptStackTrace = $failureRecord.ScriptStackTrace; positionMessage = $(if ($null -ne $failureInvocation) { $failureInvocation.PositionMessage } else { $null }) }
    }
    $script:acceptanceEvidence.endedUtc = Format-RemediationUtc (Get-RemediationUtcNow)
    Write-RemediationJson -Path $script:acceptanceEvidencePath -Value $script:acceptanceEvidence
    Write-Output 'ACCEPTANCE_STATUS=FAIL'
    Write-Output ('ACCEPTANCE_JSON=' + $script:acceptanceEvidencePath)
    Write-Output ('FIRST_FAILURE=' + $failureException.Message)
    Write-Output ('FIRST_FAILURE_POSITION=' + $(if ($null -ne $failureInvocation) { $failureInvocation.PositionMessage } else { '' }))
    exit 1
}

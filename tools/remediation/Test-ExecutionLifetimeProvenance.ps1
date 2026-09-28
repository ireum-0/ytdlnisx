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
$gradleInvocationLog = [Environment]::GetEnvironmentVariable('YTDLNISX_TOOLING_ACCEPTANCE_GRADLE_LOG')
if (-not [string]::IsNullOrWhiteSpace($gradleInvocationLog)) {
    [System.IO.File]::WriteAllText($gradleInvocationLog, 'started', (New-Object System.Text.UTF8Encoding($false)))
}
$gradleDelaySeconds = 0
if ([int]::TryParse([Environment]::GetEnvironmentVariable('YTDLNISX_TOOLING_ACCEPTANCE_GRADLE_DELAY_SECONDS'), [ref]$gradleDelaySeconds) -and $gradleDelaySeconds -gt 0) {
    Start-Sleep -Seconds $gradleDelaySeconds
}
$behaviorPath = Join-Path $workingDirectory 'behavior.cfg'
$behavior = [System.IO.File]::ReadAllText($behaviorPath)
$bootstrapCapturePath = [Environment]::GetEnvironmentVariable('YTDLNISX_TOOLING_ACCEPTANCE_BOOTSTRAP_CAPTURE')
if (-not [string]::IsNullOrWhiteSpace($bootstrapCapturePath)) {
    $bootstrapFile = Join-Path $workingDirectory 'local.properties'
    $bootstrapExists = Test-Path -LiteralPath $bootstrapFile -PathType Leaf
    $bootstrapByteCount = $null
    if ($bootstrapExists) { $bootstrapByteCount = [long](Get-Item -LiteralPath $bootstrapFile).Length }
    $git = Get-Command git.exe -ErrorAction Stop
    & $git.Source -C $workingDirectory check-ignore --quiet -- local.properties 2>$null
    $bootstrapIgnoreExitCode = $LASTEXITCODE
    $candidateHead = (& $git.Source -C $workingDirectory rev-parse HEAD).Trim()
    $candidateTree = (& $git.Source -C $workingDirectory rev-parse 'HEAD^{tree}').Trim()
    $bootstrapRecord = [pscustomobject]@{
        behavior = $behavior
        workingDirectory = $workingDirectory
        behaviorPath = $behaviorPath
        candidateHead = $candidateHead
        candidateTree = $candidateTree
        detachedLocalPropertiesExists = [bool]$bootstrapExists
        detachedLocalPropertiesByteCount = $bootstrapByteCount
        detachedLocalPropertiesIgnoreExitCode = [int]$bootstrapIgnoreExitCode
    }
    [System.IO.File]::WriteAllText($bootstrapCapturePath, (ConvertTo-Json -InputObject $bootstrapRecord -Compress), (New-Object System.Text.UTF8Encoding($false)))
}
if (-not [string]::IsNullOrWhiteSpace($control)) {
    $record = [pscustomobject]@{ behavior = $behavior; workingDirectory = $workingDirectory; behaviorPath = $behaviorPath }
    [System.IO.File]::WriteAllText((Join-Path $control 'consumed.json'), (ConvertTo-Json -InputObject $record -Compress), (New-Object System.Text.UTF8Encoding($false)))
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not (Test-Path -LiteralPath (Join-Path $control 'source-restored') -PathType Leaf)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Timed out waiting for the synchronized source restoration.' }
        Start-Sleep -Milliseconds 40
    }
}
$instrumentationClassArgument = @($args | Where-Object { $_ -like '-Pandroid.testInstrumentationRunnerArguments.class=*' } | Select-Object -First 1)
if ($instrumentationClassArgument.Count -gt 0) {
    $instrumentationClass = ([string]$instrumentationClassArgument[0]).Substring('-Pandroid.testInstrumentationRunnerArguments.class='.Length)
    $escapedClass = [System.Security.SecurityElement]::Escape($instrumentationClass)
    $resultRoot = Join-Path $workingDirectory 'app\build\outputs\androidTest-results\connected\debug'
    New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
    $junit = '<testsuite name="' + $escapedClass + '" tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + $escapedClass + '" name="synthetic_connected_gate" /></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $resultRoot 'TEST-synthetic-connected-gate.xml'), $junit, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'Starting 1 tests'
    Write-Output '1 tests completed'
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

                if (mode == "final-boundary")
                {
                    bool implementationUpdate = false;
                    for (int i = 3; i < args.Length; i++)
                    {
                        if (args[i].EndsWith(":refs/heads/implementation", StringComparison.Ordinal))
                        {
                            implementationUpdate = true;
                            break;
                        }
                    }
                    if (implementationUpdate && NextCount(Path.Combine(root, "final-boundary.push.count")) == 1)
                        WaitAtBoundary(root, "final-boundary");
                }
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

function Get-ExpectedVerificationArtifactToken {
    param([Parameter(Mandatory)][string]$GateId)
    $prefix = [Regex]::Replace($GateId, '[^A-Za-z0-9_.-]', '_')
    if ($prefix.Length -gt 8) { $prefix = $prefix.Substring(0, 8) }
    if ([string]::IsNullOrWhiteSpace($prefix)) { $prefix = 'gate' }
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try { $bytes = $hasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($GateId)) } finally { $hasher.Dispose() }
    $digest = ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant().Substring(0, 32)
    return $prefix + '-' + $digest
}

function Invoke-ConnectedVerificationChild {
    param(
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][string[]]$Classes,
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$DeviceSerial,
        [Parameter(Mandatory)][string]$AdbCallLog,
        [Parameter(Mandatory)][string]$Name,
        [string]$EvidenceRootOverride,
        [string]$GradleMarker,
        [ValidateRange(1, 300)][int]$WatchdogIntervalSeconds = 30,
        [ValidateRange(0, 30)][int]$GradleDelaySeconds = 0
    )
    $selectedEvidenceRoot = $(if ([string]::IsNullOrWhiteSpace($EvidenceRootOverride)) { $Fixture.evidenceRoot } else { $EvidenceRootOverride })
    $arguments = @(
        '-RepoPath', $Fixture.path,
        '-ExpectedSha', $Fixture.sha,
        '-ConnectedTestClass'
    ) + $Classes + @(
        '-AdbPath', $AdbPath,
        '-DeviceSerial', $DeviceSerial,
        '-ProbeTimeoutSeconds', '2',
        '-DeviceWatchdogIntervalSeconds', [string]$WatchdogIntervalSeconds,
        '-GateTimeoutSeconds', '60',
        '-EvidenceRoot', $selectedEvidenceRoot
    )
    $environment = @{ YTDLNISX_TOOLING_ACCEPTANCE_ADB_LOG = $AdbCallLog }
    if (-not [string]::IsNullOrWhiteSpace($GradleMarker)) { $environment.YTDLNISX_TOOLING_ACCEPTANCE_GRADLE_LOG = $GradleMarker }
    if ($GradleDelaySeconds -gt 0) { $environment.YTDLNISX_TOOLING_ACCEPTANCE_GRADLE_DELAY_SECONDS = [string]$GradleDelaySeconds }
    $running = Start-ToolingPowerShell -ScriptPath $script:verificationScript -Arguments $arguments -WorkingDirectory $Fixture.path -Environment $environment
    $result = Complete-ToolingPowerShell -Running $running -TimeoutSeconds 180
    Save-ChildEvidence -Name $Name -Result $result
    return $result
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

    $bootstrapFixture = New-ToolingFixture -Name 'detached-empty-local-properties-bootstrap'
    $bootstrapSourceSentinel = 'synthetic-source-local-properties-sentinel-never-emit'
    Write-ToolingUtf8 -Path (Join-Path $bootstrapFixture.path 'local.properties') -Content ($bootstrapSourceSentinel + "`n")
    $bootstrapCapturePath = Join-Path $runRoot 'detached-bootstrap-gate-observation.json'
    $bootstrapResult = Invoke-VerificationChild -Fixture $bootstrapFixture -Environment @{ YTDLNISX_TOOLING_ACCEPTANCE_BOOTSTRAP_CAPTURE = $bootstrapCapturePath } -Name 'detached-empty-local-properties-bootstrap'
    $bootstrapVerificationPath = Get-VerificationJsonPath -Result $bootstrapResult
    $bootstrapVerification = Get-Content -LiteralPath $bootstrapVerificationPath -Raw | ConvertFrom-Json
    $bootstrapGateObservation = Get-Content -LiteralPath $bootstrapCapturePath -Raw | ConvertFrom-Json
    $bootstrapProof = $bootstrapVerification.executionLifetime.detachedLocalPropertiesBootstrap
    $bootstrapGateProof = $bootstrapVerification.gates[0].executionLifetime
    $bootstrapRunId = Split-Path -Leaf $bootstrapVerification.evidenceDirectory
    $bootstrapMaterialization = [System.IO.Path]::GetFullPath((Join-Path (Join-Path $bootstrapFixture.path 'build\remediation-worktrees') $bootstrapRunId))
    $bootstrapComplete = Invoke-CompleteChild -Fixture $bootstrapFixture -VerificationPath $bootstrapVerificationPath -Name 'detached-empty-local-properties-bootstrap-completion'

    $bootstrapOldContractPath = Join-Path (Split-Path -Parent $bootstrapVerificationPath) 'verification-old-local-properties-provenance.json'
    $bootstrapOldContract = Get-Content -LiteralPath $bootstrapVerificationPath -Raw | ConvertFrom-Json
    $bootstrapOldContract.executionLifetime.contract = 'exact_candidate_execution_lifetime_v1'
    $bootstrapOldContract.executionLifetime | Add-Member -NotePropertyName localProperties -NotePropertyValue 'Not inspected, copied, or serialized by the wrapper; ignored source-worktree file remains outside the candidate tree.' -Force
    $bootstrapOldContract.executionLifetime.PSObject.Properties.Remove('sourceLocalProperties')
    $bootstrapOldContract.executionLifetime.PSObject.Properties.Remove('detachedLocalPropertiesBootstrap')
    Write-ToolingUtf8 -Path $bootstrapOldContractPath -Content (ConvertTo-Json -InputObject $bootstrapOldContract -Depth 50)
    $bootstrapOldContractCompletion = Invoke-CompleteChild -Fixture $bootstrapFixture -VerificationPath $bootstrapOldContractPath -Name 'detached-bootstrap-old-provenance-rejection'

    $bootstrapIncompletePath = Join-Path (Split-Path -Parent $bootstrapVerificationPath) 'verification-incomplete-local-properties-bootstrap.json'
    $bootstrapIncomplete = Get-Content -LiteralPath $bootstrapVerificationPath -Raw | ConvertFrom-Json
    $bootstrapIncomplete.executionLifetime.detachedLocalPropertiesBootstrap.generatedEmpty = $false
    Write-ToolingUtf8 -Path $bootstrapIncompletePath -Content (ConvertTo-Json -InputObject $bootstrapIncomplete -Depth 50)
    $bootstrapIncompleteCompletion = Invoke-CompleteChild -Fixture $bootstrapFixture -VerificationPath $bootstrapIncompletePath -Name 'detached-bootstrap-incomplete-provenance-rejection'

    $bootstrapEvidenceFiles = @(
        Get-ChildItem -LiteralPath $bootstrapVerification.evidenceDirectory -File -Recurse -Force
        Get-ChildItem -LiteralPath $bootstrapFixture.evidenceRoot -File -Recurse -Force
        Get-ChildItem -LiteralPath $script:processEvidenceRoot -File -Recurse -Force
        Get-Item -LiteralPath $bootstrapCapturePath
    )
    $bootstrapSentinelEmitted = $false
    foreach ($evidenceFile in $bootstrapEvidenceFiles) {
        if (@('.json', '.log', '.txt', '.bat', '.ps1', '.cfg') -contains $evidenceFile.Extension.ToLowerInvariant()) {
            if ([System.IO.File]::ReadAllText($evidenceFile.FullName).Contains($bootstrapSourceSentinel)) { $bootstrapSentinelEmitted = $true; break }
        }
    }
    $bootstrapGateWorkingDirectory = [System.IO.Path]::GetFullPath([string]$bootstrapGateObservation.workingDirectory)
    $bootstrapPass = (
        $bootstrapResult.exitCode -eq 0 -and
        $bootstrapVerification.status -eq 'PASS' -and
        $bootstrapVerification.candidateSha -eq $bootstrapFixture.sha -and
        $bootstrapVerification.candidateTree -eq $bootstrapFixture.tree -and
        $bootstrapVerification.executionLifetime.contract -eq 'exact_candidate_execution_lifetime_v2' -and
        $bootstrapVerification.executionLifetime.status -eq 'PASS' -and
        $bootstrapVerification.executionLifetime.sourceLocalProperties.inspected -eq $false -and
        $bootstrapVerification.executionLifetime.sourceLocalProperties.read -eq $false -and
        $bootstrapVerification.executionLifetime.sourceLocalProperties.copied -eq $false -and
        $bootstrapVerification.executionLifetime.sourceLocalProperties.serialized -eq $false -and
        $bootstrapVerification.executionLifetime.sourceLocalProperties.hashed -eq $false -and
        $bootstrapVerification.executionLifetime.sourceLocalProperties.derived -eq $false -and
        $bootstrapProof.generated -eq $true -and
        $bootstrapProof.generatedEmpty -eq $true -and
        $bootstrapProof.byteCount -eq 0 -and
        $bootstrapProof.ignored -eq $true -and
        $bootstrapProof.sourceValuesUsed -eq $false -and
        $bootstrapProof.materializationRunId -eq $bootstrapRunId -and
        [string]::Equals([string]$bootstrapProof.materializationPath, $bootstrapMaterialization, [System.StringComparison]::OrdinalIgnoreCase) -and
        $bootstrapGateObservation.behavior.TrimEnd("`r", "`n") -eq 'committed-candidate-value' -and
        $bootstrapGateObservation.candidateHead -eq $bootstrapFixture.sha -and
        $bootstrapGateObservation.candidateTree -eq $bootstrapFixture.tree -and
        $bootstrapGateObservation.detachedLocalPropertiesExists -eq $true -and
        $bootstrapGateObservation.detachedLocalPropertiesByteCount -eq 0 -and
        $bootstrapGateObservation.detachedLocalPropertiesIgnoreExitCode -eq 0 -and
        [string]::Equals($bootstrapGateWorkingDirectory, $bootstrapMaterialization, [System.StringComparison]::OrdinalIgnoreCase) -and
        $bootstrapGateProof.provenancePass -eq $true -and
        $bootstrapGateProof.detachedLocalPropertiesBootstrapPass -eq $true -and
        $bootstrapComplete.result.exitCode -eq 0 -and
        $null -ne $bootstrapComplete.finalize -and
        $bootstrapComplete.finalize.status -eq 'CHECK_PASS' -and
        $bootstrapOldContractCompletion.result.exitCode -ne 0 -and
        $null -ne $bootstrapOldContractCompletion.finalize -and
        $bootstrapOldContractCompletion.finalize.status -notin @('CHECK_PASS', 'PUSHED_AND_VERIFIED', 'ALREADY_PUSHED_EXACT_SHA') -and
        $bootstrapIncompleteCompletion.result.exitCode -ne 0 -and
        $null -ne $bootstrapIncompleteCompletion.finalize -and
        $bootstrapIncompleteCompletion.finalize.status -notin @('CHECK_PASS', 'PUSHED_AND_VERIFIED', 'ALREADY_PUSHED_EXACT_SHA') -and
        -not $bootstrapSentinelEmitted
    )
    Add-AcceptanceCheck -CheckId 'detached_empty_local_properties_bootstrap_non_exposure' -Pass $bootstrapPass -Detail 'The exact-candidate gate consumed the committed candidate from its run-bound detached worktree with a generated zero-byte ignored local.properties; no synthetic source sentinel appeared in evidence, completion accepted the v2 proof, and rejected old/incomplete proofs.'

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

    $finalBoundaryFixture = New-ToolingFixture -Name 'push-final-boundary-destination-race'
    $finalBoundaryBaseSha = $finalBoundaryFixture.sha
    $finalBoundaryIntermediateSha = New-EmptyRaceChild -Fixture $finalBoundaryFixture -Message 'intervening destination writer commit C'
    $finalBoundaryCandidateSha = New-EmptyRaceChild -Fixture $finalBoundaryFixture -Message 'tested candidate commit X'
    $finalBoundaryFixture.sha = $finalBoundaryCandidateSha
    $finalBoundaryFixture.tree = Invoke-ToolingGit -RepoPath $finalBoundaryFixture.path -Arguments @('rev-parse', 'HEAD^{tree}')
    $finalBoundaryFixture | Add-Member -NotePropertyName expectedRemoteBaseSha -NotePropertyValue $finalBoundaryBaseSha
    $finalBoundaryFixture | Add-Member -NotePropertyName recordedReviewTip -NotePropertyValue $finalBoundaryBaseSha
    $finalBoundaryParentOfC = Invoke-ToolingGit -RepoPath $finalBoundaryFixture.path -Arguments @('rev-parse', ($finalBoundaryIntermediateSha + '^'))
    $finalBoundaryParentOfX = Invoke-ToolingGit -RepoPath $finalBoundaryFixture.path -Arguments @('rev-parse', ($finalBoundaryCandidateSha + '^'))
    $finalBoundaryVerification = Invoke-VerificationChild -Fixture $finalBoundaryFixture -Name 'push-final-boundary-destination-race-verification'
    $finalBoundaryVerificationPath = Get-VerificationJsonPath -Result $finalBoundaryVerification
    $finalBoundaryControl = Join-Path $runRoot 'push-final-boundary-destination-race-control'
    New-Item -ItemType Directory -Path $finalBoundaryControl -Force | Out-Null
    $finalBoundaryPushLog = Join-Path $finalBoundaryControl 'push-commands.log'
    $finalBoundaryEnvironment = Get-GitInterceptionEnvironment -Shim $script:gitShim -Mode 'final-boundary' -Fixture $finalBoundaryFixture -ControlRoot $finalBoundaryControl -PushLogPath $finalBoundaryPushLog
    $finalBoundaryRunning = Invoke-CompleteChild -Fixture $finalBoundaryFixture -VerificationPath $finalBoundaryVerificationPath -Name 'push-final-boundary-destination-race-completion' -Push -Environment $finalBoundaryEnvironment -StartOnly
    $finalBoundaryDestinationAtBarrier = $null
    $finalBoundaryDestinationAfterWriter = $null
    try {
        Wait-ToolingMarker -Path (Join-Path $finalBoundaryControl 'final-boundary.waiting') -Running $finalBoundaryRunning -TimeoutSeconds 60
        $finalBoundaryDestinationAtBarrier = Get-ToolingRemoteRefSha -Fixture $finalBoundaryFixture -Branch 'implementation'
        Invoke-ToolingGit -RepoPath $finalBoundaryFixture.path -Arguments @('push', '--quiet', 'origin', ($finalBoundaryIntermediateSha + ':refs/heads/implementation')) | Out-Null
        $finalBoundaryDestinationAfterWriter = Get-ToolingRemoteRefSha -Fixture $finalBoundaryFixture -Branch 'implementation'
    } finally {
        Write-ToolingUtf8 -Path (Join-Path $finalBoundaryControl 'final-boundary.release') -Content 'continue'
    }
    $finalBoundaryCompletion = Finish-CompleteChild -Running $finalBoundaryRunning -Name 'push-final-boundary-destination-race-completion'
    $finalBoundaryRemote = Get-ToolingRemoteRefSha -Fixture $finalBoundaryFixture -Branch 'implementation'
    $finalBoundaryPushLogText = $(if (Test-Path -LiteralPath $finalBoundaryPushLog) { Get-Content -LiteralPath $finalBoundaryPushLog -Raw } else { '' })
    $finalBoundaryPushCount = @((Get-Content -LiteralPath $finalBoundaryPushLog) | Where-Object { $_ -match '\tpush\t' }).Count
    $finalBoundaryLease = '--force-with-lease=refs/heads/implementation:' + $finalBoundaryBaseSha
    $finalBoundaryRefspec = $finalBoundaryCandidateSha + ':refs/heads/implementation'
    $finalBoundaryRacePass = (
        $finalBoundaryParentOfC -eq $finalBoundaryBaseSha -and
        $finalBoundaryParentOfX -eq $finalBoundaryIntermediateSha -and
        $finalBoundaryDestinationAtBarrier -eq $finalBoundaryBaseSha -and
        $finalBoundaryDestinationAfterWriter -eq $finalBoundaryIntermediateSha -and
        $finalBoundaryCompletion.result.exitCode -ne 0 -and
        $null -ne $finalBoundaryCompletion.finalize -and
        $finalBoundaryCompletion.finalize.status -eq 'PUSH_REJECTED_NO_RECONCILIATION' -and
        $finalBoundaryCompletion.finalize.pushAttempted -eq $true -and
        $finalBoundaryCompletion.finalize.prePushAuthority.pass -eq $true -and
        $finalBoundaryCompletion.finalize.prePushAuthority.destinationSha -eq $finalBoundaryBaseSha -and
        $finalBoundaryCompletion.finalize.prePushAuthority.destinationIsAncestorOfTestedSha -eq $true -and
        $finalBoundaryCompletion.finalize.pushExpectedOldObjectId -eq $finalBoundaryBaseSha -and
        $finalBoundaryCompletion.finalize.pushLeaseArgument -eq $finalBoundaryLease -and
        $finalBoundaryCompletion.finalize.pushSourceObjectId -eq $finalBoundaryCandidateSha -and
        $finalBoundaryCompletion.finalize.pushRefspec -eq $finalBoundaryRefspec -and
        $finalBoundaryCompletion.finalize.pushExitCode -ne 0 -and
        $finalBoundaryPushLogText.Contains($finalBoundaryLease) -and
        $finalBoundaryPushLogText.Contains($finalBoundaryRefspec) -and
        $finalBoundaryPushCount -eq 1 -and
        $finalBoundaryRemote -eq $finalBoundaryIntermediateSha -and
        $finalBoundaryRemote -ne $finalBoundaryCandidateSha -and
        (Invoke-ToolingGit -RepoPath $finalBoundaryFixture.path -Arguments @('rev-parse', 'HEAD')) -eq $finalBoundaryCandidateSha
    )
    Add-AcceptanceCheck -CheckId 'push_final_boundary_destination_lease_rejects_intervening_writer' -Pass $finalBoundaryRacePass -Detail "The disposable B -> C -> X fixture accepted destination B at the final authority boundary, then a concurrent writer advanced the remote to C before the target update. The exact-ref lease for B rejected X, retained C, and recorded one push attempt with no retry. B=$finalBoundaryBaseSha C=$finalBoundaryIntermediateSha X=$finalBoundaryCandidateSha."

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
    $exactLease = '--force-with-lease=refs/heads/implementation:' + $pushControlFixture.expectedRemoteBaseSha
    $pushControlPass = (
        $pushControlCompletion.result.exitCode -eq 0 -and
        $null -ne $pushControlCompletion.finalize -and
        $pushControlCompletion.finalize.status -eq 'PUSHED_AND_VERIFIED' -and
        $pushControlCompletion.finalize.pushAttempted -eq $true -and
        $pushControlCompletion.finalize.pushSourceObjectId -eq $pushControlFixture.sha -and
        $pushControlCompletion.finalize.pushExpectedOldObjectId -eq $pushControlFixture.expectedRemoteBaseSha -and
        $pushControlCompletion.finalize.pushLeaseArgument -eq $exactLease -and
        $pushControlCompletion.finalize.pushRefspec -eq $exactRefspec -and
        $pushRecord.Contains($exactLease) -and
        $pushRecord.Contains($exactRefspec) -and
        -not $pushRecord.Contains('HEAD:refs/heads/implementation') -and
        $pushControlCompletion.finalize.implementationRef.after -eq $pushControlFixture.sha -and
        $pushControlCompletion.finalize.aheadBehind.leftOnly -eq 0 -and
        $pushControlCompletion.finalize.aheadBehind.rightOnly -eq 0
    )
    Add-AcceptanceCheck -CheckId 'normal_push_uses_exact_tested_object_and_verifies_equality' -Pass $pushControlPass -Detail 'With unchanged exact candidate X and recorded destination base B, the exact-ref B lease guarded the independently proven forward update from immutable source X; completion verified remote X with 0/0.'

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

    $longGateFixture = New-ToolingFixture -Name 'long-connected-gate-artifact-token'
    $longGateEvidenceRootBase = [System.IO.Path]::GetFullPath([string]$longGateFixture.evidenceRoot).TrimEnd('\')
    $longGateEvidenceRootTail = 'download01-direct-output-hook'
    $longGateEvidenceRootTargetLength = 147
    $longGateEvidenceRootPaddingLength = $longGateEvidenceRootTargetLength - $longGateEvidenceRootBase.Length - $longGateEvidenceRootTail.Length - 2
    if ($longGateEvidenceRootPaddingLength -lt 1 -or $longGateEvidenceRootPaddingLength -gt 220) {
        throw "Unable to construct the recorded long EvidenceRoot shape (base=$($longGateEvidenceRootBase.Length), padding=$longGateEvidenceRootPaddingLength)."
    }
    $longGateCustomEvidenceRoot = Join-Path (Join-Path $longGateEvidenceRootBase ('p' * $longGateEvidenceRootPaddingLength)) $longGateEvidenceRootTail
    if ($longGateCustomEvidenceRoot.Length -ne $longGateEvidenceRootTargetLength) { throw 'The custom EvidenceRoot fixture did not reach the exact whole-path boundary target.' }
    $longClassPrefix = 'com.ireum.ytdl.' + ('LongConnectedGateSegment' * 12)
    $longClasses = @(($longClassPrefix + 'A'), ($longClassPrefix + 'B'))
    $longGateIds = @($longClasses | ForEach-Object { 'connected:' + $_ })
    $syntheticAdbPath = Join-Path $runRoot 'synthetic-long-gate-adb.ps1'
    $syntheticAdb = @'
$ErrorActionPreference = 'Stop'
$arguments = @($args)
if ($arguments.Count -ge 2 -and $arguments[0] -eq '-s') { $arguments = @($arguments | Select-Object -Skip 2) }
$callLog = [Environment]::GetEnvironmentVariable('YTDLNISX_TOOLING_ACCEPTANCE_ADB_LOG')
if (-not [string]::IsNullOrWhiteSpace($callLog)) {
    [System.IO.File]::AppendAllText($callLog, (($args -join ' ') + [Environment]::NewLine), (New-Object System.Text.UTF8Encoding($false)))
}
$command = $arguments -join ' '
switch -Exact ($command) {
    'devices -l' { Write-Output 'List of devices attached'; Write-Output 'emulator-artifact device product:synthetic model:Acceptance_API_36'; exit 0 }
    'shell echo alive' { Write-Output 'alive'; exit 0 }
    'shell getprop sys.boot_completed' { Write-Output '1'; exit 0 }
    'shell cmd package path android' { Write-Output 'package:/system/framework/framework-res.apk'; exit 0 }
    'shell getprop ro.product.model' { Write-Output 'Acceptance API 36'; exit 0 }
    'shell getprop ro.build.version.sdk' { Write-Output '36'; exit 0 }
    'shell getprop ro.build.fingerprint' { Write-Output 'synthetic/acceptance/device:16/TEST/1:userdebug/test-keys'; exit 0 }
    'shell getprop ro.boot.qemu.avd_name' { Write-Output 'Synthetic_Artifact_Probe'; exit 0 }
    'shell getprop ro.kernel.qemu' { Write-Output '1'; exit 0 }
    'shell getprop persist.sys.timezone' { Write-Output 'Asia/Seoul'; exit 0 }
    'shell date +%s' { Write-Output ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()); exit 0 }
    'shell date +%Y-%m-%dT%H:%M:%S%z' {
        $now = [DateTimeOffset]::Now
        Write-Output ($now.ToString('yyyy-MM-ddTHH:mm:ss', [Globalization.CultureInfo]::InvariantCulture) + $now.ToString('zzz', [Globalization.CultureInfo]::InvariantCulture).Replace(':', ''))
        exit 0
    }
    default { Write-Output ''; exit 0 }
}
'@
    Write-ToolingUtf8 -Path $syntheticAdbPath -Content $syntheticAdb
    $syntheticAdbCallLog = Join-Path $runRoot 'synthetic-long-gate-adb-calls.log'
    $longGateRuns = @()
    $longGateRunClassIndexes = @(0, 0, 1)
    for ($runIndex = 0; $runIndex -lt $longGateRunClassIndexes.Count; $runIndex++) {
        $classIndex = $longGateRunClassIndexes[$runIndex]
        $longGateResult = Invoke-ConnectedVerificationChild -Fixture $longGateFixture -Classes @($longClasses[$classIndex]) -AdbPath $syntheticAdbPath -DeviceSerial 'emulator-artifact' -AdbCallLog $syntheticAdbCallLog -Name ('long-connected-gate-artifact-token-' + $runIndex) -EvidenceRootOverride $longGateCustomEvidenceRoot -WatchdogIntervalSeconds 1 -GradleDelaySeconds 2
        $longGateEvidencePath = Get-VerificationJsonPath -Result $longGateResult
        $longGateEvidence = Get-Content -LiteralPath $longGateEvidencePath -Raw | ConvertFrom-Json
        $longGateRuns += [pscustomobject]@{ classIndex = $classIndex; result = $longGateResult; evidence = $longGateEvidence }
    }
    $expectedLongTokens = @($longGateIds | ForEach-Object { Get-ExpectedVerificationArtifactToken -GateId $_ })
    $expectedLongTokensRepeat = @($longGateIds | ForEach-Object { Get-ExpectedVerificationArtifactToken -GateId $_ })
    $longHealthAndGateEndLogsExist = $true
    $boundedPaths = New-Object System.Collections.Generic.List[string]
    $mappingPass = $true
    foreach ($run in $longGateRuns) {
        $longGateEvidence = $run.evidence
        $classIndex = [int]$run.classIndex
        $record = @($longGateEvidence.gates)
        $mapping = @($longGateEvidence.scope.gateArtifactTokens | Where-Object { $_.gateId -eq $longGateIds[$classIndex] })
        if ($record.Count -ne 1 -or $mapping.Count -ne 1) { $mappingPass = $false; continue }
        $record = $record[0]
        $mappingPass = $mappingPass -and $run.result.exitCode -eq 0 -and $longGateEvidence.status -eq 'PASS' -and $longGateEvidence.scope.connectedTestClasses.Count -eq 1 -and $longGateEvidence.scope.connectedTestClasses[0] -eq $longClasses[$classIndex] -and $longGateEvidence.scope.gateOrder[0] -eq $longGateIds[$classIndex] -and $longGateEvidence.scope.artifactTokenPolicy -eq 'sanitized-prefix-8-plus-lowercase-sha256-128-v1' -and $longGateEvidence.scope.artifactTokenMaximumLength -eq 41
        $mappingPass = $mappingPass -and $record.gateId -eq $longGateIds[$classIndex] -and $record.requestedTestClass -eq $longClasses[$classIndex] -and $record.artifactToken -eq $expectedLongTokens[$classIndex] -and $mapping[0].artifactToken -eq $expectedLongTokens[$classIndex] -and $expectedLongTokens[$classIndex] -eq $expectedLongTokensRepeat[$classIndex] -and $expectedLongTokens[$classIndex].Length -le 41 -and $expectedLongTokens[$classIndex] -match '^[A-Za-z0-9_.-]+$'
        $mappingPass = $mappingPass -and $record.status -eq 'PASS' -and $record.executedTests -eq 1 -and $record.failureCount -eq 0 -and $record.errorCount -eq 0 -and $record.deviceHealth.healthy -eq $true
        $mappingPass = $mappingPass -and $record.watchdogSamples.Count -gt 0 -and $record.watchdogPressureSamples.Count -gt 0 -and $record.watchdogSamples[0].gateId -eq $longGateIds[$classIndex] -and $record.watchdogSamples[0].artifactToken -eq $record.artifactToken
        $mappingPass = $mappingPass -and $longGateEvidence.evidencePathBudget.accepted -and $longGateEvidence.evidencePathBudget.evidenceRootLength -eq $longGateEvidenceRootTargetLength -and $longGateEvidence.evidencePathBudget.evidenceRoot -eq $longGateCustomEvidenceRoot -and $longGateEvidence.evidencePathBudget.requiredMaximumPathLength -le 259 -and $longGateEvidence.evidencePathBudget.actualMaximumPathLength -le 259
        $deviceDirectory = Join-Path $longGateEvidence.evidenceDirectory 'h'
        $gateEndDirectory = Join-Path $longGateEvidence.evidenceDirectory 'w'
        $timeCorrelationRoot = Join-Path $longGateEvidence.evidenceDirectory 't'
        $timeCorrelationDirectory = Join-Path $timeCorrelationRoot $record.artifactToken
        $gateStartCorrelationJson = Join-Path $timeCorrelationRoot ($record.artifactToken + '.s')
        $gateEndCorrelationJson = Join-Path $timeCorrelationRoot ($record.artifactToken + '.e')
        $gateStartCorrelation = if (Test-Path -LiteralPath $gateStartCorrelationJson -PathType Leaf) { Get-Content -LiteralPath $gateStartCorrelationJson -Raw | ConvertFrom-Json } else { $null }
        $gateEndCorrelation = if (Test-Path -LiteralPath $gateEndCorrelationJson -PathType Leaf) { Get-Content -LiteralPath $gateEndCorrelationJson -Raw | ConvertFrom-Json } else { $null }
        $timeCorrelationLogs = @(Get-ChildItem -LiteralPath $timeCorrelationDirectory -Filter '*.log' -File -ErrorAction SilentlyContinue)
        $deviceProbeLog = @(Get-ChildItem -LiteralPath $deviceDirectory -Filter 'adb-devices-*.stdout.log' -File -ErrorAction SilentlyContinue)
        $gateEndProbeLog = @(Get-ChildItem -LiteralPath $gateEndDirectory -Filter 'adb-devices-*.stdout.log' -File -ErrorAction SilentlyContinue)
        if (-not (Test-Path -LiteralPath $deviceDirectory -PathType Container) -or $deviceProbeLog.Count -eq 0 -or -not (Test-Path -LiteralPath $gateEndDirectory -PathType Container) -or $gateEndProbeLog.Count -eq 0 -or -not (Test-Path -LiteralPath $timeCorrelationDirectory -PathType Container) -or $null -eq $gateStartCorrelation -or $null -eq $gateEndCorrelation -or $gateStartCorrelation.gateId -ne $longGateIds[$classIndex] -or $gateEndCorrelation.gateId -ne $longGateIds[$classIndex] -or $timeCorrelationLogs.Count -eq 0) {
            $longHealthAndGateEndLogsExist = $false
        }
        foreach ($path in (@($record.logPaths) + @($deviceProbeLog | ForEach-Object { $_.FullName }) + @($gateEndProbeLog | ForEach-Object { $_.FullName }) + @($timeCorrelationLogs | ForEach-Object { $_.FullName }) + @($gateStartCorrelationJson, $gateEndCorrelationJson))) { $boundedPaths.Add([string]$path) }
        $longGateEvidence.evidencePathBudget.accepted = $longGateEvidence.evidencePathBudget.accepted -and $longGateEvidence.evidencePathBudget.evidenceRootLength -eq $longGateEvidenceRootTargetLength -and $longGateEvidence.evidencePathBudget.evidenceRoot -eq $longGateCustomEvidenceRoot -and $longGateEvidence.evidencePathBudget.requiredMaximumPathLength -le 259 -and $longGateEvidence.evidencePathBudget.actualMaximumPathLength -le 259
        $artifactFiles = @(Get-ChildItem -LiteralPath $longGateEvidence.evidenceDirectory -File -Recurse -Force)
        foreach ($artifactFile in $artifactFiles) { $boundedPaths.Add([string]$artifactFile.FullName) }
    }
    $evidenceRootFull = [System.IO.Path]::GetFullPath([string]$longGateCustomEvidenceRoot).TrimEnd('\') + '\'
    $boundedPathsValid = ($boundedPaths.Count -gt 0)
    foreach ($path in $boundedPaths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or -not [System.IO.Path]::GetFullPath($path).StartsWith($evidenceRootFull, [System.StringComparison]::OrdinalIgnoreCase) -or $path.Length -ge 260) {
            $boundedPathsValid = $false
        }
        foreach ($component in ([System.IO.Path]::GetFullPath($path).Split([System.IO.Path]::DirectorySeparatorChar))) {
            if ($component.Length -gt 255) { $boundedPathsValid = $false }
        }
    }
    $syntheticAdbCalls = $(if (Test-Path -LiteralPath $syntheticAdbCallLog -PathType Leaf) { Get-Content -LiteralPath $syntheticAdbCallLog -Raw } else { '' })
    $syntheticAdbDeviceProbeCount = @($syntheticAdbCalls -split '\r?\n' | Where-Object { $_ -eq 'devices -l' }).Count
    $fullClassesPreserved = $mappingPass -and $longGateRuns[0].evidence.gates[0].artifactToken -eq $longGateRuns[1].evidence.gates[0].artifactToken -and $longGateRuns[0].evidence.gates[0].artifactToken -ne $longGateRuns[2].evidence.gates[0].artifactToken
    $longArtifactPass = (
        $longGateRuns.Count -eq 3 -and
        $longGateIds[0].Length -gt 255 -and
        $longGateIds[1].Length -gt 255 -and
        $fullClassesPreserved -and
        $mappingPass -and
        $longHealthAndGateEndLogsExist -and
        $boundedPathsValid -and
        $syntheticAdbDeviceProbeCount -ge 3
    )
    Add-AcceptanceCheck -CheckId 'long_connected_gate_ids_use_bounded_tokens_and_reach_synthetic_adb' -Pass $longArtifactPass -Detail 'Two >255-character semantic gate IDs remained unchanged in evidence; repeated execution mapped the same ID to the same <=41-character token, while a distinct ID mapped to a different token. All three runs materialized bounded health, gate-end, correlation, and Gradle paths after actual synthetic ADB probes. The synthetic fixture does not establish Android device health.'

    $brokenHealthAdbPath = Join-Path $runRoot 'synthetic-path-budget-health-failure-adb.ps1'
    $brokenHealthAdbText = $syntheticAdb.Replace("'shell cmd package path android' { Write-Output 'package:/system/framework/framework-res.apk'; exit 0 }", "'shell cmd package path android' { Write-Output ''; exit 1 }")
    if ($brokenHealthAdbText -eq $syntheticAdb) { throw 'Could not construct the synthetic PackageManager health-failure fixture.' }
    Write-ToolingUtf8 -Path $brokenHealthAdbPath -Content $brokenHealthAdbText
    $customHealthFailureCallLog = Join-Path $runRoot 'custom-root-health-failure-adb-calls.log'
    $customHealthGradleMarker = Join-Path $runRoot 'custom-root-health-failure-gradle-started'
    $customHealthFailure = Invoke-ConnectedVerificationChild -Fixture $longGateFixture -Classes @($longClasses[0]) -AdbPath $brokenHealthAdbPath -DeviceSerial 'emulator-artifact' -AdbCallLog $customHealthFailureCallLog -Name 'custom-root-stall-evidence-paths' -EvidenceRootOverride $longGateCustomEvidenceRoot -GradleMarker $customHealthGradleMarker
    $customHealthFailureVerificationPath = Get-VerificationJsonPath -Result $customHealthFailure
    $customHealthFailureEvidence = Get-Content -LiteralPath $customHealthFailureVerificationPath -Raw | ConvertFrom-Json
    $customHealthGate = @($customHealthFailureEvidence.gates)[0]
    $customHealthInfra = Get-Content -LiteralPath $customHealthGate.failureEvidencePath -Raw | ConvertFrom-Json
    $customHealthEvidenceFiles = @(Get-ChildItem -LiteralPath $customHealthFailureEvidence.evidenceDirectory -File -Recurse -Force)
    $customHealthStallLogs = @($customHealthEvidenceFiles | Where-Object { $_.DirectoryName -match '[\\/]stall-diagnostics$' -and $_.Extension -eq '.log' })
    $customHealthPathsValid = ($customHealthEvidenceFiles.Count -gt 0 -and $customHealthStallLogs.Count -gt 0)
    foreach ($artifactFile in $customHealthEvidenceFiles) {
        $artifactPath = [System.IO.Path]::GetFullPath($artifactFile.FullName)
        if ($artifactPath.Length -ge 260 -or -not $artifactPath.StartsWith(($longGateCustomEvidenceRoot.TrimEnd('\') + '\'), [System.StringComparison]::OrdinalIgnoreCase)) { $customHealthPathsValid = $false }
        foreach ($component in $artifactPath.Split([System.IO.Path]::DirectorySeparatorChar)) { if ($component.Length -gt 255) { $customHealthPathsValid = $false } }
    }
    $customRootBudgetPass = (
        $longGateRuns.Count -eq 3 -and
        @($longGateRuns | Where-Object { -not $_.evidence.evidencePathBudget.accepted -or $_.evidence.evidencePathBudget.requiredMaximumPathLength -gt 259 -or $_.evidence.evidencePathBudget.actualMaximumPathLength -gt 259 }).Count -eq 0 -and
        $longGateCustomEvidenceRoot.EndsWith('\download01-direct-output-hook', [System.StringComparison]::OrdinalIgnoreCase) -and
        $syntheticAdbDeviceProbeCount -ge 3 -and
        $customHealthFailure.exitCode -ne 0 -and
        $customHealthInfra.eventKind -eq 'connected_health_preflight_failure' -and
        $customHealthGate.deviceHealth.healthy -eq $false -and
        $customHealthPathsValid -and
        -not (Test-Path -LiteralPath $customHealthGradleMarker -PathType Leaf)
    )
    Add-AcceptanceCheck -CheckId 'whole_path_budget_uses_actual_custom_evidence_root_for_health_watchdog_stall_and_gate_artifacts' -Pass $customRootBudgetPass -Detail "The caller EvidenceRoot length was $($longGateCustomEvidenceRoot.Length); its download01-direct-output-hook shape reached synthetic ADB, the path budget covered the per-gate log/correlation and fixed-depth health/watchdog/stall layouts, all materialized evidence paths stayed below 260 characters, and synthetic PackageManager failure retained the existing device-health failure path."

    $tooLongPadding = 'p' * ($longGateEvidenceRootPaddingLength + 25)
    $tooLongEvidenceRoot = Join-Path (Join-Path $longGateEvidenceRootBase $tooLongPadding) $longGateEvidenceRootTail
    $tooLongAdbCallLog = Join-Path $runRoot 'oversized-root-adb-calls.log'
    $tooLongGradleMarker = Join-Path $runRoot 'oversized-root-gradle-started'
    $tooLongResult = Invoke-ConnectedVerificationChild -Fixture $longGateFixture -Classes @($longClasses[0]) -AdbPath $syntheticAdbPath -DeviceSerial 'emulator-artifact' -AdbCallLog $tooLongAdbCallLog -Name 'oversized-evidence-root-rejected' -EvidenceRootOverride $tooLongEvidenceRoot -GradleMarker $tooLongGradleMarker
    $tooLongVerificationPath = Get-VerificationJsonPath -Result $tooLongResult
    $tooLongEvidence = Get-Content -LiteralPath $tooLongVerificationPath -Raw | ConvertFrom-Json
    $tooLongInfra = Get-Content -LiteralPath $tooLongEvidence.toolingInfrastructureBootstrap.failurePath -Raw | ConvertFrom-Json
    $tooLongPass = (
        $tooLongResult.exitCode -ne 0 -and
        $tooLongEvidence.status -eq 'BLOCKED_BY_TOOLING_EVIDENCE_PATH_BUDGET' -and
        $tooLongEvidence.evidencePathBudget.accepted -eq $false -and
        $tooLongEvidence.evidencePathBudget.evidenceRoot -eq $tooLongEvidenceRoot -and
        $tooLongEvidence.evidencePathBudget.evidenceRootLength -eq $tooLongEvidenceRoot.Length -and
        $tooLongEvidence.evidencePathBudget.requiredMaximumPathLength -gt $tooLongEvidence.evidencePathBudget.maximumSupportedPathLength -and
        $tooLongEvidence.toolingInfrastructureBootstrap.deviceProbeStarted -eq $false -and
        $tooLongEvidence.toolingInfrastructureBootstrap.gradleStarted -eq $false -and
        $tooLongEvidence.toolingInfrastructureBootstrap.zeroTests -eq $true -and
        $tooLongInfra.deviceProbeStarted -eq $false -and
        $tooLongInfra.gradleStarted -eq $false -and
        $tooLongInfra.zeroTests -eq $true -and
        $tooLongInfra.deviceHealthFailureObserved -eq $false -and
        $tooLongInfra.scope.gateOrder[0] -eq $longGateIds[0] -and
        -not (Test-Path -LiteralPath $tooLongAdbCallLog -PathType Leaf) -and
        -not (Test-Path -LiteralPath $tooLongGradleMarker -PathType Leaf)
    )
    Add-AcceptanceCheck -CheckId 'oversized_actual_evidence_root_rejected_with_explicit_zero_test_evidence_before_probe_or_gradle' -Pass $tooLongPass -Detail "The requested EvidenceRoot length was $($tooLongEvidenceRoot.Length) and required maximum path was $($tooLongEvidence.evidencePathBudget.requiredMaximumPathLength); the bounded fallback evidence records zero tests, gradleStarted=false, deviceProbeStarted=false, and neither synthetic ADB nor Gradle ran."

    $sourceTokens = $null
    $sourceParseErrors = $null
    $verificationAst = [System.Management.Automation.Language.Parser]::ParseFile($script:verificationScript, [ref]$sourceTokens, [ref]$sourceParseErrors)
    if ($sourceParseErrors.Count -gt 0) { throw 'Cannot inspect the production verifier path-exception classifier because its PowerShell AST has parse errors.' }
    $classifierAst = $verificationAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-VerificationEvidencePathExceptionDetails' }, $true)
    if ($null -eq $classifierAst) { throw 'The production verifier path-exception classifier function was not found.' }
    . ([scriptblock]::Create($classifierAst.Extent.Text))
    $missingParentPath = Join-Path (Join-Path $runRoot ('missing-path-parent-' + [Guid]::NewGuid().ToString('N'))) 'output.log'
    $wrappedFileStreamException = $null
    try {
        New-Object System.IO.FileStream($missingParentPath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None) | Out-Null
    } catch {
        $wrappedFileStreamException = $_.Exception
    }
    $wrappedPathDetails = if ($null -ne $wrappedFileStreamException) { Get-VerificationEvidencePathExceptionDetails -Exception $wrappedFileStreamException } else { $null }
    $ordinaryAdbFailureDetails = Get-VerificationEvidencePathExceptionDetails -Exception (New-Object System.Exception('ADB executable could not be started.'))
    $wrappedPathClassifierPass = (
        $null -ne $wrappedFileStreamException -and
        @($wrappedPathDetails.exceptionTypes | Where-Object { $_ -eq 'System.Management.Automation.MethodInvocationException' }).Count -gt 0 -and
        @($wrappedPathDetails.exceptionTypes | Where-Object { $_ -eq 'System.IO.DirectoryNotFoundException' }).Count -gt 0 -and
        $wrappedPathDetails.isEvidencePathFailure -eq $true -and
        $wrappedPathDetails.classification -eq 'tooling_evidence_path_materialization_failure' -and
        $ordinaryAdbFailureDetails.isEvidencePathFailure -eq $false
    )
    Add-AcceptanceCheck -CheckId 'wrapped_filestream_path_failure_classified_as_tooling_without_reclassifying_adb_start_failure' -Pass $wrappedPathClassifierPass -Detail "The extracted production classifier observed exception chain $(@($wrappedPathDetails.exceptionTypes) -join ' -> ') and returned $($wrappedPathDetails.classification); a plain ADB-start exception remained outside the evidence-path classification."

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

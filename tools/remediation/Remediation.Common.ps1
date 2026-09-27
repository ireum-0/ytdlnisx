Set-StrictMode -Version 2.0

function Get-RemediationUtcNow {
    return [DateTimeOffset]::UtcNow
}

function Format-RemediationUtc {
    param([Parameter(Mandatory)][DateTimeOffset]$Value)
    return $Value.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ', [Globalization.CultureInfo]::InvariantCulture)
}

function Format-RemediationKoreaTime {
    param([Parameter(Mandatory)][DateTimeOffset]$Value)
    return $Value.ToOffset([TimeSpan]::FromHours(9)).ToString('yyyy-MM-ddTHH:mm:ss.fff+09:00', [Globalization.CultureInfo]::InvariantCulture)
}

function Write-RemediationJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $json = ConvertTo-Json -InputObject $Value -Depth 40
    $temporary = $Path + '.tmp-' + [Guid]::NewGuid().ToString('N')
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($temporary, $json, $encoding)
    if (Test-Path -LiteralPath $Path) {
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } else {
        Move-Item -LiteralPath $temporary -Destination $Path
    }
}

function Read-RemediationTextFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('Head', 'Tail')][string]$Position = 'Tail',
        [int]$MaxBytes = 65536
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ''
    }
    $stream = New-Object System.IO.FileStream($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        if ($stream.Length -le $MaxBytes) {
            $start = 0
            $count = [int]$stream.Length
        } elseif ($Position -eq 'Tail') {
            $start = [Math]::Max(0, $stream.Length - $MaxBytes)
            $count = [int]($stream.Length - $start)
        } else {
            $start = 0
            $count = $MaxBytes
        }
        [void]$stream.Seek($start, [System.IO.SeekOrigin]::Begin)
        $buffer = New-Object byte[] $count
        $read = $stream.Read($buffer, 0, $count)
        $text = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $read)
        if ($Position -eq 'Tail' -and $start -gt 0) {
            $newline = $text.IndexOf([char]10)
            if ($newline -ge 0 -and $newline -lt ($text.Length - 1)) {
                $text = $text.Substring($newline + 1)
            }
        }
        return $text
    } finally {
        $stream.Dispose()
    }
}

function ConvertTo-WindowsProcessArgument {
    param([AllowEmptyString()][string]$Value)
    if ($null -eq $Value) { $Value = '' }
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) {
            $slashes++
            continue
        }
        if ($character -eq [char]34) {
            if ($slashes -gt 0) {
                [void]$builder.Append(([string][char]92) * (($slashes * 2) + 1))
            } else {
                [void]$builder.Append([char]92)
            }
            [void]$builder.Append([char]34)
            $slashes = 0
            continue
        }
        if ($slashes -gt 0) {
            [void]$builder.Append(([string][char]92) * $slashes)
            $slashes = 0
        }
        [void]$builder.Append($character)
    }
    if ($slashes -gt 0) {
        [void]$builder.Append(([string][char]92) * ($slashes * 2))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Get-RemediationCommandDisplay {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )
    $items = @((ConvertTo-WindowsProcessArgument -Value $FilePath))
    foreach ($argument in $ArgumentList) {
        $items += (ConvertTo-WindowsProcessArgument -Value $argument)
    }
    return ($items -join ' ')
}

function Stop-RemediationProcessTree {
    param([Parameter(Mandatory)][System.Diagnostics.Process]$Process)
    if ($Process.HasExited) { return }
    if ($env:OS -eq 'Windows_NT') {
        $taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
        if (Test-Path -LiteralPath $taskkill) {
            $killInfo = New-Object System.Diagnostics.ProcessStartInfo
            $killInfo.FileName = $taskkill
            $killInfo.Arguments = '/PID ' + $Process.Id + ' /T /F'
            $killInfo.UseShellExecute = $false
            $killInfo.CreateNoWindow = $true
            $killer = [System.Diagnostics.Process]::Start($killInfo)
            if ($null -ne $killer) {
                [void]$killer.WaitForExit(15000)
                $killer.Dispose()
            }
        }
    } else {
        $Process.Kill()
    }
    if (-not $Process.HasExited) {
        try { $Process.Kill() } catch {}
    }
}

function Invoke-RemediationProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$Name,
        [ValidateRange(1, 86400)][int]$TimeoutSeconds = 30,
        [ValidateRange(1, 300)][int]$PollIntervalSeconds = 5,
        [scriptblock]$OnPulse,
        [scriptblock]$OnStart,
        [switch]$KillProcessTreeOnTimeout,
        [int]$SampleBytes = 65536
    )
    if (-not (Test-Path -LiteralPath $WorkingDirectory -PathType Container)) {
        throw "Working directory does not exist: $WorkingDirectory"
    }
    $resolvedFile = $null
    if (Test-Path -LiteralPath $FilePath -PathType Leaf) {
        $resolvedFile = (Resolve-Path -LiteralPath $FilePath).Path
    } else {
        $command = Get-Command $FilePath -ErrorAction Stop
        if ($command.CommandType -ne 'Application') {
            throw "Executable is not an external application: $FilePath"
        }
        $resolvedFile = $command.Source
    }
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    $safeName = [Regex]::Replace($Name, '[^A-Za-z0-9_.-]', '_')
    $stamp = [Guid]::NewGuid().ToString('N').Substring(0, 10)
    $stdoutPath = Join-Path $LogDirectory ($safeName + '-' + $stamp + '.stdout.log')
    $stderrPath = Join-Path $LogDirectory ($safeName + '-' + $stamp + '.stderr.log')

    $extension = [System.IO.Path]::GetExtension($resolvedFile).ToLowerInvariant()
    $effectiveFile = $resolvedFile
    $effectiveArguments = @($ArgumentList)
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    if ($extension -eq '.ps1') {
        $powershell = (Get-Command powershell.exe -ErrorAction Stop).Source
        $effectiveFile = $powershell
        $effectiveArguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $resolvedFile) + @($ArgumentList)
        $processInfo.FileName = $effectiveFile
        $processInfo.Arguments = (($effectiveArguments | ForEach-Object { ConvertTo-WindowsProcessArgument -Value ([string]$_) }) -join ' ')
    } elseif ($extension -eq '.bat' -or $extension -eq '.cmd') {
        if ($resolvedFile -match '["%!]' ) {
            throw 'Batch-file paths containing quote, percent, or exclamation characters are not supported.'
        }
        foreach ($argument in $ArgumentList) {
            if ([string]$argument -match '[&|<>^%!"\r\n]') {
                throw "Batch-file argument contains a shell metacharacter: $argument"
            }
        }
        $commandShell = $env:ComSpec
        if ([string]::IsNullOrWhiteSpace($commandShell)) {
            $commandShell = Join-Path $env:SystemRoot 'System32\cmd.exe'
        }
        $processInfo.FileName = $commandShell
        $safeArguments = @($ArgumentList | ForEach-Object { ConvertTo-WindowsProcessArgument -Value ([string]$_) })
        $argumentText = ($safeArguments -join ' ')
        $commandText = '"' + '"' + $resolvedFile + '"' + $(if ($argumentText.Length -gt 0) { ' ' + $argumentText } else { '' }) + '"'
        $processInfo.Arguments = '/d /s /c ' + $commandText
    } else {
        $processInfo.FileName = $effectiveFile
        $processInfo.Arguments = (($effectiveArguments | ForEach-Object { ConvertTo-WindowsProcessArgument -Value ([string]$_) }) -join ' ')
    }
    $processInfo.WorkingDirectory = $WorkingDirectory
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo
    $startUtc = Get-RemediationUtcNow
    $stdoutStream = $null
    $stderrStream = $null
    $stdoutCopy = $null
    $stderrCopy = $null
    $timedOut = $false
    $pulseErrors = New-Object System.Collections.Generic.List[string]
    $exitCode = $null
    $processStarted = $false
    try {
        if (-not $process.Start()) {
            throw "Failed to start process: $FilePath"
        }
        $processStarted = $true
        # Keep streamed Gradle phase markers observable by the watchdog while preserving raw bytes.
        $stdoutStream = New-Object System.IO.FileStream($stdoutPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite, 1)
        $stderrStream = New-Object System.IO.FileStream($stderrPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite, 1)
        $stdoutCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
        $stderrCopy = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
        if ($null -ne $OnStart) {
            try {
                & $OnStart ([pscustomobject]@{ processId = $process.Id; stdoutPath = $stdoutPath; stderrPath = $stderrPath; startUtc = Format-RemediationUtc $startUtc })
            } catch {
                $pulseErrors.Add('OnStart: ' + $_.Exception.Message)
            }
        }
        $nextPulse = [DateTimeOffset]::UtcNow.AddSeconds($PollIntervalSeconds)
        while (-not $process.HasExited) {
            $now = [DateTimeOffset]::UtcNow
            if (($now - $startUtc).TotalSeconds -ge $TimeoutSeconds) {
                $timedOut = $true
                if ($KillProcessTreeOnTimeout) {
                    Stop-RemediationProcessTree -Process $process
                } else {
                    try { $process.Kill() } catch {}
                }
                break
            }
            if ($null -ne $OnPulse -and $now -ge $nextPulse) {
                try {
                    & $OnPulse $process
                } catch {
                    $pulseErrors.Add($_.Exception.Message)
                }
                $nextPulse = [DateTimeOffset]::UtcNow.AddSeconds($PollIntervalSeconds)
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $process.HasExited) {
            try { [void]$process.WaitForExit(10000) } catch {}
        }
        if (-not $process.HasExited) {
            try { Stop-RemediationProcessTree -Process $process } catch {}
        }
        if ($process.HasExited) {
            $exitCode = $process.ExitCode
            $process.WaitForExit()
        }
        if ($null -ne $stdoutCopy) { try { $stdoutCopy.Wait(30000) | Out-Null } catch {} }
        if ($null -ne $stderrCopy) { try { $stderrCopy.Wait(30000) | Out-Null } catch {} }
        if ($null -ne $stdoutStream) { $stdoutStream.Flush() }
        if ($null -ne $stderrStream) { $stderrStream.Flush() }
    } catch {
        if ($processStarted) {
            try { if (-not $process.HasExited) { Stop-RemediationProcessTree -Process $process } } catch {}
            try { if ($process.HasExited) { $process.WaitForExit(10000) | Out-Null } } catch {}
        }
        if ($null -ne $stdoutCopy) { try { $stdoutCopy.Wait(10000) | Out-Null } catch {} }
        if ($null -ne $stderrCopy) { try { $stderrCopy.Wait(10000) | Out-Null } catch {} }
        throw
    } finally {
        if ($null -ne $stdoutStream) { $stdoutStream.Dispose() }
        if ($null -ne $stderrStream) { $stderrStream.Dispose() }
        $process.Dispose()
    }
    $endedUtc = Get-RemediationUtcNow
    return [pscustomobject][ordered]@{
        name = $Name
        command = Get-RemediationCommandDisplay -FilePath $resolvedFile -ArgumentList $ArgumentList
        executable = $resolvedFile
        arguments = @($ArgumentList)
        workingDirectory = $WorkingDirectory
        startUtc = Format-RemediationUtc $startUtc
        endUtc = Format-RemediationUtc $endedUtc
        durationSeconds = [Math]::Round(($endedUtc - $startUtc).TotalSeconds, 3)
        exitCode = $exitCode
        timedOut = $timedOut
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
        stdoutSample = Read-RemediationTextFile -Path $stdoutPath -Position Tail -MaxBytes $SampleBytes
        stderrSample = Read-RemediationTextFile -Path $stderrPath -Position Tail -MaxBytes $SampleBytes
        pulseErrors = @($pulseErrors.ToArray())
    }
}

function Resolve-RemediationGit {
    $command = Get-Command git.exe -ErrorAction Stop
    return $command.Source
}

function Invoke-RemediationGit {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 30
    )
    $git = Resolve-RemediationGit
    return Invoke-RemediationProcess -FilePath $git -ArgumentList (@('-C', $RepoPath) + $ArgumentList) -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name $Name -TimeoutSeconds $TimeoutSeconds
}

function Get-RemediationGitText {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$Name,
        [int[]]$AllowedExitCodes = @(0)
    )
    $result = Invoke-RemediationGit -RepoPath $RepoPath -ArgumentList $ArgumentList -LogDirectory $LogDirectory -Name $Name
    if ($result.timedOut -or $AllowedExitCodes -notcontains [int]$result.exitCode) {
        throw "Git command failed ($($result.exitCode)): $($result.command)"
    }
    return $result
}

function Get-RemediationHead {
    param([string]$RepoPath, [string]$LogDirectory)
    $result = Get-RemediationGitText -RepoPath $RepoPath -ArgumentList @('rev-parse', 'HEAD') -LogDirectory $LogDirectory -Name 'git-head'
    return $result.stdoutSample.Trim()
}

function Get-RemediationTreeSha {
    param([string]$RepoPath, [string]$CommitSha, [string]$LogDirectory)
    $result = Get-RemediationGitText -RepoPath $RepoPath -ArgumentList @('rev-parse', ($CommitSha + '^{tree}')) -LogDirectory $LogDirectory -Name 'git-tree'
    return $result.stdoutSample.Trim()
}

function Test-RemediationGitAncestor {
    param([string]$RepoPath, [string]$AncestorSha, [string]$DescendantSha, [string]$LogDirectory)
    $result = Invoke-RemediationGit -RepoPath $RepoPath -ArgumentList @('merge-base', '--is-ancestor', $AncestorSha, $DescendantSha) -LogDirectory $LogDirectory -Name 'git-ancestry'
    if ($result.timedOut) { throw 'Git ancestry check timed out.' }
    if ($result.exitCode -eq 0) { return $true }
    if ($result.exitCode -eq 1) { return $false }
    throw "Git ancestry check could not be completed (exit $($result.exitCode))."
}

function Get-RemediationRemoteRefSha {
    param(
        [string]$RepoPath,
        [string]$RemoteName,
        [string]$BranchName,
        [string]$LogDirectory
    )
    if ($RemoteName -notmatch '^[A-Za-z0-9_.-]+$') { throw "Invalid remote name: $RemoteName" }
    $format = Invoke-RemediationGit -RepoPath $RepoPath -ArgumentList @('check-ref-format', ('refs/heads/' + $BranchName)) -LogDirectory $LogDirectory -Name 'git-check-ref'
    if ($format.exitCode -ne 0) { throw "Invalid branch ref: $BranchName" }
    $result = Invoke-RemediationGit -RepoPath $RepoPath -ArgumentList @('ls-remote', '--exit-code', $RemoteName, ('refs/heads/' + $BranchName)) -LogDirectory $LogDirectory -Name 'git-ls-remote'
    if ($result.timedOut -or $result.exitCode -ne 0) {
        throw "Unable to read remote ref $RemoteName/$BranchName (exit $($result.exitCode))."
    }
    $match = [Regex]::Match($result.stdoutSample, '(?m)^([0-9a-fA-F]{40,64})\s+refs/heads/')
    if (-not $match.Success) { throw "Remote ref response was ambiguous for $RemoteName/$BranchName." }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Get-RemediationTrackedTreeState {
    param([string]$RepoPath, [string]$CandidateSha, [string]$LogDirectory)
    $diff = Invoke-RemediationGit -RepoPath $RepoPath -ArgumentList @('diff', '--quiet', $CandidateSha, '--') -LogDirectory $LogDirectory -Name 'git-tracked-diff'
    if ($diff.timedOut -or ($diff.exitCode -ne 0 -and $diff.exitCode -ne 1)) {
        throw "Unable to determine tracked diff state (exit $($diff.exitCode))."
    }
    $status = Get-RemediationGitText -RepoPath $RepoPath -ArgumentList @('status', '--porcelain=v1', '--untracked-files=no') -LogDirectory $LogDirectory -Name 'git-tracked-status'
    $statusText = $status.stdoutSample.Trim()
    $untracked = Get-RemediationGitText -RepoPath $RepoPath -ArgumentList @('ls-files', '--others', '--exclude-standard', '-z') -LogDirectory $LogDirectory -Name 'git-untracked-status'
    $untrackedText = [string]$untracked.stdoutSample
    $untrackedBytes = 0L
    if (Test-Path -LiteralPath $untracked.stdoutPath -PathType Leaf) {
        $untrackedBytes = (Get-Item -LiteralPath $untracked.stdoutPath).Length
    }
    $untrackedPaths = @()
    if ($untrackedBytes -gt 0) {
        $untrackedPaths = @($untrackedText.Split([char[]]@([char]0), [StringSplitOptions]::RemoveEmptyEntries))
    }
    $untrackedSummary = @($untrackedPaths | Select-Object -First 8 | ForEach-Object {
        $path = ([string]$_).Replace(([string][char]13), '<CR>').Replace(([string][char]10), '<LF>').Replace(([string][char]9), '<TAB>')
        if ($path.Length -gt 160) { $path = $path.Substring(0, 157) + '...' }
        '?? ' + $path
    })
    $untrackedListingTruncated = ($untrackedBytes -gt 65536 -or $untrackedPaths.Count -gt 8)
    $untrackedCount = $(if ($untrackedBytes -gt 65536) { $null } else { $untrackedPaths.Count })
    $untrackedPresent = ($untrackedBytes -gt 0)
    return [pscustomobject][ordered]@{
        clean = ($diff.exitCode -eq 0 -and [string]::IsNullOrWhiteSpace($statusText) -and -not $untrackedPresent)
        diffExitCode = [int]$diff.exitCode
        trackedStatus = $statusText
        untrackedPresent = [bool]$untrackedPresent
        untrackedCount = $untrackedCount
        untrackedStatus = $untrackedSummary
        untrackedListingTruncated = [bool]$untrackedListingTruncated
    }
}

function New-RemediationRunDirectory {
    param(
        [string]$RepoPath,
        [string]$CandidateSha,
        [string]$EvidenceRoot
    )
    if ($CandidateSha -notmatch '^[0-9a-fA-F]{40,64}$') {
        throw 'Candidate SHA must be a full hexadecimal Git object id.'
    }
    $repoFull = [System.IO.Path]::GetFullPath($RepoPath).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
        $evidenceBase = Join-Path $repoFull ('build\remediation-agent\' + $CandidateSha)
    } else {
        $evidenceBase = [System.IO.Path]::GetFullPath($EvidenceRoot)
    }
    $evidenceBaseFull = [System.IO.Path]::GetFullPath($evidenceBase)
    if (-not $evidenceBaseFull.StartsWith($repoFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence path must remain inside the implementation repository.'
    }
    $relative = $evidenceBaseFull.Substring($repoFull.Length).Replace('\', '/')
    $bootstrapLogs = Join-Path $env:TEMP 'ytdlnisx-remediation-bootstrap'; $ignore = Invoke-RemediationGit -RepoPath $RepoPath -ArgumentList @('check-ignore', '--quiet', '--', $relative) -LogDirectory $bootstrapLogs -Name 'git-check-ignore'
    if ($ignore.exitCode -ne 0) {
        throw "Evidence path is not ignored by Git: $relative"
    }
    $runId = (Get-RemediationUtcNow).ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture) + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $runPath = Join-Path $evidenceBaseFull $runId
    New-Item -ItemType Directory -Path $runPath -Force | Out-Null
    return [pscustomobject][ordered]@{
        runId = $runId
        evidenceBase = $evidenceBaseFull
        evidenceDirectory = $runPath
    }
}

function Invoke-RemediationAdbCommand {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [string]$DeviceSerial,
        [Parameter(Mandatory)][string[]]$AdbArguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$Name,
        [ValidateRange(1, 600)][int]$TimeoutSeconds = 10
    )
    $arguments = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $arguments += @('-s', $DeviceSerial)
    }
    $arguments += $AdbArguments
    return Invoke-RemediationProcess -FilePath $AdbPath -ArgumentList $arguments -WorkingDirectory $WorkingDirectory -LogDirectory $LogDirectory -Name $Name -TimeoutSeconds $TimeoutSeconds -PollIntervalSeconds 1 -SampleBytes 32768
}

function Get-RemediationTimeCorrelationSample {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string]$AdbPath,
        [string]$DeviceSerial,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$NamePrefix,
        [int]$TimeoutSeconds = 5
    )
    $hostStart = Get-RemediationUtcNow
    $epochRaw = ''
    $rawDeviceTime = ''
    $zoneRaw = ''
    $commands = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $epoch = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'date', '+%s') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name ($NamePrefix + '-device-epoch') -TimeoutSeconds $TimeoutSeconds
        $wall = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'date', '+%Y-%m-%dT%H:%M:%S%z') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name ($NamePrefix + '-device-wall') -TimeoutSeconds $TimeoutSeconds
        $zone = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'getprop', 'persist.sys.timezone') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name ($NamePrefix + '-device-zone') -TimeoutSeconds $TimeoutSeconds
        $epochRaw = [string]$epoch.stdoutSample
        $rawDeviceTime = [string]$wall.stdoutSample
        $zoneRaw = [string]$zone.stdoutSample
        $commands = @($epoch, $wall, $zone)
    }
    $hostEnd = Get-RemediationUtcNow
    $hostMid = $hostStart.AddTicks([long](($hostEnd - $hostStart).Ticks / 2))
    $deviceEpochSeconds = $null
    $epochMatch = [Regex]::Match($epochRaw, '(?m)^\s*(\d{9,12})\s*$')
    if ($epochMatch.Success) {
        $epochValue = 0L
        if ([long]::TryParse($epochMatch.Groups[1].Value, [Globalization.NumberStyles]::Integer, [Globalization.CultureInfo]::InvariantCulture, [ref]$epochValue)) {
            $deviceEpochSeconds = $epochValue
        }
    }
    $deviceUtc = $null
    $deviceKorea = $null
    $confidence = 'unavailable'
    if ($null -ne $deviceEpochSeconds) {
        try {
            $deviceTime = [DateTimeOffset]::FromUnixTimeSeconds([long]$deviceEpochSeconds)
            $deviceUtc = $deviceTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ', [Globalization.CultureInfo]::InvariantCulture)
            $deviceKorea = Format-RemediationKoreaTime $deviceTime
            $distance = [Math]::Abs(($deviceTime.ToUniversalTime() - $hostMid).TotalSeconds)
            if ($distance -le 60) { $confidence = 'high' }
            elseif ($distance -le 300) { $confidence = 'moderate' }
            else { $confidence = 'low_device_host_clock_delta' }
        } catch {
            $confidence = 'invalid_device_epoch'
        }
    }
    if ([string]::IsNullOrWhiteSpace($deviceKorea)) {
        $deviceKorea = Format-RemediationKoreaTime $hostMid
        if ($confidence -eq 'unavailable') { $confidence = 'host_fallback' }
    }
    $rawOffsetSeconds = $null
    $rawTrimmed = $rawDeviceTime.Trim()
    $offsetMatch = [Regex]::Match($rawTrimmed, '([+-]\d{2})(\d{2})$')
    if ($offsetMatch.Success) {
        $normalizedWall = $rawTrimmed.Substring(0, $rawTrimmed.Length - 5) + $offsetMatch.Groups[1].Value + ':' + $offsetMatch.Groups[2].Value
        try {
            $parsedWall = [DateTimeOffset]::Parse($normalizedWall, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal)
            $rawOffsetSeconds = [int]$parsedWall.Offset.TotalSeconds
        } catch {
            $rawOffsetSeconds = $null
        }
    }
    $wallEpochDelta = $null
    if ($null -ne $deviceEpochSeconds -and $null -ne $rawOffsetSeconds -and -not [string]::IsNullOrWhiteSpace($rawTrimmed)) {
        try {
            $normalizedWall = $rawTrimmed.Substring(0, $rawTrimmed.Length - 5) + $offsetMatch.Groups[1].Value + ':' + $offsetMatch.Groups[2].Value
            $parsedWall = [DateTimeOffset]::Parse($normalizedWall, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal)
            $wallEpochDelta = [Math]::Round(($parsedWall.ToUnixTimeSeconds() - [long]$deviceEpochSeconds), 3)
        } catch {
            $wallEpochDelta = $null
        }
    }
    return [pscustomobject][ordered]@{
        raw_device_time = $rawDeviceTime
        device_epoch_seconds = $deviceEpochSeconds
        device_timezone_property = $zoneRaw
        host_utc_iso8601 = Format-RemediationUtc $hostMid
        host_utc_start_iso8601 = Format-RemediationUtc $hostStart
        host_utc_end_iso8601 = Format-RemediationUtc $hostEnd
        device_utc_iso8601 = $deviceUtc
        korea_iso8601 = $deviceKorea
        raw_to_utc_offset_seconds = $rawOffsetSeconds
        raw_wall_to_epoch_delta_seconds = $wallEpochDelta
        timestamp_basis = $(if ($null -ne $deviceEpochSeconds) { 'device_epoch_seconds' } else { 'host_utc_fallback' })
        timestamp_basis_confidence = $confidence
        source_clock = $(if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { 'host_only' } else { 'adb_device' })
        probeCommands = $commands
    }
}

function Get-RemediationDeviceHealth {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$DeviceSerial,
        [Parameter(Mandatory)][string]$LogDirectory,
        [ValidateRange(1, 600)][int]$ProbeTimeoutSeconds = 10,
        [ValidateRange(0.1, 600)][double]$MaxShellLatencySeconds = 8,
        [ValidateRange(0.1, 600)][double]$MaxPackageManagerLatencySeconds = 15,
        [switch]$Quick
    )
    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        throw 'An explicit device serial is required; automatic device selection is disabled.'
    }
    $started = Get-RemediationUtcNow
    $probes = New-Object System.Collections.Generic.List[object]
    $devices = Invoke-RemediationAdbCommand -AdbPath $AdbPath -AdbArguments @('devices', '-l') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name 'adb-devices' -TimeoutSeconds $ProbeTimeoutSeconds
    $probes.Add([pscustomobject][ordered]@{
        name = 'adb_devices'
        success = (-not $devices.timedOut -and $devices.exitCode -eq 0)
        durationSeconds = $devices.durationSeconds
        exitCode = $devices.exitCode
        timedOut = $devices.timedOut
        stdoutPath = $devices.stdoutPath
        stderrPath = $devices.stderrPath
        stdoutSample = $devices.stdoutSample
        stderrSample = $devices.stderrSample
    })
    $deviceRow = $null
    foreach ($line in ($devices.stdoutSample -split '\r?\n')) {
        if ($line -match '^\s*(\S+)\s+(\S+)(?:\s+(.*))?$' -and $Matches[1] -eq $DeviceSerial) {
            $deviceRow = [pscustomobject]@{ serial = $Matches[1]; state = $Matches[2]; details = [string]$Matches[3] }
            break
        }
    }
    $isOnline = ($null -ne $deviceRow -and $deviceRow.state -eq 'device')
    $correlationStart = $null
    $correlationEnd = $null
    $identity = [ordered]@{
        model = $null
        sdk = $null
        fingerprint = $null
        avdName = $null
        qemu = $null
        timezone = $null
        deviceKind = $(if ($DeviceSerial -match '^emulator-') { 'emulator_serial' } else { 'device_serial_unverified' })
        avdConfig = $null
    }
    $shellHealthy = $false
    $bootCompleted = $false
    $packageManagerHealthy = $false
    $shellLatency = $null
    $packageLatency = $null
    if ($isOnline) {
        if (-not $Quick) {
            $correlationStart = Get-RemediationTimeCorrelationSample -RepoPath $RepoPath -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $LogDirectory -NamePrefix 'time-start' -TimeoutSeconds ([Math]::Min(5, $ProbeTimeoutSeconds))
        }
        $shell = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'echo', 'alive') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name 'adb-shell-alive' -TimeoutSeconds $ProbeTimeoutSeconds
        $shellLatency = [double]$shell.durationSeconds
        $shellHealthy = (-not $shell.timedOut -and $shell.exitCode -eq 0 -and $shell.stdoutSample.Trim() -eq 'alive' -and $shellLatency -le $MaxShellLatencySeconds)
        $probes.Add([pscustomobject][ordered]@{
            name = 'shell_responsive'
            success = $shellHealthy
            durationSeconds = $shell.durationSeconds
            maxLatencySeconds = $MaxShellLatencySeconds
            exitCode = $shell.exitCode
            timedOut = $shell.timedOut
            stdoutPath = $shell.stdoutPath
            stderrPath = $shell.stderrPath
            stdoutSample = $shell.stdoutSample
            stderrSample = $shell.stderrSample
        })
        $boot = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'getprop', 'sys.boot_completed') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name 'adb-boot-completed' -TimeoutSeconds $ProbeTimeoutSeconds
        $bootCompleted = (-not $boot.timedOut -and $boot.exitCode -eq 0 -and $boot.stdoutSample.Trim() -eq '1')
        $probes.Add([pscustomobject][ordered]@{
            name = 'boot_completed'
            success = $bootCompleted
            durationSeconds = $boot.durationSeconds
            exitCode = $boot.exitCode
            timedOut = $boot.timedOut
            stdoutPath = $boot.stdoutPath
            stderrPath = $boot.stderrPath
            stdoutSample = $boot.stdoutSample
            stderrSample = $boot.stderrSample
        })
        $package = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'cmd', 'package', 'path', 'android') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name 'adb-package-manager' -TimeoutSeconds $ProbeTimeoutSeconds
        $packageLatency = [double]$package.durationSeconds
        $packageManagerHealthy = (-not $package.timedOut -and $package.exitCode -eq 0 -and $package.stdoutSample.Trim().Length -gt 0 -and $packageLatency -le $MaxPackageManagerLatencySeconds)
        $probes.Add([pscustomobject][ordered]@{
            name = 'package_manager_responsive'
            success = $packageManagerHealthy
            durationSeconds = $package.durationSeconds
            maxLatencySeconds = $MaxPackageManagerLatencySeconds
            exitCode = $package.exitCode
            timedOut = $package.timedOut
            stdoutPath = $package.stdoutPath
            stderrPath = $package.stderrPath
            stdoutSample = $package.stdoutSample
            stderrSample = $package.stderrSample
        })
        if (-not $Quick) {
            $properties = @(
                @{ key = 'ro.product.model'; field = 'model'; name = 'adb-model' },
                @{ key = 'ro.build.version.sdk'; field = 'sdk'; name = 'adb-sdk' },
                @{ key = 'ro.build.fingerprint'; field = 'fingerprint'; name = 'adb-fingerprint' },
                @{ key = 'ro.boot.qemu.avd_name'; field = 'avdName'; name = 'adb-avd-name' },
                @{ key = 'ro.kernel.qemu'; field = 'qemu'; name = 'adb-qemu' },
                @{ key = 'persist.sys.timezone'; field = 'timezone'; name = 'adb-timezone' }
            )
            foreach ($property in $properties) {
                $result = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'getprop', $property.key) -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name $property.name -TimeoutSeconds $ProbeTimeoutSeconds
                $identity[$property.field] = $result.stdoutSample
                $probes.Add([pscustomobject][ordered]@{
                    name = $property.field
                    success = (-not $result.timedOut -and $result.exitCode -eq 0)
                    durationSeconds = $result.durationSeconds
                    exitCode = $result.exitCode
                    timedOut = $result.timedOut
                    stdoutPath = $result.stdoutPath
                    stderrPath = $result.stderrPath
                    stdoutSample = $result.stdoutSample
                    stderrSample = $result.stderrSample
                })
            }
            if ($identity.qemu -eq '1' -or $DeviceSerial -match '^emulator-') {
                $identity.deviceKind = 'emulator'
            } else {
                $identity.deviceKind = 'physical_or_unidentified'
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$identity.avdName)) {
                $identity.avdConfig = Get-RemediationAvdConfigSummary -AvdName ([string]$identity.avdName)
            }
            $correlationEnd = Get-RemediationTimeCorrelationSample -RepoPath $RepoPath -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $LogDirectory -NamePrefix 'time-end' -TimeoutSeconds ([Math]::Min(5, $ProbeTimeoutSeconds))
        }
    }
    $ended = Get-RemediationUtcNow
    $hardFailures = @($probes | Where-Object { -not $_.success -and $_.name -in @('adb_devices', 'shell_responsive', 'boot_completed', 'package_manager_responsive') })
    $healthy = ($isOnline -and $hardFailures.Count -eq 0)
    return [pscustomobject][ordered]@{
        serial = $DeviceSerial
        adbDeviceState = $(if ($null -ne $deviceRow) { $deviceRow.state } else { 'not_listed' })
        adbDeviceDetails = $(if ($null -ne $deviceRow) { $deviceRow.details } else { '' })
        healthy = $healthy
        hardFailure = (-not $healthy)
        shellLatencySeconds = $shellLatency
        packageManagerLatencySeconds = $packageLatency
        shellResponsive = $shellHealthy
        bootCompleted = $bootCompleted
        packageManagerResponsive = $packageManagerHealthy
        deviceIdentity = $identity
        probes = @($probes.ToArray())
        correlationStart = $correlationStart
        correlationEnd = $correlationEnd
        startUtc = Format-RemediationUtc $started
        endUtc = Format-RemediationUtc $ended
        durationSeconds = [Math]::Round(($ended - $started).TotalSeconds, 3)
        quickSample = [bool]$Quick
    }
}

function Get-RemediationAvdConfigSummary {
    param([Parameter(Mandatory)][string]$AvdName)
    if ($AvdName -notmatch '^[A-Za-z0-9_.-]+$') {
        return [pscustomobject]@{ available = $false; reason = 'AVD name contains unsupported characters' }
    }
    $homes = @()
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_AVD_HOME)) { $homes += $env:ANDROID_AVD_HOME }
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) { $homes += (Join-Path $env:USERPROFILE '.android\avd') }
    foreach ($home in ($homes | Select-Object -Unique)) {
        $config = Join-Path (Join-Path $home ($AvdName + '.avd')) 'config.ini'
        if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { continue }
        $values = [ordered]@{}
        foreach ($line in (Get-Content -LiteralPath $config)) {
            if ($line -match '^\s*(snapshot\.present|fastboot\.forceColdBoot|hw\.gpu\.enabled|hw\.gpu\.mode|hw\.cpu\.ncore|hw\.ramSize)\s*=\s*(.*?)\s*$') {
                $values[$Matches[1]] = $Matches[2]
            }
        }
        return [pscustomobject][ordered]@{
            available = $true
            configPath = $config
            observedSettings = $values
            actionTaken = $false
        }
    }
    return [pscustomobject]@{ available = $false; reason = 'AVD config not found in known AVD homes'; actionTaken = $false }
}

function Get-RemediationInstrumentationPresence {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$DeviceSerial,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$Name,
        [ValidateRange(1, 600)][int]$TimeoutSeconds = 8
    )
    $probe = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'dumpsys', 'activity', 'instrumentation') -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name $Name -TimeoutSeconds $TimeoutSeconds
    $lines = @([string]$probe.stdoutSample -split '\r?\n' | Where-Object { $_ -match '(?i)(instrumentation|mFinished|runner|processName)' } | Select-Object -Last 40)
    $available = (-not $probe.timedOut -and $probe.exitCode -eq 0)
    return [pscustomobject][ordered]@{
        available = $available
        observed = ($available -and $lines.Count -gt 0)
        observationBasis = 'bounded dumpsys activity instrumentation output'
        matchingLines = $lines
        stdoutPath = $probe.stdoutPath
        stderrPath = $probe.stderrPath
        timedOut = $probe.timedOut
        exitCode = $probe.exitCode
        durationSeconds = $probe.durationSeconds
    }
}

function Get-RemediationPressureSample {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$DeviceSerial,
        [Parameter(Mandatory)][string]$LogDirectory,
        [Parameter(Mandatory)][string]$NamePrefix,
        [int]$TimeoutSeconds = 5
    )
    $records = New-Object System.Collections.Generic.List[object]
    foreach ($kind in @('cpu', 'io', 'memory')) {
        $remotePath = '/proc/pressure/' + $kind
        $result = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'cat', $remotePath) -WorkingDirectory $RepoPath -LogDirectory $LogDirectory -Name ($NamePrefix + '-psi-' + $kind) -TimeoutSeconds $TimeoutSeconds
        $raw = [string]$result.stdoutSample
        $available = (-not $result.timedOut -and $result.exitCode -eq 0 -and $raw -match '(?m)^(some|full)\s')
        $lines = @()
        foreach ($line in ($raw -split '\r?\n')) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $parsed = [ordered]@{ raw = $line; scope = $null; avg10 = $null; avg60 = $null; avg300 = $null; total = $null }
            if ($line -match '^(some|full)\s+') { $parsed.scope = $Matches[1] }
            foreach ($key in @('avg10', 'avg60', 'avg300', 'total')) {
                if ($line -match ('(?:^|\s)' + $key + '=([0-9.]+)')) {
                    if ($key -eq 'total') { $parsed[$key] = $Matches[1] }
                    else {
                        $number = 0.0
                        if ([double]::TryParse($Matches[1], [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) { $parsed[$key] = $number }
                    }
                }
            }
            $lines += [pscustomobject]$parsed
        }
        $records.Add([pscustomobject][ordered]@{
            kind = $kind
            remotePath = $remotePath
            available = $available
            reason = $(if ($available) { $null } elseif ($result.timedOut) { 'probe_timeout' } elseif ($result.exitCode -ne 0) { 'probe_failed' } else { 'pressure_file_unavailable_or_unrecognized' })
            raw = $raw
            parsed = $lines
            durationSeconds = $result.durationSeconds
            stdoutPath = $result.stdoutPath
            stderrPath = $result.stderrPath
        })
    }
    return @($records.ToArray())
}

function Get-RemediationHostSnapshot {
    param([string]$AvdName)
    $captured = Get-RemediationUtcNow
    $freeMemoryBytes = $null
    $osReason = $null
    try {
        $os = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        $freeMemoryBytes = [long]$os.FreePhysicalMemory * 1024
    } catch {
        $osReason = $_.Exception.Message
    }
    $processRecords = @()
    try {
        $selected = Get-Process -ErrorAction Stop | Where-Object {
            $_.ProcessName -match '^(emulator|qemu-system.*|adb)$'
        }
        foreach ($process in $selected) {
            $processRecords += [pscustomobject][ordered]@{
                name = $process.ProcessName
                pid = $process.Id
                cpuSeconds = $(try { [Math]::Round([double]$process.CPU, 3) } catch { $null })
                workingSetBytes = [long]$process.WorkingSet64
                startTimeUtc = $(try { $process.StartTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ') } catch { $null })
            }
        }
    } catch {}
    $launchRecords = @()
    try {
        $native = Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -match '^(emulator|qemu-system.*)\.exe$'
        }
        foreach ($process in $native) {
            $commandLine = [string]$process.CommandLine
            $avd = $null
            $snapshot = 'unspecified'
            $graphics = $null
            $acceleration = $null
            if ($commandLine -match '(?i)(?:^|\s)-avd\s+"?([^\s"]+)') { $avd = $Matches[1] }
            if ($commandLine -match '(?i)(?:^|\s)-no-snapshot(?:-load|-save)?(?:\s|$)') { $snapshot = 'no_snapshot_flag_observed' }
            elseif ($commandLine -match '(?i)(?:^|\s)-snapshot\s+([^\s"]+)') { $snapshot = 'named_snapshot:' + $Matches[1] }
            if ($commandLine -match '(?i)(?:^|\s)-gpu\s+([^\s"]+)') { $graphics = $Matches[1] }
            if ($commandLine -match '(?i)(?:^|\s)-accel\s+([^\s"]+)') { $acceleration = $Matches[1] }
            $launchRecords += [pscustomobject][ordered]@{
                processName = $process.Name
                pid = $process.ProcessId
                avdName = $avd
                snapshotPolicyObserved = $snapshot
                graphicsModeObserved = $graphics
                accelerationModeObserved = $acceleration
                commandLineCaptured = (-not [string]::IsNullOrWhiteSpace($commandLine))
            }
        }
    } catch {}
    $disk = $null
    $diskReason = $null
    try {
        $disk = Get-CimInstance -ClassName Win32_PerfFormattedData_PerfDisk_PhysicalDisk -ErrorAction Stop |
            Where-Object { $_.Name -eq '_Total' } |
            Select-Object -First 1 |
            ForEach-Object {
                [pscustomobject][ordered]@{
                    currentQueueLength = $_.CurrentDiskQueueLength
                    averageQueueLength = $_.AvgDiskQueueLength
                    percentDiskTime = $_.PercentDiskTime
                    percentIdleTime = $_.PercentIdleTime
                }
            }
        if ($null -eq $disk) { $diskReason = 'aggregate disk counter unavailable' }
    } catch {
        $diskReason = $_.Exception.Message
    }
    return [pscustomobject][ordered]@{
        capturedUtc = Format-RemediationUtc $captured
        capturedKorea = Format-RemediationKoreaTime $captured
        hostName = $env:COMPUTERNAME
        freeMemoryBytes = $freeMemoryBytes
        memoryUnavailableReason = $osReason
        diskQueue = $disk
        diskUnavailableReason = $diskReason
        emulatorAndQemuProcesses = $processRecords
        adbProcesses = @($processRecords | Where-Object { $_.name -eq 'adb' })
        emulatorLaunchObservation = $launchRecords
        requestedAvdName = $AvdName
        profilingMode = 'bounded_snapshot_only'
    }
}

function Capture-RemediationGuestStallDiagnostics {
    param(
        [Parameter(Mandatory)][string]$RepoPath,
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$DeviceSerial,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$NamePrefix,
        $WatchSample,
        $PreviousPressureSamples = @(),
        [int]$AdbTimeoutSeconds = 8
    )
    $directory = Join-Path $EvidenceDirectory 'stall-diagnostics'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $captureTime = Get-RemediationUtcNow
    $pressure = Get-RemediationPressureSample -RepoPath $RepoPath -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $directory -NamePrefix ($NamePrefix + '-capture-pressure') -TimeoutSeconds $AdbTimeoutSeconds
    $logcat = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('logcat', '-d', '-t', '1500', '-v', 'threadtime') -WorkingDirectory $RepoPath -LogDirectory $directory -Name ($NamePrefix + '-bounded-logcat') -TimeoutSeconds ([Math]::Max(10, $AdbTimeoutSeconds))
    $top = Invoke-RemediationAdbCommand -AdbPath $AdbPath -DeviceSerial $DeviceSerial -AdbArguments @('shell', 'top', '-b', '-n', '1', '-m', '50') -WorkingDirectory $RepoPath -LogDirectory $directory -Name ($NamePrefix + '-guest-top') -TimeoutSeconds $AdbTimeoutSeconds
    $instrumentation = Get-RemediationInstrumentationPresence -RepoPath $RepoPath -AdbPath $AdbPath -DeviceSerial $DeviceSerial -LogDirectory $directory -Name ($NamePrefix + '-instrumentation-presence') -TimeoutSeconds $AdbTimeoutSeconds
    $logText = [string]$logcat.stdoutSample
    $topText = [string]$top.stdoutSample
    $slowSystem = @([Regex]::Matches($logText, '(?im)(system_server.{0,120}(slow|dispatch|delivery)|slow\s+(dispatch|delivery)|dispatching.{0,80}\d{3,}\s*ms)') | ForEach-Object { $_.Value } | Select-Object -Last 30)
    $anrMatches = @([Regex]::Matches($logText, '(?im)ANR in\s+([A-Za-z0-9_.]+)|Input dispatching timed out.{0,180}') | ForEach-Object { $_.Value } | Select-Object -Last 30)
    $unrelatedSystemAnr = @($anrMatches | Where-Object { $_ -match '(?i)(com\.android\.|android\.|com\.google\.android\.)' })
    $binderMatches = @([Regex]::Matches($logText, '(?im)(binder.{0,100}(timeout|timed out|failed)|DeadObjectException|waitForService.{0,80}timeout|service manager.{0,80}failed|transaction.{0,80}(failed|timeout))') | ForEach-Object { $_.Value } | Select-Object -Last 30)
    $installMatches = @([Regex]::Matches($logText, '(?im)(PackageManager|PackageInstaller|install session|InstallSession|INSTALL_FAILED).{0,160}') | ForEach-Object { $_.Value } | Select-Object -Last 30)
    $instrumentationAvailable = [bool]$instrumentation.available
    $instrumentationObserved = [bool]$instrumentation.observed
    $topLines = @($topText -split '\r?\n')
    $cpuHeaderTokens = @()
    foreach ($line in $topLines) {
        if ($line -match '(?i)\bPID\b.*(?:%CPU|CPU%)') {
            $cpuHeaderTokens = @($line.Trim() -split '\s+')
            break
        }
    }
    $cpuColumnIndex = -1
    for ($index = 0; $index -lt $cpuHeaderTokens.Count; $index++) {
        if ($cpuHeaderTokens[$index] -match '(?i)%?CPU') { $cpuColumnIndex = $index; break }
    }
    $guestProcessCpu = New-Object System.Collections.Generic.List[object]
    $targetProcessPatterns = @(
        @{ name = 'system_server'; pattern = '(?i)system_server' },
        @{ name = 'adbd'; pattern = '(?i)(?:^|\s)adbd(?:\s|$)' },
        @{ name = 'sensor_hal_multihal'; pattern = '(?i)android\.hardware\.sensors-service\.multihal' },
        @{ name = 'ranchu_graphics_composer'; pattern = '(?i)android\.hardware\.graphics\.composer3-service\.ranchu' },
        @{ name = 'surfaceflinger'; pattern = '(?i)surfaceflinger' }
    )
    foreach ($line in $topLines) {
        foreach ($target in $targetProcessPatterns) {
            if ($line -match $target.pattern) {
                $tokens = @($line.Trim() -split '\s+')
                $cpuText = $null
                $cpuPercent = $null
                if ($cpuColumnIndex -ge 0 -and $tokens.Count -gt $cpuColumnIndex) {
                    $cpuText = [string]$tokens[$cpuColumnIndex]
                    $cpuMatch = [Regex]::Match($cpuText, '^(\d+(?:\.\d+)?)%?$')
                    if ($cpuMatch.Success) {
                        $parsedCpu = 0.0
                        if ([double]::TryParse($cpuMatch.Groups[1].Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsedCpu)) { $cpuPercent = $parsedCpu }
                    }
                }
                $guestProcessCpu.Add([pscustomobject][ordered]@{
                    process = $target.name
                    cpuPercent = $cpuPercent
                    cpuColumnText = $cpuText
                    cpuColumnHeader = $(if ($cpuColumnIndex -ge 0) { $cpuHeaderTokens[$cpuColumnIndex] } else { $null })
                    cpuParseStatus = $(if ($null -ne $cpuPercent) { 'parsed_from_top_cpu_column' } else { 'raw_line_preserved_cpu_column_unavailable_or_unparsed' })
                    rawLine = $line
                })
            }
        }
    }
    $pressureKinds = @()
    foreach ($sample in $pressure) {
        $high = @($sample.parsed | Where-Object { $null -ne $_.avg10 -and [double]$_.avg10 -ge 20 })
        if ($high.Count -gt 0) { $pressureKinds += [string]$sample.kind }
    }
    $previousHighKinds = @()
    foreach ($priorEntry in @($PreviousPressureSamples)) {
        $priorPressure = @()
        if ($null -ne $priorEntry -and $null -ne $priorEntry.PSObject.Properties['pressure']) {
            $priorPressure = @($priorEntry.pressure)
        } else {
            $priorPressure = @($priorEntry)
        }
        foreach ($sample in $priorPressure) {
            if ($null -ne $sample -and $sample.available) {
                $high = @($sample.parsed | Where-Object { $null -ne $_.avg10 -and [double]$_.avg10 -ge 20 })
                if ($high.Count -gt 0) { $previousHighKinds += [string]$sample.kind }
            }
        }
    }
    $persistentPressureKinds = @($pressureKinds | Where-Object { $previousHighKinds -contains $_ } | Select-Object -Unique)
    $healthProbeFailed = ($null -ne $WatchSample -and [bool]$WatchSample.hardFailure)
    $signalFamilies = New-Object System.Collections.Generic.List[string]
    if ($healthProbeFailed) { $signalFamilies.Add('bounded_adb_or_package_manager_health_failure') }
    if ($persistentPressureKinds.Count -gt 0) { $signalFamilies.Add('pressure_elevated_across_consecutive_samples') }
    if ($slowSystem.Count -gt 0) { $signalFamilies.Add('system_server_slow_dispatch_or_delivery_log_signal') }
    if ($unrelatedSystemAnr.Count -gt 0) { $signalFamilies.Add('unrelated_system_app_anr_log_signal') }
    if ($binderMatches.Count -gt 0) { $signalFamilies.Add('binder_or_service_manager_log_signal') }
    $probable = ($healthProbeFailed -or $signalFamilies.Count -ge 2)
    $avdName = $null
    if ($null -ne $WatchSample -and $null -ne $WatchSample.deviceIdentity) { $avdName = [string]$WatchSample.deviceIdentity.avdName }
    $hostSnapshot = Get-RemediationHostSnapshot -AvdName $avdName
    $signalSummary = [pscustomobject][ordered]@{
        capturedUtc = Format-RemediationUtc $captureTime
        capturedKorea = Format-RemediationKoreaTime $captureTime
        diagnosticOnly = $true
        rootCauseVerdict = 'not_assigned'
        probableGuestWideStallSignals = $probable
        healthProbeFailure = $healthProbeFailed
        pressureKindsHighNow = @($pressureKinds | Select-Object -Unique)
        pressureKindsHighAcrossSamples = $persistentPressureKinds
        systemServerSlowWarningCount = $slowSystem.Count
        systemServerSlowWarningExamples = $slowSystem
        anrSignalCount = $anrMatches.Count
        unrelatedSystemAnrSignalCount = $unrelatedSystemAnr.Count
        unrelatedSystemAnrExamples = $unrelatedSystemAnr
        binderSignalCount = $binderMatches.Count
        binderSignalExamples = $binderMatches
        installMilestoneSignalCount = $installMatches.Count
        installMilestoneExamples = $installMatches
        instrumentationProcessPresence = $instrumentation
        guestProcessCpuObservations = @($guestProcessCpu.ToArray())
        guestTopCpuColumnHeader = $(if ($cpuColumnIndex -ge 0) { $cpuHeaderTokens[$cpuColumnIndex] } else { $null })
        logcatRawOutputPath = $logcat.stdoutPath
        logcatCommandTimedOut = $logcat.timedOut
        logcatExitCode = $logcat.exitCode
        guestTopOutputPath = $top.stdoutPath
        guestTopTimedOut = $top.timedOut
        guestTopExitCode = $top.exitCode
        pressure = $pressure
        hostSnapshot = $hostSnapshot
        independentSignalFamilies = @($signalFamilies.ToArray())
    }
    Write-RemediationJson -Path (Join-Path $directory 'device-pressure.json') -Value $signalSummary
    Write-RemediationJson -Path (Join-Path $directory 'host-snapshot.json') -Value $hostSnapshot
    return $signalSummary
}

function Test-RemediationClassPattern {
    param([string]$ExpectedPattern, [string]$ActualClass)
    if ([string]::IsNullOrWhiteSpace($ExpectedPattern) -or [string]::IsNullOrWhiteSpace($ActualClass)) { return $false }
    $regex = '^' + [Regex]::Escape($ExpectedPattern).Replace('\*', '.*').Replace('\?', '.') + '$'
    return [Regex]::IsMatch($ActualClass, $regex, [System.Text.RegularExpressions.RegexOptions]::CultureInvariant)
}

function Get-RemediationTestResultSummary {
    param(
        [Parameter(Mandatory)][string[]]$ResultRoots,
        [Parameter(Mandatory)][string]$ExpectedClass,
        [Parameter(Mandatory)][DateTimeOffset]$StartedUtc,
        [string[]]$LogPaths = @()
    )
    $files = New-Object System.Collections.Generic.List[object]
    foreach ($root in $ResultRoots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        foreach ($file in (Get-ChildItem -LiteralPath $root -Filter 'TEST-*.xml' -File -Recurse -ErrorAction SilentlyContinue)) {
            if ($file.LastWriteTimeUtc -ge $StartedUtc.UtcDateTime) {
                $files.Add($file)
            }
        }
    }
    $matchingCases = 0
    $matchingSkipped = 0
    $matchingFailures = 0
    $matchingErrors = 0
    $outOfScopeCases = 0
    $suiteNames = New-Object System.Collections.Generic.List[string]
    $reportPaths = New-Object System.Collections.Generic.List[string]
    foreach ($file in $files) {
        try {
            $document = New-Object System.Xml.XmlDocument
            $document.XmlResolver = $null
            $document.Load($file.FullName)
            if ($document.DocumentElement.Name -eq 'testsuite') {
                $suites = @($document.DocumentElement)
            } else {
                $suites = @($document.SelectNodes('//testsuite[not(testsuite)]'))
            }
            foreach ($suite in $suites) {
                $suiteName = [string]$suite.GetAttribute('name')
                if (-not [string]::IsNullOrWhiteSpace($suiteName)) { $suiteNames.Add($suiteName) }
                $cases = @($suite.SelectNodes('./testcase'))
                if ($cases.Count -eq 0 -and [string]::IsNullOrWhiteSpace($suiteName)) { continue }
                foreach ($testCase in $cases) {
                    $className = [string]$testCase.GetAttribute('classname')
                    if ([string]::IsNullOrWhiteSpace($className)) { $className = $suiteName }
                    if (Test-RemediationClassPattern -ExpectedPattern $ExpectedClass -ActualClass $className) {
                        $matchingCases++
                        if ($null -ne $testCase.SelectSingleNode('./skipped')) { $matchingSkipped++ }
                        if ($null -ne $testCase.SelectSingleNode('./failure')) { $matchingFailures++ }
                        if ($null -ne $testCase.SelectSingleNode('./error')) { $matchingErrors++ }
                    } else {
                        $outOfScopeCases++
                    }
                }
                if ($cases.Count -eq 0 -and (Test-RemediationClassPattern -ExpectedPattern $ExpectedClass -ActualClass $suiteName)) {
                    $tests = 0
                    $skipped = 0
                    $failures = 0
                    $errors = 0
                    [void][int]::TryParse($suite.GetAttribute('tests'), [ref]$tests)
                    [void][int]::TryParse($suite.GetAttribute('skipped'), [ref]$skipped)
                    [void][int]::TryParse($suite.GetAttribute('failures'), [ref]$failures)
                    [void][int]::TryParse($suite.GetAttribute('errors'), [ref]$errors)
                    $matchingCases += $tests
                    $matchingSkipped += $skipped
                    $matchingFailures += $failures
                    $matchingErrors += $errors
                }
            }
            $reportPaths.Add($file.FullName)
        } catch {
            $reportPaths.Add($file.FullName + ' [parse error: ' + $_.Exception.Message + ']')
        }
    }
    $instrumentationStarted = $false
    $explicitZero = $false
    $loggedCount = $null
    foreach ($logPath in $LogPaths) {
        $text = (Read-RemediationTextFile -Path $logPath -Position Tail -MaxBytes 262144)
        if ($text -match '(?i)(INSTRUMENTATION_STATUS:|INSTRUMENTATION_CODE:|Starting\s+\d+\s+tests?|Running\s+\d+\s+tests?|Starting instrumentation)') {
            $instrumentationStarted = $true
        }
        if ($text -match '(?i)\b0\s+tests?\b') { $explicitZero = $true }
        $countMatch = [Regex]::Match($text, '(?i)(\d+)\s+tests?\s+(?:completed|run|executed)')
        if ($countMatch.Success) {
            $value = 0
            if ([int]::TryParse($countMatch.Groups[1].Value, [ref]$value)) { $loggedCount = $value }
        }
    }
    $executed = $null
    $countBasis = 'unavailable'
    if ($files.Count -gt 0) {
        $executed = [Math]::Max(0, $matchingCases - $matchingSkipped)
        $countBasis = 'fresh_junit_xml'
    } elseif ($explicitZero) {
        $executed = 0
        $countBasis = 'explicit_zero_test_log'
    } elseif (-not $instrumentationStarted) {
        $executed = 0
        $countBasis = 'instrumentation_not_started'
    } elseif ($null -ne $loggedCount) {
        $executed = $loggedCount
        $countBasis = 'gradle_log_count'
    }
    return [pscustomobject][ordered]@{
        expectedClass = $ExpectedClass
        reportFilesFound = $files.Count
        reportPaths = @($reportPaths.ToArray())
        suiteNames = @($suiteNames.ToArray() | Select-Object -Unique)
        executedTests = $executed
        skippedTests = $matchingSkipped
        failureCount = $matchingFailures
        errorCount = $matchingErrors
        outOfScopeTestCaseCount = $outOfScopeCases
        scopeMatches = ($outOfScopeCases -eq 0)
        instrumentationStarted = ($instrumentationStarted -or ($null -ne $executed -and $executed -gt 0))
        countBasis = $countBasis
    }
}

function Get-RemediationGradlePhase {
    param([string]$StdoutPath, [string]$StderrPath)
    $text = (Read-RemediationTextFile -Path $StdoutPath -Position Tail -MaxBytes 131072) +
            [Environment]::NewLine +
            (Read-RemediationTextFile -Path $StderrPath -Position Tail -MaxBytes 131072)
    $phase = 'gradle_startup_configuration'
    foreach ($line in ($text -split '\r?\n')) {
        if ($line -match '(?i)(BUILD SUCCESSFUL|BUILD FAILED|Test results|results collected)') {
            $phase = 'result_collection'
        } elseif ($line -match '(?i)(INSTRUMENTATION_STATUS|Starting\s+\d+\s+tests?|Running\s+\d+\s+tests?|Test run started)') {
            $phase = 'instrumentation_or_test_execution'
        } elseif ($line -match '(?i)(install.*apk|installDebug|Installing APK|PackageInstaller|install session)') {
            $phase = 'apk_install_or_device_controller'
        } elseif ($line -match '(?i)(> Task .*?(package.*apk|assemble.*|packageDebug)|task .*?(package.*apk|assemble.*|packageDebug))') {
            $phase = 'apk_packaging'
        } elseif ($line -match '(?i)(> Task .*?(compile|ksp|kapt|process.*Resources|mergeResources|process.*Manifest)|task .*?(compile|ksp|kapt|process.*Resources|mergeResources|process.*Manifest))') {
            $phase = 'compilation_and_android_preparation'
        } elseif ($line -match '(?i)(> Task .*?(compile|ksp|mergeResources|process.*Manifest|package|assemble)|task .*?(compile|ksp|package|assemble))') {
            $phase = 'build_or_package'
        } elseif ($line -match '(?i)> Task ') {
            $phase = 'gradle_task_execution'
        }
    }
    return $phase
}

function ConvertTo-RemediationPhaseMap {
    param($Map)
    $output = [ordered]@{}
    foreach ($key in @('gradle_startup_configuration', 'gradle_task_execution', 'compilation_and_android_preparation', 'build_or_package', 'apk_packaging', 'apk_install_or_device_controller', 'instrumentation_or_test_execution', 'result_collection')) {
        $value = 0.0
        if ($null -ne $Map -and $Map.ContainsKey($key)) { $value = [Math]::Round([double]$Map[$key], 3) }
        $output[$key] = $value
    }
    return $output
}

function Get-RemediationInfrastructureSignals {
    param([string[]]$LogPaths)
    $text = ''
    foreach ($path in $LogPaths) {
        $text += [Environment]::NewLine + (Read-RemediationTextFile -Path $path -Position Tail -MaxBytes 262144)
    }
    $patterns = @(
        'ShellCommandUnresponsiveException',
        'INSTALL_FAILED_[A-Z0-9_]+',
        'INSTALL_PARSE_FAILED_[A-Z0-9_]+',
        'device offline',
        'no devices/emulators found',
        'ADB server didn.t ACK',
        'failed to install',
        'Unable to install',
        'Package Manager.{0,100}(not responding|timed out|timeout)',
        'PackageManager.{0,100}(not responding|timed out|timeout)',
        '(timed out|timeout).{0,100}(install|package manager|adb)',
        'device.{0,80}(unresponsive|not responding|offline)',
        'instrumentation.{0,100}(could not be started|failed to start|unresponsive)'
    )
    $matches = New-Object System.Collections.Generic.List[string]
    foreach ($pattern in $patterns) {
        foreach ($match in [Regex]::Matches($text, '(?im)' + $pattern)) {
            $matches.Add($match.Value.Trim())
        }
    }
    return @($matches.ToArray() | Select-Object -Unique)
}

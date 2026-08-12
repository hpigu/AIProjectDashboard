# update-fixture.ps1 — real Windows ZIP updater rollback matrix.
#
# This gate is intentionally Windows-only. It consumes the jlink ZIP produced by the same
# workflow run and exercises the packaged launcher/updater with isolated user state. Every
# cleanup path can force only the exact PID from the fixture PID file; its dedicated loopback
# port is asserted free before and after each case, never used to kill an unknown listener.

#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReleaseZip,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Fail { param([string]$Message) throw "[windows-update-fixture] $Message" }
function Assert-True {
    param([bool]$Condition, [string]$Description)
    if (-not $Condition) { Fail $Description }
    Write-Host "  [PASS] $Description"
}
function Write-Utf8NoBomLf {
    param([string]$Path, [string]$Content)
    $utf8NoBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllBytes($Path, $utf8NoBom.GetBytes($Content))
}
function Invoke-ChildPowerShell {
    param([string]$ScriptPath, [string[]]$Arguments)

    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1 |
            ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = ($lines -join "`n") }
}
function Invoke-Board {
    param([object]$Scenario, [string[]]$Arguments)
    return Invoke-ChildPowerShell -ScriptPath $Scenario.Board -Arguments $Arguments
}
function Assert-ExitCode {
    param([object]$Result, [int]$Expected, [string]$Description)
    if ($Result.ExitCode -ne $Expected) {
        Fail "$Description (expected exit $Expected, got $($Result.ExitCode))`n$($Result.Output)"
    }
    Write-Host "  [PASS] $Description"
}
function Assert-PortClosed {
    param([int]$Port, [string]$Description)

    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        $connected = $pending.AsyncWaitHandle.WaitOne(750, $false) -and $client.Connected
    } catch {
        $connected = $false
    } finally {
        $client.Dispose()
    }
    Assert-True (-not $connected) $Description
}
function Get-ProjectsJson {
    param([int]$Port)
    return (Invoke-WebRequest -Uri ("http://127.0.0.1:$Port/api/projects") -UseBasicParsing -TimeoutSec 5).Content
}
function Set-ScenarioEnvironment {
    param([object]$Scenario)

    $env:USERPROFILE = $Scenario.Profile
    # pwsh 7 的 $HOME 是唯讀自動變數，直接 $env:HOME = ... 會觸發
    # SessionStateUnauthorizedAccessException。用 .NET API 繞過。
    [Environment]::SetEnvironmentVariable('HOME', $Scenario.Profile, 'Process')
    $env:BOARD_HOME_DIR = $Scenario.Home
    $env:BOARD_PORT = [string]$Scenario.Port
    $env:BOARD_HOST = '127.0.0.1'
    $env:BOARD_DB_URL = 'jdbc:h2:file:' + $Scenario.DbBase + ';DB_CLOSE_ON_EXIT=FALSE'
    $env:BOARD_LOG_FILE = $Scenario.Log
    $env:BOARD_CONSOLE_LOG = $Scenario.ConsoleLog
    $env:BOARD_BACKUP_DIR = $Scenario.Backups
    $env:BOARD_CONFIG_DIR = $Scenario.Config
    $env:BOARD_PID_FILE = $Scenario.PidFile
    $env:BOARD_START_TIMEOUT_SEC = '120'
    $env:BOARD_STOP_TIMEOUT_SEC = '120'
    $env:BOARD_JAVA = 'C:\must-not-be-used\java.exe'
    $env:JAVA_HOME = 'C:\must-not-be-used'
    $env:BOARD_JAR = 'C:\must-not-be-used\server.jar'
    $env:BOARD_UPDATE_FAIL_AT = $null
}
function New-ChecksumFile {
    param([string]$Path, [string]$ZipPath, [bool]$Valid)

    $zipHash = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not $Valid) {
        $replacement = if ($zipHash[0] -eq '0') { '1' } else { '0' }
        $zipHash = $replacement + $zipHash.Substring(1)
    }
    $zero = '0' * 64
    $content = @(
        "$zero  ai-project-board-backend-linux-x64-$Version.jar",
        "$zero  ai-project-board-backend-macos-arm64-$Version.jar",
        "$zero  ai-project-board-backend-macos-x64-$Version.jar",
        "$zipHash  ai-project-board-backend-windows-x64-$Version.zip"
    ) -join "`n"
    Write-Utf8NoBomLf -Path $Path -Content ($content + "`n")
}
function New-Scenario {
    param([string]$Name, [int]$Port)

    # $scenarioRoot／$programs／$extract 只能用 ASCII：bundled 的 jlink runtime 會
    # 落在 $programs 底下並由 board.ps1 啟動，而 Windows 的 java.exe launcher 以
    # ANSI code page 解析自身路徑定位 java.dll，非 ASCII 路徑會失敗（could not
    # find java.dll）。這是 Windows 自帶 runtime 的已知限制。空白字元仍保留。
    # 下面的 $homeDir／Profile（使用者資料）維持中文，那些由 JVM 自己處理，不受限。
    $scenarioRoot = Join-Path $work ($Name + '-space path')
    $programs = Join-Path $scenarioRoot 'programs root'
    $extract = Join-Path $scenarioRoot 'extract temp'
    $assets = Join-Path $scenarioRoot 'release assets'
    New-Item -ItemType Directory -Path $programs, $extract, $assets -Force | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extract -Force
    $extracted = Join-Path $extract $expectedTop
    Assert-True (Test-Path -LiteralPath $extracted -PathType Container) "$Name 使用當次真實 jlink ZIP"
    $oldRoot = Join-Path $programs ('ai-project-board-backend-windows-x64-' + $oldVersion)
    Move-Item -LiteralPath $extracted -Destination $oldRoot -ErrorAction Stop
    $checksum = Join-Path $assets ('ai-project-board-backend-' + $Version + '-SHA256SUMS.txt')
    New-ChecksumFile -Path $checksum -ZipPath $zipPath -Valid $true
    $homeDir = Join-Path $scenarioRoot '使用者 資料'
    $scenario = [pscustomobject]@{
        Name = $Name
        Port = $Port
        Root = $scenarioRoot
        Programs = $programs
        OldRoot = $oldRoot
        TargetRoot = (Join-Path $programs $expectedTop)
        Board = (Join-Path $oldRoot 'bin\board.ps1')
        Profile = (Join-Path $scenarioRoot '使用者 Profile')
        Home = $homeDir
        DbBase = (Join-Path $homeDir 'data\fixture-board')
        Log = (Join-Path $homeDir 'logs\fixture-board.log')
        ConsoleLog = (Join-Path $homeDir 'logs\fixture-board.console.log')
        Backups = (Join-Path $homeDir 'backups')
        Config = (Join-Path $homeDir 'config')
        PidFile = (Join-Path $homeDir 'fixture-board.pid')
        Checksums = $checksum
    }
    Set-ScenarioEnvironment -Scenario $scenario
    Assert-PortClosed -Port $scenario.Port -Description "${Name}: fixture port was free before start"
    return $scenario
}
function Stop-ScenarioSafely {
    param([object]$Scenario)

    Set-ScenarioEnvironment -Scenario $Scenario
    # The port was asserted free before this scenario started. Even so, cleanup trusts only
    # the exact PID written to this isolated PID file; it never kills an unknown listener.
    $ownedPid = 0
    if (Test-Path -LiteralPath $Scenario.PidFile) {
        $pidText = (Get-Content -LiteralPath $Scenario.PidFile -Raw -ErrorAction SilentlyContinue).Trim()
        if ($pidText -match '^\d+$') { $ownedPid = [int]$pidText }
    }
    if ($ownedPid -gt 0) {
        if (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue) {
            Stop-Process -Id $ownedPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 500
        }
    }
    Assert-PortClosed -Port $Scenario.Port -Description "$($Scenario.Name): cleanup left fixture port closed"
}
function Start-And-Read {
    param([object]$Scenario)
    $started = Invoke-Board -Scenario $Scenario -Arguments @('start')
    Assert-ExitCode -Result $started -Expected 0 -Description "$($Scenario.Name): bundled start 成功"
    $status = Invoke-Board -Scenario $Scenario -Arguments @('status')
    Assert-ExitCode -Result $status -Expected 0 -Description "$($Scenario.Name): bundled status 成功"
    return Get-ProjectsJson -Port $Scenario.Port
}
function Stop-And-Assert {
    param([object]$Scenario, [string]$Description)
    $stopped = Invoke-Board -Scenario $Scenario -Arguments @('stop')
    Assert-ExitCode -Result $stopped -Expected 0 -Description "$Description stop 成功"
    $status = Invoke-Board -Scenario $Scenario -Arguments @('status')
    Assert-ExitCode -Result $status -Expected 3 -Description "$Description 保持 stopped"
    Assert-PortClosed -Port $Scenario.Port -Description "$Description loopback port 已釋放"
}
function Assert-Snapshot {
    param([object]$Scenario)

    $snapshots = @(Get-ChildItem -LiteralPath $Scenario.Backups -Directory -Filter "update-$oldVersion-to-$Version-*" -ErrorAction SilentlyContinue)
    Assert-True ($snapshots.Count -eq 1) "$($Scenario.Name): 保留唯一 updater DB snapshot"
    $snapshot = $snapshots[0].FullName
    $database = Join-Path $snapshot 'board.mv.db'
    $hashFile = Join-Path $snapshot 'SHA256SUMS.txt'
    $manifest = Join-Path $snapshot 'manifest.txt'
    Assert-True (Test-Path -LiteralPath $database -PathType Leaf) "$($Scenario.Name): snapshot 含 board.mv.db"
    Assert-True (Test-Path -LiteralPath $hashFile -PathType Leaf) "$($Scenario.Name): snapshot 含 SHA256 manifest"
    Assert-True (Test-Path -LiteralPath $manifest -PathType Leaf) "$($Scenario.Name): snapshot 含 transaction manifest"
    $hashLine = (Get-Content -LiteralPath $hashFile | Select-Object -First 1)
    Assert-True ($hashLine -match '^([0-9a-f]{64})  board\.mv\.db$') "$($Scenario.Name): snapshot hash row 格式有效"
    $expectedHash = $Matches[1]
    Assert-True ((Get-FileHash -LiteralPath $database -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expectedHash) `
        "$($Scenario.Name): snapshot 與 manifest hash 相符"
    $manifestText = Get-Content -LiteralPath $manifest -Raw
    Assert-True ($manifestText -match ("(?m)^current=" + [regex]::Escape($oldVersion) + '\r?$') -and
        $manifestText -match ("(?m)^target=" + [regex]::Escape($Version) + '\r?$') -and
        $manifestText -match ("(?m)^activation=" + [regex]::Escape($Scenario.OldRoot) + '\r?$')) `
        "$($Scenario.Name): transaction manifest 指向舊版 root 與目標版本"
    return $snapshot
}
function Invoke-NormalRollbackCase {
    param([string]$Fault, [int]$Port, [bool]$InitiallyRunning)

    $state = if ($InitiallyRunning) { 'running' } else { 'stopped' }
    $scenario = New-Scenario -Name ("$Fault-$state") -Port $Port
    try {
        $before = Start-And-Read -Scenario $scenario
        if (-not $InitiallyRunning) { Stop-And-Assert -Scenario $scenario -Description "$Fault original state" }
        $env:BOARD_UPDATE_FAIL_AT = $Fault
        $result = Invoke-Board -Scenario $scenario -Arguments @('update', '-Version', $Version, '-ReleaseZip', $zipPath, '-Checksums', $scenario.Checksums)
        Assert-True ($result.ExitCode -ne 0) "$Fault injection 讓 updater 失敗"
        Assert-True ($result.Output -match [regex]::Escape("failure injection: $Fault")) "$Fault failure 有明確診斷"
        Assert-True (Test-Path -LiteralPath $scenario.OldRoot -PathType Container) "$Fault rollback 還原舊 versioned root"
        Assert-True (-not (Test-Path -LiteralPath $scenario.TargetRoot)) "$Fault rollback 未留下 active target root"
        Assert-Snapshot -Scenario $scenario | Out-Null
        $env:BOARD_UPDATE_FAIL_AT = $null
        if ($InitiallyRunning) {
            $status = Invoke-Board -Scenario $scenario -Arguments @('status')
            Assert-ExitCode -Result $status -Expected 0 -Description "$Fault rollback 還原原 running 狀態"
            Assert-True ((Get-ProjectsJson -Port $scenario.Port) -eq $before) "$Fault rollback 後舊資料仍可讀"
            Stop-And-Assert -Scenario $scenario -Description "$Fault cleanup"
        } else {
            $status = Invoke-Board -Scenario $scenario -Arguments @('status')
            Assert-ExitCode -Result $status -Expected 3 -Description "$Fault rollback 還原原 stopped 狀態"
            $after = Start-And-Read -Scenario $scenario
            Assert-True ($after -eq $before) "$Fault stopped rollback 的舊資料可重新啟動讀取"
            Stop-And-Assert -Scenario $scenario -Description "$Fault stopped recovery check"
        }
    } finally {
        Stop-ScenarioSafely -Scenario $scenario
    }
}
function Invoke-ChecksumRejectionCase {
    param([int]$Port)

    $scenario = New-Scenario -Name 'checksum-rejection' -Port $Port
    try {
        $before = Start-And-Read -Scenario $scenario
        $badChecksums = Join-Path (Split-Path $scenario.Checksums -Parent) ('bad-' + (Split-Path $scenario.Checksums -Leaf))
        New-ChecksumFile -Path $badChecksums -ZipPath $zipPath -Valid $false
        # The updater also checks the checksum basename, so retain the contract filename in
        # a separate directory rather than weakening that precondition.
        $badDir = Join-Path $scenario.Root 'bad-checksums'
        New-Item -ItemType Directory -Path $badDir -Force | Out-Null
        $badContractPath = Join-Path $badDir (Split-Path $scenario.Checksums -Leaf)
        Move-Item -LiteralPath $badChecksums -Destination $badContractPath
        $headBytes = [IO.File]::ReadAllBytes($badContractPath)
        if ($headBytes.Length -ge 3 -and $headBytes[0] -eq 0xEF -and $headBytes[1] -eq 0xBB -and $headBytes[2] -eq 0xBF) {
            Fail "bad checksum file unexpectedly has a UTF-8 BOM at $badContractPath"
        }
        $result = Invoke-Board -Scenario $scenario -Arguments @('update', '-Version', $Version, '-ReleaseZip', $zipPath, '-Checksums', $badContractPath)
        if (-not ($result.ExitCode -ne 0 -and $result.Output -match 'SHA-256 verification failed')) {
            Write-Host "--- checksum rejection diag: ExitCode=$($result.ExitCode) ---"
            Write-Host "badContractPath=$badContractPath"
            Write-Host "fileSize=$([IO.File]::ReadAllBytes($badContractPath).Length)"
            Write-Host $result.Output
            Write-Host '--- end diag ---'
        }
        Assert-True ($result.ExitCode -ne 0 -and $result.Output -match 'SHA-256 verification failed') 'checksum mismatch 在 transaction 前被拒絕'
        Assert-True (Test-Path -LiteralPath $scenario.OldRoot -PathType Container) 'checksum 拒絕保留舊 versioned root'
        Assert-True (-not (Test-Path -LiteralPath $scenario.TargetRoot)) 'checksum 拒絕不建立 target root'
        $snapshots = @(Get-ChildItem -LiteralPath $scenario.Backups -Directory -Filter 'update-*' -ErrorAction SilentlyContinue)
        Assert-True ($snapshots.Count -eq 0) 'checksum 拒絕不進入 stop/backup transaction'
        $status = Invoke-Board -Scenario $scenario -Arguments @('status')
        Assert-ExitCode -Result $status -Expected 0 -Description 'checksum 拒絕保留原 running 狀態'
        Assert-True ((Get-ProjectsJson -Port $scenario.Port) -eq $before) 'checksum 拒絕後舊資料仍可讀'
        Stop-And-Assert -Scenario $scenario -Description 'checksum cleanup'
    } finally {
        Stop-ScenarioSafely -Scenario $scenario
    }
}
function Invoke-HardRollbackFailureCase {
    param([int]$Port)

    $scenario = New-Scenario -Name 'rollback-activate-failure' -Port $Port
    try {
        $before = Start-And-Read -Scenario $scenario
        $env:BOARD_UPDATE_FAIL_AT = 'readiness,rollback_activate'
        $result = Invoke-Board -Scenario $scenario -Arguments @('update', '-Version', $Version, '-ReleaseZip', $zipPath, '-Checksums', $scenario.Checksums)
        Assert-True ($result.ExitCode -ne 0) 'rollback activation injection 讓 updater fail closed'
        Assert-True ($result.Output -match 'rollback activation failed; service remains stopped') 'rollback 失敗明示 service remains stopped'
        Assert-PortClosed -Port $scenario.Port -Description 'rollback 失敗不啟動任何 runtime'
        Assert-True (-not (Test-Path -LiteralPath $scenario.OldRoot)) 'rollback activation 失敗不謊稱舊 root 已還原'
        $rollbackRoots = @(Get-ChildItem -LiteralPath $scenario.Programs -Directory -Filter ('.' + (Split-Path $scenario.OldRoot -Leaf) + '.rollback-*'))
        Assert-True ($rollbackRoots.Count -eq 1) 'rollback 失敗保留可恢復的舊 versioned root'
        Assert-True (Test-Path -LiteralPath (Join-Path $rollbackRoots[0].FullName 'bin\board.ps1') -PathType Leaf) '保留的舊 root 含 bundled launcher'
        $failedRoots = @(Get-ChildItem -LiteralPath $scenario.Programs -Directory -Filter ($expectedTop + '.failed-*'))
        Assert-True ($failedRoots.Count -eq 1) 'rollback 失敗隔離 target root 供診斷'
        $snapshot = Assert-Snapshot -Scenario $scenario
        $databaseRecovery = "Copy-Item -LiteralPath '$snapshot\board.mv.db' -Destination '$($scenario.DbBase + '.mv.db')' -Force"
        $rootRecovery = "Move-Item -LiteralPath '$($rollbackRoots[0].FullName)' -Destination '$($scenario.OldRoot)'"
        $startRecovery = "& '$($scenario.OldRoot)\bin\board.ps1' start"
        $databaseIndex = $result.Output.IndexOf($databaseRecovery)
        $rootIndex = $result.Output.IndexOf($rootRecovery)
        $startIndex = $result.Output.IndexOf($startRecovery)
        Assert-True ($databaseIndex -ge 0 -and $rootIndex -gt $databaseIndex -and $startIndex -gt $rootIndex) `
            'manual recovery 依序提供精確 DB restore、old root rename 與原 running start 命令'

        # Execute the documented recovery against the isolated scenario. Restore the verified
        # database snapshot while stopped, rename the retained old root, then prove it can
        # start/read/stop with the bundled runtime.
        Copy-Item -LiteralPath (Join-Path $snapshot 'board.mv.db') -Destination ($scenario.DbBase + '.mv.db') -Force
        Move-Item -LiteralPath $rollbackRoots[0].FullName -Destination $scenario.OldRoot -ErrorAction Stop
        $env:BOARD_UPDATE_FAIL_AT = $null
        $after = Start-And-Read -Scenario $scenario
        Assert-True ($after -eq $before) 'manual recovery 的 snapshot 與舊 runtime 可實際啟動讀取'
        Stop-And-Assert -Scenario $scenario -Description 'manual recovery final state'
    } finally {
        Stop-ScenarioSafely -Scenario $scenario
    }
}

if ($env:OS -ne 'Windows_NT') { Fail '此 updater fixture 必須在 Windows x64 runner 執行。' }
if ($env:PROCESSOR_ARCHITECTURE -and $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { Fail '此 updater fixture 必須在 Windows x64 執行。' }

$zipPath = (Resolve-Path -LiteralPath $ReleaseZip -ErrorAction Stop).Path
$expectedTop = 'ai-project-board-backend-windows-x64-' + $Version
if ((Split-Path $zipPath -Leaf) -ne ($expectedTop + '.zip')) { Fail "ReleaseZip 必須是 $expectedTop.zip" }
$versionParts = $Version.Split('.')
$oldPatch = if ([int]$versionParts[2] -eq 0) { 1 } else { [int]$versionParts[2] - 1 }
$oldVersion = "$($versionParts[0]).$($versionParts[1]).$oldPatch"
# ASCII only：這是所有 scenario 的父目錄，bundled runtime 就解壓在它底下，
# 理由見 New-Scenario 內的說明。空白字元保留。
$work = Join-Path ([IO.Path]::GetTempPath()) ('board-update-release-fixture-' + [Guid]::NewGuid().ToString('N') + '-space test')
$environmentNames = @('USERPROFILE', 'HOME', 'BOARD_HOME_DIR', 'BOARD_PORT', 'BOARD_HOST', 'BOARD_DB_URL',
    'BOARD_LOG_FILE', 'BOARD_CONSOLE_LOG', 'BOARD_BACKUP_DIR', 'BOARD_CONFIG_DIR', 'BOARD_PID_FILE',
    'BOARD_START_TIMEOUT_SEC', 'BOARD_STOP_TIMEOUT_SEC', 'BOARD_JAVA', 'JAVA_HOME', 'BOARD_JAR', 'BOARD_UPDATE_FAIL_AT')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

try {
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    Write-Host '=== checksum fail-before-mutation ==='
    Invoke-ChecksumRejectionCase -Port 18161
    Write-Host '=== normal rollback fault matrix (originally running) ==='
    Invoke-NormalRollbackCase -Fault 'publish' -Port 18162 -InitiallyRunning $true
    Invoke-NormalRollbackCase -Fault 'activate' -Port 18163 -InitiallyRunning $true
    Invoke-NormalRollbackCase -Fault 'start' -Port 18164 -InitiallyRunning $true
    Invoke-NormalRollbackCase -Fault 'readiness' -Port 18165 -InitiallyRunning $true
    Write-Host '=== original stopped state ==='
    Invoke-NormalRollbackCase -Fault 'readiness' -Port 18166 -InitiallyRunning $false
    Write-Host '=== rollback itself fails closed ==='
    Invoke-HardRollbackFailureCase -Port 18167
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host '[windows-update-fixture] 全部通過。'

# release-check.ps1 — validate a Windows x64 jlink release ZIP.
#
# Run after package-windows-x64.ps1 on a Windows x64 runner:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\release-check.ps1 `
#     -ReleaseZip target\release\ai-project-board-backend-windows-x64-3.1.1.zip -Smoke
#
# -Smoke is deliberately opt-in: it starts the supplied artifact on an isolated loopback
# port and proves the bundled launcher works without JAVA_HOME or a Java on PATH.

#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReleaseZip,
    [switch]$Smoke
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Fail { param([string]$Message) throw "[windows-release-check] $Message" }
function Assert-True {
    param([bool]$Condition, [string]$Description)
    if (-not $Condition) { Fail $Description }
    Write-Host "  [PASS] $Description"
}

function Get-ZipTopLevel {
    # Keep this untyped: Windows PowerShell 5.1 parses function signatures before the
    # System.IO.Compression assembly is loaded below.
    param([object]$Archive)
    # 先把分隔符正規化再切：ZIP 規格要求 '/'，但 .NET 在 Windows 上建立的
    # entry 可能帶 '\'。少了這步，'a\b\c' 會被當成單一段落，同一份 ZIP 的每個
    # 檔案都成為獨立的「top-level」，於是這裡誤報「必須只有一個 top-level」。
    # 下一行的 $names 與 package-windows-x64.ps1 的 Assert-ZipLayout 都已這樣做，
    # 這裡漏掉會讓打包端通過、驗收端卻失敗。
    $topLevels = @($Archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/').Split('/')[0] } | Where-Object { $_ } | Select-Object -Unique)
    if ($topLevels.Count -ne 1) { Fail 'ZIP 必須只有一個 top-level 目錄。' }
    return $topLevels[0]
}

if ($env:OS -ne 'Windows_NT') { Fail 'Windows x64 release ZIP 必須在 Windows 驗證。' }
if ($env:PROCESSOR_ARCHITECTURE -and $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { Fail '必須在 Windows x64 驗證。' }

$zipPath = (Resolve-Path -LiteralPath $ReleaseZip -ErrorAction Stop).Path
if ((Split-Path $zipPath -Leaf) -notmatch '^ai-project-board-backend-windows-x64-([0-9]+(\.[0-9]+){1,5}([-.][0-9A-Za-z.]+)?)\.zip$') {
    Fail "release ZIP 檔名不符合契約：$(Split-Path $zipPath -Leaf)"
}
$version = $Matches[1]
$expectedTop = 'ai-project-board-backend-windows-x64-' + $version
$expectedJar = 'ai-project-board-backend-' + $version + '.jar'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $topLevel = Get-ZipTopLevel -Archive $archive
    Assert-True ($topLevel -eq $expectedTop) "單一 top-level 為 $expectedTop"
    $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    foreach ($required in @(
        "$expectedTop/app/$expectedJar",
        "$expectedTop/bin/board.ps1",
        "$expectedTop/bin/board-update.ps1",
        "$expectedTop/bin/board-env.ps1",
        "$expectedTop/bin/backup-db.ps1",
        "$expectedTop/runtime/bin/java.exe",
        "$expectedTop/app/release-metadata.json")) {
        Assert-True ($names -contains $required) "含必要路徑 $required"
    }
    foreach ($entry in $names) {
        Assert-True ($entry.StartsWith($expectedTop + '/')) "沒有額外 top-level：$entry"
        Assert-True ($entry -notmatch '(^|/)\.\.(/|$)') "沒有 traversal 路徑：$entry"
    }
    foreach ($forbidden in @('/pom.xml', '/mvnw', '/mvnw.cmd', '/.git/', '/src/', '/target/', '/plugin/', '/plugins/', '.sh')) {
        Assert-True (@($names | Where-Object { $_.Contains($forbidden) }).Count -eq 0) "沒有洩漏 $forbidden"
    }
} finally {
    $archive.Dispose()
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ('board-release-check-' + [Guid]::NewGuid().ToString('N') + '-空白')
$extract = Join-Path $work '解壓 空白'
$profile = Join-Path $work '使用者 空白'
$smokeBoard = $null
$smokeStarted = $false
try {
    New-Item -ItemType Directory -Path $extract -Force | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extract -Force
    $root = Join-Path $extract $expectedTop
    $runtimeJava = Join-Path $root 'runtime\bin\java.exe'
    $jar = Join-Path $root ('app\' + $expectedJar)
    Assert-True (Test-Path -LiteralPath $runtimeJava -PathType Leaf) 'bundled java.exe 可定位'
    Assert-True (Test-Path -LiteralPath $jar -PathType Leaf) 'bundled server JAR 可定位'

    $versionProbe = & {
        $ErrorActionPreference = 'Continue'
        $text = (& $runtimeJava -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
        [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Text = $text
        }
    }
    if ($versionProbe.ExitCode -ne 0 -or $versionProbe.Text -notmatch 'version "21') {
        Fail "bundled runtime 不是可執行的 Java 21（exit $($versionProbe.ExitCode)）。輸出：$($versionProbe.Text)"
    }
    Write-Host '  [PASS] bundled runtime 為 Java 21'

    $metadata = Get-Content -LiteralPath (Join-Path $root 'app\release-metadata.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ($metadata.version -eq $version) 'release metadata version 正確'
    Assert-True ($metadata.jar -eq $expectedJar) 'release metadata JAR 正確'
    Assert-True ($metadata.defaultHost -eq '127.0.0.1' -and $metadata.enforcedHost -eq '127.0.0.1' -and $metadata.healthUrl -eq 'http://127.0.0.1:<BOARD_PORT>/api/health') `
        'release metadata 固定 loopback health 契約'

    foreach ($scriptPath in @(
        (Join-Path $root 'bin\board.ps1'),
        (Join-Path $root 'bin\board-update.ps1'),
        (Join-Path $root 'bin\board-env.ps1'),
        (Join-Path $root 'bin\backup-db.ps1'))) {
        $bytes = [System.IO.File]::ReadAllBytes($scriptPath)
        Assert-True ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) `
            "$(Split-Path $scriptPath -Leaf) 是 UTF-8 BOM（PowerShell 5.1）"
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$parseErrors) | Out-Null
        Assert-True ((-not $parseErrors) -or $parseErrors.Count -eq 0) "$(Split-Path $scriptPath -Leaf) 可解析"
    }

    # Dot-source the packaged environment with an unset Board configuration. This proves
    # its defaults are user-scope and not the extracted program directory. It also creates
    # user-only ACL directories using the exact helpers the launcher will use.
    $saved = @{}
    foreach ($name in @('USERPROFILE', 'BOARD_PORT', 'BOARD_HOST', 'BOARD_HOME_DIR', 'BOARD_DB_URL', 'BOARD_LOG_FILE', 'BOARD_BACKUP_DIR', 'BOARD_PID_FILE', 'BOARD_CONFIG_DIR', 'BOARD_JAVA', 'JAVA_HOME', 'BOARD_JAR', 'BOARD_START_TIMEOUT_SEC', 'BOARD_STOP_TIMEOUT_SEC')) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    [Environment]::SetEnvironmentVariable('USERPROFILE', $profile, 'Process')
    try {
        $RepoRoot = $root
        . (Join-Path $root 'bin\board-env.ps1')
        Assert-True $script:BoardIsBundledRelease 'launcher 偵測到 bundled release layout'
        Assert-True ($script:BoardHomeDir -eq (Join-Path $profile '.ai-project-board')) '預設 home 在 user scope'
        Assert-True ($script:BoardDbUrl -match [regex]::Escape((Join-Path $script:BoardHomeDir 'data\board'))) '預設資料庫在 user scope'
        Assert-True ($script:BoardLogFile -eq (Join-Path $script:BoardHomeDir 'logs\board.log')) '預設 log 在 user scope'
        Assert-True ($script:BoardBackupDir -eq (Join-Path $script:BoardHomeDir 'backups')) '預設 backup 在 user scope'
        Assert-True ($script:BoardPidFile -eq (Join-Path $script:BoardHomeDir 'board.pid')) '預設 PID 在 user scope'
        Assert-True ($script:BoardConfigDir -eq (Join-Path $script:BoardHomeDir 'config')) '預設 config 在 user scope'
        foreach ($dir in @($script:BoardHomeDir, $script:BoardConfigDir, (Split-Path $script:BoardLogFile), $script:BoardBackupDir)) {
            Assert-True (New-BoardSecureDirectory -Path $dir) "可建立並收斂 ACL：$dir"
            $acl = Get-Acl -LiteralPath $dir
            Assert-True ($acl.AreAccessRulesProtected -and $acl.Access.Count -eq 1) "ACL 僅限目前使用者：$dir"
        }
    } finally {
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }

    if ($Smoke) {
        $smokeHome = Join-Path $work '資料 空白'
        $env:BOARD_HOME_DIR = $smokeHome
        $env:BOARD_PORT = '18147'
        $env:BOARD_START_TIMEOUT_SEC = '90'
        $env:BOARD_STOP_TIMEOUT_SEC = '90'
        $env:BOARD_JAVA = 'C:\must-not-be-used\java.exe'
        $env:JAVA_HOME = 'C:\must-not-be-used'
        $env:BOARD_JAR = 'C:\must-not-be-used\server.jar'
        $env:BOARD_HOST = '0.0.0.0'
        $board = Join-Path $root 'bin\board.ps1'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $board start
        Assert-True ($LASTEXITCODE -ne 0) 'release launcher 拒絕公開 BOARD_HOST'
        $env:BOARD_HOST = $null
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $board start
        Assert-True ($LASTEXITCODE -eq 0) 'start 使用 bundled runtime 成功'
        $smokeBoard = $board
        $smokeStarted = $true
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $board status
        Assert-True ($LASTEXITCODE -eq 0) 'status 回報 health 成功'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $board stop
        Assert-True ($LASTEXITCODE -eq 0) 'stop 成功且不依賴 bash/lsof'
        $smokeStarted = $false
    }
} finally {
    if ($smokeStarted -and $smokeBoard) {
        # A failed status assertion must not strand the test JVM. Use the bundled
        # lifecycle command (PID/port validation), never a process-name kill.
        $previous = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $smokeBoard stop 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $previous
        }
    }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host '[windows-release-check] 全部通過。'

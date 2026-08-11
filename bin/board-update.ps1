# board-update.ps1 — explicit transactional updater for the portable Windows release ZIP.
# It is intentionally invoked only by `board.ps1 update -Version V ...`; it never discovers
# “latest”, polls GitHub, touches plugin marketplaces, or runs automatically.

#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$ReleaseUrl,
    [string]$ReleaseZip,
    [string]$Checksums,
    [switch]$Check
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Log { param([string]$Message) Write-Host "[board update] $Message" }
function Fail { param([string]$Message) throw "[board update] $Message" }
function Test-FailAt { param([string]$Step) return ($env:BOARD_UPDATE_FAIL_AT -eq $Step) }
function Get-Sha256 { param([string]$Path) return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { Fail $Message } }

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { Fail '-Version must be an explicit stable X.Y.Z value' }
if ($ReleaseUrl -and $ReleaseUrl -match '(?i)latest') { Fail 'mutable latest URLs are forbidden; provide the exact vV release asset URL' }
if ($ReleaseUrl -and $ReleaseUrl -notmatch ('/v' + [regex]::Escape($Version) + '/?$')) { Fail '-ReleaseUrl must name the exact immutable vV release' }
if ($ReleaseUrl -and ($ReleaseZip -or $Checksums)) { Fail 'do not combine -ReleaseUrl with local offline artifacts' }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$rootName = Split-Path $root -Leaf
if ($rootName -notmatch '^ai-project-board-backend-windows-x64-([0-9]+\.[0-9]+\.[0-9]+)$') {
    Fail 'update is only available from an extracted stable Windows release root'
}
$currentVersion = $Matches[1]
Log "current=v$currentVersion target=v$Version"
if ($currentVersion -eq $Version) {
    Log 'target is already active; no files, downloads, or service state changed'
    exit 0
}
if (-not $ReleaseUrl -and ((-not $ReleaseZip) -or (-not $Checksums))) { Fail 'provide -ReleaseZip and -Checksums, or -ReleaseUrl' }

$download = $null
try {
    if ($ReleaseUrl) {
        $download = Join-Path ([IO.Path]::GetTempPath()) ('board-update-download-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $download -Force | Out-Null
        $ReleaseZip = Join-Path $download ('ai-project-board-backend-windows-x64-' + $Version + '.zip')
        $Checksums = Join-Path $download ('ai-project-board-backend-' + $Version + '-SHA256SUMS.txt')
        Log "downloading explicitly requested stable v$Version assets"
        try {
            Invoke-WebRequest -Uri (($ReleaseUrl.TrimEnd('/')) + '/' + (Split-Path $Checksums -Leaf)) -OutFile $Checksums -UseBasicParsing -ErrorAction Stop
            Invoke-WebRequest -Uri (($ReleaseUrl.TrimEnd('/')) + '/' + (Split-Path $ReleaseZip -Leaf)) -OutFile $ReleaseZip -UseBasicParsing -ErrorAction Stop
        } catch { Fail 'cannot download target release assets; current installation was not changed' }
    }

    $zipPath = (Resolve-Path -LiteralPath $ReleaseZip -ErrorAction Stop).Path
    $checksumsPath = (Resolve-Path -LiteralPath $Checksums -ErrorAction Stop).Path
    $expectedZip = 'ai-project-board-backend-windows-x64-' + $Version + '.zip'
    $expectedChecksums = 'ai-project-board-backend-' + $Version + '-SHA256SUMS.txt'
    Assert-True ((Split-Path $zipPath -Leaf) -eq $expectedZip) "wrong platform or version artifact: expected $expectedZip"
    Assert-True ((Split-Path $checksumsPath -Leaf) -eq $expectedChecksums) 'checksum filename/version does not match target'

    $bytes = [IO.File]::ReadAllBytes($checksumsPath)
    Assert-True ($bytes.Length -gt 0 -and $bytes[-1] -eq 0x0a -and -not ($bytes -contains 0x0d) -and -not ($bytes -contains 0x00)) 'checksum list must be ASCII UTF-8 with LF only'
    $checksumText = [Text.Encoding]::UTF8.GetString($bytes)
    Assert-True (-not $checksumText.StartsWith([char]0xfeff)) 'checksum list must not contain a BOM'
    $expectedRows = @(
        "ai-project-board-backend-linux-x64-$Version.jar",
        "ai-project-board-backend-macos-arm64-$Version.jar",
        "ai-project-board-backend-macos-x64-$Version.jar",
        $expectedZip)
    $rows = @($checksumText.TrimEnd("`n").Split("`n"))
    Assert-True ($rows.Count -eq 4) 'checksum list must contain exactly four rows'
    $hashes = @{}
    for ($i = 0; $i -lt $rows.Count; $i++) {
        if ($rows[$i] -notmatch '^([0-9a-f]{64})  ([^\\/\s]+)$') { Fail 'checksum list has invalid row format' }
        Assert-True ($Matches[2] -eq $expectedRows[$i]) 'checksum list entries/order/version violate release contract'
        $hashes[$Matches[2]] = $Matches[1]
    }
    Assert-True ((Get-Sha256 $zipPath) -eq $hashes[$expectedZip]) 'SHA-256 verification failed'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $top = 'ai-project-board-backend-windows-x64-' + $Version
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        Assert-True (@($names | Where-Object { -not $_.StartsWith($top + '/') -or $_ -match '(^|/)\.\.(/|$)' }).Count -eq 0) 'release ZIP has unsafe layout'
        foreach ($required in @("$top/app/ai-project-board-backend-$Version.jar", "$top/bin/board.ps1", "$top/bin/board-update.ps1", "$top/runtime/bin/java.exe")) {
            Assert-True ($names -contains $required) "release ZIP lacks required path: $required"
        }
    } finally { $archive.Dispose() }

    if ($Check) { Log 'target passed ZIP/layout/checksum validation; -Check makes no change'; exit 0 }

    # Keep staging and rollback siblings of the program root: same-volume rename is the only
    # activation primitive used here. Data is deliberately never inside either directory.
    $parent = Split-Path $root -Parent
    $leaf = Split-Path $root -Leaf
    $targetRoot = Join-Path $parent ('ai-project-board-backend-windows-x64-' + $Version)
    if (Test-Path -LiteralPath $targetRoot) { Fail "target versioned root already exists; refusing to overwrite or guess: $targetRoot" }
    $stage = Join-Path $parent ('.' + $leaf + '.update-stage-' + [Guid]::NewGuid().ToString('N'))
    $rollback = Join-Path $parent ('.' + $leaf + '.rollback-' + $currentVersion + '-' + (Get-Date -Format 'yyyyMMddTHHmmssZ'))
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $stage -Force
    $stagedRoot = Join-Path $stage ('ai-project-board-backend-windows-x64-' + $Version)
    Assert-True (Test-Path (Join-Path $stagedRoot 'runtime\bin\java.exe')) 'staged runtime is missing'
    Assert-True (Test-Path (Join-Path $stagedRoot ('app\ai-project-board-backend-' + $Version + '.jar'))) 'staged JAR is missing'
    $javaVersion = (& (Join-Path $stagedRoot 'runtime\bin\java.exe') -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
    Assert-True ($javaVersion -match 'version "21') 'staged bundled runtime is not Java 21'

    $oldBoard = Join-Path $root 'bin\board.ps1'
    $wasRunning = $false
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $oldBoard status *> $null
    if ($LASTEXITCODE -eq 0) { $wasRunning = $true } elseif ($LASTEXITCODE -ne 3) { Fail 'cannot establish current readiness; refusing update' }

    $RepoRoot = $root
    . (Join-Path $root 'bin\board-env.ps1')
    $dbBase = Get-BoardDbFilePath
    $snapshot = Join-Path $script:BoardBackupDir ('update-' + $currentVersion + '-to-' + $Version + '-' + (Get-Date -Format 'yyyyMMddTHHmmssZ'))
    $oldMoved = $false; $switched = $false; $snapshotReady = $false
    function Restore-Old {
        param([string]$Reason)
        Write-Host "[board update][錯誤] update failed at $Reason; beginning rollback"
        $newBoard = Join-Path $targetRoot 'bin\board.ps1'
        if (Test-Path $newBoard) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $newBoard stop *> $null }
        if ($oldMoved) {
            $failed = $targetRoot + '.failed-' + [Guid]::NewGuid().ToString('N')
            try {
                if (Test-Path $targetRoot) { Move-Item -LiteralPath $targetRoot -Destination $failed -ErrorAction Stop }
                Move-Item -LiteralPath $rollback -Destination $root -ErrorAction Stop
            } catch { Write-Host "[board update][錯誤] rollback activation failed; recover manually: rename '$rollback' to '$root'"; return $false }
        }
        if ($snapshotReady -and $dbBase -and (Test-Path (Join-Path $snapshot 'board.mv.db'))) {
            $expected = (Get-Content (Join-Path $snapshot 'SHA256SUMS.txt') | Where-Object { $_ -match ' board\.mv\.db$' } | Select-Object -First 1).Split(' ')[0]
            if ($expected -and (Get-Sha256 (Join-Path $snapshot 'board.mv.db')) -eq $expected) { Copy-Item -LiteralPath (Join-Path $snapshot 'board.mv.db') -Destination ($dbBase + '.mv.db') -Force }
        }
        if ($wasRunning) {
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'bin\board.ps1') start *> $null
            if ($LASTEXITCODE -ne 0) { Write-Host "[board update][錯誤] rollback start failed; inspect '$snapshot' and '$rollback'"; return $false }
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'bin\board.ps1') status *> $null
            if ($LASTEXITCODE -ne 0) { Write-Host "[board update][錯誤] rollback readiness failed; inspect '$snapshot' and '$rollback'"; return $false }
        }
        return $true
    }

    $stopFailed = Test-FailAt 'stop'
    if ($wasRunning -and -not $stopFailed) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $oldBoard stop *> $null
        if ($LASTEXITCODE -ne 0) { $stopFailed = $true }
    }
    if ($stopFailed) {
        if ($wasRunning) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $oldBoard start *> $null }
        Fail 'service stop failed; old activation was retained'
    }
    try {
        if (Test-FailAt 'backup') { Fail 'failure injection: backup' }
        New-Item -ItemType Directory -Path $snapshot -Force | Out-Null
        if ($dbBase -and (Test-Path ($dbBase + '.mv.db'))) { Copy-Item -LiteralPath ($dbBase + '.mv.db') -Destination (Join-Path $snapshot 'board.mv.db') -Force }
        if (Test-Path (Join-Path $snapshot 'board.mv.db')) { (Get-Sha256 (Join-Path $snapshot 'board.mv.db')) + '  board.mv.db' | Set-Content -LiteralPath (Join-Path $snapshot 'SHA256SUMS.txt') -Encoding ASCII }
        "current=$currentVersion`ntarget=$Version`nactivation=$root" | Set-Content -LiteralPath (Join-Path $snapshot 'manifest.txt') -Encoding UTF8
        $snapshotReady = $true
    } catch {
        Restore-Old -Reason 'backup' | Out-Null
        throw
    }

    try {
        if (Test-FailAt 'publish') { Fail 'failure injection: publish' }
        Move-Item -LiteralPath $root -Destination $rollback -ErrorAction Stop
        $oldMoved = $true
        if (Test-FailAt 'activate') { Fail 'failure injection: activate' }
        Move-Item -LiteralPath $stagedRoot -Destination $targetRoot -ErrorAction Stop
        $switched = $true
        $newBoard = Join-Path $targetRoot 'bin\board.ps1'
        if (Test-FailAt 'start') { Fail 'failure injection: start' }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $newBoard start
        if ($LASTEXITCODE -ne 0) { Fail 'new runtime could not start' }
        if (Test-FailAt 'readiness') { Fail 'failure injection: readiness' }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $newBoard status *> $null
        if ($LASTEXITCODE -ne 0) { Fail 'new runtime did not become ready' }
        $health = (Invoke-WebRequest -Uri ('http://127.0.0.1:' + $script:BoardPort + '/api/health') -UseBasicParsing -TimeoutSec 3).Content
        if ($health -notmatch ('"version"\s*:\s*"' + [regex]::Escape($Version) + '"') -or $health -notmatch '"commit"\s*:\s*"[0-9a-f]{7,40}"') { Fail 'ready runtime did not report target version and commit' }
        if (-not $wasRunning) {
            if (Test-FailAt 'stop_after_validation') { Fail 'failure injection: stop_after_validation' }
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $newBoard stop *> $null
            if ($LASTEXITCODE -ne 0) { Fail 'target validation completed but could not stop target runtime' }
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $newBoard status *> $null
            if ($LASTEXITCODE -ne 3) { Fail 'target validation completed but service did not return to original stopped state' }
        }
    } catch {
        $message = $_.Exception.Message
        Restore-Old -Reason $message | Out-Null
        throw
    }
    $newEntry = Join-Path $targetRoot 'bin\board.ps1'
    if ($wasRunning) { $state = 'service restored to ready' } else { $state = 'target verified, then returned to stopped' }
    Log "updated transactionally: v$currentVersion -> v$Version; $state; new entry: $newEntry; old runtime retained at $rollback"
} finally {
    if ($download -and (Test-Path $download)) { Remove-Item -LiteralPath $download -Recurse -Force -ErrorAction SilentlyContinue }
}

# package-windows-x64.ps1 — produce the self-contained Windows x64 release ZIP.
#
# This script is deliberately Windows-only: jlink images contain native launchers, so a
# macOS/Linux host cannot produce a Windows runtime safely.  It is suitable for a
# windows-latest release job and never reads a system Java at runtime; the supplied JDK
# is the sole build input used for jlink.
#
# Example (after Maven has built the server JAR):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\package-windows-x64.ps1 `
#     -ServerJar target\ai-project-board-backend-3.1.1.jar `
#     -JdkHome "$env:JAVA_HOME" -Version 3.1.1 -OutputDirectory target\release

#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+(\.[0-9]+){1,5}([-.][0-9A-Za-z.]+)?$')][string]$Version,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    # jdeps cannot reliably discover Spring/Hibernate reflection or Java service loading.
    # This explicit, reviewed allowlist is the reproducible module contract for this image.
    [string]$JlinkModules = 'java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.smartcardio,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Fail {
    param([string]$Message)
    throw "[package-windows-x64] $Message"
}

function Get-JavaText {
    param([string]$Java, [string[]]$Arguments)

    # java -version writes to stderr. Windows PowerShell 5.1 turns that into an
    # ErrorRecord under ErrorActionPreference=Stop, so isolate and downgrade it.
    return & {
        $ErrorActionPreference = 'Continue'
        (& $Java @Arguments 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
    }
}

function Assert-Java21X64 {
    param([string]$Java)

    if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { Fail "JDK java.exe 不存在：$Java" }
    $versionText = Get-JavaText -Java $Java -Arguments @('-version')
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch 'version "21(?:\.|"|-)') {
        Fail "JDK 必須是 Java 21，實際輸出：$versionText"
    }
    $settingsText = Get-JavaText -Java $Java -Arguments @('-XshowSettings:properties', '-version')
    if ($LASTEXITCODE -ne 0 -or $settingsText -notmatch '(?m)^\s*os\.arch\s*=\s*(amd64|x86_64)\s*$') {
        Fail "JDK 必須是 Windows x64（os.arch=amd64），實際輸出：$settingsText"
    }
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding($false)))
}

function Assert-ServerJar {
    param([string]$JarPath, [string]$ExpectedVersion)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    } catch {
        Fail "ServerJar 不是可讀的 executable JAR：$JarPath ($($_.Exception.Message))"
    }
    try {
        $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $manifestEntry) { Fail 'ServerJar 缺少 META-INF/MANIFEST.MF。' }
        $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($manifest -notmatch ('(?m)^Implementation-Version:\s*' + [regex]::Escape($ExpectedVersion) + '\s*$')) {
            Fail "ServerJar 的 Implementation-Version 必須是 $ExpectedVersion。"
        }
        if ($manifest -notmatch '(?m)^Main-Class:\s*org\.springframework\.boot\.loader\.launch\.JarLauncher\s*$') {
            Fail 'ServerJar 不是 Spring Boot executable JAR。'
        }
        if (-not $archive.GetEntry('BOOT-INF/classes/dev/aiboard/health/HealthService.class')) {
            Fail 'ServerJar 缺少 health endpoint class，無法建立可驗證的 release。'
        }
    } finally {
        $archive.Dispose()
    }
}

function Assert-StagingTree {
    param([string]$Root, [string]$ExpectedJarName, [string]$ExpectedVersion)

    $required = @(
        (Join-Path $Root ('app\' + $ExpectedJarName)),
        (Join-Path $Root 'bin\board.ps1'),
        (Join-Path $Root 'bin\board-update.ps1'),
        (Join-Path $Root 'bin\board-env.ps1'),
        (Join-Path $Root 'bin\backup-db.ps1'),
        (Join-Path $Root 'runtime\bin\java.exe'),
        (Join-Path $Root 'app\release-metadata.json')
    )
    foreach ($path in $required) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "staging 缺少必要檔案：$path" }
    }

    $metadata = Get-Content -LiteralPath (Join-Path $Root 'app\release-metadata.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($metadata.version -ne $ExpectedVersion -or $metadata.jar -ne $ExpectedJarName -or
        $metadata.healthUrl -ne 'http://127.0.0.1:<BOARD_PORT>/api/health' -or
        $metadata.defaultHost -ne '127.0.0.1' -or $metadata.enforcedHost -ne '127.0.0.1') {
        Fail 'release-metadata.json 的 version／JAR／loopback health metadata 不符合契約。'
    }

    # The staging tree is assembled from an explicit allowlist. Keep a second negative
    # guard so a future copy change cannot accidentally put source, Maven, or plugin cache
    # into the shipping archive.
    $forbidden = @('pom.xml', 'mvnw', 'mvnw.cmd', '.git', 'src', 'target', 'plugin', 'plugins')
    foreach ($name in $forbidden) {
        if (Test-Path -LiteralPath (Join-Path $Root $name)) { Fail "staging 不得包含：$name" }
    }
}

function Assert-ZipLayout {
    param([string]$ZipPath, [string]$TopLevel, [string]$ExpectedJarName)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        if ($names.Count -eq 0) { Fail 'ZIP 為空。' }
        foreach ($name in $names) {
            if (-not $name.StartsWith($TopLevel + '/')) { Fail "ZIP 有非單一 top-level 的項目：$name" }
            if ($name -match '(^|/)\.\.(/|$)') { Fail "ZIP 有不安全路徑：$name" }
        }
        foreach ($required in @(
            "$TopLevel/app/$ExpectedJarName",
            "$TopLevel/bin/board.ps1",
            "$TopLevel/bin/board-update.ps1",
            "$TopLevel/runtime/bin/java.exe",
            "$TopLevel/app/release-metadata.json")) {
            if ($names -notcontains $required) { Fail "ZIP 缺少契約路徑：$required" }
        }
        foreach ($forbidden in @('/pom.xml', '/mvnw', '/mvnw.cmd', '/.git/', '/src/', '/target/', '/plugin/', '/plugins/')) {
            if (@($names | Where-Object { $_.Contains($forbidden) }).Count -gt 0) { Fail "ZIP 含禁止內容：$forbidden" }
        }
    } finally {
        $archive.Dispose()
    }
}

if ($env:OS -ne 'Windows_NT') { Fail '此腳本只能在 Windows runner 執行，jlink image 含 Windows 原生 java.exe。' }
if ($env:PROCESSOR_ARCHITECTURE -and $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    Fail "必須在 Windows x64 runner 執行，實際 PROCESSOR_ARCHITECTURE=$env:PROCESSOR_ARCHITECTURE"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$serverJarPath = (Resolve-Path -LiteralPath $ServerJar -ErrorAction Stop).Path
$jdkHomePath = (Resolve-Path -LiteralPath $JdkHome -ErrorAction Stop).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$expectedJarName = 'ai-project-board-backend-' + $Version + '.jar'
$topLevel = 'ai-project-board-backend-windows-x64-' + $Version
$finalZip = Join-Path $outputPath ($topLevel + '.zip')
$java = Join-Path $jdkHomePath 'bin\java.exe'
$jlink = Join-Path $jdkHomePath 'bin\jlink.exe'
$jmods = Join-Path $jdkHomePath 'jmods'

if ((Split-Path $serverJarPath -Leaf) -ne $expectedJarName) {
    Fail "ServerJar 檔名必須精確為 $expectedJarName，實際為 $(Split-Path $serverJarPath -Leaf)"
}
Assert-ServerJar -JarPath $serverJarPath -ExpectedVersion $Version
if (-not (Test-Path -LiteralPath $jlink -PathType Leaf) -or -not (Test-Path -LiteralPath $jmods -PathType Container)) {
    Fail "JdkHome 必須是含 bin\\jlink.exe 與 jmods 的完整 JDK：$jdkHomePath"
}
foreach ($source in @('bin\board.ps1', 'bin\board-env.ps1', 'bin\backup-db.ps1', 'bin\board-update.ps1')) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $source) -PathType Leaf)) { Fail "repo 缺少 ZIP launcher 需要的檔案：$source" }
}
Assert-Java21X64 -Java $java

New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
if (Test-Path -LiteralPath $finalZip) { Fail "拒絕覆寫既有 release artifact：$finalZip" }

$stagingParent = Join-Path $outputPath ('.windows-release-stage-' + [Guid]::NewGuid().ToString('N'))
$stagingRoot = Join-Path $stagingParent $topLevel
$temporaryZip = Join-Path $outputPath ('.' + $topLevel + '.' + [Guid]::NewGuid().ToString('N') + '.zip.tmp')

try {
    New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'app') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'bin') -Force | Out-Null
    Copy-Item -LiteralPath $serverJarPath -Destination (Join-Path $stagingRoot ('app\' + $expectedJarName)) -Force
    foreach ($source in @('board.ps1', 'board-env.ps1', 'backup-db.ps1', 'board-update.ps1')) {
        Copy-Item -LiteralPath (Join-Path $repoRoot ('bin\' + $source)) -Destination (Join-Path $stagingRoot ('bin\' + $source)) -Force
    }

    $metadata = [pscustomobject]@{
        version = $Version
        jar = $expectedJarName
        jarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $serverJarPath).Hash.ToLowerInvariant()
        runtime = 'JDK 21 jlink Windows x64'
        defaultHost = '127.0.0.1'
        enforcedHost = '127.0.0.1'
        healthUrl = 'http://127.0.0.1:<BOARD_PORT>/api/health'
        jlinkModules = $JlinkModules
    }
    Write-Utf8NoBom -Path (Join-Path $stagingRoot 'app\release-metadata.json') -Content (($metadata | ConvertTo-Json -Depth 3) + "`n")

    & $jlink '--module-path' $jmods '--add-modules' $JlinkModules '--output' (Join-Path $stagingRoot 'runtime') `
        '--strip-debug' '--no-header-files' '--no-man-pages' '--compress=zip-6'
    if ($LASTEXITCODE -ne 0) { Fail "jlink 失敗（exit $LASTEXITCODE）。" }

    Assert-Java21X64 -Java (Join-Path $stagingRoot 'runtime\bin\java.exe')
    Assert-StagingTree -Root $stagingRoot -ExpectedJarName $expectedJarName -ExpectedVersion $Version

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stagingRoot, $temporaryZip, [System.IO.Compression.CompressionLevel]::Optimal, $true)
    Assert-ZipLayout -ZipPath $temporaryZip -TopLevel $topLevel -ExpectedJarName $expectedJarName

    # Same-directory Move-Item is an atomic rename on NTFS. The final artifact does not
    # appear until every validation has passed, and an already published artifact is never
    # overwritten by a failed or concurrent build.
    Move-Item -LiteralPath $temporaryZip -Destination $finalZip -ErrorAction Stop
    Write-Host "[package-windows-x64] 已建立：$finalZip"
} finally {
    if (Test-Path -LiteralPath $temporaryZip) { Remove-Item -LiteralPath $temporaryZip -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $stagingParent) { Remove-Item -LiteralPath $stagingParent -Recurse -Force -ErrorAction SilentlyContinue }
}

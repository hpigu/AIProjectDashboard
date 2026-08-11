# package-fixture.ps1 — failure fixtures for the Windows jlink packager.
#
# Run this after Maven has built the server JAR. It proves bad input, version, and JDK
# paths fail before an artifact is published, including paths with spaces/non-ASCII.

#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][string]$Version
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Assert-True {
    param([bool]$Condition, [string]$Description)
    if (-not $Condition) { throw "[package-fixture] $Description" }
    Write-Host "  [PASS] $Description"
}

function Invoke-Packager {
    param([string[]]$Arguments)

    # A deliberately failing child PowerShell writes a terminating error to stderr. Keep
    # that expected failure inside this helper so the fixture can assert its exit code.
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $packager @Arguments 2>&1 | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

if ($env:OS -ne 'Windows_NT') { throw '[package-fixture] 僅能在 Windows 執行。' }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$packager = Join-Path $repoRoot 'scripts\release\package-windows-x64.ps1'
$serverJarPath = (Resolve-Path -LiteralPath $ServerJar -ErrorAction Stop).Path
$expectedJarName = 'ai-project-board-backend-' + $Version + '.jar'
if ((Split-Path $serverJarPath -Leaf) -ne $expectedJarName) { throw "[package-fixture] ServerJar 必須為 $expectedJarName" }
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('board-package-fixture-' + [Guid]::NewGuid().ToString('N') + '-空白')
$output = Join-Path $work '輸出 空白'
try {
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $wrongInput = Join-Path $work 'wrong-input.jar'
    [System.IO.File]::WriteAllText($wrongInput, 'not a jar')

    $exitCode = Invoke-Packager @('-ServerJar', $wrongInput, '-JdkHome', (Join-Path $work 'missing jdk'), '-Version', $Version, '-OutputDirectory', $output)
    Assert-True ($exitCode -ne 0) '錯誤 server JAR 檔名會失敗'
    Assert-True (-not (Test-Path (Join-Path $output ('ai-project-board-backend-windows-x64-' + $Version + '.zip')))) '錯誤輸入不留下 final artifact'

    $wrongVersion = $Version + '.1'
    $exitCode = Invoke-Packager @('-ServerJar', $serverJarPath, '-JdkHome', (Join-Path $work 'missing jdk'), '-Version', $wrongVersion, '-OutputDirectory', $output)
    Assert-True ($exitCode -ne 0) 'JAR／版本不一致會失敗'
    Assert-True (-not (Test-Path (Join-Path $output ('ai-project-board-backend-windows-x64-' + $wrongVersion + '.zip')))) '版本錯誤不留下 final artifact'

    $exitCode = Invoke-Packager @('-ServerJar', $serverJarPath, '-JdkHome', (Join-Path $work 'missing jdk'), '-Version', $Version, '-OutputDirectory', $output)
    Assert-True ($exitCode -ne 0) '不存在或非 JDK 21 x64 的 JDK home 會失敗'
    Assert-True (-not (Test-Path (Join-Path $output ('ai-project-board-backend-windows-x64-' + $Version + '.zip')))) '錯誤 JDK 不留下 final artifact'
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host '[package-fixture] 全部通過。'

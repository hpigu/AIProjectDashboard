# board.ps1 — Windows 版看板生命週期入口（start / stop / restart / status / logs）
#
# 這是 bin/board 與 bin/start-board.sh 兩支 bash 腳本合併後的 Windows 對應實作。
# Windows 上沒有 bash，原本只能自己 `java -jar` 再靠 Stop-Process 收工，缺兩件事：
#   1. 啟動前的 JDK／埠號／H2 鎖檔檢查與冷備份；
#   2. 一條會觸發關閉前備份的停止路徑。
#
# 用法：
#   .\bin\board.ps1 start
#   .\bin\board.ps1 stop [-Force]
#   .\bin\board.ps1 restart
#   .\bin\board.ps1 status
#   .\bin\board.ps1 logs [-Lines 200]
#   .\bin\board.ps1 start -Foreground    # 在當前視窗執行，用來看啟動失敗的原因
#
# 離開碼：0 成功（status：執行中）／1 失敗／3 status 專用：未執行

#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status', 'logs', 'help')]
    [string]$Command = 'help',

    # stop：等待逾時後強制終止（會失去關閉前備份）
    [switch]$Force,

    # start：在當前視窗前景執行，方便看見 logback 初始化前的錯誤
    [switch]$Foreground,

    # logs：先顯示的行數
    [int]$Lines = 50
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'board-env.ps1')
. (Join-Path $PSScriptRoot 'backup-db.ps1')

function Write-BoardLog { param([string]$Message) Write-Host "[board] $Message" }
function Write-BoardErr { param([string]$Message) Write-Host "[board][錯誤] $Message" -ForegroundColor Red }

$StopTimeoutSec = 60
$stopTimeoutEnv = [Environment]::GetEnvironmentVariable('BOARD_STOP_TIMEOUT_SEC')
if ($stopTimeoutEnv -and ($stopTimeoutEnv -as [int])) { $StopTimeoutSec = [int]$stopTimeoutEnv }

$StartTimeoutSec = 60
$startTimeoutEnv = [Environment]::GetEnvironmentVariable('BOARD_START_TIMEOUT_SEC')
if ($startTimeoutEnv -and ($startTimeoutEnv -as [int])) { $StartTimeoutSec = [int]$startTimeoutEnv }

# ---------------------------------------------------------------------------
# JDK 21 偵測
#
# 與 bin/start-board.sh 的 version_is_21() 同樣的陷阱要避開：不能只看
# `java -version` 的第一行，JVM 會把 JAVA_TOOL_OPTIONS／_JAVA_OPTIONS 的
# "Picked up ..." 提示印在版本字串之前，那會讓已安裝的 JDK 21 被誤判成沒裝。
# ---------------------------------------------------------------------------
function Test-Java21 {
    param([string]$JavaPath)

    if ([string]::IsNullOrWhiteSpace($JavaPath) -or -not (Test-Path $JavaPath)) { return $false }
    try {
        $output = & $JavaPath -version 2>&1 | Out-String
    } catch {
        return $false
    }
    $versionLine = ($output -split "`r?`n" | Where-Object { $_ -match 'version "' } | Select-Object -First 1)
    if (-not $versionLine) { return $false }
    return ($versionLine -match 'version "21')
}

function Find-Java21 {
    $candidates = New-Object System.Collections.ArrayList

    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) | Out-Null }

    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add($onPath.Source) | Out-Null }

    # Windows 上 JDK 的常見安裝位置（Adoptium／Microsoft Build of OpenJDK／Oracle／
    # winget 與 scoop 的預設路徑）。java.exe 不在 PATH 上是 Windows 的常態，
    # 因此這一段不是可選的錦上添花。
    $searchRoots = @(
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Amazon Corretto'),
        (Join-Path $env:ProgramFiles 'Zulu'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
        (Join-Path $env:USERPROFILE 'scoop\apps')
    )
    if (${env:ProgramFiles(x86)}) {
        $searchRoots += (Join-Path ${env:ProgramFiles(x86)} 'Java')
    }

    foreach ($root in $searchRoots) {
        if (-not $root -or -not (Test-Path $root)) { continue }
        $matched = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '21' }
        foreach ($dir in $matched) {
            $candidates.Add((Join-Path $dir.FullName 'bin\java.exe')) | Out-Null
            # scoop 的目錄結構多一層（apps\openjdk21\current\bin）
            $candidates.Add((Join-Path $dir.FullName 'current\bin\java.exe')) | Out-Null
        }
    }

    foreach ($candidate in $candidates) {
        if (Test-Java21 -JavaPath $candidate) { return $candidate }
    }
    return $null
}

function Show-JdkInstallHint {
    Write-BoardErr "系統上找不到 JDK 21（專案 pom.xml 要求 java.version=21）。"
    Write-Host @'
安裝方式（擇一，安裝後重開 PowerShell 視窗讓 PATH 生效）：
  winget install EclipseAdoptium.Temurin.21.JDK
  choco install temurin21
  scoop bucket add java; scoop install openjdk21
或到 https://adoptium.net/ 下載 Windows x64 的 21 版安裝檔。
已安裝但仍偵測不到時，可設定 JAVA_HOME 指向該 JDK 目錄，或用
BOARD_JAVA 環境變數直接指定 java.exe 的完整路徑。
'@
}

function Resolve-BoardJar {
    $explicit = [Environment]::GetEnvironmentVariable('BOARD_JAR')
    if ($explicit) { return $explicit }

    $targetDir = Join-Path $RepoRoot 'target'
    if (-not (Test-Path $targetDir)) { return $null }

    $jar = Get-ChildItem -Path $targetDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object Name |
        Select-Object -Last 1
    if ($jar) { return $jar.FullName }
    return $null
}

# ---------------------------------------------------------------------------
# 解析目前的看板 PID：先信 PID 檔，再退回埠號反查。兩者都會經過
# Test-BoardProcess 確認行程真的是看板。
# ---------------------------------------------------------------------------
function Resolve-BoardPid {
    $fromFile = Get-BoardPidFromFile
    if ($fromFile -gt 0) { return $fromFile }

    $fromPort = Get-BoardPidFromPort
    if ($fromPort -gt 0 -and (Test-BoardProcess -ProcessId $fromPort)) { return $fromPort }

    return 0
}

# ---------------------------------------------------------------------------
# 對目標行程送出 CTRL_C_EVENT —— 這是 Windows 上唯一能觸發 JVM shutdown hook
# （也就是 ShutdownBackupService 關閉前備份）的停止方式。
#
# Stop-Process 走的是 TerminateProcess，等同 kill -9：行程直接消失，shutdown
# hook 完全不會執行，那份一致性快照就永遠不存在。Windows 沒有 SIGTERM，因此
# 必須改用 console control event。
#
# 為什麼要在「另一個」隱藏的 PowerShell 行程裡做：AttachConsole 要求呼叫端自己
# 沒有 console，所以得先 FreeConsole。若直接在使用者的互動式視窗裡呼叫，
# 會把該視窗的 console 拔掉，之後的輸出全部失效。丟給短命的子行程做就沒有這個
# 副作用。
# ---------------------------------------------------------------------------
# 目標行程是否與「我們自己」共用同一個 console。
#
# 這一題必須先問：CTRL_C_EVENT 是送給整個 console 的 process group，不是單一
# 行程。如果看板是使用者自己在某個 PowerShell 視窗裡 `java -jar` 起來的，它就跟
# 執行本腳本的 shell 共用 console——送出去的 Ctrl+C 會連自己的 shell 一起打斷。
# 這種情況不該硬送，改為告知使用者到那個視窗按 Ctrl+C（效果完全相同且安全）。
#
# 由 board.ps1 start 啟動的行程有自己的隱藏 console，不會落在這個分支。
function Test-BoardSharesOurConsole {
    param([int]$ProcessId)

    try {
        if (-not ('BoardConsole.Api' -as [type])) {
            Add-Type -Namespace BoardConsole -Name Api -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)]
public static extern uint GetConsoleProcessList(uint[] processList, uint count);
'@
        }
        $buffer = New-Object uint32[] 64
        $count = [BoardConsole.Api]::GetConsoleProcessList($buffer, 64)
        if ($count -le 0) { return $false }
        $limit = [Math]::Min([int]$count, 64)
        for ($i = 0; $i -lt $limit; $i++) {
            if ($buffer[$i] -eq [uint32]$ProcessId) { return $true }
        }
        return $false
    } catch {
        # 拿不到資訊時不阻擋停止流程，交給後續的 Ctrl+C 結果判斷。
        return $false
    }
}

function Send-BoardCtrlC {
    param([int]$ProcessId)

    $helper = @"
`$ErrorActionPreference = 'Stop'
Add-Type -Namespace BoardWin -Name Native -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool FreeConsole();
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool AttachConsole(uint dwProcessId);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetConsoleCtrlHandler(System.IntPtr handler, bool add);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool GenerateConsoleCtrlEvent(uint dwCtrlEvent, uint dwProcessGroupId);
'@
[BoardWin.Native]::FreeConsole() | Out-Null
if (-not [BoardWin.Native]::AttachConsole($ProcessId)) { exit 2 }
# 自己忽略即將發出的 Ctrl+C，否則這個 helper 會先被自己殺掉
[BoardWin.Native]::SetConsoleCtrlHandler([System.IntPtr]::Zero, `$true) | Out-Null
# 0 = CTRL_C_EVENT；process group 0 = 送給附掛在這個 console 上的整組行程
`$sent = [BoardWin.Native]::GenerateConsoleCtrlEvent(0, 0)
[BoardWin.Native]::FreeConsole() | Out-Null
if (`$sent) { exit 0 } else { exit 3 }
"@

    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($helper))
    $process = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded) `
        -WindowStyle Hidden -Wait -PassThru
    return ($process.ExitCode -eq 0)
}

# ---------------------------------------------------------------------------
# start
# ---------------------------------------------------------------------------
function Invoke-BoardStart {
    Write-BoardLog "repo 根目錄：$RepoRoot"

    $javaBin = [Environment]::GetEnvironmentVariable('BOARD_JAVA')
    if ($javaBin) {
        if (-not (Test-Java21 -JavaPath $javaBin)) {
            Write-BoardErr "BOARD_JAVA 指定的執行檔不是 JDK 21：$javaBin"
            return 1
        }
    } else {
        $javaBin = Find-Java21
    }
    if (-not $javaBin) {
        Show-JdkInstallHint
        return 1
    }
    Write-BoardLog "使用 JDK 21：$javaBin"
    Write-BoardLog "BOARD_PORT=$script:BoardPort"
    Write-BoardLog "BOARD_DB_URL=$script:BoardDbUrl"
    Write-BoardLog "BOARD_LOG_FILE=$script:BoardLogFile"

    # 埠號檢查：被佔用時先判斷是不是看板自己，避免重複啟動或誤殺別的服務。
    $portPid = Get-BoardPidFromPort
    if ($portPid -gt 0) {
        Write-BoardLog "埠號 $script:BoardPort 已被 PID $portPid 佔用，檢查是否為看板本身……"
        if (Test-BoardHttpReady -TimeoutSec 3) {
            Write-BoardLog "偵測到看板已在 :$script:BoardPort 正常運作（PID $portPid），不重複啟動。"
            # 補寫 PID 檔：看板可能是手動 java -jar 起的，補上之後 stop 才停得掉。
            New-Item -ItemType Directory -Path (Split-Path $script:BoardPidFile) -Force -ErrorAction SilentlyContinue | Out-Null
            Set-Content -Path $script:BoardPidFile -Value $portPid -Encoding ASCII
            return 0
        }
        Write-BoardErr "埠號 $script:BoardPort 被其他行程佔用（PID $portPid，非本看板服務）。"
        Write-BoardErr "請先確認該行程用途，或改用 BOARD_PORT 指定其他埠號再重試。"
        return 1
    }

    # H2 檔案鎖偵測：不要讓 MVStoreException 堆疊糊弄使用者。
    $dbFilePath = Get-BoardDbFilePath
    $dbMvFile = ''
    if ($dbFilePath) {
        $dbMvFile = "$dbFilePath.mv.db"
        if (Test-BoardFileLocked -Path $dbMvFile) {
            Write-BoardErr "資料庫檔 $dbMvFile 目前被其他行程持有中，啟動會失敗（MVStoreException）。"
            Write-BoardErr "請先確認並結束該行程（.\bin\board.ps1 status 可看目前的看板行程），再重試。"
            return 1
        }
        New-Item -ItemType Directory -Path (Split-Path $dbMvFile) -Force -ErrorAction SilentlyContinue | Out-Null
    }

    # 啟動前冷備份：失敗必須中止啟動，不可讓 migration 在沒有備份的情況下繼續。
    if ($dbMvFile) {
        if (-not (Invoke-BoardStartupBackup -DatabaseFile $dbMvFile -BackupDir $script:BoardBackupDir)) {
            Write-BoardErr "啟動前資料庫備份失敗，為避免在無可還原快照的情況下執行 migration，中止啟動。"
            return 1
        }
    }

    $jarPath = Resolve-BoardJar
    if (-not $jarPath -or -not (Test-Path $jarPath)) {
        $mvnw = Join-Path $RepoRoot 'mvnw.cmd'
        if ((Test-Path (Join-Path $RepoRoot 'pom.xml')) -and (Test-Path $mvnw)) {
            Write-BoardLog "找不到可執行 jar，偵測到完整 repo，現場組裝一次……"
            Write-BoardLog "（首次啟動需下載 Maven 依賴並編譯，可能需要數分鐘）"
            Push-Location $RepoRoot
            try {
                & $mvnw package -DskipTests
                $mvnExit = $LASTEXITCODE
            } finally {
                Pop-Location
            }
            if ($mvnExit -ne 0) {
                Write-BoardErr "自動組裝失敗，請在 $RepoRoot 手動執行：.\mvnw.cmd package -DskipTests"
                return 1
            }
            $jarPath = Resolve-BoardJar
        }
    }
    if (-not $jarPath -or -not (Test-Path $jarPath)) {
        Write-BoardErr "找不到可執行 jar（$RepoRoot\target\*.jar），自動組裝亦不可用。"
        Write-BoardErr "請先執行：.\mvnw.cmd package -DskipTests"
        return 1
    }

    Write-BoardLog "啟動 jar：$jarPath"
    New-Item -ItemType Directory -Path (Split-Path $script:BoardLogFile) -Force -ErrorAction SilentlyContinue | Out-Null
    New-Item -ItemType Directory -Path (Split-Path $script:BoardPidFile) -Force -ErrorAction SilentlyContinue | Out-Null

    if ($Foreground) {
        # 診斷模式：logback 初始化之前的錯誤（JVM 參數、classpath、埠號綁定失敗的
        # 原始堆疊）只會出現在 stdout，背景模式看不到，這個模式就是為了看它。
        Write-BoardLog "前景模式執行（Ctrl+C 可停止並觸發關閉前備份）"
        & $javaBin -jar $jarPath
        return $LASTEXITCODE
    }

    # 刻意不用 -RedirectStandardOutput：那會讓子行程沿用當前視窗的 console，
    # 之後就無法只對看板送 Ctrl+C（會波及同一個 console 上的其他行程，包含使用者
    # 自己的 shell）。這裡讓它取得自己的隱藏 console，代價是 logback 初始化前的
    # stdout 不會落檔——需要看那段輸出時改用 -Foreground。
    $process = Start-Process -FilePath $javaBin -ArgumentList @('-jar', $jarPath) `
        -WindowStyle Hidden -PassThru
    $boardPid = $process.Id
    Set-Content -Path $script:BoardPidFile -Value $boardPid -Encoding ASCII
    Write-BoardLog "已啟動子行程 PID=$boardPid（PID 檔：$script:BoardPidFile），等待服務就緒……"

    $elapsed = 0
    while ($elapsed -lt $StartTimeoutSec) {
        if ($process.HasExited) {
            Write-BoardErr "行程 PID=$boardPid 已提前結束，啟動失敗。請查看 $script:BoardLogFile，"
            Write-BoardErr "或改用 .\bin\board.ps1 start -Foreground 直接看啟動輸出。"
            Remove-Item $script:BoardPidFile -Force -ErrorAction SilentlyContinue
            return 1
        }
        if (Test-BoardHttpReady -TimeoutSec 2) {
            Write-BoardLog "看板已就緒：http://127.0.0.1:$script:BoardPort（PID=$boardPid）"
            try {
                $health = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $script:BoardPort + "/api/health") `
                    -TimeoutSec 3 -UseBasicParsing
                Write-BoardLog "版本資訊：$($health.Content)"
            } catch {
                Write-BoardLog "此行程沒有 /api/health（可能是較舊的 build），略過版本檢查。"
            }
            return 0
        }
        Start-Sleep -Seconds 1
        $elapsed++
    }

    Write-BoardErr "等待 $StartTimeoutSec 秒後仍未就緒（PID=$boardPid）。請查看 $script:BoardLogFile。"
    Write-BoardErr "行程仍在執行中，PID 檔保留於 $script:BoardPidFile，可用 .\bin\board.ps1 stop 停止。"
    return 1
}

# ---------------------------------------------------------------------------
# stop
# ---------------------------------------------------------------------------
function Invoke-BoardStop {
    $boardPid = Resolve-BoardPid
    if ($boardPid -le 0) {
        if (Test-Path $script:BoardPidFile) {
            Write-BoardLog "PID 檔存在但行程已不在（殘留），清除：$script:BoardPidFile"
            Remove-Item $script:BoardPidFile -Force -ErrorAction SilentlyContinue
        }
        Write-BoardLog "看板未在執行，無需停止。"
        return 0
    }

    if (Test-BoardSharesOurConsole -ProcessId $boardPid) {
        Write-BoardErr "看板（PID=$boardPid）與這個視窗共用同一個 console——它是在某個終端機裡"
        Write-BoardErr "手動 java -jar 啟動的。此時送出 Ctrl+C 會連這個視窗一起打斷，因此不執行。"
        Write-BoardErr "請直接到執行 java 的那個視窗按 Ctrl+C（效果與 stop 完全相同，"
        Write-BoardErr "同樣會觸發關閉前備份）。之後改用 .\bin\board.ps1 start 啟動，"
        Write-BoardErr "看板就會有自己的 console，stop 便可正常運作。"
        if (-not $Force) { return 1 }
        Write-BoardErr "已指定 -Force，改為強制終止（會失去關閉前備份）。"
        Stop-Process -Id $boardPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
        Remove-Item $script:BoardPidFile -Force -ErrorAction SilentlyContinue
        Write-BoardLog "已強制終止（PID=$boardPid）。"
        return 0
    }

    Write-BoardLog "停止看板（PID=$boardPid）……送出 Ctrl+C 事件，等待關閉前備份完成。"
    $sent = Send-BoardCtrlC -ProcessId $boardPid
    if (-not $sent) {
        Write-BoardErr "無法對 PID=$boardPid 送出 Ctrl+C 事件（可能是看板在其他使用者的 session，"
        Write-BoardErr "或它是在共用 console 下手動啟動的）。"
        Write-BoardErr "若它是在某個 PowerShell 視窗前景執行，請直接到那個視窗按 Ctrl+C；"
        Write-BoardErr "確定要強制終止（會失去關閉前備份）再執行：.\bin\board.ps1 stop -Force"
        if (-not $Force) { return 1 }
    }

    $elapsed = 0
    while ($elapsed -lt $StopTimeoutSec) {
        if (-not (Get-Process -Id $boardPid -ErrorAction SilentlyContinue)) {
            Remove-Item $script:BoardPidFile -Force -ErrorAction SilentlyContinue
            Write-BoardLog "已停止（PID=$boardPid，耗時 $elapsed 秒）。關閉前備份見 $script:BoardBackupDir。"
            return 0
        }
        Start-Sleep -Seconds 1
        $elapsed++
        if ($elapsed % 10 -eq 0) {
            Write-BoardLog "仍在關閉中……（已等待 $elapsed／$StopTimeoutSec 秒）"
        }
    }

    if ($Force) {
        Write-BoardErr "等待 $StopTimeoutSec 秒仍未結束，依 -Force 強制終止。"
        Write-BoardErr "注意：強制終止不會執行關閉前備份，本次停止沒有一致性快照。"
        Stop-Process -Id $boardPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
        if (Get-Process -Id $boardPid -ErrorAction SilentlyContinue) {
            Write-BoardErr "強制終止後行程仍存在（PID=$boardPid），請手動確認。"
            return 1
        }
        Remove-Item $script:BoardPidFile -Force -ErrorAction SilentlyContinue
        Write-BoardLog "已強制終止（PID=$boardPid）。"
        return 0
    }

    Write-BoardErr "等待 $StopTimeoutSec 秒後行程 PID=$boardPid 仍在執行，已放棄等待（未強制終止）。"
    Write-BoardErr "刻意不自動強制終止：那會中斷關閉前備份。可先查看 $script:BoardLogFile 確認"
    Write-BoardErr "是否正在進行備份；確定要強制終止再執行：.\bin\board.ps1 stop -Force"
    return 1
}

# ---------------------------------------------------------------------------
# status / logs / restart
# ---------------------------------------------------------------------------
function Invoke-BoardStatus {
    $boardPid = Resolve-BoardPid
    if ($boardPid -gt 0) {
        Write-BoardLog "行程：執行中（PID=$boardPid）"
    } else {
        Write-BoardLog "行程：未執行"
    }

    $pidNote = ''
    if (-not (Test-Path $script:BoardPidFile)) { $pidNote = '（不存在）' }
    Write-BoardLog "PID 檔：$script:BoardPidFile$pidNote"
    Write-BoardLog "埠號：$script:BoardPort"
    Write-BoardLog "資料庫：$script:BoardDbUrl"
    Write-BoardLog "日誌：$script:BoardLogFile"

    $httpReady = Test-BoardHttpReady -TimeoutSec 3
    if ($httpReady) {
        Write-BoardLog "HTTP：/api/health/live 正常回應"
        try {
            $health = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $script:BoardPort + "/api/health") `
                -TimeoutSec 3 -UseBasicParsing
            Write-BoardLog "版本資訊：$($health.Content)"
        } catch {
            # 舊版 build 沒有 /api/health，不算失敗
        }
    } else {
        Write-BoardLog "HTTP：沒有回應（服務未啟動，或仍在啟動中）"
    }

    # 離開碼以「服務可用」為準，方便寫進其他腳本或監控。
    if ($boardPid -gt 0 -and $httpReady) { return 0 }
    return 3
}

function Invoke-BoardLogs {
    if (-not (Test-Path $script:BoardLogFile)) {
        Write-BoardErr "日誌檔不存在：$script:BoardLogFile（看板可能尚未啟動過）"
        return 1
    }
    Write-BoardLog "追蹤 $script:BoardLogFile（Ctrl+C 結束）"
    Get-Content -Path $script:BoardLogFile -Tail $Lines -Wait
    return 0
}

function Show-BoardUsage {
    Write-Host @"
用法：.\bin\board.ps1 <指令> [選項]

指令：
  start [-Foreground]  啟動看板（JDK 偵測、埠號與鎖檔檢查、啟動前備份、
                       必要時現場組裝 jar）。-Foreground 在當前視窗執行，
                       用來查看啟動失敗的原因
  stop [-Force]        送 Ctrl+C 事件停止並等待關閉前備份完成。逾時後預設
                       不強制終止；-Force 才強制（會失去關閉前備份）
  restart              stop 後再 start
  status               顯示 PID、埠號與 /api/health 版本資訊
  logs [-Lines N]      追蹤日誌，預設先顯示 50 行

環境變數：
  BOARD_PORT               預設 8080
  BOARD_PID_FILE           預設 $script:BoardHomeDir\board.pid
  BOARD_LOG_FILE           預設 <repo>\logs\board.log
  BOARD_JAVA               直接指定 java.exe 路徑（跳過自動偵測）
  BOARD_STOP_TIMEOUT_SEC   stop 等待秒數，預設 60

離開碼：
  0  指令成功（status：看板正在執行）
  1  指令失敗
  3  status 專用：看板未在執行
"@
}

switch ($Command) {
    'start'   { exit (Invoke-BoardStart) }
    'stop'    { exit (Invoke-BoardStop) }
    'restart' {
        $stopResult = Invoke-BoardStop
        if ($stopResult -ne 0) { exit $stopResult }
        # 停止後 H2 需要一點時間釋放檔案鎖；沒有這段間隔，start 有機會撞上
        # MVStoreException。
        Start-Sleep -Seconds 2
        exit (Invoke-BoardStart)
    }
    'status'  { exit (Invoke-BoardStatus) }
    'logs'    { exit (Invoke-BoardLogs) }
    default   { Show-BoardUsage; exit 0 }
}

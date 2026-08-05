# board-env.ps1 — Windows 版的看板路徑與環境變數單一事實來源
#
# 這是 bin/board-env.sh 的對應實作，由 bin/board.ps1、bin/backup-db.ps1、
# bin/restore-db.ps1 共同 dot-source。兩邊的預設值必須保持一致，否則同一台機器上
# 用 PowerShell 啟動、用 WSL/Git Bash 查狀態時會指向不同的資料庫。
#
# 使用方式（呼叫端需先算好 $RepoRoot）：
#   . (Join-Path $PSScriptRoot 'board-env.ps1')
#
# 所有值都尊重呼叫端既有的環境變數，只在未設定時才填入預設值。

#Requires -Version 5.1

Set-StrictMode -Version 2.0

# 用 Test-Path variable: 而不是直接 `if (-not $RepoRoot)`：StrictMode 2.0 之下
# 引用「從未定義過」的變數會直接拋例外，不會當成 $null。
if (-not (Test-Path 'variable:RepoRoot') -or [string]::IsNullOrWhiteSpace($RepoRoot)) {
    # 腳本放在 <repo>\bin\ 下；$PSScriptRoot 在 dot-source 時指向被載入檔案的目錄。
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

function Get-BoardEnvValue {
    param([string]$Name, [string]$Default)

    $existing = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($existing)) { return $Default }
    return $existing
}

# 家目錄的判斷不只依賴 $env:USERPROFILE：它在部分執行情境（Windows 服務、
# 排程工作、精簡過的環境）可能是空的，直接用會讓 Join-Path 以「參數為 null」
# 失敗，訊息又看不出真正原因。GetFolderPath 是 .NET 層的查詢，比環境變數可靠。
function Get-BoardUserHome {
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) { return $env:USERPROFILE }

    $viaDotNet = [Environment]::GetFolderPath('UserProfile')
    if (-not [string]::IsNullOrWhiteSpace($viaDotNet)) { return $viaDotNet }

    if (-not [string]::IsNullOrWhiteSpace($env:HOME)) { return $env:HOME }

    throw "無法判斷使用者家目錄，請明確設定 BOARD_HOME_DIR 環境變數。"
}

# ---------------------------------------------------------------------------
# 家目錄與資料目錄
#
# 資料目錄預設值有兩種情境，與 board-env.sh 完全相同（不可任意更動，否則既有
# 使用者的看板會「看起來資料不見了」）：
#   a) 既有使用者：repo 內 <repo>\data\board.mv.db 已存在 → 沿用該路徑。
#   b) 全新環境：改用 %USERPROFILE%\.ai-project-board\data\board。
#      plugin 目錄可能因更新而遺失內容，H2 檔案不能放在會被覆蓋的路徑下。
# ---------------------------------------------------------------------------
$script:BoardHomeDir = Get-BoardEnvValue 'BOARD_HOME_DIR' (Join-Path (Get-BoardUserHome) '.ai-project-board')

if (Test-Path (Join-Path $RepoRoot 'data\board.mv.db')) {
    $script:BoardDefaultDbDir = Join-Path $RepoRoot 'data'
} else {
    $script:BoardDefaultDbDir = Join-Path $script:BoardHomeDir 'data'
}

$script:BoardPort      = Get-BoardEnvValue 'BOARD_PORT' '8080'
$script:BoardDbUrl     = Get-BoardEnvValue 'BOARD_DB_URL' `
    ("jdbc:h2:file:" + (Join-Path $script:BoardDefaultDbDir 'board') + ";DB_CLOSE_ON_EXIT=FALSE")
$script:BoardLogFile   = Get-BoardEnvValue 'BOARD_LOG_FILE' (Join-Path $RepoRoot 'logs\board.log')
$script:BoardBackupDir = Get-BoardEnvValue 'BOARD_BACKUP_DIR' (Join-Path $script:BoardHomeDir 'backups')

# PID 檔放在家目錄而非 repo 內：repo 可能被 plugin 更新覆蓋，且同一份 repo 可能
# 被多個 worktree 共用，而「正在跑的看板」在一台機器上只有一個。
$script:BoardPidFile   = Get-BoardEnvValue 'BOARD_PID_FILE' (Join-Path $script:BoardHomeDir 'board.pid')

# 子行程要看得到這些值（Spring 讀的是環境變數）。
$env:BOARD_PORT = $script:BoardPort
$env:BOARD_DB_URL = $script:BoardDbUrl
$env:BOARD_LOG_FILE = $script:BoardLogFile
$env:BOARD_BACKUP_DIR = $script:BoardBackupDir

# ---------------------------------------------------------------------------
# 由 BOARD_DB_URL 反推 H2 檔案路徑（不含 .mv.db 副檔名）。
# 非 file 模式（mem:、tcp:）回傳空字串，呼叫端需自行判斷。
# ---------------------------------------------------------------------------
function Get-BoardDbFilePath {
    if ($script:BoardDbUrl -notmatch '^jdbc:h2:file:([^;]+)') { return '' }
    return $Matches[1]
}

# ---------------------------------------------------------------------------
# 確認某個 PID 真的是本看板的 Java 行程，而不是 PID 被回收後的無關行程。
# stop/restart 前一定要問過這一題：對著陌生行程動手是這類腳本最典型的災難。
# ---------------------------------------------------------------------------
function Test-BoardProcess {
    param([int]$ProcessId)

    if ($ProcessId -le 0) { return $false }
    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $false }

    # 需要完整命令列才能判斷是不是我們的 jar；Get-Process 拿不到，只能問 CIM/WMI。
    $commandLine = $null
    try {
        $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop).CommandLine
    } catch {
        try {
            $commandLine = (Get-WmiObject Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop).CommandLine
        } catch {
            return $false
        }
    }

    if ([string]::IsNullOrWhiteSpace($commandLine)) { return $false }
    return ($commandLine -match 'ai-project-board' -or $commandLine -match 'project-board')
}

# 讀 PID 檔並驗證；檔案不存在、內容不是數字、行程已不在或不是看板都回傳 0。
function Get-BoardPidFromFile {
    if (-not (Test-Path $script:BoardPidFile)) { return 0 }

    $raw = (Get-Content $script:BoardPidFile -First 1 -ErrorAction SilentlyContinue)
    if (-not $raw) { return 0 }

    $parsed = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$parsed)) { return 0 }
    if (-not (Test-BoardProcess -ProcessId $parsed)) { return 0 }
    return $parsed
}

# 從埠號反查目前正在聽的 PID（PID 檔遺失時的後備手段，例如看板是手動
# java -jar 起的）。Get-NetTCPConnection 是 Windows 8 以後才有，因此保留
# netstat 解析作為退路。
function Get-BoardPidFromPort {
    $candidates = @()

    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        try {
            $candidates = @(Get-NetTCPConnection -LocalPort ([int]$script:BoardPort) -State Listen -ErrorAction Stop |
                Select-Object -ExpandProperty OwningProcess -Unique)
        } catch {
            $candidates = @()
        }
    }

    if ($candidates.Count -eq 0) {
        $pattern = ':' + $script:BoardPort + '\s'
        foreach ($line in (netstat -ano 2>$null)) {
            if ($line -match $pattern -and $line -match 'LISTENING\s+(\d+)\s*$') {
                $candidates += [int]$Matches[1]
            }
        }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and [int]$candidate -gt 0) { return [int]$candidate }
    }
    return 0
}

# 看板是否正在回應 HTTP（不看行程，只看服務）。
function Test-BoardHttpReady {
    param([int]$TimeoutSec = 3)

    try {
        $response = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $script:BoardPort + "/api/health/live") `
            -TimeoutSec $TimeoutSec -UseBasicParsing -ErrorAction Stop
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300)
    } catch {
        return $false
    }
}

# 檔案是否被其他行程獨占（H2 開著資料庫時會鎖住 .mv.db）。
# 這是 Windows 上對應 lsof 的作法：試著以「不共享」模式開檔，失敗即代表被持有。
function Test-BoardFileLocked {
    param([string]$Path)

    if (-not (Test-Path $Path)) { return $false }
    try {
        $stream = [System.IO.File]::Open($Path, 'Open', 'ReadWrite', 'None')
        $stream.Close()
        $stream.Dispose()
        return $false
    } catch {
        return $true
    }
}

# 備份檔名用的時區標記。bash 版用 date +%Z 取得 CST/JST 這類縮寫；Windows 的
# 時區名稱是「Taipei Standard Time」這種長字串且含空白，不能直接進檔名，
# 因此改用 UTC 偏移量（例如 UTC+0800），同樣可人工判讀且保證是合法檔名。
function Get-BoardTimeZoneTag {
    $offset = [System.TimeZoneInfo]::Local.GetUtcOffset([DateTime]::Now)
    $sign = '+'
    if ($offset.Ticks -lt 0) { $sign = '-' }
    return ('UTC{0}{1:00}{2:00}' -f $sign, [Math]::Abs($offset.Hours), [Math]::Abs($offset.Minutes))
}

# H2 的 .mv.db 是單一檔案的 MVStore 格式，檔首固定是 "H:2" 三個 magic bytes。
# 與 bin/backup-db.sh 的驗證方式一致：不引入對 H2 Driver 的依賴，只做檔案層級檢查。
function Test-BoardH2File {
    param([string]$Path)

    if (-not (Test-Path $Path)) { return $false }
    $info = Get-Item $Path
    if ($info.Length -le 0) { return $false }

    $bytes = New-Object byte[] 3
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $read = $stream.Read($bytes, 0, 3)
        if ($read -lt 3) { return $false }
    } finally {
        $stream.Close()
        $stream.Dispose()
    }

    return ($bytes[0] -eq 0x48 -and $bytes[1] -eq 0x3A -and $bytes[2] -eq 0x32)  # H : 2
}

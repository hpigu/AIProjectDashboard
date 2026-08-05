# restore-db.ps1 — Windows 版：從備份還原看板資料庫
#
# bin/restore-db.sh 的對應實作，兩種備份格式都支援：
#   - board-startup-*.mv.db  啟動前冷備份（直接複製）
#   - board-shutdown-*.zip   關閉前一致性備份（H2 BACKUP TO 產生，內含 .mv.db）
#
# 用法：
#   .\bin\restore-db.ps1 -List                     列出可用備份（新到舊）
#   .\bin\restore-db.ps1 latest                    還原最新一份
#   .\bin\restore-db.ps1 C:\path\board-startup-... 還原指定備份
#   .\bin\restore-db.ps1 latest -Yes               跳過互動確認
#   .\bin\restore-db.ps1 latest -DbPath C:\x\board 還原到指定資料庫（不含副檔名）
#
# 安全設計（與 bash 版逐條一致，每一條都對應一種會真的弄丟資料的情境）：
#   1. 看板還在跑、或資料庫檔被行程持有，就拒絕還原。
#   2. 現有資料庫不刪除，改名保留成 <db>.mv.db.pre-restore-<UTC 時間戳>。
#   3. 寫入走 .tmp → 驗證 H2 檔頭 → 改名，避免半成品被當成正式資料庫。
#   4. 非互動環境（無主控台輸入）必須明確加 -Yes。
#
# 離開碼：0 成功／1 失敗或被拒絕／2 用法錯誤

#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Backup,

    [switch]$List,
    [switch]$Yes,
    [string]$DbPath,
    [string]$BackupDir
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'board-env.ps1')

function Write-RestoreLog { param([string]$Message) Write-Host "[restore-db] $Message" }
function Write-RestoreErr { param([string]$Message) Write-Host "[restore-db][錯誤] $Message" -ForegroundColor Red }

if ($BackupDir) { $script:BoardBackupDir = $BackupDir }

if (-not $DbPath) { $DbPath = Get-BoardDbFilePath }
if ([string]::IsNullOrWhiteSpace($DbPath)) {
    Write-RestoreErr "無法從 BOARD_DB_URL 取得 H2 檔案路徑（目前為：$script:BoardDbUrl）。"
    Write-RestoreErr "非 file 模式（mem:／tcp:）不適用本腳本，或請用 -DbPath 明確指定。"
    exit 2
}
$dbMvFile = "$DbPath.mv.db"

# ---------------------------------------------------------------------------
# 列出備份：兩種格式一起列，依 mtime 新到舊
# ---------------------------------------------------------------------------
function Get-BoardBackups {
    if (-not (Test-Path $script:BoardBackupDir)) { return @() }
    return @(Get-ChildItem -Path $script:BoardBackupDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'board-startup-*.mv.db' -or $_.Name -like 'board-shutdown-*.zip' } |
        Sort-Object LastWriteTimeUtc -Descending)
}

if ($List) {
    Write-RestoreLog "備份目錄：$script:BoardBackupDir"
    $backups = Get-BoardBackups
    if ($backups.Count -eq 0) {
        Write-RestoreLog "（沒有找到任何備份）"
        exit 0
    }
    foreach ($item in $backups) {
        $kind = '啟動前（冷備份）'
        if ($item.Extension -eq '.zip') { $kind = '關閉前（一致性快照）' }
        Write-Host ('  {0}  {1,10} bytes  {2}  {3}' -f `
            $item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), $item.Length, $kind, $item.FullName)
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Backup)) {
    Write-RestoreErr "請指定要還原的備份檔，或用 latest 取用最新一份；先看清單可執行：$($MyInvocation.MyCommand.Name) -List"
    exit 2
}

# ---------------------------------------------------------------------------
# 決定來源備份檔
# ---------------------------------------------------------------------------
if ($Backup -eq 'latest') {
    $latest = Get-BoardBackups | Select-Object -First 1
    if (-not $latest) {
        Write-RestoreErr "備份目錄沒有可用備份：$script:BoardBackupDir"
        exit 1
    }
    $sourceBackup = $latest.FullName
    Write-RestoreLog "latest 解析為：$sourceBackup"
} else {
    $sourceBackup = $Backup
}

if (-not (Test-Path $sourceBackup -PathType Leaf)) {
    Write-RestoreErr "備份檔不存在：$sourceBackup"
    exit 1
}

# ---------------------------------------------------------------------------
# 安全檢查：看板不能在執行中，資料庫檔不能被持有
# ---------------------------------------------------------------------------
if (Test-BoardHttpReady -TimeoutSec 2) {
    Write-RestoreErr "看板正在 :$script:BoardPort 執行中，不能在執行中還原資料庫。"
    Write-RestoreErr "請先停止：.\bin\board.ps1 stop（會順便產生一份關閉前備份），再重新執行本指令。"
    exit 1
}

if (Test-BoardFileLocked -Path $dbMvFile) {
    Write-RestoreErr "資料庫檔 $dbMvFile 正被其他行程持有，請先結束該行程再還原，"
    Write-RestoreErr "否則會寫出損壞的資料庫。"
    exit 1
}

# ---------------------------------------------------------------------------
# 確認
# ---------------------------------------------------------------------------
Write-RestoreLog "來源備份：$sourceBackup"
Write-RestoreLog "還原目標：$dbMvFile"
if (Test-Path $dbMvFile) {
    Write-RestoreLog "現有資料庫會先改名保留，不會被刪除。"
} else {
    Write-RestoreLog "目標資料庫目前不存在（全新環境），將直接建立。"
}

if (-not $Yes) {
    # 沒有互動輸入時（CI、被其他腳本呼叫、stdin 被導向）不詢問，直接要求 -Yes，
    # 避免在無人看管的情況下覆寫資料庫。
    #
    # 判斷依據是 [Console]::IsInputRedirected，等同 bash 的 [ ! -t 0 ]。
    # 刻意不用 [Environment]::UserInteractive：它問的是「有沒有互動式桌面」，
    # 一般 console 行程即使 stdin 被導向也回傳 true，會讓腳本掉進 Read-Host
    # 而卡住或讀到空字串——實測時就是這樣誤判的。
    $interactive = $true
    try {
        $interactive = (-not [Console]::IsInputRedirected) -and [Environment]::UserInteractive
    } catch {
        $interactive = $false
    }

    if ($interactive) {
        $answer = Read-Host "[restore-db] 確認要還原嗎？輸入 yes 繼續"
        if ($answer -ne 'yes') {
            Write-RestoreLog "已取消，未變更任何檔案。"
            exit 1
        }
    } else {
        Write-RestoreErr "非互動環境需明確加上 -Yes 才會執行還原，已中止。"
        exit 1
    }
}

# ---------------------------------------------------------------------------
# 還原本體：先產生 .tmp、驗證通過後才動現有資料庫
# ---------------------------------------------------------------------------
$parent = Split-Path $dbMvFile
if ($parent -and -not (Test-Path $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

$tmpRestore = "$dbMvFile.restore.tmp"
$workDir = $null
$preserved = $null

try {
    if ([System.IO.Path]::GetExtension($sourceBackup) -eq '.zip') {
        $workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("board-restore-" + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $workDir -Force | Out-Null
        Write-RestoreLog "解壓關閉前備份……"
        Expand-Archive -LiteralPath $sourceBackup -DestinationPath $workDir -Force

        $extracted = Get-ChildItem -Path $workDir -Filter '*.mv.db' -File -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $extracted) {
            Write-RestoreErr "zip 內找不到任何 .mv.db，這不是 H2 BACKUP TO 產生的備份：$sourceBackup"
            exit 1
        }
        Write-RestoreLog "取出：$($extracted.Name)"
        Copy-Item -LiteralPath $extracted.FullName -Destination $tmpRestore -Force
    } else {
        Copy-Item -LiteralPath $sourceBackup -Destination $tmpRestore -Force
    }

    if (-not (Test-BoardH2File -Path $tmpRestore)) {
        Write-RestoreErr "還原來源缺少 H2 MVStore 檔頭（H:2），不是有效的 H2 資料庫，已中止。"
        exit 1
    }

    # 現有資料庫改名保留。這一步失敗就直接中止，絕不強行覆蓋。
    if (Test-Path $dbMvFile) {
        $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd\THHmmss\Z')
        $preserved = "$dbMvFile.pre-restore-$stamp"
        try {
            Move-Item -LiteralPath $dbMvFile -Destination $preserved -ErrorAction Stop
        } catch {
            Write-RestoreErr "無法保留現有資料庫（改名失敗），已中止，未變更任何檔案。"
            exit 1
        }
        Write-RestoreLog "現有資料庫已保留為：$preserved"
    }

    try {
        Move-Item -LiteralPath $tmpRestore -Destination $dbMvFile -Force -ErrorAction Stop
    } catch {
        Write-RestoreErr "還原改名（原子提交）失敗：$tmpRestore -> $dbMvFile"
        if ($preserved) {
            Write-RestoreErr "現有資料庫仍保留在 $preserved，可手動改回原檔名。"
        }
        exit 1
    }

    if (-not (Test-BoardH2File -Path $dbMvFile)) {
        Write-RestoreErr "還原後的資料庫驗證失敗：$dbMvFile"
        exit 1
    }

    # H2 的 trace/lock 殘留檔會讓下次啟動誤判狀態，還原後一併清掉。
    Remove-Item "$DbPath.trace.db" -Force -ErrorAction SilentlyContinue
    Remove-Item "$DbPath.lock.db" -Force -ErrorAction SilentlyContinue

    Write-RestoreLog "還原完成：$dbMvFile"
    Write-RestoreLog "下一步：.\bin\board.ps1 start，啟動後確認看板資料是否為預期的時間點。"
    if ($preserved) {
        Write-RestoreLog "若還原錯了備份，改回原檔名即可：Move-Item '$preserved' '$dbMvFile'"
    }
    exit 0
} finally {
    Remove-Item $tmpRestore -Force -ErrorAction SilentlyContinue
    if ($workDir -and (Test-Path $workDir)) {
        Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

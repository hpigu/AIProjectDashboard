# backup-db.ps1 — Windows 版啟動前資料庫冷備份與保留策略
#
# bin/backup-db.sh 的對應實作，由 bin/board.ps1 在確認 H2 檔案未被其他行程持有
# 之後、啟動 jar（進而觸發 Flyway migration）之前呼叫。目的相同：一旦某次啟動的
# migration 或後續操作把資料庫弄壞，還有啟動當下的快照可以還原。
#
# 獨立執行（主要供測試使用），參數為位置參數，與 bash 版介面一致：
#   .\bin\backup-db.ps1 <db-mv-file> [backup-dir]
#
# 設計原則與 bash 版逐條一致：
#   1. 資料庫不存在（全新環境首次啟動）不是錯誤，直接跳過，回傳成功。
#   2. 備份先寫到 *.tmp，複製完成且通過驗證後才改名成正式檔名。
#   3. 驗證檔案大小與 H2 MVStore 檔頭（H:2），不引入對 H2 Driver 的依賴。
#   4. 備份失敗一律回傳 $false，呼叫端必須因此中止啟動，不得繼續 migrate。
#   5. 保留策略：刪除 30 天前的備份，但刪除後至少要留最新 7 份。

#Requires -Version 5.1

# 刻意「不」宣告 param()：這個檔案會被 bin/board.ps1 dot-source，而 param() 會在
# 呼叫端的 scope 建立同名變數。PowerShell 的變數名稱不分大小寫，因此一個
# `param([string]$BackupDir)` 會把呼叫端自己的 $backupDir 清成 $null——實測時
# 立刻踩到（備份目錄變成空字串，錯誤訊息還看不出原因）。獨立執行時改讀 $args，
# 與 bin/backup-db.sh 的位置參數介面一致。

Set-StrictMode -Version 2.0

function Write-BackupLog { param([string]$Message) Write-Host "[backup-db] $Message" }
function Write-BackupErr { param([string]$Message) Write-Host "[backup-db][錯誤] $Message" -ForegroundColor Red }

# 保留規則參數維持可覆寫以利測試（與 bash 版的環境變數同名）。
function Get-BackupRetentionDays {
    param([int]$Override)
    if ($Override -gt 0) { return $Override }
    $value = [Environment]::GetEnvironmentVariable('BOARD_BACKUP_RETENTION_DAYS')
    if ($value -and ($value -as [int])) { return [int]$value }
    return 30
}

function Get-BackupRetentionMinCount {
    param([int]$Override)
    if ($Override -gt 0) { return $Override }
    $value = [Environment]::GetEnvironmentVariable('BOARD_BACKUP_RETENTION_MIN_COUNT')
    if ($value -and ($value -as [int])) { return [int]$value }
    return 7
}

# ---------------------------------------------------------------------------
# 清理由前次中斷留下的啟動備份暫存檔。只處理備份目錄第一層、符合本腳本產生的
# board-startup-*.mv.db.tmp 命名；正式備份與其他檔案都不在清理範圍內。
# ---------------------------------------------------------------------------
function Remove-StaleBackupTmp {
    param([string]$Directory)

    if (-not (Test-Path $Directory)) { return $true }

    $stale = Get-ChildItem -Path $Directory -Filter 'board-startup-*.mv.db.tmp' -File -ErrorAction SilentlyContinue
    foreach ($file in $stale) {
        Write-BackupLog "清理前次中斷留下的暫存備份：$($file.FullName)"
        try {
            Remove-Item $file.FullName -Force -ErrorAction Stop
        } catch {
            Write-BackupErr "無法清理暫存備份：$($file.FullName)"
            return $false
        }
    }
    return $true
}

# ---------------------------------------------------------------------------
# 保留策略（與 bash 版、ShutdownBackupService 完全相同的規則）：
#   - 保留 30 天內（含）的所有備份。
#   - 30 天前的備份，只要刪除後剩餘總份數仍 >= 7 就可以刪；
#     一旦刪到只剩 7 份就停手，即使還有更舊的備份。
#   - 依 mtime 新到舊走訪，確保優先保留新的備份。
# ---------------------------------------------------------------------------
function Invoke-BackupRetention {
    param([string]$Directory, [int]$Days, [int]$MinCount)

    if (-not (Test-Path $Directory)) { return }

    $files = @(Get-ChildItem -Path $Directory -Filter 'board-startup-*.mv.db' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending)
    if ($files.Count -eq 0) { return }

    $cutoff = (Get-Date).ToUniversalTime().AddDays(-1 * $Days)
    $remaining = 0

    foreach ($file in $files) {
        if ($file.LastWriteTimeUtc -ge $cutoff) {
            $remaining++
            continue
        }
        if ($remaining -ge $MinCount) {
            Write-BackupLog "刪除超過保留期限的舊備份：$($file.FullName)"
            try {
                Remove-Item $file.FullName -Force -ErrorAction Stop
            } catch {
                Write-BackupErr "刪除舊備份失敗，略過：$($file.FullName)"
                $remaining++
            }
            continue
        }
        $remaining++
    }
}

# ---------------------------------------------------------------------------
# 主流程。回傳 $true 代表備份成功或資料庫本來就不存在（無需備份）；
# 回傳 $false 代表失敗，呼叫端必須中止啟動。
# ---------------------------------------------------------------------------
function Invoke-BoardStartupBackup {
    param(
        [string]$DatabaseFile,
        [string]$BackupDir,
        [int]$RetentionDays = 0,
        [int]$RetentionMinCount = 0
    )

    if ([string]::IsNullOrWhiteSpace($DatabaseFile) -or -not (Test-Path $DatabaseFile)) {
        Write-BackupLog "資料庫檔不存在（首次啟動或全新環境），略過備份：$DatabaseFile"
        return $true
    }

    if (-not (New-BoardSecureDirectory -Path $BackupDir)) {
        Write-BackupErr "無法建立或收斂備份目錄權限：$BackupDir"
        return $false
    }

    if (-not (Remove-StaleBackupTmp -Directory $BackupDir)) {
        Write-BackupErr "清理前次中斷留下的暫存備份失敗，中止啟動。"
        return $false
    }

    # 檔名標示 startup 與時區：board-startup-<UTC 時間戳>-<時區標記>.mv.db
    # 時間戳用 UTC 避免主機時區變動或 DST 造成檔名歧義。
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd\THHmmss\Z')
    $finalName = "board-startup-$timestamp-$(Get-BoardTimeZoneTag).mv.db"
    $tmpPath = Join-Path $BackupDir "$finalName.tmp"
    $finalPath = Join-Path $BackupDir $finalName

    try {
        Copy-Item -LiteralPath $DatabaseFile -Destination $tmpPath -Force -ErrorAction Stop
    } catch {
        Write-BackupErr "複製資料庫到暫存備份檔失敗：$DatabaseFile -> $tmpPath"
        Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
        return $false
    }

    $sourceSize = (Get-Item -LiteralPath $DatabaseFile).Length
    $backupSize = (Get-Item -LiteralPath $tmpPath).Length
    if ($sourceSize -ne $backupSize) {
        Write-BackupErr "備份檔案大小與來源不一致（來源 $sourceSize bytes，備份 $backupSize bytes）"
        Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
        return $false
    }

    if (-not (Test-BoardH2File -Path $tmpPath)) {
        Write-BackupErr "備份檔案缺少 H2 MVStore 檔頭（H:2），可能已損壞：$tmpPath"
        Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
        return $false
    }

    try {
        Protect-BoardPath -Path $tmpPath -NewlyCreated $true
    } catch {
        Write-BackupErr $_.Exception.Message
        Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
        return $false
    }

    try {
        Move-Item -LiteralPath $tmpPath -Destination $finalPath -Force -ErrorAction Stop
    } catch {
        Write-BackupErr "備份改名（原子提交）失敗：$tmpPath -> $finalPath"
        Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
        return $false
    }

    Write-BackupLog "已建立啟動前備份：$finalPath"

    Invoke-BackupRetention -Directory $BackupDir `
        -Days (Get-BackupRetentionDays -Override $RetentionDays) `
        -MinCount (Get-BackupRetentionMinCount -Override $RetentionMinCount)

    return $true
}

# 允許獨立執行本腳本以利手動測試。被 dot-source 時 InvocationName 為 '.'，
# 不執行下面這段，也就不會有副作用。
if ($MyInvocation.InvocationName -ne '.' -and $args.Count -ge 1) {
    . (Join-Path $PSScriptRoot 'board-env.ps1')

    $standaloneDb = $args[0]
    if ($args.Count -ge 2 -and -not [string]::IsNullOrWhiteSpace($args[1])) {
        $standaloneBackupDir = $args[1]
    } else {
        $standaloneBackupDir = $script:BoardBackupDir
    }

    if (Invoke-BoardStartupBackup -DatabaseFile $standaloneDb -BackupDir $standaloneBackupDir) {
        exit 0
    }
    exit 1
}

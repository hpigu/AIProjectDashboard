# check.ps1 — bin/*.ps1 的語法檢查與備份／還原實測
#
# 為什麼需要這支腳本：Windows 版腳本（bin/board.ps1 等）不在 Maven 測試生命週期
# 內，而它們碰的是「使用者的資料庫檔案」——搬錯一步就是資料消失。這支腳本用假的
# H2 檔案（只有 H:2 檔頭是真的，腳本本來就不開資料庫）在暫存目錄裡跑完整流程，
# 驗證備份、保留策略、兩種格式的還原、以及所有拒絕路徑。
#
# 用法：
#   pwsh -NoProfile -File scripts/windows-check/check.ps1
#
# 可在 Windows PowerShell 5.1、Windows 的 pwsh 7+ 與 Linux/macOS 的 pwsh 上執行。
#
# 覆蓋不到的部分（必須在真的 Windows 上手動驗證，見 docs/operations.md）：
#   - JDK 21 自動偵測（依賴 Windows 上的實際安裝路徑）
#   - Get-NetTCPConnection 埠號反查
#   - stop 的 CTRL_C_EVENT 送出與關閉前備份是否真的產生
# 這些都需要真實的 Windows 環境與執行中的看板，不是這支腳本的範圍。

#Requires -Version 5.1

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$BinDir = Join-Path $RepoRoot 'bin'
$script:Failures = 0

function Assert-True {
    param([bool]$Condition, [string]$Description)

    if ($Condition) {
        Write-Host "  [PASS] $Description"
    } else {
        Write-Host "  [FAIL] $Description" -ForegroundColor Red
        $script:Failures++
    }
}

function Invoke-Restore {
    param([string[]]$Arguments)

    $script = Join-Path $BinDir 'restore-db.ps1'
    # 用子行程執行才能取得真正的離開碼，也才貼近使用者實際的呼叫方式。
    $exe = (Get-Process -Id $PID).Path
    if (-not $exe) { $exe = 'pwsh' }
    $output = & $exe -NoProfile -File $script @Arguments 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

function New-FakeH2File {
    param([string]$Path, [string]$Marker)
    [System.IO.File]::WriteAllText($Path, "H:2-$Marker")
}

# CI 的 Windows 記錄檔可讀性用；失敗不影響檢查本身。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

Write-Host '=== 1. 編碼與語法檢查 ==='
$scriptFiles = @(
    (Join-Path $BinDir 'board-env.ps1'),
    (Join-Path $BinDir 'backup-db.ps1'),
    (Join-Path $BinDir 'board.ps1'),
    (Join-Path $BinDir 'restore-db.ps1'),
    (Join-Path $PSScriptRoot 'check.ps1')
)

foreach ($path in $scriptFiles) {
    $name = Split-Path $path -Leaf

    # 這些檔案含中文註解與訊息，必須存成「UTF-8 with BOM」。
    #
    # Windows PowerShell 5.1（Windows 內建、多數使用者的預設）在檔案沒有 BOM 時
    # 會用系統 ANSI 代碼頁解碼 .ps1，非 ASCII 字元因此變成亂碼，字串字面值收不了尾，
    # 整份腳本 parse 失敗——不是訊息變亂碼而已，是完全跑不起來。PowerShell 7 預設
    # UTF-8 無 BOM，所以在 pwsh 上測不出這件事（實際上就是這樣漏掉、由 CI 的
    # windows-latest job 抓到的）。這個斷言把它釘住。
    $firstBytes = [System.IO.File]::ReadAllBytes($path)[0..2]
    $hasBom = ($firstBytes[0] -eq 0xEF -and $firstBytes[1] -eq 0xBB -and $firstBytes[2] -eq 0xBF)
    Assert-True $hasBom "$name 為 UTF-8 with BOM（Windows PowerShell 5.1 必要）"

    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$parseErrors) | Out-Null
    $clean = (-not $parseErrors) -or ($parseErrors.Count -eq 0)
    Assert-True $clean "$name 可正確解析"
    if (-not $clean) {
        $parseErrors | ForEach-Object {
            Write-Host ("         line " + $_.Extent.StartLineNumber + ": " + $_.Message) -ForegroundColor Red
        }
    }
}

# ---------------------------------------------------------------------------
# 測試環境：全部指向暫存目錄，並用不會有人聽的高位埠，確保絕不碰到正式看板
# ---------------------------------------------------------------------------
$workRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("board-wincheck-" + [Guid]::NewGuid().ToString('N'))
$dataDir = (New-Item -ItemType Directory -Path (Join-Path $workRoot 'data') -Force).FullName
$backupDir = (New-Item -ItemType Directory -Path (Join-Path $workRoot 'backups') -Force).FullName
$dbFile = Join-Path $dataDir 'board.mv.db'

$env:BOARD_HOME_DIR = $workRoot
$env:BOARD_PORT = '18097'
$env:BOARD_DB_URL = 'jdbc:h2:file:' + (Join-Path $dataDir 'board') + ';DB_CLOSE_ON_EXIT=FALSE'
$env:BOARD_BACKUP_DIR = $backupDir
$env:BOARD_LOG_FILE = Join-Path $workRoot 'board.log'
$env:BOARD_PID_FILE = Join-Path $workRoot 'board.pid'

try {
    . (Join-Path $BinDir 'board-env.ps1')
    . (Join-Path $BinDir 'backup-db.ps1')

    Write-Host '=== 2. 環境解析 ==='
    Assert-True ((Get-BoardDbFilePath) -eq (Join-Path $dataDir 'board')) 'BOARD_DB_URL 可反推出 H2 檔案路徑'
    Assert-True (-not (Test-BoardHttpReady -TimeoutSec 1)) '未啟動時 Test-BoardHttpReady 為 false'
    Assert-True ((Get-BoardPidFromFile) -eq 0) '沒有 PID 檔時回傳 0'

    Write-Host '=== 3. 啟動前備份 ==='
    New-FakeH2File -Path $dbFile -Marker 'original'
    Assert-True (Invoke-BoardStartupBackup -DatabaseFile $dbFile -BackupDir $backupDir) '備份回傳成功'
    $created = @(Get-ChildItem $backupDir -Filter 'board-startup-*.mv.db')
    Assert-True ($created.Count -eq 1) '產生一份正式備份'
    Assert-True (@(Get-ChildItem $backupDir -Filter '*.tmp').Count -eq 0) '沒有留下 .tmp 半成品'
    if ($created.Count -lt 1) {
        # 後續步驟都要用這份備份當來源，缺了就直接收工，不要再連鎖噴一堆
        # 看不出根因的例外。
        throw '啟動前備份未產生，後續檢查無法繼續'
    }
    Assert-True ([System.IO.File]::ReadAllText($created[0].FullName) -eq 'H:2-original') '備份內容與來源一致'

    Write-Host '=== 4. 資料庫不存在時略過備份（不算失敗）==='
    $missing = Join-Path $dataDir 'not-there.mv.db'
    Assert-True (Invoke-BoardStartupBackup -DatabaseFile $missing -BackupDir $backupDir) '首次啟動情境回傳成功'

    Write-Host '=== 5. 損壞的備份必須被拒絕，且不動現有資料庫 ==='
    $corrupt = Join-Path $backupDir 'board-startup-19990101T000000Z-UTC+0000.mv.db'
    [System.IO.File]::WriteAllText($corrupt, 'not-an-h2-file')
    $result = Invoke-Restore @($corrupt, '-Yes')
    Assert-True ($result.ExitCode -ne 0) '離開碼為非 0'
    Assert-True ([System.IO.File]::ReadAllText($dbFile) -eq 'H:2-original') '現有資料庫未被修改'
    Assert-True (@(Get-ChildItem $dataDir -Filter '*.restore.tmp').Count -eq 0) '沒有留下還原用的 .tmp'
    Assert-True (@(Get-ChildItem $dataDir -Filter 'board.mv.db.pre-restore-*').Count -eq 0) '沒有多餘的保留檔'
    Remove-Item $corrupt -Force

    Write-Host '=== 6. 不存在的備份檔 ==='
    $result = Invoke-Restore @((Join-Path $backupDir 'nope.mv.db'), '-Yes')
    Assert-True ($result.ExitCode -ne 0) '離開碼為非 0'
    Assert-True ([System.IO.File]::ReadAllText($dbFile) -eq 'H:2-original') '現有資料庫未被修改'

    Write-Host '=== 7. 非互動環境未加 -Yes 必須拒絕 ==='
    $result = Invoke-Restore @('latest')
    Assert-True ($result.ExitCode -ne 0) '離開碼為非 0'
    Assert-True ($result.Output -match '-Yes') '訊息說明需要 -Yes'
    Assert-True ([System.IO.File]::ReadAllText($dbFile) -eq 'H:2-original') '現有資料庫未被修改'

    Write-Host '=== 8. 從關閉前備份（zip）還原 ==='
    $zipWork = (New-Item -ItemType Directory -Path (Join-Path $workRoot 'zipwork') -Force).FullName
    $inner = Join-Path $zipWork 'board.mv.db'
    New-FakeH2File -Path $inner -Marker 'from-shutdown-zip'
    $zipPath = Join-Path $backupDir 'board-shutdown-20260805T140000Z-UTC+0000.zip'
    Compress-Archive -Path $inner -DestinationPath $zipPath -Force
    $result = Invoke-Restore @('latest', '-Yes')
    Assert-True ($result.ExitCode -eq 0) '離開碼為 0'
    Assert-True ([System.IO.File]::ReadAllText($dbFile) -eq 'H:2-from-shutdown-zip') '資料庫已換成備份內容'
    $preserved = @(Get-ChildItem $dataDir -Filter 'board.mv.db.pre-restore-*')
    Assert-True ($preserved.Count -eq 1) '舊資料庫改名保留一份'
    Assert-True ([System.IO.File]::ReadAllText($preserved[0].FullName) -eq 'H:2-original') '保留檔內容是還原前的資料庫'
    $preserved | ForEach-Object { Remove-Item $_.FullName -Force }

    Write-Host '=== 9. 從啟動前備份（.mv.db）還原 ==='
    $result = Invoke-Restore @($created[0].FullName, '-Yes')
    Assert-True ($result.ExitCode -eq 0) '離開碼為 0'
    Assert-True ([System.IO.File]::ReadAllText($dbFile) -eq 'H:2-original') '資料庫回到啟動前備份的內容'

    Write-Host '=== 10. -List 列出全部三種階段，且 latest 挑得到排程備份 ==='
    # scheduled 是最容易被漏掉、後果又最嚴重的一種：看板長時間運行時最新的備份
    # 幾乎必然是排程備份，還原工具若看不見它，latest 會安靜地挑到一份舊得多的
    # 快照，而使用者沒有任何線索。
    $schedInner = Join-Path $zipWork 'board.mv.db'
    New-FakeH2File -Path $schedInner -Marker 'from-scheduled-zip'
    $schedZip = Join-Path $backupDir 'board-scheduled-20260806T140000Z-UTC+0000.zip'
    Compress-Archive -Path $schedInner -DestinationPath $schedZip -Force
    (Get-Item $schedZip).LastWriteTimeUtc = (Get-Date).ToUniversalTime()

    $result = Invoke-Restore @('-List')
    Assert-True ($result.ExitCode -eq 0) '離開碼為 0'
    Assert-True ($result.Output -match 'board-startup-') '列出啟動前備份'
    Assert-True ($result.Output -match 'board-shutdown-') '列出關閉前備份'
    Assert-True ($result.Output -match 'board-scheduled-') '列出定期備份'
    Assert-True ($result.Output -match '定期（一致性快照）') '定期備份標示為自己的類別，不會被誤標成關閉前'

    $result = Invoke-Restore @('latest', '-Yes')
    Assert-True ($result.ExitCode -eq 0) 'latest 還原離開碼為 0'
    Assert-True ([System.IO.File]::ReadAllText($dbFile) -eq 'H:2-from-scheduled-zip') 'latest 挑到最新的排程備份'
    @(Get-ChildItem $dataDir -Filter 'board.mv.db.pre-restore-*') | ForEach-Object { Remove-Item $_.FullName -Force }

    Write-Host '=== 11. 保留策略：超過 30 天但只刪到剩 7 份 ==='
    $retentionDir = (New-Item -ItemType Directory -Path (Join-Path $workRoot 'retention') -Force).FullName
    for ($i = 1; $i -le 8; $i++) {
        $old = Join-Path $retentionDir ("board-startup-2020010{0}T000000Z-UTC+0000.mv.db" -f $i)
        New-FakeH2File -Path $old -Marker "old-$i"
        (Get-Item $old).LastWriteTimeUtc = (Get-Date).ToUniversalTime().AddDays(-40 + $i)
    }
    New-FakeH2File -Path $dbFile -Marker 'current'
    Assert-True (Invoke-BoardStartupBackup -DatabaseFile $dbFile -BackupDir $retentionDir) '備份成功'
    $remaining = @(Get-ChildItem $retentionDir -Filter 'board-startup-*.mv.db')
    # 9 份（8 份過期 + 1 份剛建立）。規則是「刪除後總份數仍 >= 7 才刪」，
    # 因此刪 2 份後剩 7 份就停手 → 最終 7 份。已用同一組 fixture 對 bin/backup-db.sh
    # 實測，bash 版結果相同（剩 7 份，刪掉的是最舊兩份），兩邊語意一致。
    Assert-True ($remaining.Count -eq 7) "刪除後保留 7 份（實際 $($remaining.Count)）"
    Assert-True (@($remaining | Where-Object { $_.Name -match '2020010[12]' }).Count -eq 0) '刪掉的是最舊的兩份'
    Assert-True (@($remaining | Where-Object { $_.LastWriteTimeUtc -ge (Get-Date).ToUniversalTime().AddDays(-1) }).Count -eq 1) '最新的備份必定保留'
} finally {
    if (Test-Path $workRoot) { Remove-Item $workRoot -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
Write-Host '=== jar 版號排序（Resolve-BoardJar 的挑選規則）==='

# Resolve-BoardJar 原本用 Sort-Object Name，那是字典序：3.10.0 排在 3.9.0 之前，
# 跳到 3.10.0 的那一刻就會挑到舊 jar，而且啟動會成功、沒有任何錯誤訊息。
# 這裡直接測排序鍵，不需要真的產生 jar 檔案。
# bin/board-env.sh 的 board_sort_jars_by_version 是同一套規則，對應的 bash 斷言
# 在 scripts/shell-check/check.sh，兩邊必須給出相同的順序。
# board-env.ps1 在上面第 2 節已經 dot-source 過（try/finally 不另開 scope），
# 這裡直接用 Get-BoardJarVersionKey。

function Get-LatestJarName {
    param([string[]]$Names)
    return ($Names | Sort-Object -Property @{ Expression = { Get-BoardJarVersionKey $_ } } | Select-Object -Last 1)
}

Assert-True ((Get-LatestJarName @(
    'ai-project-board-backend-3.9.0.jar',
    'ai-project-board-backend-3.10.0.jar')) -eq 'ai-project-board-backend-3.10.0.jar') `
    '3.10.0 比 3.9.0 新（字典序會弄反）'

Assert-True ((Get-LatestJarName @(
    'ai-project-board-backend-3.2.0.jar',
    'ai-project-board-backend-3.10.0.jar',
    'ai-project-board-backend-3.1.0.jar')) -eq 'ai-project-board-backend-3.10.0.jar') `
    '多筆混雜時仍挑到最新的一份'

Assert-True ((Get-LatestJarName @(
    'ai-project-board-backend-3.2.0-SNAPSHOT.jar',
    'ai-project-board-backend-3.2.0.jar')) -eq 'ai-project-board-backend-3.2.0.jar') `
    '3.2.0 比 3.2.0-SNAPSHOT 新'

Assert-True ((Get-LatestJarName @(
    'ai-project-board-backend-3.99.0.jar',
    'ai-project-board-backend-4.0.0.jar')) -eq 'ai-project-board-backend-4.0.0.jar') `
    '4.0.0 比 3.99.0 新'

Assert-True ((Get-BoardJarVersionKey 'ai-project-board-backend-3.10.0.jar') -eq
    (Get-BoardJarVersionKey 'C:\repo9\target\ai-project-board-backend-3.10.0.jar')) `
    '路徑裡的數字不會污染排序鍵'

Write-Host ''
if ($script:Failures -eq 0) {
    Write-Host '全部通過。' -ForegroundColor Green
    exit 0
}
Write-Host "有 $script:Failures 項失敗。" -ForegroundColor Red
exit 1

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
# 資料目錄預設值有三種情境：
#   a) Windows release ZIP：一律使用 user scope，ZIP 程式目錄可被覆蓋或刪除。
#   b) 既有 repo 使用者：repo 內 <repo>\data\board.mv.db 已存在 → 沿用該路徑。
#   c) 全新 repo 環境：改用 %USERPROFILE%\.ai-project-board\data\board。
#      plugin 目錄可能因更新而遺失內容，H2 檔案不能放在會被覆蓋的路徑下。
# ---------------------------------------------------------------------------
$script:BoardHomeDir = Get-BoardEnvValue 'BOARD_HOME_DIR' (Join-Path (Get-BoardUserHome) '.ai-project-board')

# Release ZIP 的根目錄同時含 app/ 與 runtime/；只要其中一個存在就視為 release
# 佈局，讓損壞／不完整的 ZIP 也 fail closed，絕不退回 PATH、target 或 repo data。
$script:BoardIsBundledRelease = (Test-Path (Join-Path $RepoRoot 'app')) -or `
    (Test-Path (Join-Path $RepoRoot 'runtime'))

if ($script:BoardIsBundledRelease) {
    $script:BoardDefaultDbDir = Join-Path $script:BoardHomeDir 'data'
} elseif (Test-Path (Join-Path $RepoRoot 'data\board.mv.db')) {
    $script:BoardDefaultDbDir = Join-Path $RepoRoot 'data'
} else {
    $script:BoardDefaultDbDir = Join-Path $script:BoardHomeDir 'data'
}

$script:BoardPort      = Get-BoardEnvValue 'BOARD_PORT' '8080'
$script:BoardDbUrl     = Get-BoardEnvValue 'BOARD_DB_URL' `
    ("jdbc:h2:file:" + (Join-Path $script:BoardDefaultDbDir 'board') + ";DB_CLOSE_ON_EXIT=FALSE")
if ($script:BoardIsBundledRelease) {
    $defaultBoardLogFile = Join-Path $script:BoardHomeDir 'logs\board.log'
} else {
    $defaultBoardLogFile = Join-Path $RepoRoot 'logs\board.log'
}
$script:BoardLogFile   = Get-BoardEnvValue 'BOARD_LOG_FILE' $defaultBoardLogFile
$script:BoardBackupDir = Get-BoardEnvValue 'BOARD_BACKUP_DIR' (Join-Path $script:BoardHomeDir 'backups')
$script:BoardConfigDir = Get-BoardEnvValue 'BOARD_CONFIG_DIR' (Join-Path $script:BoardHomeDir 'config')

# PID 檔放在家目錄而非 repo 內：repo 可能被 plugin 更新覆蓋，且同一份 repo 可能
# 被多個 worktree 共用，而「正在跑的看板」在一台機器上只有一個。
$script:BoardPidFile   = Get-BoardEnvValue 'BOARD_PID_FILE' (Join-Path $script:BoardHomeDir 'board.pid')

# 子行程要看得到這些值（Spring 讀的是環境變數）。
$env:BOARD_PORT = $script:BoardPort
$env:BOARD_DB_URL = $script:BoardDbUrl
$env:BOARD_LOG_FILE = $script:BoardLogFile
$env:BOARD_BACKUP_DIR = $script:BoardBackupDir
$env:BOARD_HOME_DIR = $script:BoardHomeDir
$env:BOARD_CONFIG_DIR = $script:BoardConfigDir

# ---------------------------------------------------------------------------
# 由 BOARD_DB_URL 反推 H2 檔案路徑（不含 .mv.db 副檔名）。
# 非 file 模式（mem:、tcp:）回傳空字串，呼叫端需自行判斷。
# ---------------------------------------------------------------------------
function Get-BoardDbFilePath {
    if ($script:BoardDbUrl -notmatch '^jdbc:h2:file:([^;]+)') { return '' }
    return $Matches[1]
}

# ---------------------------------------------------------------------------
# 遮罩 BOARD_DB_URL 中可能內嵌的連線憑證，供任何要印到 console／log 的地方使用。
#
# 對應 bin/board-env.sh 的 board_mask_db_url：H2 的 JDBC URL 允許把帳密當成
# 分號分隔參數內嵌在 URL 裡（USER=.../PASSWORD=...），而 BOARD_DB_URL 整串來自
# 使用者可控的環境變數，直接印出可能讓密碼明文留在終端機或日誌檔。
# ---------------------------------------------------------------------------
function Get-BoardMaskedDbUrl {
    param([string]$Url)

    if ([string]::IsNullOrEmpty($Url)) { return $Url }
    $masked = $Url -replace '(?i)(USER=)[^;]*', '$1***'
    $masked = $masked -replace '(?i)(PASSWORD=)[^;]*', '$1***'
    return $masked
}

# ---------------------------------------------------------------------------
# 套用「僅目前使用者」的 NTFS ACL：停用繼承、移除既有規則，只留目前使用者的
# FullControl。對稱於 dev.aiboard.config.LocalFilePermissionHardener 在 JVM
# 端對 Windows 套用的 AclFileAttributeView，以及 bin/board-env.sh 的
# board_secure_dir／board_secure_file（POSIX 0700／0600）。
#
# 新建路徑（$NewlyCreated=$true）套用失敗會拋例外，呼叫端必須讓它中止啟動——
# 此時路徑裡還沒有使用者資料，放行只會留下一個從一開始就權限過寬的路徑。
# 既有路徑套用失敗只印警告並回傳，不刪除或搬動任何資料、不阻擋服務繼續運作。
#
# 非 NTFS 磁碟區（例如 FAT32/exFAT，常見於隨身碟或部分虛擬磁碟）不支援
# Get-Acl/Set-Acl 的物件式 ACL，會在 Get-Acl 或 SetAccessRuleProtection 丟出
# 例外；這裡統一當成「檔案系統不支援此安全機制」的降級情境處理，而不是失敗，
# 語意與 Java 端 LocalFilePermissionHardener 對「找不到 AclFileAttributeView」
# 的處理一致。
# ---------------------------------------------------------------------------
function Protect-BoardPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][bool]$NewlyCreated
    )

    try {
        $acl = Get-Acl -Path $Path -ErrorAction Stop
    } catch {
        Write-Host "[board-env] 檔案系統不支援 NTFS ACL，略過權限收斂（僅本機存取風險仍在，建議改用 NTFS 磁碟區）：$Path"
        return
    }

    try {
        $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
        # 繼承旗標只對「容器」（目錄）有意義；.NET 對檔案的 ACE 不允許設定
        # ContainerInherit/ObjectInherit，會丟出 "No flags can be set." 例外，
        # 必須依路徑實際型別（目錄／檔案）分別建立規則。
        $isContainer = Test-Path -LiteralPath $Path -PathType Container
        if ($isContainer) {
            $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                $currentUser, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        } else {
            $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                $currentUser, 'FullControl', 'None', 'None', 'Allow')
        }

        # 停用繼承並清除既有規則（第二參數 $true = 移除繼承來的規則），
        # 只留下面明確加入的「目前使用者 FullControl」一條，等同 POSIX 的 0700/0600：
        # 沒有群組、沒有 Everyone、沒有繼承自父目錄的任何規則。
        #
        # 先用 @(...) 把 $acl.Access 具體化成獨立陣列再逐一移除：直接對
        # $acl.Access 管線呼叫 RemoveAccessRule 是邊列舉邊修改同一個底層集合，
        # .NET 對此的行為未定義（輕則漏刪，重則丟例外），必須先拷貝一份快照。
        $acl.SetAccessRuleProtection($true, $false)
        $existingRules = @($acl.Access)
        foreach ($existingRule in $existingRules) { $acl.RemoveAccessRule($existingRule) | Out-Null }
        $acl.AddAccessRule($rule)

        Set-Acl -Path $Path -AclObject $acl -ErrorAction Stop
        Write-Host "[board-env] 已套用僅限目前使用者的 ACL：$Path"
    } catch {
        if ($NewlyCreated) {
            throw "無法為新建路徑套用僅限目前使用者的 ACL，為避免留下權限過寬的敏感路徑，已中止：$Path ($($_.Exception.Message))"
        }
        Write-Host "[board-env][錯誤] 警告：無法修正既有路徑的 ACL（不影響服務運作、未變更任何資料）：$Path ($($_.Exception.Message))"
    }
}

# 建立目錄（若不存在）並套用僅限目前使用者的 ACL。沿路每一層由本次呼叫新建的
# 祖先都必須由外到內逐層收斂，不能只保護最內層：例如一次建立
# %USERPROFILE%\.ai-project-board\data 時，外層若仍繼承寬鬆 ACL，同機其他使用者
# 依然可能列出敏感路徑。語意與 bash 的 board_secure_dir 及 Java 的
# SensitiveDirectories.ensureSecureDirectory 對稱。
#
# 回傳 $true 成功／$false 失敗（任一新建祖先套用 ACL 失敗時）；呼叫端應在
# $false 時中止對應流程。既有目錄仍只做盡力收斂，失敗警告但不阻擋。
function New-BoardSecureDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    try {
        $fullPath = [System.IO.Path]::GetFullPath($Path)
    } catch {
        Write-Host "[board-env][錯誤] 無法解析目錄路徑：$Path ($($_.Exception.Message))"
        return $false
    }

    # 在 New-Item -Force 一次建立整條路徑前，先保存所有缺失層級。List.Insert(0,...)
    # 讓後續順序固定為外層到內層；若中途失敗，尚未放入資料前就 fail closed。
    $missingAncestors = New-Object 'System.Collections.Generic.List[string]'
    $cursor = $fullPath
    while (-not (Test-Path -LiteralPath $cursor -PathType Container)) {
        if (Test-Path -LiteralPath $cursor) {
            Write-Host "[board-env][錯誤] 目錄路徑已被非目錄項目佔用：$cursor"
            return $false
        }
        $missingAncestors.Insert(0, $cursor)
        $parent = Split-Path -Parent $cursor
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $cursor) { break }
        $cursor = $parent
    }

    try {
        New-Item -ItemType Directory -Path $fullPath -Force -ErrorAction Stop | Out-Null
    } catch {
        Write-Host "[board-env][錯誤] 無法建立目錄：$fullPath ($($_.Exception.Message))"
        return $false
    }

    try {
        if ($missingAncestors.Count -eq 0) {
            Protect-BoardPath -Path $fullPath -NewlyCreated $false
        } else {
            foreach ($createdPath in $missingAncestors) {
                Protect-BoardPath -Path $createdPath -NewlyCreated $true
            }
        }
        return $true
    } catch {
        Write-Host "[board-env][錯誤] $($_.Exception.Message)"
        return $false
    }
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

# 把 jar 檔名轉成「可以直接用字典序比較」的版號排序鍵。
#
# 原本 Resolve-BoardJar 用 Sort-Object Name，那是字典序：「3.10.0」排在「3.9.0」
# 之前，所以跳到 3.10.0 的那一刻就會挑到舊 jar。症狀是啟動成功、版本錯誤、全程
# 沒有任何訊息——最難發現的那一種。
#
# 與 bin/board-env.sh 的 board_sort_jars_by_version 是同一套規則，兩邊必須一致：
#   - 版號取自檔名中第一個「-數字」之後的部分（Maven 的 <artifactId>-<version>.jar），
#     因此路徑或 artifactId 裡的數字不會污染排序鍵
#   - 每段數字補零到 8 位，最多取 6 段，不足補 0
#   - 結尾 0／1 代表預發布／正式版：3.2.0-SNAPSHOT 比同版號的 3.2.0 舊
function Get-BoardJarVersionKey {
    param([Parameter(Mandatory = $true)][string]$Name)

    $base = [System.IO.Path]::GetFileNameWithoutExtension($Name)
    if ($base -match '-(\d.*)$') { $version = $Matches[1] } else { $version = $base }

    # 固定產出 6 段，不足的補 0——「3.2」與「3.2.0」必須算出同一把鍵，
    # 否則兩者在第一個相異字元上就被分開，等長比較才成立。
    $digits = [regex]::Matches($version, '\d+')
    $key = ''
    for ($i = 0; $i -lt 6; $i++) {
        if ($i -lt $digits.Count) { $value = [int64]$digits[$i].Value } else { $value = 0 }
        $key += '{0:D8}.' -f $value
    }

    if ($version -like '*-*') { return ($key + '0') }
    return ($key + '1')
}

# Windows Sandbox 乾淨環境手動 clean install 清單（#150）

## 背景

`#150` 的驗收條件之一是「Windows Sandbox 手動 clean install 結果有紀錄」。
Windows Sandbox 是 Windows 11 Pro/Enterprise 內建的一次性虛擬環境（設定 →
選用功能 → 啟用「Windows Sandbox」，需重開機），每次啟動都是一台**沒裝過任何
東西的機器**，關閉即銷毀，是「陌生人第一次拿到這個 release 會遇到什麼」的
最接近模擬。

`.github/workflows/release.yml` 的 `windows-x64` job 已經在 `windows-2022`
runner 上對**同一次 workflow 產生的真實 jlink ZIP** 做自動化驗證：
`scripts\windows-check\release-check.ps1 -Smoke`（clean 解壓 → 真實
start/status/stop）與 `scripts\windows-check\update-fixture.ps1`（checksum
拒絕、`publish`/`activate`/`start`/`readiness` 故障注入 rollback、
`rollback_activate` 硬失敗與手動復原路徑，見該檔案）。這些**已經是真實
Windows 上的自動化驗證，不是這份清單要重做的範圍**。

這份清單補的是自動化 runner 無法回答的部分：GitHub Actions runner 預先裝有
大量開發工具與環境變數（Java、Git、各種 PATH 設定），不是「沒裝過任何東西的
機器」；而 launcher 對「系統完全沒有 Java」「使用者帳號沒有系統管理權限」
「首次執行的 Windows Defender/SmartScreen 互動」這類環境假設，只有在真正
乾淨的機器上才能驗證。

**本機是 macOS，沒有 Windows／Windows Sandbox 環境，以下項目在本次任務中
無法實際執行，如實列為「待人工執行」。** 這份清單本身、以及上一段對「哪些
已被 CI 涵蓋、不必在 Sandbox 重做」的判斷，就是本次任務的產出。

## 前置準備

1. 從目標 stable release（例如 `vV`）下載
   `ai-project-board-backend-windows-x64-V.zip` 與
   `ai-project-board-backend-V-SHA256SUMS.txt` 到主機（Sandbox 外）。
2. 啟用 Windows Sandbox：設定 → 應用程式 → 選用功能 → 新增功能 →
   「Windows Sandbox」→ 重開機。
3. 開啟 Windows Sandbox，把上述兩個檔案拖曳/複製進 Sandbox 視窗（Sandbox
   預設與主機共用剪貼簿及拖放）。

## 清單

### 環境確認（證明真的是「沒裝過任何東西」）

- [ ] Sandbox 內執行 `java -version`：應回報找不到命令（找得到就代表這次
      驗證失去意義，需確認 Sandbox 真的是全新啟動）
- [ ] Sandbox 內確認目前使用者不是系統管理員（Sandbox 預設帳號
      `WDAGUtilityAccount` 為標準權限），驗證安裝與啟動全程不需要
      系統管理員權限

### Checksum 驗證

- [ ] 依 [release contract](release-contract.md#4-sha-256-完整性契約) 用
      `certutil -hashfile <zip> SHA256`（Windows 內建，Sandbox 內不需額外
      安裝）手動核對下載的 ZIP 與 `SHA256SUMS.txt` 內對應行的 hash 相符

### Clean install

- [ ] 解壓 ZIP 到一個含空白與中文字元的路徑（例如
      `C:\Users\WDAGUtilityAccount\Desktop\看板 安裝\`），確認解壓過程
      無錯誤、只有一層 top-level 目錄
- [ ] 執行 `bin\board.ps1 start`，確認：
  - 不需要另外安裝或指定 `JAVA_HOME`（launcher 使用 ZIP 內 bundled JDK 21）
  - 不需要系統管理員權限（不彈出 UAC 提示）
  - 印出的版本資訊與下載的 release 版號相符
  - 首次啟動如觸發 Windows SmartScreen「未知發行者」提示，記錄實際出現的
    文案與需要的操作步驟（例如「其他資訊」→「仍要執行」），供 README/
    安裝文件之後決定是否需要補充說明
- [ ] 開瀏覽器（Sandbox 內建 Edge）造訪 `http://127.0.0.1:8080`，確認畫面
      正常載入、無 console 錯誤
- [ ] 執行 `bin\board.ps1 status`，回報執行中
- [ ] 執行 `bin\board.ps1 stop`，確認行程實際結束、再次 `status` 回報已停止
- [ ] 確認資料、備份、日誌、PID、設定都落在
      `%USERPROFILE%\.ai-project-board` 下，而不是解壓目錄本身

### 收尾

- [ ] 記錄執行日期、下載的版本號、Windows Sandbox 底層宿主的 Windows 版本
      （`winver`）、以及上述每一項的實際結果（通過/失敗+原因）到本檔案的
      「驗證記錄」一節（比照 `docs/installation.md` 第 6 節的格式），或另開
      task 追蹤發現的落差
- [ ] 關閉 Windows Sandbox 視窗（即銷毀整個環境，不需額外清理）

## 已被自動化涵蓋、不需要在 Sandbox 重做的項目

以下項目已由 `.github/workflows/release.yml` 的 `windows-x64` job 在真實
Windows Server 2022 runner 上，對同一次建置的真實 jlink ZIP 自動驗證，
除非該 CI job 失敗，不需要在 Sandbox 內重複人工操作：

- ZIP 結構契約（單一 top-level 目錄、`app/*.jar`、`bin/*.ps1`、
  `runtime/bin/java.exe` 等必要檔案存在）——`release-check.ps1`
- 真實 start/status/stop（`-Smoke`）、`/api/health` 回報 bundled runtime
  版本——`release-check.ps1 -Smoke`
- checksum 拒絕在任何檔案變動前失敗——`update-fixture.ps1`
  `Invoke-ChecksumRejectionCase`
- `publish`/`activate`/`start`/`readiness` 各階段故障注入後 rollback：
  還原舊 versioned root、原 running/stopped 狀態、含 hash 可核對的 DB
  snapshot manifest——`update-fixture.ps1` `Invoke-NormalRollbackCase`
- rollback 本身失敗（`rollback_activate`）時 fail closed、保留舊 root 與
  手動復原路徑，並實際執行一次該復原路徑證明可行——`update-fixture.ps1`
  `Invoke-HardRollbackFailureCase`
- 含空白與非 ASCII 字元的安裝路徑——上述 fixture 全數使用
  `*-空白 路徑`/`程式 根目錄`/`解壓 暫存` 等路徑
- 專用 loopback port 於每個案例前後皆確認為空，清理只 force 該案例 PID
  檔中的 exact PID——`Assert-PortClosed`/`Stop-ScenarioSafely`

這份清單只補「CI runner 已預裝工具、不是真正乾淨環境」與「需要人眼判斷
SmartScreen/UAC 等互動式提示」這兩類 CI 結構性回答不了的問題。

## 驗證記錄

尚無記錄。第一次在 Windows Sandbox 執行本清單後，請在此處依「日期 / 版本 /
Windows 版本 / 逐項結果」的格式附上記錄，格式可參考
[docs/installation.md 第 6 節](installation.md#6-驗證記錄)。

# 產品化清單

「從能跑的專案 → 別人能拿去用的產品」還缺什麼、已經補了什麼、以及為什麼是這樣。

**最後更新**：2026-08-07 ｜ **目前版本**：v3.1.0 ｜ **下一批**：v3.2.0

編號沿用 2026-08-05 的產品化分析，**數字永不重排**，方便對照歷史討論。

---

## 這份文件怎麼讀

分三段，對應你會問的三個問題：

| 你想知道 | 看哪一段 |
|---|---|
| **接下來要做什麼？** | [還開著的](#還開著的) |
| **這個為什麼不做／為什麼長這樣？** | [決定不做的](#決定不做的-)、[決策紀錄](#決策紀錄) |
| **這件事以前發生過什麼？** | [已完成](#已完成)、[修掉的 bug](#修掉的-bug) |

**一個編號只會出現在一個地方。** 狀態改變時就地搬移，不留兩份。

圖例：⬜ 未開始 ｜ 🟡 部分完成 ｜ ✅ 完成 ｜ 🔒 已決定不做

---

# 還開著的

## 下一批：v3.2.0

**目標**：陌生人能 clone 就用。
**對象**：真的想拿去跟 agent 用的使用者——不是想貢獻的開發者，也不是路過看 README 的人。
**終點線**：在一台沒裝過任何東西的 Windows 上，從下載到「agent 實際建了任務、瀏覽器上看得到」。

一次發 v3.2.0，不拆 patch。執行順序：

| 序 | 工作 | 為什麼是這個順序 |
|---|---|---|
| 1 | **乾淨安裝演練 v1** | 演練結果可能推翻 #32 的優先度，必須最先拿到 |
| 2 | **#32** 免 JDK 的 Windows zip | 目標下的真正瓶頸 |
| 3 | **#35** `RoleSeeder` 別把本 repo 的開發設定編進產品 | 陌生人裝到的東西不該內建你的路徑與埠號 |
| 4 | **#15** `delete_project`（先補 archive 測試） | 唯一有真實證據的項目，但不阻擋前面 |

### 演練 v1 的設定

- **環境**：Windows Sandbox（Windows 11 Pro 內建，需在「選用功能」啟用並重開機）。
  每次啟動全新、關閉即銷毀，正是「沒裝過任何東西的機器」的準確定義。
- **受測物**：現況——下載 v3.1.0 的 jar、自備 JDK。**不是** zip，zip 還不存在。
  這是前測量：先知道陌生人今天實際卡在哪、卡多久。
- **切法**：Sandbox 只回答「沒有 JDK 的人能不能把看板跑起來」（那是它唯一能回答
  而主機回答不了的）。agent 接線與 plugin 在主機用一個全新專案目錄驗——那段跟
  JDK 無關，塞進 Sandbox 只會為了重裝 Claude Code 而增加噪音。
- **最大的風險是演練者本人**：你知道太多，會下意識補上文件沒寫的步驟。必須嚴格照
  文件字面走，不准用記憶。否則演練會回報「很順」，然後陌生人照樣卡死。
- **文件端已就緒**：#29（README 重整）已於 2026-08-07 完成，演練不會量到文件結構
  的噪音。

### v3.2.0 的項目

| # | 項目 | 狀態 | 要做什麼 |
|---|---|---|---|
| 32 | **免 JDK 的 Windows zip** | ⬜ | 走 **jlink 自帶 runtime 的 zip**：解壓即用，`bin/` 腳本完全保留，只改 java 的定位邏輯（優先找包裡的 runtime）。內容為 runtime + jar + Windows 腳本 + `plugin/`，**不放 `.sh`**（Windows zip 裡的 bash 腳本只會讓人猜錯）。**只出 Windows**，mac／Linux 維持現有 jar。`release.yml` 需從單一 ubuntu job 改成 matrix。<br>**兩個已知坑**：(1) java 定位邏輯是已經爆過兩次的同一塊程式碼（`JAVA_TOOL_OPTIONS` 前置行、PowerShell 5.1 的 `NativeCommandError`），第三次動它必須帶測試；(2) Spring 大量用反射與動態載入，`jdeps` 自動推導的模組集會漏掉執行期才要的東西（典型是 `jdk.crypto.ec`、`java.naming`、`java.sql`），要明確列模組或 `--add-modules ALL-MODULE-PATH`。<br>**待決**：解壓後的 `BOARD_DATA_DIR` 預設值——`Program Files` 沒寫入權限，Downloads 又容易被清掉 |
| 35 | **`RoleSeeder` 把本 repo 的開發設定編進產品** | ⬜ | `seedGenericRoles()` 是必要的——沒有它全新安裝的 `role` 表是空的，`get_role` 回「找不到角色」。但 `seedAgentDashboardOverrides()` 會偵測看板上有沒有叫 `AgentDashboard` 的專案，有就注入寫死本 repo 路徑所有權、埠號與 commit 規則的 `AGENT_DASHBOARD_SEEDS`。**等於把這個 repo 的開發夾層編譯進要發給陌生人的 jar**：任何人只要把專案取名 AgentDashboard 就會拿到這套慣例。也讓 `RoleSeeder.java` 膨脹到 350+ 行、絕大部分是寫死的文字。<br>**做法**：移到測試 fixture，或改為從外部檔案載入 |
| 15 | **`delete_project`（清掉整個專案）** | ⬜ | **形狀已改**。原規格是逐筆 `delete_tasks`，但真實場景是「我剛剛亂試了一堆，想把痕跡清掉」——要的是一次清整區；而且任務之間有前置相依，逐筆硬刪會留下孤兒相依。<br>**設計**：`delete_project`，**要求專案先是 ARCHIVED 才能刪**。理由見[決策紀錄](#決策紀錄)。<br>**前置**：先補 `ProjectArchiveTools` 的測試，否則整條「先封存才能刪」的安全鎖鏈建立在未被測試的邏輯上。<br>逐筆刪除留待真的有人要求再說——從來沒出現過「我想刪掉某一筆」這個痛點 |

## 已知未爆彈

放這裡的東西有一個共同特徵：**今天不痛，但已經確定會爆，而且爆的時候沒有錯誤訊息**。
它們跟優先度排序放在一起必然被稀釋掉，因為排序永遠選今天痛的。**不排優先度，發現就修。**

| # | 項目 | 狀態 | 說明 |
|---|---|---|---|
| 36 | **跨聚合直接抓別人的 Repository** | ⬜ | **新增（2026-08-07）**。五處：`health/DiagnosticsService`（→ Project + Task）、`project/ProjectArchiveService`（→ Task）、`web/ProjectQuery`（→ Project + Task）。加上 `task ↔ project` 的套件循環（task→project 5 次、project→task 2 次）。<br>今天不痛是因為只有一個人在改；但它讓「改 Task 會不會影響封存」這個問題沒有邊界可循。**注意這個問題只有在 package-by-feature 下看得見**——改成 controller/service/repo 分層會讓它變成「正常操作」而永久隱形 |

*（#30 jar 版號排序已修，見[修掉的 bug](#修掉的-bug)。）*

## 尚未排程

沒有觸發條件、也還沒被否決的。**每一項都該有「什麼時候會變成該做」的條件**，
沒有條件的就往 🔒 搬——一個永遠不會被觸發的待辦只是在稀釋其他待辦。

| # | 項目 | 狀態 | 觸發條件／備註 |
|---|---|---|---|
| 34 | **升 Spring Boot 4 / Spring AI 2** | ⬜ | Dependabot 想把 Spring Boot `3.5.16 → 4.1.0`、Spring AI `1.1.8 → 2.0.0`——**兩個都是主版號跳躍，不是安全修補**，不能當一般依賴更新合併。整個 MCP 層直接建在 Spring AI 的 tool API 上（`McpToolConfig` 的 `MethodToolCallbackProvider`、六個工具類別的 `@Tool`／`@ToolParam`），1.x → 2.0 很可能改掉這些，那是移植工作不是升版號。**Dependabot 把它包成一個 PR 是因為自己設的 Spring 群組規則**——規則是對的，但它讓一次大改長得跟一般更新一樣。<br>**觸發**：3.5.x 進入 EOL，或出現只有 4.x 才修的安全問題。做之前先在分支上跑 `mvnw test` 量損害範圍。#25 的 MCP 協定層 E2E 測試正好是這次升級的安全網 |
| 33 | `TaskEditTools`／`TaskBlockTools` 補測試 | ⬜ | 兩個 MCP 工具類別目前無覆蓋測試。`ProjectArchiveTools` 的部分已納入 #15 前置。**觸發**：任一類別要改動時 |
| 12b | 依賴弱點掃描 | 🟡 | Dependabot alerts 已開；`dependency-review` 已於 2026-08-07 加進 CI（擋 PR 新引進的漏洞依賴）。**仍缺**：對「已存在依賴」的定期掃描（CodeQL 或 OWASP dependency-check）。**觸發**：專案開始有外部使用者，或依賴數再翻倍 |
| 27 | 升級 migration 測試 | ⬜ | 缺「舊版 data 檔升到新版」的回歸。**觸發**：下一次有破壞性 schema 變更時 |
| 26 | 覆蓋率門檻與靜態分析 | ⬜ | jacoco／spotbugs／checkstyle 皆無。**觸發**：有第二個人開始提 PR |
| 24 | 前端回歸進 CI | ⬜ | `scripts/frontend-regression/check.mjs` 需要 CDP 的 Chrome（`:9222`）與看板跑在 `:8091`，仍需手動。**觸發**：前端出現第二次回歸事故 |
| 13 | export/import | ⬜ | 資料只活在 H2 檔裡。排程備份解決了「快照存在嗎」，但沒解決「想換機器、想給別人看、想用別的工具分析」。**觸發**：你真的要換機器，或有人問「資料能導出嗎」 |
| 20 | 首頁「需要我處理什麼」 | ⬜ | 跨專案 BLOCKED／待驗收 aggregate；後端已有 blocked 統計。**觸發**：同時跑的專案數 > 3 |
| 23 | 統計視圖 | ⬜ | cycle time、各角色吞吐量；資料都在 `task_log`。**觸發**：有人問「這批做多久」 |
| 16 | 資料模型欄位 | ⬜ | priority／due date／estimate／label。**觸發**：`sort_order` 不夠用時 |
| 8 | 觀測性 | ⬜ | 無 metrics／結構化日誌；可考慮 `bin/board diagnose` 產出可貼的診斷包。**觸發**：第一次出現「線上壞了但看不出為什麼」 |
| 28 | 開源治理檔案 | 🟡 | `SECURITY.md` 已有；CONTRIBUTING、issue/PR template 未做。**優先度低**：對象已定為「想拿去用的使用者」而非「想貢獻的開發者」。**觸發**：收到第一個外部 PR |

## 需要人手動處理的

不是寫程式能解決的，只有你能做。

| 事項 | 狀態 | 說明 |
|---|---|---|
| **啟用 Windows Sandbox**（選用功能 + 重開機） | ⬜ | 演練 v1 的前置。Pro 版內建，目前未啟用 |

*（其餘手動項目皆已完成，見[已完成](#已完成)。）*

---

# 決定不做的 🔒

集中放在這裡的用意：**避免下一輪又被重新提案**。每一項都附理由；有條件的寫清楚
什麼情況下會重新評估。

| # | 項目 | 為什麼不做 |
|---|---|---|
| 10 | 可選 token 認證 | **有條件**。`BOARD_TOKEN` → `Authorization: Bearer`。**它不是「手機功能」本身**：手機看得到需要 `BOARD_HOST=0.0.0.0` + `BOARD_ALLOWED_HOSTS` + 本項，三者缺一不可。真正的順序位置是「你哪天想開 `BOARD_HOST` 的前一刻」——在那之前沒有價值，在那之後是必須 |
| 11 | `/api/diagnostics` 保護 | 原清單說「會吐路徑」**已過期**——路徑洩漏在 P0 收斂時就處理掉了。目前吐的是磁碟容量、備份時間與大小、專案／任務數量、migration 版本，沒有路徑或 secret。伺服器只聽 loopback 且前面有 Host/Origin guard。與 #10 綁同一條決策：開 `BOARD_HOST` 的那一刻兩者一起解鎖 |
| 14 | board API 分頁 | 單機、H2、一個人用，一次撈幾百筆對本機服務不是問題；真正會先痛的是前端渲染而非 API，那是不同的項目。改 Postgres 或多人使用時連同 #17 一起重新評估 |
| 17 | Postgres profile | 換 DB 就是換定位，屆時是新的一輪分析，不是清單上的一格 |
| 18 | UI 開放寫入 | **維持唯讀**：所有寫入繼續只走 MCP，`web/` 僅 GET。定位為 agent 的觀測面板 |
| 21 | UI i18n | 改為收回 `README.en.md` 的承諾：明說這是中文專案、介面為中文，`README.en.md` 只讓英文讀者看懂這是什麼。原因是範圍遠大於清單所寫——UI 字串、錯誤訊息、MCP 工具 description 與回傳訊息全是中文，而 MCP description 改成英文會直接影響 agent 行為、需要重新驗證一輪 |
| — | Docker image | Windows 使用者要先裝 Docker Desktop（門檻可能比裝 JDK 高），且 `bin/` 腳本、PID 檔、埠號反查、`CTRL_C_EVENT`、備份還原全部失效——等於重做 P0 |
| — | jpackage 原生安裝檔 | 使用者體驗最好，但 Windows 要面對 SmartScreen、macOS 不簽章／公證就被擋。對單人專案是一條持續噴錢的路 |
| — | mac／Linux zip | 只出能真機驗證的平台。那兩個平台的使用者裝 JDK 的成本本來就低很多 |
| — | 改成 controller/service/repo 分層 | **評估後否決（2026-08-07）**。寫入路徑根本不經過 controller（走 `mcp/`），資料夾名稱會立刻說謊；每次改一個功能要動四個資料夾；**而且它會讓 #36 的跨聚合耦合永久隱形**——所有 Repository 都在同一個套件裡之後，跨聚合存取就變成正常的事。現在的 `mcp/`＋`web/`（兩個真實入口）＋`task`/`project`/`role`（三個真實領域）已經是對的形狀，分層紀律由 `AGENTS.md` 的三條規則提供 |
| — | 設 branch protection（CI 為必要檢查） | 單人開發時只會擋到自己；CI 照樣每次都跑。等有外部貢獻者再設 |

---

# 決策紀錄

只記「為什麼」，不記「做了什麼」（後者在[已完成](#已完成)）。

| 日期 | 決策 | 理由 |
|---|---|---|
| 2026-08-05 | 先走「單機 agent 觀測台」而非「團隊看板」 | 後者要先解認證、多人身分、H2 換 Postgres，是不同量級的工程 |
| 2026-08-05 | **UI 維持唯讀** | 所有寫入只走 MCP。開放 UI 寫入需先有認證與 audit，且會改變產品定位 |
| 2026-08-06 | **下一批的目標是「陌生人能 clone 就用」，對象是「想拿去跟 agent 用的使用者」** | 三種陌生人（想用的、想貢獻的、路過的）需要的東西完全不同，做錯對象就是白做。選「想用的」是因為另兩種都要先有這種人才會出現 |
| 2026-08-06 | **這個目標沒有真實使用者，所以先演練再投** | 選了這個目標就等於承認整批工作沒有證據支撐——「陌生人會需要 X」全是猜的。用一次乾淨環境演練把猜測換成觀察，做法沿用 `docs/operations.md` 的還原演練（那次抓出三個真機 bug）|
| 2026-08-06 | **免 JDK 走 jlink zip，不走 Docker、不走 jpackage** | `bin/` 腳本、PID 檔、埠號反查、`CTRL_C_EVENT`、備份還原**全部建立在「JVM 直接跑在主機上」的假設上**，那是整個 P0 的主要投資。Docker 會讓它們全部作廢，jpackage 則是簽章與公證的無底洞。jlink 只需要改 java 的定位邏輯 |
| 2026-08-06 | **只出 Windows zip** | 只出能真機驗證的平台。發一個自己驗不了的產物給陌生人，比不發更傷信任 |
| 2026-08-06 | **備份保留額度依階段分桶**（startup／shutdown／scheduled 各自獨立） | 共用一個桶的話，每 6 小時一次的排程備份一週內就會把關閉前那份一致性快照擠掉，而那份往往最值得留。且這種錯誤毫無徵兆，只有需要還原時才會發現 |
| 2026-08-06 | **跟不上的 SSE 客戶端直接斷線，不默默丟事件** | 前端在重連後本來就會整批重抓，斷線讓它自我修復到正確狀態；丟事件則讓畫面停在一個沒人知道是錯的狀態 |
| 2026-08-06 | **MCP 測試自寫客戶端，不引入 MCP client SDK** | 這層測試要驗的正是「線上實際傳了什麼」，用官方 client 會把 schema 與分幀細節一起抽象掉，等於用被測物件驗自己 |
| 2026-08-06 | **`delete_project` 要求專案先是 ARCHIVED** | agent 會走阻力最小的路徑。如果 delete 比 archive 好呼叫，就等於用一個不可逆的工具取代了可逆的那個。「先封存再刪」讓阻力天然高於 archive，同時複用 archive 已有的 preview／reason／稽核機制 |
| 2026-08-06 | **本文件移進 repo 版控** | 原本在 `~/Downloads/`。它同時是清單也是變更紀錄，變更紀錄的價值來自「看得到它怎麼變的」——放在版控外等於留下清單、殺掉紀錄那一半 |
| 2026-08-07 | **`bin/` 還原維持獨立入口，不做成 `board restore`** | 還原必須在看板停止時執行、而且會覆寫資料庫檔。放進天天用的 `start`/`stop` 入口等於降低一個破壞性操作的阻力——與 #15「先封存才能刪」是同一條推理 |
| 2026-08-07 | **兩套 plugin 用「工具名集合比對」守門，不做逐行 diff** | 兩邊本來就有意寫得不一樣（frontmatter、`board 的` 前綴、粗體用法）。逐行比會每次都紅燈然後被忽略——**一道永遠在響的警報等於沒有警報**。改為比對會出事的東西：角色集合、工具名集合、category、硬邊界在不在 |
| 2026-08-07 | **依賴弱點用 `dependency-review`，不用 OWASP dependency-check** | 後者要維護整份 NVD 資料庫，2023 年起沒有 API key 會被 rate limit 到數十分鐘，API key 還要申請與存 secret。對單人專案是持續耗維運心力的路，而解的問題與前者高度重疊 |
| 2026-08-07 | **不改成 controller/service/repo 分層** | 見[決定不做的](#決定不做的-)最後一列 |
| 2026-08-07 | **本文件依「開著的／不做的／已發生的」重排** | 原本同時用優先度（P0/P1/P2）與完成狀態兩個軸分類，結果兩邊都看不清楚：P0/P1 整段完成了卻擺在最上面，真正待辦的被擠到下面；同一個編號出現在兩處；🔒 散落三處。**一份清單的價值來自「能不能一眼看到接下來要做什麼」**，其他都是次要 |

---

# 已完成

按批次分組。**完成的項目就地搬到這裡，不留在上面的區塊**。

## Unreleased（2026-08-07 起）

| # | 項目 | 改動 |
|---|---|---|
| 29 | **README 拆分** | **提前做掉，因為它擋在 #32 前面**：#32 的終點線是「陌生人照文件字面走能不能跑起來」，而演練的受測物就是 README。用一份把入門與治理混在一起的 34KB 單檔去演練，量到的是文件結構的問題，不是免 JDK 的問題。<br>README 584 行 → 400 行，只走入門路徑（安裝 → 接 agent → 第一次使用 → 設定 → 備份 → 安全），**Windows 與 mac／Linux 並列成表格而非附註在後**；完整介面參考移到 `docs/mcp-tools.md`、角色與派工移到 `docs/agent-roles.md`。順帶刪掉兩份「不是任何東西的正本」的文件（`scope-leader-dispatch.md`、`dev-isolation.md`）。`cb17d95` |
| 7 | 排程備份 | 執行中預設每 6 小時產生一份 H2 一致性快照（`BOARD_BACKUP_INTERVAL` 可調、`BOARD_BACKUP_SCHEDULE_ENABLED=false` 可停）。**保留額度依階段分桶**，否則排程備份一週內就會把關閉前那份快照擠掉。還原工具與 `/api/diagnostics` 同步納入新 phase。`bbd8425` |
| 12 | SSE 連線上限與非同步廣播 | 廣播改為入列 + 背景執行緒寫入，解除與 agent tool call 的耦合；每連線有界佇列，跟不上就斷線讓前端重連重抓；連線上限 32，超過回 503。`9c711ed`（PR #8） |
| 25 | MCP 協定層 E2E 測試 | 7 個測試打真正的 `/mcp`：initialize 身分宣告、tools/list 完整清單、關鍵工具的參數名與必填集合、tools/call 走完整條線、業務錯誤回可讀訊息、未知工具被拒絕、缺 session 不被當正常請求。自寫極簡客戶端而非用 SDK。`59d82f6` |
| 30 | jar 挑選的版號排序 | 見[修掉的 bug](#修掉的-bug)。`3b58b51` |
| 31 | plugin 完整性 | 從 #13 拆出。**原本的假設有兩項是錯的**：<br>(1) ~~`plugin/bin/` 缺 Windows 腳本~~ → 實際更糟：`plugin/bin/start-board.sh` 是 git symlink，而 Windows 的 `core.symlinks` 預設 false，checkout 出來是 24 位元組的文字檔，**每個 Windows 使用者拿到的都是壞檔案**。它同時是死碼，因此直接移除。`d8ee0d5`<br>(2) ~~角色指引不跟著 plugin 走~~ → **不成立**。`plugin/agents/` 六個角色檔完整在 plugin 裡；實質指引走 `get_role`，而 `RoleSeeder` 啟動時以 `createRoleIfAbsent` 補齊通用角色。<br>(3) plugin 版號同步 → 隨 `e5fdcab` 解決 |

## v3.1.0（2026-08-06）

| # | 項目 | 改動 |
|---|---|---|
| 1 | 完整 release | `v3.1.0` 已發布，附可下載的 jar 與 checksum，且是跑完 ubuntu 上的完整測試才產出。原備註裡的「Docker 或 jpackage 免 JDK 包裝仍未做」已獨立為 #32——藏在備註裡等於不存在 |
| 4 | 版號紀律 | 跳 3.1.0、補 `CHANGELOG.md`、`/api/health` 與 `/api/diagnostics` 帶 `commit`。版本與 commit 收斂到共用的 `BuildInfoProvider`，來源是建置期產生的 `build-info.properties` 與 `git.properties`。`0856f23`。plugin manifest 的部分由 `scripts/check-versions.sh` 守門（`e5fdcab`）|
| 19 | **BLOCKED 桌面通知** | 標題列開關，預設關閉；任務轉 BLOCKED 時發系統通知，點擊跳到該專案。視窗在前景時不發，權限被收回時顯示「已封鎖」而非假裝關著。**比原估多一步**：payload 原本沒有任務標題，通知會是「任務 #42 → BLOCKED」，已在五個 publish 點補上 `title`。webhook（Slack／Discord）仍未做。`9a62bd6` |

## v3.0.0（2026-08-05，P0 全數完成）

沒有這些就不能叫產品。

| # | 項目 | 改動 |
|---|---|---|
| 2 | **CI** | 每個 PR 跑 `mvnw test` 與 package；第二個 job 對 `bin/` 與 `scripts/` 做版號檢查、行為檢查、`bash -n` 與 shellcheck；第三個 job 在 windows-latest 上以 PowerShell 5.1 與 pwsh 7 各跑一次 `scripts/windows-check/check.ps1`。`db619a8` `d6bb91f`。2026-08-07 再加 plugin 漂移守門與 `dependency-review`（`a49d436`）|
| 3 | **Windows 支援** | `bin/board.ps1`、`board-env.ps1`、`backup-db.ps1`、`restore-db.ps1`，與 bash 版共用預設值、備份命名與保留策略。Windows 沒有 SIGTERM，`Stop-Process` 等同 `kill -9`（跳過關閉前備份），因此 `stop` 改送 `CTRL_C_EVENT`。`.ps1` 必須存成 UTF-8 with BOM，否則 Windows PowerShell 5.1 會用 ANSI 解碼而完全無法解析。`d6bb91f` `2aeb2f3`／真機驗證時發現三個 bug，見下方 `f23cd31` `9a62bd6` |
| 5 | **服務啟停** | `bin/board`（start/stop/restart/status/logs）。`stop` 送 SIGTERM 並等待關閉前備份，逾時不自動 SIGKILL；動手前確認 PID 真的是看板。改 nohup + disown + PID 檔（舊版關掉終端機的 SIGHUP 會殺掉看板並連帶失去備份）。`1a1048f`。2026-08-07 把 `start-board.sh` 併回本體，兩平台各四支對稱（`ca0c533`）|
| 6 | **備份還原** | `bin/restore-db.sh`：三種來源皆可還原，拒絕在執行中還原，現有資料庫改名保留為 `.pre-restore-<UTC>` 而非刪除，寫入走 tmp → 驗證 H2 檔頭 → 原子改名。`docs/operations.md` 附發版前演練清單。`1a1048f` |
| 9 | **Origin/Host 驗證** | `LocalOriginGuardFilter`：非 loopback 的 `Host`／`Origin` 回 403，阻擋 DNS rebinding。`Origin` 擋非 GET/HEAD 請求，`Host` 擋 rebinding 後不送 Origin 的 GET，兩者缺一不可。`BOARD_ALLOWED_HOSTS` 為刻意放行的逃生門。`3e22153` |
| 22 | **首次啟動引導** | 空看板改顯示三步驟引導，MCP 端點由 `window.location.origin` 推導（換過 `BOARD_PORT` 也正確）、可一鍵複製；「篩選沒篩到」是另一個狀態，兩者不互相誤判。順帶補 favicon，消掉每次載入的 404。`1b037b5` |

## 已完成的手動項目

| 事項 | 說明 |
|---|---|
| 開啟 Private vulnerability reporting | `SECURITY.md` 指向的回報入口 |
| 開啟 Dependabot alerts | 與 `dependabot.yml`（版本更新）是兩套機制 |
| 合併 PR #1 | `d4e80a3`，最終 6 個 commit |
| **在真實 Windows 上確認 `board.ps1 stop` 真的產生 `board-shutdown-*.zip`** | Windows 11 Pro + Oracle JDK 21.0.12 實跑：產生 zip（內含 `board.mv.db`），行程全清、埠號釋放、PID 檔清除，重複 `stop` 為 idempotent |
| Windows 上確認 JDK 21 自動偵測與埠號反查 | 埠號反查正確。**JDK 偵測原本是壞的**，見下方 bug 表 |
| 推第一個 tag 發布 release | `v3.1.0`，附 jar 與 `.sha256`。第一次打錯（tag 打在未合併版號變更的 main 上，產出 `3.0.0.jar`），已加 CI 守門 |
| 在真實瀏覽器確認 BLOCKED 通知會彈出 | 授權後實際觸發，通知內容與點擊跳轉都正確 |

---

# 修掉的 bug

這一段是本文件最值得保留的部分：**每一列都是「當時以為對、實際上錯」的紀錄**，
而且大多沒有錯誤訊息。依發現時間排序。

## 第一輪 — P0 實作過程中

| 問題 | 影響 | 改動 |
|---|---|---|
| 保留策略在 Linux 上不是「保留最新 N 份」 | `stat -f '%m' \|\| stat -c '%Y'`：GNU 的 `-f` 是「顯示檔案系統狀態」，會以 exit 0 印出多行資訊讓 `\|\|` 右邊永不執行 → 排序鍵是垃圾，可能刪掉最新的備份 | 新增 `board_file_mtime()`（GNU → BSD → perl，驗證純數字），補測試斷言「留下的是最新七份」`d6bb91f` |
| JDK 21 被誤判為未安裝 | 只看 `java -version` 第一行，但 `JAVA_TOOL_OPTIONS` 的 "Picked up ..." 會排在版本字串前（企業 proxy、IDE、容器常設） | 改抓含 `version "` 的那一行 `1a1048f` |
| 備份路徑含空白時保留策略失效 | `sorted=($(...))` 的 word splitting 把一筆路徑拆成兩筆 | 改 while read，避開 `mapfile`（macOS bash 3.2 沒有）`db619a8` |

## 第二輪 — 2026-08-06 真機驗證與 P1 實作過程中

| 問題 | 影響 | 改動 |
|---|---|---|
| **SSE 連線讓行程關不掉**（不限 Windows） | SSE emitter 永不逾時，對 Tomcat 是永遠不結束的 async request。只要瀏覽器開著看板（＝正常使用情況），graceful shutdown 就一直等，逾時後 JVM 仍不退出，持續持有 H2 `.mv.db` 鎖 → 下次啟動 MVStoreException，而 `stop` 早已回報「已停止」 | `SseEmitterRegistry` 實作 `SmartLifecycle`，關閉時主動結束所有連線；phase 需大於 `webServerGracefulShutdown`。實測 30 秒逾時 → 3 毫秒完成 `9a62bd6` |
| **PowerShell 5.1 上 JDK 21 一律偵測不到** | `java -version` 寫 stderr，5.1 在 `ErrorActionPreference='Stop'` 之下把 native 指令的 stderr 包成終止性 `NativeCommandError`，偵測函式必定落進 catch → `board.ps1 start` 完全無法使用。pwsh 7 無此行為，只測 7 發現不了 | 呼叫包進自己的 scope 並降回 `Continue`，以 `ToString()` 還原純文字 `f23cd31` |
| **`stop` 會謊報成功** | Oracle `javapath\java.exe` 是 launcher stub，會 spawn 真正跑 shutdown hook 的子行程，PID 檔記到 stub；且埠號釋放只是關閉序列的早期步驟 | 送訊號前先收集整組看板行程（PID 檔 + 埠號持有者 + 子行程），等全部消失才回報成功 `9a62bd6` |
| **慢客戶端拖慢 agent** | 事件廣播跑在 `AFTER_COMMIT` 同步監聽器上，也就是 agent 的 tool call 執行緒；一條讀取太慢的 SSE 連線會讓 `create_tasks` 跟著卡住 | commit 執行緒只入列不做 I/O，寫入交給背景執行緒；跟不上的客戶端直接斷線 `9c711ed`（PR #8） |
| MCP `serverInfo.version` 寫死 | agent 端唯一看得到的版本號。跳版時必然忘記改——3.1.0 的 build 實際回報 3.0.0 | 改由 `pom.xml` 的 `${project.version}` 帶入 `9a62bd6` |
| `/api/health` 的 version 在開發時一律 `unknown` | 讀 jar manifest，只有打包後才有值；`mvnw spring-boot:run` 與 IDE 啟動都拿不到 | 改由建置期的 `build-info.properties` 提供 `0856f23` |
| `board.ps1 logs` 中文亂碼 | logback 以 UTF-8 寫檔，PowerShell 5.1 的 `Get-Content` 預設用系統 ANSI 代碼頁解碼 | 加上 `-Encoding UTF8` `9a62bd6` |
| release 不檢查 tag 與版號是否一致 | `v3.1.0` 打在未合併版號變更的 commit 上，產出 `3.0.0.jar` 掛在 `v3.1.0` 底下，全程綠燈 | workflow 加前置檢查，不一致就中止 `40fe317` |

## 第三輪 — 2026-08-06 產品化盤點時

| 問題 | 影響 | 改動 |
|---|---|---|
| **三份 plugin manifest 的 version 停在 3.0.0** | **原描述只記到 `plugin/.claude-plugin/plugin.json`，實際盤點後是三個現場**：另有 `.claude-plugin/marketplace.json` 與 `.codex-plugin/.codex-plugin/plugin.json`。第一份的版號正是陌生人安裝時看到的數字。跟「MCP `serverInfo.version` 寫死」是同一類問題的第二、三、四個現場 | 新增 `scripts/check-versions.sh`，以 `pom.xml` 為唯一事實來源比對三份 manifest **與 README 安裝指令裡的 jar 檔名**（後者是陌生人照抄的第一行指令）。取值前先確認每個檔案剛好只有一個 `version` 鍵、且 pom 的 `<version>` 確實是字面值。讀 pom 用 awk 跳過 `<parent>` 而非 `mvn help:evaluate`——為一個字面值開 JVM 要十幾秒。已用反例驗證。`e5fdcab` |
| **jar 挑選用字典序**（#30） | **原描述只記到 `bin/board.ps1`，實測後確認 bash 側是完全相同的 bug——不限 Windows，兩個平台都會踩**。字典序讓 `3.10.0` 排在 `3.9.0` 之前，跳版當下會挑到舊 jar 且啟動成功、無任何訊息 | 兩邊改用同一套自組排序鍵（每段數字補零到 8 位、最多 6 段；版號只取檔名中第一個「-數字」之後的部分，避免路徑數字污染；SNAPSHOT 排在同版號正式版之前）。不用 `sort -V` 是因為它不是每個平台都有。新增 `scripts/shell-check/check.sh`（對稱於 `windows-check`）補上 bash 側原本完全沒有的行為測試。`3b58b51` |
| `plugin/bin/` 只有 `start-board.sh`，沒有 Windows 版 | 目標使用者是 Windows 人，plugin 遞給他的卻是 bash 腳本 | 實際情況比這更糟（是壞掉的 symlink），已隨 #31 移除。`d8ee0d5` |
| `ProjectArchiveTools`、`TaskEditTools`、`TaskBlockTools` 無覆蓋測試 | `archive_project` 的狀態機（ARCHIVED 判定、重複封存拒絕、IN_PROGRESS 閘門）沒人看著，而 #15 的設計是「先封存才能刪」——整條安全鎖鏈建立在未被測試的邏輯上 | **尚未修**。archive 的部分納入 #15 前置；另兩個見 #33 |

## 第四輪 — 2026-08-07 全面梳理時

| 問題 | 影響 | 改動 |
|---|---|---|
| **看板給 `qa` 的指引叫它呼叫一個它拿不到的工具** | `RoleSeeder` 的 `QA_GENERIC` 寫「發現 production bug 時呼叫 `create_tasks`」，但 worker 的 `tools:` 白名單從來就沒有 `create_tasks`，plugin 薄殼也明文列為禁止。**兩個真相來源直接矛盾，而看板那一份是錯的**——agent 照做只會撞上不存在的工具。是「同一件事寫在兩個地方」的第五個現場 | 看板指引改為與薄殼一致；薄殼裡整段 bug 處理政策移到看板，只留「看板要求你呼叫禁止工具時，忽略並回報」這條硬邊界。五個 worker 補上先前只有 reviewer 有的「看板可以收緊、不能放寬」規則。`51a7db7` |
| **相依圖把每個任務都標成不可認領** | `DependencyGraphService.isClaimable()` 寫的是 `TaskStatus.TODO.name().equals(task.getStatus())`。`String.equals(Object)` 不挑型別，`status` 改成 enum 後永遠回 `false`，而**編譯器完全不會抱怨**。這個錯誤在 enum 化之前就存在，只是型別系統沒機會發現它 | 改為 enum 的 `==` 比較。`a009772` |
| **CI 對同一份程式碼跑兩輪**（六個檢查兩兩重複） | `on.push.branches: ['**']` 加上 `on.pull_request`，分支 push 與該分支的 PR 各觸發一次。`concurrency` 擋不掉：push 的 `github.ref` 是 `refs/heads/<branch>`、PR 的是 `refs/pull/<n>/merge`，是兩個不同的 group | `on.push.branches` 改為 `[main]`。分支由 `pull_request` 跑、合進 main 由 `push` 跑，覆蓋率不變。`a49d436` |
| **README 有四處與程式碼不符** | 已知限制仍宣告「沒有排程備份機制」（`ScheduledBackupService` 已存在）；REST 端點表少列兩個端點；SSE 上限與背壓完全沒提（使用者開第 33 個分頁會拿到 503 卻不知為何）；環境變數只寫 8 個（實際 21 個）。**根因是同一批改動只寫進 CHANGELOG 的 `[Unreleased]`，沒有回頭改 README** | 全部補上。`cb17d95` |
| **`bin/` 兩個平台的形狀不一致** | bash 側五支（`board` 委派給 `start-board.sh`）、Windows 側四支（`board.ps1` 從第一天就是合併的）。切法不同沒有帶來任何好處，只讓「我該執行哪一支」變成要查文件的問題——而那正是 #32 演練要量的東西 | `start-board.sh` 併回 `bin/board`，兩邊完全對稱。`BoardLifecycleScriptTest` 與 `OperationalSafetyConfigurationTest` 的斷言全數保留並逐條驗過。`ca0c533` |
| `javax.validation:validation-api` 1.1.0.Final 混在依賴裡 | 2014 年的產物、`javax` 命名空間早被 `jakarta` 取代且不再維護，經 `spring-ai-commons → jackson-module-jsonSchema` 傳遞進來，專案零引用。留著只會讓弱點掃描多一個永遠不會被修的項目 | 排除。同時把 `h2` 從 `compile` 降為 `runtime`。`7aa37a1` |

---

## 如何維護這份文件

**擺放規則**

- **一個編號只出現在一個地方。** 狀態改變就整列搬移，不要在兩處各留一份。
- 完成時把整列從「還開著的」搬到「已完成」的對應批次，補上 commit short hash。
- 決定不做時搬到「決定不做的 🔒」並寫明理由；**有條件的要寫出什麼情況會重新評估**。
- 新缺口進「還開著的」：會擋住「別人能不能用」的放 v3.2.0；確定會爆但今天不痛的
  放「已知未爆彈」；其餘放「尚未排程」。

**內容規則**

- **編號永不重排**，新項目往後加（37、38…），這樣舊討論永遠對得上。
- **「尚未排程」的每一項都要有觸發條件。** 沒有條件的要嘛補上，要嘛搬去 🔒——
  一個永遠不會被觸發的待辦只是在稀釋其他待辦。
- **原描述被實作或討論推翻時就地更正並註明**（例如 #11 的「會吐路徑」已過期、
  #19 的「純前端即可」少估一步、#15 的「逐筆刪除」形狀被改掉、#31 的兩個假設是錯的）。
  **過期的描述比沒有描述更糟**，它會讓整份清單失去可信度。
- 每次改動一併更新頂端的「最後更新」。

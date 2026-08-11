# 安裝指南

本文件涵蓋三種整合方式：Claude Code plugin、Codex plugin，以及手動 clone +
執行 jar／接線。兩套 plugin 會帶入 MCP 宣告、角色 agent 與 leader skill；所有
方式最終連到的都是同一顆 Spring Boot 行程。

## 0. 前置需求

- **JDK 21**（`pom.xml` 的 `java.version` 寫死 21，其他版本編譯不會過）
- **Maven**：不需要另外安裝，repo 內附 `./mvnw` 會自動下載對應版本
- **作業系統**：Windows、macOS、Linux 都可以，**Windows 不需要 WSL 或 Git Bash**。
  每個平台各有一套原生腳本：

  | | Windows | macOS / Linux |
  |---|---|---|
  | 服務入口 | `bin\board.ps1` | `bin/board` |
  | 共用預設值 | `bin\board-env.ps1` | `bin/board-env.sh` |
  | 啟動前備份 | `bin\backup-db.ps1` | `bin/backup-db.sh` |
  | 還原 | `bin\restore-db.ps1` | `bin/restore-db.sh` |

  兩套的預設值、備份命名與保留策略完全一致。Windows 版以 **Windows PowerShell
  5.1**（系統內建）為目標並在 CI 上以 5.1 與 pwsh 7 各測一次，不需要另外安裝
  PowerShell 7。

### 沒有 JDK 21 時怎麼辦

`bin/board start`（Windows：`.\bin\board.ps1 start`）開頭會自動掃描系統上已安裝的
JDK 找版本 21：

1. 先看 `PATH` 上的 `java` 是不是剛好就是 21
2. macOS：依序試 `/usr/libexec/java_home -v 21`、掃 `java_home -V` 列出的
   全部候選、最後試 Homebrew 常見安裝路徑（`/opt/homebrew/opt/openjdk@21/bin/java`
   等）——即使裝了 `brew install openjdk@21` 但沒有 `brew link`、
   `java_home` 抓不到，這一步仍抓得到
3. Linux：依序試 `update-alternatives --list java`、常見路徑
   （`/usr/lib/jvm/*21*` 等）
4. Windows（`board.ps1`）：`PATH` 上的 `java`、`JAVA_HOME`、`Program Files` 底下
   常見的 JDK 安裝路徑

全部找不到時腳本會直接印出對應平台的安裝指令並以非零狀態結束，不會讓
Java 丟一坨看不懂的堆疊：

```bash
# macOS
brew install openjdk@21
# 安裝後如果 /usr/libexec/java_home -V 仍未列出，執行：
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk

# Debian/Ubuntu
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk
# Fedora/RHEL
sudo dnf install -y java-21-openjdk-devel
# Arch
sudo pacman -S jdk21-openjdk
```

Windows 從 [adoptium.net](https://adoptium.net/) 或 Oracle 下載 JDK 21 安裝檔即可。

裝完之後重新執行 `bin/board start`（Windows：`.\bin\board.ps1 start`）即可，
不需要手動指定 `JAVA_HOME`。

本文件撰寫時，實測機器上 `java_home` 只列出 JDK 11/8，但透過
`brew install openjdk@21`（未 link）安裝了 21；腳本仍正確透過 Homebrew
路徑 fallback 抓到並啟動成功，驗證方式與結果見本文件最後一節「驗證記錄」。

## 1. 方式一：Claude Code plugin（推薦）

適合已經在用 Claude Code、想要「認領任務」這套多 agent 分工流程的情境。

目前 plugin 尚未發佈到公開 marketplace，以本機路徑安裝：

```bash
claude plugin marketplace add /path/to/AIProjectDashboard
```

```bash
claude plugin install ai-project-board@ai-board
```

裝完在當前 session 生效需要 `/reload-plugins`，或重開 Claude Code。之後更新本機
來源執行 `claude plugin update ai-project-board@ai-board` 並重啟。

plugin 內含：

- `agents/`：六個角色薄殼（`backend-dev`、`frontend-dev`、`qa`、`infra`、
  `docs`、`reviewer`），各自呼叫 `get_role` 取得看板上的最新指引
- `skills/claim-tasks/`：leader 事件驅動流程（盤點 → task worktree → dev 整合 →
  整批 reviewer → main）
- `.mcp.json`：預先指到 `http://127.0.0.1:8080/mcp`

**plugin 不負責啟停看板，也不內含編譯好的 jar。**

先前 `plugin/bin/` 底下有一支指回 repo 根目錄的 `start-board.sh` symlink，用意是
「兩邊永遠是同一份」。實際上那在 Windows 上是壞的：git 的 `core.symlinks` 在
Windows 預設為 `false`，checkout 會把 symlink 變成一個「內容是路徑字串」的 24
位元組文字檔，**每個在 Windows 上安裝 plugin 的人拿到的都是壞檔案，而且沒有任何
錯誤訊息**。它同時也是死碼（plugin 的 `.mcp.json`、`plugin.json`、`SKILL.md` 都
沒有引用它），因此已移除。啟停一律走 repo 的 `bin/board`（Windows：
`bin\board.ps1`），plugin 只負責宣告 MCP 端點與提供薄殼。

clone 下來的 repo 裡沒有 `target/*.jar`（59MB 的二進位檔不適合放進 git、也不利於
plugin 更新）。第一次執行 `bin/board start` 找不到 jar 時，若偵測到 repo 根目錄有
`pom.xml` 與 `mvnw`，會自動執行 `./mvnw package -DskipTests` 現場組裝一次。代價：

- 需要前置需求裡的 JDK 21 + 網路（下載 Maven 依賴）
- 首次啟動會多花數十秒到數分鐘不等（視網路與機器效能），之後有
  `target/*.jar` 就不會重複組裝

安裝完成後啟動看板：

```bash
./bin/board start
```

```powershell
.\bin\board.ps1 start
```

`start` 會依序完成：偵測 JDK 21 → 決定資料庫路徑（見下方「資料目錄」）→
檢查埠號 8080 是否已被佔用（若已是看板本身則直接略過、不重複啟動）→
偵測 H2 檔案是否被舊行程鎖住 → 啟動前冷備份 → 找 jar（找不到則自動組裝）→ 啟動 →
輪詢 `/api/projects` 直到就緒或逾時 → 印出 `/api/health` 版本資訊。全部訊息都會
標上 `[board]` 前綴印在終端機上。

看到 `看板已就緒：http://127.0.0.1:8080` 就代表可以打開瀏覽器了。

### 家目錄薄殼要移除（重要）

Claude Code 讀取 subagent 定義的優先序是：

```
專案 .claude/agents/  >  家目錄 ~/.claude/agents/  >  plugin 提供的 agents/
```

如果你的機器上 `~/.claude/agents/` 底下已經有舊版的
`backend-dev.md`、`frontend-dev.md`、`qa.md`、`infra.md`、`docs.md`
這幾個檔案（例如照著本 repo 較早版本的 README 手動建立過），**它們會蓋掉
plugin 內建的版本**，導致你以為裝了新 plugin、實際上還在跑舊薄殼邏輯。

改用 plugin 之前請先確認並移除：

```bash
ls ~/.claude/agents/
rm ~/.claude/agents/{backend-dev,frontend-dev,qa,infra,docs}.md   # 視實際存在檔案調整
```

（`reviewer.md` 是新增角色，家目錄通常還沒有同名檔案，但仍建議一併確認。）

## 2. 方式二：Codex plugin

repo 內的 Codex plugin 由以下兩層組成：

- `plugins/ai-project-board/`：plugin manifest、`.mcp.json`、六個角色薄殼與
  `skills/claim-tasks/`
- `.agents/plugins/marketplace.json`：把上述目錄登錄為 repo/Git marketplace 可安裝的
  **AI Project Board** plugin

從 GitHub 的 `main` branch 加入 marketplace 並安裝：

```bash
codex plugin marketplace add hpigu/AIProjectDashboard --ref main
codex plugin add ai-project-board@ai-board
```

日後發布新版後，更新 marketplace snapshot 並重新安裝：

```bash
codex plugin marketplace upgrade ai-board
codex plugin add ai-project-board@ai-board
```

如果 `codex plugin marketplace list` 顯示既有 `ai-board` 指向本機 clone，Git source
尚未推送完成前先保留原設定。發布到遠端 `main` 後，經使用者確認再切換：

```bash
codex plugin marketplace remove ai-board
codex plugin marketplace add hpigu/AIProjectDashboard --ref main
codex plugin add ai-project-board@ai-board
```

`marketplace remove` 只移除 marketplace source 設定，和解除安裝 plugin 的
`codex plugin remove` 是不同操作。切換後需開新 task 驗證新版 skill 與 MCP。

在 Codex 開啟本 repo 後，也能從 repo marketplace 安裝 **AI Project Board**。
安裝時若提示啟用 `board` connector，需確認一次；它會連到
`http://127.0.0.1:8080/mcp`。安裝或更新後重開 task，使角色與 skill 清單重新
載入。只在 `~/.codex/config.toml` 手動設定 MCP 也能使用看板工具，但不會帶入
plugin 內的角色薄殼與 leader skill。

Codex plugin 與 Claude Code plugin 使用不同的包裝目錄，但兩者的角色邊界與
leader 流程應保持一致；更新其中一份時需同步檢查另一份。

3.0.0 起，worker 完成修改、驗證與 commit 後保持 `IN_PROGRESS`，由 leader 將
`task/<task-id>-<role>` 合併進 `dev` 後才標記 `DONE`。整批 task 完成後 reviewer
只讀審查 `main...dev` 並回報 leader；它不建立 task、不修改或合併。

## 3. 方式三：手動 clone + 執行 jar

適合不使用 Claude Code plugin 機制、或想手動控制啟動流程的情境，
細節與完整環境變數表見 README「安裝與啟動」與「設定」兩節，這裡不重複。

## 4. 資料目錄

`bin/board`／`bin\board.ps1` 決定資料庫路徑的邏輯（也適用於直接執行 jar 時手動
指定 `BOARD_DB_URL` 的參考預設值）：

1. 若 `<repo>/data/board.mv.db` 已存在（代表這個 repo 路徑本來就是既有
   看板的資料所在地），沿用 `<repo>/data`，向下相容
2. 否則（全新安裝、尚未在這個路徑啟動過）使用
   `~/.ai-project-board/data`，可用 `BOARD_HOME_DIR` 環境變數整個改掉
   家目錄位置，或直接用 `BOARD_DB_URL` 指定完整 JDBC URL

這樣設計的原因：plugin 目錄可能因為更新（重新 clone / 覆蓋整個目錄）而
遺失內容，H2 資料庫檔案不能放在會被覆蓋的路徑下。

## 5. 已知限制與落差

### 角色指引不跟著 plugin 走

角色（`backend-dev`、`frontend-dev`、`qa`、`infra`、`docs`）的**完整工作
指引存在看板的 H2 資料庫**（`role` 表），透過 MCP 工具 `get_role` 取得，
不是寫在 plugin 的 `agents/*.md` 檔案裡——那些檔案只是薄殼，內容是
「呼叫 `get_role` 拿最新指引來 work，拿不到才退回檔案內建的最小規則」。

實務影響：

- 全新安裝的看板，行程啟動時 `RoleSeeder` 會自動建立五個角色的**初始**
  通用指引（只在該角色尚未存在時建立，已存在就原樣保留，不會重複匯入
  也不會覆蓋）
- 但如果你在既有看板上由 leader 依使用者目前明確要求透過 `upsert_role` 調整過任何角色的指引內容
  （通用層或某專案的覆寫層），**這些調整不會被 plugin 帶走**——plugin
  只是程式碼與薄殼檔案的散布單位，不含資料庫內容。換一台機器、或砍掉
  重建看板的資料庫，等於回到 `RoleSeeder` 給的初始版本
- 若要讓調整過的指引在新環境上重現，需要另外手動呼叫 `upsert_role`
  重新灌一次，或自行備份/搬移 `data/board.mv.db`

`reviewer` 是 leader 驗收階段直接呼叫的唯讀角色，不認領 category 任務，因而
不在 `RoleSeeder` 初始化的五個 worker 角色內。Claude Code 與 Codex plugin 的
reviewer 薄殼都含完整 fallback，且「不修改、不建 task、不認領、不分派、不合併、
只回報 leader」是不可被資料庫 role 覆蓋的硬邊界。看板若另有 reviewer 指引，
只能補充唯讀審查準則；找不到時則直接依薄殼工作。

### 版本號無法區分同版本號跨多次 commit 的新舊 build（3.1.0 起已解決）

3.1.0 之前，`/api/health` 只回傳 `pom.xml` 的版本號（例如 `3.0.0`），不含 git
commit hash；同一個版本號底下可能已經有多次 commit，單看 `version` 無法判斷跑
的是不是最新程式碼。

3.1.0 起 `/api/health` 與 `/api/diagnostics` 都會回傳 `commit`，內容是 build
來源的 short commit hash：

```json
{"version":"3.1.0","commit":"f23cd31","tools":["..."],"startedAt":"..."}
```

該值由建置期產生的 `git.properties` 提供。從沒有 `.git` 的原始碼壓縮檔建置時
（例如 release 頁面的 Source code (zip)）取不到，此時會是 `unknown`，這是預期
行為而非錯誤。同一份 `version` 也不再依賴 jar manifest，因此 `mvnw
spring-boot:run` 與 IDE 直接跑 main 都能看到正確版號（3.1.0 之前一律是
`unknown`）。

## 6. 驗證記錄

以下為撰寫本文件時的實際驗證方式，供之後比對程式行為是否漂移：

1. 用 `git clone` 把本 repo clone 到暫存目錄（模擬全新安裝，確認
   clone 出來的目錄底下沒有 `target/*.jar`、沒有 `data/`）
2. 確認本機 `/usr/libexec/java_home -V` 只列出 JDK 11 / 8，**沒有 21**；
   但透過 Homebrew 裝有 `openjdk@21`（未 link，`java_home` 抓不到）
3. 以 `BOARD_HOME_DIR=<暫存目錄>` `BOARD_PORT=18099`（避開正式看板的
   8080）執行啟動腳本（當時為 `bin/start-board.sh`，現已併入 `bin/board start`），
   觀察到：
   - 正確透過 Homebrew 路徑 fallback 找到 JDK 21 並印出版本
   - 找不到 `target/*.jar`，自動執行 `./mvnw package -DskipTests` 組裝
   - 組裝完成後啟動，資料庫路徑落在 `BOARD_HOME_DIR` 指定的目錄下
   - 輪詢就緒後印出 `/api/health`：`version`、`databasePath`、
     `tools`（16 個，與程式實際註冊的一致）、`startedAt` 均正確回傳
     （**注意**：`databasePath` 是撰寫本文件當時的回應內容，後續
     `/api/health` 已收斂為不含資料庫路徑等敏感資訊的最小版本，該欄位
     現在改由 `/api/diagnostics` 提供，見
     [docs/mcp-tools.md](mcp-tools.md) 的 REST 端點表）
4. 測試完畢後 `kill` 掉該臨時行程、刪除暫存目錄，並確認正式看板
   （8080）的 `/api/projects` 仍正常回應，未受影響

這份記錄只描述當次隔離環境的驗證結果，不代表任何時間點正式 `:8080` 行程的
即時狀態。判斷目前服務是否已更新，請直接查看該行程的 `/api/health`；若端點
不存在，再確認實際啟動的 jar 與 commit，而不要依賴本文件的歷史狀態。

# GitHub 發布、安裝與更新 SOP

[← 回 README](../README.md)

給要把這個看板接進 Claude Code、Claude Desktop 或 Codex 的人一份端到端流程：
Git marketplace 加入/更新、plugin 安裝/重裝、server 首次安裝、`board update`，
以及各步驟在哪一種介面能做、哪一種必須用 CLI。

底層契約（asset 檔名、checksum 格式、四平台 release job）見
[release-contract.md](release-contract.md)；完整安裝細節（資料目錄規劃、
plugin 疑難排解、驗證記錄）見 [installation.md](installation.md)；日常啟停、
備份還原見 [operations.md](operations.md)。本文件只整合「使用者從零開始，到
裝好、連上、之後怎麼更新」這條路徑，並把三個常被混淆的操作講清楚：
**marketplace 加入 marketplace 版本、plugin 安裝取得 plugin 檔案、`board
update` 更新 server binary，是三件互不隱含的事**。

## 0. 先搞懂三層是分開的東西

```
Git/本機 marketplace  →  plugin（agents + skill + MCP 宣告）  →  server（Spring Boot 行程）
      加入/更新              安裝/更新                          安裝/啟停/update
```

| 層 | 是什麼 | 版本事實來源 | 更新指令 | 不會做的事 |
|---|---|---|---|---|
| marketplace | 一份指向 plugin 目錄或 Git ref 的登錄資訊 | 不持有版本；只是指標 | 本機路徑來源靠 `git pull` 更新內容；`codex plugin marketplace upgrade` 刷新 Git ref snapshot | 不安裝、不更新已裝的 plugin 檔案 |
| plugin | `agents/*.md`、`skills/`、`.mcp.json` | `plugin.json`／`plugin/.claude-plugin/plugin.json` 的 `version` | `claude plugin update`／重新 `codex plugin add` | 不含 server JAR、不啟停 server、不含 `bin/board` |
| server | Spring Boot 行程與 H2 資料 | `pom.xml` 的 `project.version`，`/api/health` 回報 | `bin/board update`／`bin\board.ps1 update` | 不會因為 plugin 或 marketplace 更新而自動發生 |

**marketplace upgrade／plugin update 只換 agent 薄殼、skill 與 MCP 端點宣告的
檔案內容，完全不觸碰已經在跑的 server 行程或它的 H2 資料庫。** 兩套 plugin 都是
thin client：它們不含 server source、任何 server JAR、jlink runtime 或
`bin/board`／`bin/board.ps1`（見 [release-contract.md 第 3 節](release-contract.md#3-server-與-plugin-的相容性)）。
更新 server 永遠是另一個獨立步驟——手動 clone 是 `bin/board update`／
`bin\board.ps1 update`，stable release 安裝是同名入口，見本文件第 3、4 節。反過來
也一樣：更新了 server 不會自動更新已安裝的 plugin，兩邊都要使用者各自明確操作。

## 1. Claude Code：加入 marketplace 與安裝 plugin

**全部步驟目前都必須用 CLI**；Claude Code 沒有圖形化的 marketplace 瀏覽/安裝
介面可以取代下列指令（若後續版本新增，請以官方文件為準，本文件僅涵蓋目前
repo 提供的方式）。

目前 plugin 尚未發佈到公開 marketplace，以本機路徑加入：

```bash
claude plugin marketplace add /path/to/AIProjectDashboard
```

```bash
claude plugin install ai-project-board@ai-board
```

第一行註冊 marketplace 來源，第二行才真的安裝 plugin 檔案（`agents/`、
`skills/claim-tasks/`、`.mcp.json`）到你的 Claude Code 環境。裝完在當前 session
生效需要 `/reload-plugins`（Claude Code slash command，可在介面內直接輸入），
或重開 Claude Code。

安裝後 plugin 宣告的 `board` MCP connector 需要在 Claude Code 介面按一次
**Install** 才會註冊（這一步是介面操作，CLI 不提供等價指令）；它固定指到
`http://127.0.0.1:8080/mcp`，只在該 session 內生效。

### 之後更新 plugin（本機路徑來源）

本機路徑 marketplace 沒有獨立的「upgrade」語意——來源本身就是那個目錄，
你對 repo 做 `git pull` 後，plugin 內容已經是新的；接著只需要重新安裝一次
把新內容套進 Claude Code 環境：

```bash
claude plugin update ai-project-board@ai-board
```

之後重開 Claude Code 或 `/reload-plugins` 才會載入新版角色薄殼與 skill。**這一步
不會啟動、停止或更新 server**；server 是否需要更新見第 3 節。

### Claude Desktop

Claude Desktop 是否支援同一套 `claude plugin marketplace`／`claude plugin
install` CLI 尚待確認——本 repo 的 plugin 是為 Claude Code 撰寫並驗證，**未在
Claude Desktop 實際測試過**。若你在 Claude Desktop 使用，請改走本文件第 5 節
「不用 plugin，手動接 MCP」的路徑，並向 leader/issue 回報 Claude Desktop 的
實際安裝流程，以便後續補上經過驗證的步驟。

### 家目錄薄殼可能蓋掉 plugin 版本（重要）

Claude Code 讀取 subagent 定義的優先序是 `專案 .claude/agents/` >
`家目錄 ~/.claude/agents/` > `plugin 提供的 agents/`。若 `~/.claude/agents/`
底下已有舊版 `backend-dev.md`、`frontend-dev.md`、`qa.md`、`infra.md`、
`docs.md`（例如照著本 repo 較早版本手動建立過），它們會蓋掉 plugin 內建版本，
導致「以為裝了新 plugin，實際還在跑舊薄殼」。改用 plugin 前先確認並移除：

```bash
ls ~/.claude/agents/
rm ~/.claude/agents/{backend-dev,frontend-dev,qa,infra,docs}.md   # 視實際存在檔案調整
```

## 2. Codex：加入 Git marketplace、安裝與更新 plugin

repo 內的 Codex plugin 由 `plugins/ai-project-board/`（manifest、`.mcp.json`、
角色薄殼、`skills/claim-tasks/`）與 `.agents/plugins/marketplace.json`（把上述
目錄登錄為 marketplace 可安裝的 **AI Project Board**）組成。目前 repo 尚未推送到
遠端（本批次規則要求不自行 push），下列 Git marketplace 指令示範的是加入已推送
到 GitHub 的版本；**目前 repo 狀態下這組指令會失敗，需等 leader 實際 push 到
`hpigu/AIProjectDashboard` 的 `main` 後才可執行**。

以下步驟全部必須用 CLI；Codex 目前沒有圖形化 marketplace 安裝介面可以取代。

### 加入 Git marketplace 並安裝

```bash
codex plugin marketplace add hpigu/AIProjectDashboard --ref main
codex plugin add ai-project-board@ai-board
```

### 之後更新（新版已推送到 `main`）

先刷新 marketplace 的 Git snapshot，再重新安裝套用新內容：

```bash
codex plugin marketplace upgrade ai-board
codex plugin add ai-project-board@ai-board
```

`codex plugin marketplace upgrade` 只更新 marketplace 記錄的 Git ref 內容快照，
不會自動觸發 plugin 重裝；`codex plugin add` 這一步才會把新版檔案套用到你已安裝
的 plugin。更新或重新安裝完成後**開新 task**，讓角色薄殼與 skill 清單重新載入
（沿用舊 task 的對話不會自動抓到新內容）。

### 本機 clone 轉 Git marketplace 的遷移

如果 `codex plugin marketplace list` 顯示既有 `ai-board` 指向本機 clone，**不要
在 Git source 推送完成前直接加入同名 Git source**（會造成同名衝突或指向不確定
的來源）。等新版確實推送到遠端 `main` 後，明確確認要切換，再依序執行：

```bash
codex plugin marketplace remove ai-board
codex plugin marketplace add hpigu/AIProjectDashboard --ref main
codex plugin add ai-project-board@ai-board
```

`codex plugin marketplace remove` **只移除 marketplace 來源設定**，和解除安裝
plugin 的 `codex plugin remove` 是不同操作——移除來源不會自動把已安裝的 plugin
檔案解除安裝，也不會清掉本機 clone 目錄本身（marketplace 只是登錄資訊，不擁有
你 clone 出來的檔案）。若不確定目前 marketplace 是否已切換成功，用
`codex plugin marketplace list` 確認來源，並在新開的 task 內用 leader/worker
薄殼實測角色與工具是否為新版。切換後同樣需要開新 task 驗證新版 skill 與 MCP。

在 Codex 開啟本 repo 時，也能直接從 repo marketplace（即 `.agents/plugins/
marketplace.json` 指向的本機路徑）安裝 **AI Project Board**，這與上面的 Git
marketplace 是兩種不同的 source（本機路徑 vs. `hpigu/AIProjectDashboard`
Git ref），**不要混用**：`marketplace list` 中的 `ai-board` 只能指向其中一種
來源。安裝時若提示啟用 `board` connector，需確認一次；它連到
`http://127.0.0.1:8080/mcp`。

### Codex CLI 與 Codex Desktop

上述 `codex plugin` 系列指令是 CLI 操作。是否有等價的 Codex Desktop 圖形介面
**尚待確認**——本文件撰寫時未能從 repo 內容或已驗證的操作記錄確認 Codex
Desktop 的實際安裝流程，若你在 Codex Desktop 使用，請先以本文件第 5 節手動
接線的方式操作，並回報實際介面步驟供後續補上。

## 3. Server 首次安裝

Server 與兩套 plugin 完全分開安裝，選其中一種即可；plugin 不會替你裝好或啟動
server。三選一：

### 3a. 手動 clone + 現場組裝（跟著 plugin 一起裝時最常見）

裝好 plugin 後，第一次執行 `bin/board start`（Windows：`.\bin\board.ps1
start`）若找不到 `target/*.jar`，且偵測到 repo 根目錄有 `pom.xml` 與 `mvnw`，
會自動 `./mvnw package -DskipTests` 現場組裝一次（需要 JDK 21 + 網路下載
Maven 依賴，首次數十秒到數分鐘）：

```bash
./bin/board start
```

```powershell
.\bin\board.ps1 start
```

### 3b. macOS／Linux stable release 安裝器（不需 repo checkout）

從同一個 GitHub stable release `vV` 下載本機平台 JAR 與
`ai-project-board-backend-V-SHA256SUMS.txt`，用 `install/install.sh`：

```bash
./install/install.sh \
  --jar /path/to/ai-project-board-backend-macos-arm64-V.jar \
  --checksums /path/to/ai-project-board-backend-V-SHA256SUMS.txt
```

或在**這一次明確操作**下指定 immutable tag 讓 installer 直接下載（不能用
`latest`）：

```bash
./install/install.sh \
  --release-url "https://github.com/hpigu/AIProjectDashboard/releases/download/vV" \
  --version V
```

預設安裝到 `~/.ai-project-board`；可用 `--home DIR` 換一個使用者可寫的根目錄。
安裝後入口固定在該根目錄下：

```bash
~/.ai-project-board/bin/board start
```

### 3c. Windows x64 stable release ZIP（不需預裝 Java）

從同一個 stable release `vV` 下載 `ai-project-board-backend-windows-x64-V.zip`
與對應 `SHA256SUMS.txt`，驗證 hash 後解壓到使用者可寫的位置：

```powershell
<解壓目錄>\ai-project-board-backend-windows-x64-V\bin\board.ps1 start
```

ZIP 內固定帶 bundled JDK 21 jlink runtime，不吃系統 `JAVA_HOME`／PATH。詳細
結構契約見 [release-contract.md 第 2 節](release-contract.md#2-stable-release-assets)。

以上三種方式都只監聽 `127.0.0.1:8080`；**MCP 沒有 server-side 身分驗證，不得
改綁公開位址對外暴露**。完整前置需求（JDK 21 偵測 fallback）、資料目錄決策
邏輯見 [installation.md](installation.md)。

## 4. Server 明確更新（`board update`）——不是 marketplace upgrade

**再強調一次：marketplace upgrade／plugin update 完全不會觸發這一節的任何
動作。** server 更新永遠是使用者另外明確執行的指令，沒有背景輪詢、沒有啟動時
自動檢查、沒有 `latest` 推測。

### 手動 clone / 3a 安裝：`bin/board update`

```bash
bin/board update --version V --jar /path/to/ai-project-board-backend-macos-arm64-V.jar \
  --checksums /path/to/ai-project-board-backend-V-SHA256SUMS.txt --check
bin/board update --version V \
  --release-url "https://github.com/hpigu/AIProjectDashboard/releases/download/vV"
```

`--check` 只驗證目標 artifact 並顯示 current/target 版本，**不停止服務、不備份、
不改動安裝**；拿掉 `--check` 才會真的執行更新。

### 3b stable 安裝根目錄：同名入口

```bash
~/.ai-project-board/bin/board update --version V --jar /path/to/JAR \
  --checksums /path/to/ai-project-board-backend-V-SHA256SUMS.txt --check
~/.ai-project-board/bin/board update --version V \
  --release-url "https://github.com/hpigu/AIProjectDashboard/releases/download/vV"
```

### 3c Windows ZIP 安裝根目錄

```powershell
<解壓目錄>\ai-project-board-backend-windows-x64-V\bin\board.ps1 update -Version V `
  -ReleaseZip C:\path\ai-project-board-backend-windows-x64-V.zip `
  -Checksums C:\path\ai-project-board-backend-V-SHA256SUMS.txt -Check
```

更新器會在停止服務前完整下載並嚴格驗證 checksum、platform、layout 與 runtime，
保存原 PID/activation identity 與可驗證的 DB snapshot manifest；POSIX 用同檔案
系統的 `current` symlink rename 切換，Windows 用同磁碟區的 versioned-root
rename transaction（舊解壓 root 保留為 rollback 目錄）。任何 download、
checksum、stop、backup、publish、activate、start 或 readiness 失敗都會復原舊
activation、DB snapshot 與原本的 running/stopped 狀態；若 rollback 本身失敗，
更新器 fail closed 並印出保留路徑，絕不刪除唯一資料副本或宣稱成功。完整語意見
[installation.md「明確更新與回滾」](installation.md#明確更新與回滾)。

GitHub 不可達時 `--release-url`／`-ReleaseZip` 在停止服務前就會失敗；改用已
下載的 `--jar/--checksums`／`-ReleaseZip/-Checksums` 即為完全離線的等價操作。

**更新只處理 server runtime，絕不安裝、更新或移除 Claude/Codex marketplace
或 plugin。** plugin 端要不要跟著升到同一個 `V`，是第 1、2 節的獨立操作，
兩者順序不影響彼此是否成功——但要達成 stable 支援矩陣（見下一節），最終兩邊
都要對齊同一個 `V`。

## 5. 不用 plugin，手動接 MCP

不透過 plugin 也能接上 server，代價是不會自動取得角色薄殼與 `claim-tasks`
skill：

```bash
claude mcp add --transport http board http://127.0.0.1:8080/mcp --scope project
```

```toml
# ~/.codex/config.toml
[mcp_servers.board]
url = "http://127.0.0.1:8080/mcp"
```

這種接法完全不涉及本文件第 1、2 節的 marketplace／plugin 操作；server 的安裝與
更新仍照第 3、4 節進行。

## 6. 版本相容矩陣

三個追溯點（server、Claude plugin manifest、Codex plugin manifest）必須來自
**同一個 tag commit**，完整規則見
[release-contract.md 第 1、3 節](release-contract.md#1-版本tag-與來源追溯)：

| Server release version | Claude plugin manifest version | Codex plugin manifest version | 支援狀態 |
|---|---|---|---|
| `V` | `V` | `V` | 支援；三者同屬 tag `vV` |
| `V` | 非 `V` | 任意 | 不支援；先以相同 stable tag 的 Claude plugin 對齊 |
| `V` | 任意 | 非 `V` | 不支援；先以相同 stable tag 的 Codex plugin 對齊 |
| 任一缺失、非 stable，或無法追溯同一 tag | 任意 | 任意 | 不支援；不要以猜測的跨版組合發佈或驗收 |

這是發佈與支援矩陣，不是 runtime 版本封鎖——現有 MCP 端點不會因 plugin 版本
不同而自動拒絕連線。遇到版本不一致時，正確處理方式是讓安裝器/更新器/plugin
都回到同一個 `vV`，而不是繼續在不一致狀態下使用。目前 repo 的三個追溯點皆為
`3.1.1`：`pom.xml` 的 `project.version`、`plugin/.claude-plugin/plugin.json`
與 `.claude-plugin/marketplace.json` 的 `version`、`plugins/ai-project-board/
.codex-plugin/plugin.json` 的 `version`。`scripts/check-versions.sh` 在每次
release preflight（`.github/workflows/release.yml` 的 `preflight` job）都會
重新核對這組值，跳版忘記改會直接讓 CI 紅燈。

查詢已安裝 server 的實際版本：

```bash
curl -s http://127.0.0.1:8080/api/health
```

3.1.0 起回應包含建置期 short commit hash（`{"version":"3.1.1","commit":"..."}`），
可用來判斷同版本號底下是否為預期的 commit；從沒有 `.git` 的原始碼壓縮檔建置時
`commit` 會是 `unknown`，這是預期行為。

## 7. 資料位置

| 安裝方式 | 資料／備份／日誌／PID 位置 |
|---|---|
| 手動 clone（`<repo>/data/board.mv.db` 已存在時） | `<repo>/data`（向下相容既有安裝） |
| 手動 clone（全新，尚未在此路徑啟動過） | `~/.ai-project-board/data`（可用 `BOARD_HOME_DIR` 改） |
| 3b macOS／Linux stable installer | `~/.ai-project-board`（或 `--home DIR` 指定的根目錄），0700/0600 權限 |
| 3c Windows stable ZIP | `%USERPROFILE%\.ai-project-board`（或 `BOARD_HOME_DIR`），**不是**解壓目錄 |

這樣設計是因為 plugin 目錄可能因更新（重新 clone／整包覆蓋）而遺失內容，H2
資料庫不能放在會被覆蓋的路徑下。完整判斷邏輯見
[installation.md 第 4 節](installation.md#4-資料目錄)。

## 8. 備份與回滾

日常備份／還原操作（`bin/backup-db.sh`、`bin/restore-db.sh` 與 Windows
對應版本、三種備份時機、保留策略）見
[operations.md 備份一節](operations.md#備份)，這裡只列與「更新」直接相關的
回滾路徑：

- **`board update` 失敗時**：更新器自動復原，不需要使用者手動操作；POSIX 以
  temporary copy → hash verification → atomic rename 還原 live DB，snapshot、
  `manifest.sha256` 與原始 hash 都保留供事後重驗或手動恢復。若 rollback 本身
  失敗，更新器 fail closed 並印出保留路徑，此時依印出的路徑手動處理，不要
  自行刪除任何保留檔案。
- **一般還原到某個時間點**：`bin/board stop` → `bin/restore-db.sh latest`（或
  指定備份檔）→ `bin/board start`，還原前會先把現有資料庫改名保留成
  `board.mv.db.pre-restore-<UTC>` 而非刪除，還錯了可以直接改回檔名。
- **從既有 repo H2 資料遷移到新安裝根目錄**：`install/install.sh --migrate-from
  /path/to/old-repo/data`，只有明確指定才會執行，且只允許建立全新安裝根目錄；
  來源檔永遠不移動或覆寫，遷移失敗會捨棄 staging installation，原 repo 與既有
  安裝仍可直接使用。回滾方式是「不啟用新安裝根目錄，繼續使用原 repo」——新安裝
  內的 migration backup 也保留了已驗證的原始副本。

## 9. 本機 marketplace 轉 Git marketplace：影響、保留與回退

目前 `.claude-plugin/marketplace.json` 指向 `./plugin`、`.agents/plugins/
marketplace.json` 指向 `./plugins/ai-project-board`，都是**路徑型／本機**
marketplace entry。改成 Git marketplace（Codex 的 `codex plugin marketplace
add hpigu/AIProjectDashboard --ref main`）之後：

### 影響

- 已安裝的 plugin 檔案（`agents/*.md`、`skills/`、`.mcp.json`）會在下次
  `codex plugin add` 時被 Git ref 對應版本的內容取代；角色薄殼與 skill 內容
  若在該次 commit 有變動就會跟著變。
- **不影響 server**：server 的安裝位置、H2 資料、版本完全不受 marketplace 來源
  切換影響（見第 0 節）。
- **不影響看板資料庫內的角色指引**（`role` 表）：那是執行期由 `get_role` 讀取
  的內容，不隨 plugin 散布，切換 marketplace 來源不會動到它。
- 本機路徑來源與 Git ref 來源若同時以同一個 marketplace 名稱（`ai-board`）
  註冊會衝突；必須先 `remove` 舊來源才能 `add` 新來源，見第 2 節。

### 保留（不直接移除本機 marketplace 的原因）

`release-contract.md` 第 5 節明確要求：「不修改或移除既有 Claude/Codex
marketplace；後續工作只能讓兩個既有 marketplace 指向的 thin plugin manifest
與 server release 依版本共同升版」。也就是說：

- `.claude-plugin/marketplace.json`、`.agents/plugins/marketplace.json` 這兩份
  **repo 內的路徑型 marketplace 定義本身不會被刪除或取代**，它們是本機開發、
  demo 與尚未推送遠端時唯一可用的安裝路徑。
- Git marketplace 是**額外**選項，給已經有遠端 `hpigu/AIProjectDashboard` 可用
  的使用者一個不需要本機路徑的安裝方式，兩者長期並存。
- 目前 repo 尚未推送到遠端（見第 2 節開頭），所以本文件撰寫時 Git marketplace
  流程**仍是待推送後才可執行的步驟**，本機路徑 marketplace 仍是唯一可用方式。

### 回退方法

若切到 Git marketplace 後想改回本機路徑（例如遠端版本有問題、想先用本機修改
中的內容測試）：

```bash
# Codex：先移除 Git 來源，再加回本機路徑
codex plugin marketplace remove ai-board
codex plugin marketplace add /path/to/AIProjectDashboard
codex plugin add ai-project-board@ai-board
```

Claude Code 沒有需要「切換」的問題——本文件涵蓋的 Claude plugin 安裝方式本身
就是本機路徑（第 1 節），repo 尚未有已驗證的 Claude Code Git marketplace 流程
可切換。

切換來源（任一方向）後都要開新 task／重開 session，確認角色與工具清單已經是
預期版本，不要只憑 marketplace list 的輸出判斷已生效。

## 10. 已知落差與待確認事項

- Claude Desktop 的實際安裝 UI 步驟**未經驗證**，本文件未編造操作路徑（見第 1
  節）。
- Codex Desktop 是否有對應 CLI 指令的圖形介面**未經驗證**（見第 2 節）。
- Codex Git marketplace 流程（第 2、9 節）依賴 repo 已推送到
  `hpigu/AIProjectDashboard` 的 `main`；**目前尚未推送**，這些指令在推送完成前
  無法實際執行成功，執行前請先確認推送已完成。
- 三平台 release CI workflow（`.github/workflows/release.yml`）已建立並在本批
  完成程式碼層級驗證，但**尚未實際觸發過一次 tag push 或 workflow_dispatch**，
  因此本文件引用的 release asset 皆為契約規格，不是已產出並驗證過的真實
  GitHub Release 檔案。
- Windows Sandbox 乾淨環境手動驗收（`docs/windows-sandbox-clean-install-checklist.md`）
  **尚未實際執行**（#163 追蹤中）；本文件第 3c、4 節的 Windows 流程是依 CI
  自動化驗證與程式碼推導，不是 Sandbox 內人工操作的結果。

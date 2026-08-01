# 安裝指南

本文件涵蓋三種整合方式：Claude Code plugin、Codex plugin，以及手動 clone +
執行 jar／接線。兩套 plugin 會帶入 MCP 宣告、角色 agent 與 leader skill；所有
方式最終連到的都是同一顆 Spring Boot 行程。

## 0. 前置需求

- **JDK 21**（`pom.xml` 的 `java.version` 寫死 21，其他版本編譯不會過）
- **Maven**：不需要另外安裝，repo 內附 `./mvnw` 會自動下載對應版本
- macOS／Linux（`bin/start-board.sh` 是 bash script；Windows 需要 WSL 或
  Git Bash，未實際驗證過）

### 沒有 JDK 21 時怎麼辦

`bin/start-board.sh` 開頭會自動掃描系統上已安裝的 JDK 找版本 21：

1. 先看 `PATH` 上的 `java` 是不是剛好就是 21
2. macOS：依序試 `/usr/libexec/java_home -v 21`、掃 `java_home -V` 列出的
   全部候選、最後試 Homebrew 常見安裝路徑（`/opt/homebrew/opt/openjdk@21/bin/java`
   等）——即使裝了 `brew install openjdk@21` 但沒有 `brew link`、
   `java_home` 抓不到，這一步仍抓得到
3. Linux：依序試 `update-alternatives --list java`、常見路徑
   （`/usr/lib/jvm/*21*` 等）

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

裝完之後重新執行 `bin/start-board.sh` 即可，不需要手動指定 `JAVA_HOME`。

本文件撰寫時，實測機器上 `java_home` 只列出 JDK 11/8，但透過
`brew install openjdk@21`（未 link）安裝了 21；腳本仍正確透過 Homebrew
路徑 fallback 抓到並啟動成功，驗證方式與結果見本文件最後一節「驗證記錄」。

## 1. 方式一：Claude Code plugin（推薦）

適合已經在用 Claude Code、想要「認領任務」這套多 agent 分工流程的情境。

```bash
# 在 Claude Code 裡安裝 plugin（實際指令依 marketplace 設定，
# 目前 plugin 尚未發佈到公開 marketplace，需以本機路徑或 git 來源安裝）
```

plugin 內含：

- `agents/`：六個角色薄殼（`backend-dev`、`frontend-dev`、`qa`、`infra`、
  `docs`、`reviewer`），各自呼叫 `get_role` 取得看板上的最新指引
- `skills/claim-tasks/`：leader 分派流程（盤點 → 分波派工 → 驗收 → 彙整）
- `.mcp.json`：預先指到 `http://127.0.0.1:8080/mcp`
- `bin/start-board.sh`：指回 repo 根目錄 `bin/start-board.sh` 的相對
  symlink，啟動用的是同一份腳本

**plugin 不內含編譯好的 jar。** clone 下來的 repo 裡沒有 `target/*.jar`
（59MB 的二進位檔不適合放進 git、也不利於 plugin 更新）。第一次執行
`bin/start-board.sh` 找不到 jar 時，若偵測到 repo 根目錄有 `pom.xml` 與
`mvnw`，會自動執行 `./mvnw package -DskipTests` 現場組裝一次。代價：

- 需要前置需求裡的 JDK 21 + 網路（下載 Maven 依賴）
- 首次啟動會多花數十秒到數分鐘不等（視網路與機器效能），之後有
  `target/*.jar` 就不會重複組裝

安裝完成後啟動：

```bash
./bin/start-board.sh
```

腳本會依序完成：偵測 JDK 21 → 決定資料庫路徑（見下方「資料目錄」）→
檢查埠號 8080 是否已被佔用（若已是看板本身則直接略過、不重複啟動）→
偵測 H2 檔案是否被舊行程鎖住 → 找 jar（找不到則自動組裝）→ 啟動 →
輪詢 `/api/projects` 直到就緒或逾時 → 印出 `/api/health` 版本資訊（若該
build 有這個端點）。全部訊息都會標上 `[start-board]` 前綴印在終端機上。

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

- `.codex-plugin/`：plugin manifest、`.mcp.json`、六個角色薄殼與
  `skills/claim-tasks/`
- `.agents/plugins/marketplace.json`：把上述目錄登錄為本機可安裝的
  **AI Project Board** plugin

在 Codex 開啟本 repo 後，從 plugin marketplace 安裝 **AI Project Board**。
安裝時若提示啟用 `board` connector，需確認一次；它會連到
`http://127.0.0.1:8080/mcp`。安裝或更新後重開 task，使角色與 skill 清單重新
載入。只在 `~/.codex/config.toml` 手動設定 MCP 也能使用看板工具，但不會帶入
plugin 內的角色薄殼與 leader skill。

Codex plugin 與 Claude Code plugin 使用不同的包裝目錄，但兩者的角色邊界與
leader 流程應保持一致；更新其中一份時需同步檢查另一份。

## 3. 方式三：手動 clone + 執行 jar

適合不使用 Claude Code plugin 機制、或想手動控制啟動流程的情境，
細節與環境變數見 README「快速開始」一節，這裡不重複。

## 4. 資料目錄

`bin/start-board.sh` 決定資料庫路徑的邏輯（也適用於直接執行 jar 時手動
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
- 但如果你在既有看板上透過 `upsert_role` 調整過任何角色的指引內容
  （通用層或某專案的覆寫層），**這些調整不會被 plugin 帶走**——plugin
  只是程式碼與薄殼檔案的散布單位，不含資料庫內容。換一台機器、或砍掉
  重建看板的資料庫，等於回到 `RoleSeeder` 給的初始版本
- 若要讓調整過的指引在新環境上重現，需要另外手動呼叫 `upsert_role`
  重新灌一次，或自行備份/搬移 `data/board.mv.db`

`reviewer` 是 leader 驗收階段直接呼叫的唯讀角色，不認領 category 任務，因而
不在 `RoleSeeder` 初始化的五個 worker 角色內。Claude Code 與 Codex plugin 的
reviewer 薄殼都含完整 fallback；除非使用者另用 `upsert_role` 建立 reviewer
指引，否則 `get_role("reviewer", projectName)` 找不到時會直接依薄殼工作。

### 版本號無法區分同版本號跨多次 commit 的新舊 build

`/api/health` 回傳的 `version` 讀的是 `pom.xml` 的版本號（例如
`2.0.0`），**不含 git commit hash**。同一個版本號底下可能已經有多次
commit（例如新增了 `/api/health` 本身這個端點），單看 `version` 欄位
無法判斷目前跑的行程是不是最新程式碼——只能透過該行程「有沒有某個新端點
／新欄位」間接推斷，或直接比對啟動時間（`startedAt`）與程式碼 commit
時間。

## 6. macOS 開機自動啟動與崩潰自動重啟（launchd）

適合「至少在要 build 新專案時看板不能死掉」這類需求：登入時自動啟動、
行程意外終止（crash、被系統回收、手滑 kill 到）時自動拉起，同時保留
「使用者想手動停掉就要停得掉」的退路。

### 安裝

```bash
bin/install-launchd.sh
```

預設安裝 label `dev.aiboard.board`、埠號 `8080`，資料庫沿用
`start-board.sh` 原本的推斷邏輯（`<repo>/data/board.mv.db` 已存在時沿用
該路徑，這對已在跑的正式看板是向下相容的路徑）。安裝完成會把
plist 寫到 `~/Library/LaunchAgents/dev.aiboard.board.plist` 並立刻載入
（`launchctl bootstrap`）。

如果 `:8080` 當下已有看板在跑（例如你原本就是手動啟動的），`RunAtLoad`
觸發後 `bin/board-agent-wrapper.sh` 會呼叫 `start-board.sh`，其埠號檢查
邏輯偵測到「已有看板在正常運作」會直接結束、不搶埠號、不動既有行程——
可以放心安裝，不會因為裝了 launchd job 就把手動啟動的行程弄掛。之後你
可以自行選擇何時把手動行程換成 launchd 管理（例如下次重開機，或手動
`kill` 掉舊行程一次，讓 launchd 接手）。

### 機制設計與三個衝突問題

1. **自動重啟 vs 使用者手動啟動的行程互相打架（H2 檔案鎖）**
   `start-board.sh` 第 3 節（埠號檢查）與第 4 節（H2 鎖檔偵測）本來就會
   在啟動前先確認「是不是已經有人（不管是不是 launchd 管的）在用同一個
   埠號／同一個資料庫檔案」，偵測到就直接結束並提示，不會硬闖造成
   `MVStoreException`。launchd 這層只是「定期／崩潰後嘗試呼叫
   `start-board.sh`」，實際的互斥判斷仍在 `start-board.sh` 本身，兩者
   共用同一套防呆，不會出現「launchd 版本」與「手動版本」用不同邏輯各
   判各的而互相踩線的情況。

2. **無限重啟迴圈（資料庫壞掉、埠號永遠被佔等不可恢復錯誤）**
   兩層防護：
   - launchd `ThrottleInterval=15`：兩次啟動嘗試之間至少間隔 15 秒，
     避免瞬間狂重試洗爆 CPU 與 log。
   - `bin/board-agent-wrapper.sh` 額外做「時間窗內失敗次數」判斷：
     120 秒內累計失敗（`start-board.sh` 結束碼非 0）達 5 次，就自動寫入
     停用旗標並停止嘗試，同時把偵測結果記進 log，不會無止盡地每 15 秒
     重試一個注定失敗的狀況。失敗計數與旗標檔都在
     `~/.ai-project-board/`（`board-launchd-failures.log`、
     `board-disabled`），可用 `BOARD_LOOP_WINDOW_SEC` /
     `BOARD_LOOP_MAX_FAILURES` 環境變數調整門檻。

3. **使用者想手動停掉時，不能一 kill 就被拉起來**
   ```bash
   bin/stop-board.sh            # 停止目前行程 + 寫入停用旗標
   bin/stop-board.sh --status   # 查看目前是否處於停用狀態、行程是否在跑
   bin/stop-board.sh --enable   # 移除停用旗標，恢復自動重啟
   ```
   `board-agent-wrapper.sh` 每次被 launchd 呼叫時最先檢查停用旗標
   （`~/.ai-project-board/board-disabled`），存在就直接 `exit 0`（對
   launchd 而言視為正常執行完畢，不會被當成崩潰而更積極重試，也不會真的
   再啟動一份 java）。這個旗標只影響「要不要啟動」，不會移除 launchd job
   本身；如果要連 job 定義都不要了，用 `bin/uninstall-launchd.sh`。

### 卸載

```bash
bin/uninstall-launchd.sh
```

只移除 launchd job 定義（`launchctl bootout` + 刪除 plist），不會動到
當下正在跑的看板行程；如果也想同時停掉行程，另外執行
`bin/stop-board.sh`。

### 驗證方式（重要：不得使用正式 label／埠號／資料庫）

正式看板常駐在 `:8080`，**驗證 launchd 設定務必用不同的 label 與埠號，
並明確指定隔離的測試資料庫**，驗證完務必卸載乾淨：

```bash
BOARD_LAUNCHD_LABEL=dev.aiboard.board.test \
BOARD_PORT=18099 \
BOARD_DB_URL='jdbc:h2:file:/tmp/dev-infra-launchd-test;DB_CLOSE_ON_EXIT=FALSE' \
  bin/install-launchd.sh

# 驗證完畢後
BOARD_LAUNCHD_LABEL=dev.aiboard.board.test bin/uninstall-launchd.sh
```

**必須明確指定 `BOARD_DB_URL`**：`start-board.sh` 預設邏輯是「若
`<repo>/data/board.mv.db` 已存在就沿用該路徑」，這是為了讓既有使用者
向下相容；但這代表如果驗證時只換了 label 與埠號、沒有另外指定
`BOARD_DB_URL`，驗證用的 job 會與正式看板共用同一個 H2 資料庫檔案，
即使埠號不同也會有鎖檔衝突風險。

實測記錄（本機驗證，label `dev.aiboard.board.test`、埠號 `18099`，
資料庫另外指到 `/tmp` 下的隔離路徑，全程與正式看板 `:8080`
PID 隔離、未受影響）：

1. 安裝後約 15～20 秒完成 Spring Boot 啟動，`/api/health` 回應正常
2. `kill` 掉行程後，約 15 秒（`ThrottleInterval`）后被 launchd 自動重啟
   為全新 PID 的行程（`/api/health` 的 `startedAt` 也隨之更新，確認不是
   舊行程殘留）
3. 執行 `bin/stop-board.sh` 寫入停用旗標並 kill 行程後，等待超過
   `ThrottleInterval` 的時間，行程**未**被重新拉起（`launchctl print`
   顯示 `state = not running`、`last exit code = 0`）
4. 執行 `bin/stop-board.sh --enable` 後，`launchctl kickstart -k` 手動
   觸發一次即恢復正常啟動
5. 全程以 `curl http://127.0.0.1:8080/api/projects` 確認正式看板
   PID／回應碼未變化
6. 驗證完畢執行 `bin/uninstall-launchd.sh`，確認
   `~/Library/LaunchAgents/` 底下無殘留、`launchctl print` 找不到該
   job、測試埠號已釋放

## 7. 驗證記錄

以下為撰寫本文件時的實際驗證方式，供之後比對程式行為是否漂移：

1. 用 `git clone` 把本 repo clone 到暫存目錄（模擬全新安裝，確認
   clone 出來的目錄底下沒有 `target/*.jar`、沒有 `data/`）
2. 確認本機 `/usr/libexec/java_home -V` 只列出 JDK 11 / 8，**沒有 21**；
   但透過 Homebrew 裝有 `openjdk@21`（未 link，`java_home` 抓不到）
3. 以 `BOARD_HOME_DIR=<暫存目錄>` `BOARD_PORT=18099`（避開正式看板的
   8080）執行 `bin/start-board.sh`，觀察到：
   - 正確透過 Homebrew 路徑 fallback 找到 JDK 21 並印出版本
   - 找不到 `target/*.jar`，自動執行 `./mvnw package -DskipTests` 組裝
   - 組裝完成後啟動，資料庫路徑落在 `BOARD_HOME_DIR` 指定的目錄下
   - 輪詢就緒後印出 `/api/health`：`version`、`databasePath`、
     `tools`（8 個，與程式實際註冊的一致）、`startedAt` 均正確回傳
4. 測試完畢後 `kill` 掉該臨時行程、刪除暫存目錄，並確認正式看板
   （8080）的 `/api/projects` 仍正常回應，未受影響

這份記錄只描述當次隔離環境的驗證結果，不代表任何時間點正式 `:8080` 行程的
即時狀態。判斷目前服務是否已更新，請直接查看該行程的 `/api/health`；若端點
不存在，再確認實際啟動的 jar 與 commit，而不要依賴本文件的歷史狀態。

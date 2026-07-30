# AI Project Board

本機 MCP server：AI coding agent 工作時把進度寫進看板，瀏覽器即時看到所有專案的狀態，不用盯著多個終端機。

![Demo](docs/demo.gif)

```mermaid
flowchart LR
    Chat["Chat 規劃專案<br/>create_project / create_tasks"]
    ClaudeCode["Claude Code<br/>（認領「個人記帳 App」）"]
    Codex["Codex CLI<br/>（認領「SMTP 監控工具」）"]

    subgraph Board["AI Project Board（單一 Spring Boot 行程）"]
        direction TB
        MCP["MCP server（/mcp）<br/>Streamable HTTP :8080"]
        REST["唯讀 REST API + SSE"]
        UI["Vue 3 CDN 看板"]
        MCP -.唯讀查詢.-> REST -.推送.-> UI
    end

    Chat -->|建立專案／任務| MCP
    ClaudeCode -->|認領任務| MCP
    Codex -->|認領任務| MCP
```

<details>
<summary>文字版架構圖</summary>

```
Chat 規劃專案
    │  create_project / create_tasks
    ▼
┌─────────────────────────┐
│   AI Project Board      │   單一 Spring Boot 行程
│   Streamable HTTP :8080 │   ├─ MCP server（/mcp）
└─────────────────────────┘   ├─ 唯讀 REST API + SSE
    ▲            ▲            └─ Vue 3 CDN 看板
    │ 認領        │ 認領
「個人記帳 App」  「SMTP 監控工具」
┌──────────┐  ┌──────────┐
│Claude Code│ │Codex CLI │
└──────────┘  └──────────┘
```

</details>

在 chat 裡規劃專案、拆成任務卡片；到任一個專案的 repo 裡跟 Claude Code 或 Codex 說「認領 {專案名} 的任務」，主 session 會盤點待辦、依相依關係分波派工給對應職能的角色 agent（backend / frontend / qa / infra / docs，設定方式見下方「角色 agent」）；瀏覽器開著看板即時反映狀態變化，不用重整頁面。

## 需求

- JDK 21
- Maven（或直接用內附的 `./mvnw`，不需另外安裝）

## 快速開始

```bash
./mvnw clean package
java -jar target/ai-project-board-backend-2.0.0.jar
```

常駐執行在 `:8080`，瀏覽器開 `http://localhost:8080` 看看板。資料庫是 H2 檔案，
預設路徑寫在 `application.yml`（相對於啟動時的工作目錄）；換機器或多套環境用
環境變數蓋掉：

```bash
BOARD_PORT=18080 \
BOARD_DB_URL='jdbc:h2:file:./data/dev;DB_CLOSE_ON_EXIT=FALSE' \
java -jar target/ai-project-board-backend-2.0.0.jar
```

第一次啟動後，看板是空的（`http://localhost:8080` 顯示無專案）——這是正常的，
因為新增專案與任務只能透過 MCP 工具寫入，REST 端點是唯讀的，不會有種子資料。
接下來：

1. 依下方「接線」把看板接進 Claude Code 或 Codex。
2. 在 chat 裡請 agent 呼叫 `create_project` 建一個專案，再用 `create_tasks`
   拆幾張任務卡片。
3. 回到瀏覽器的看板頁面，應該會即時（透過 SSE）看到剛建立的專案與任務卡片，
   不用重整。
4. 到該專案的 repo 裡跟 Claude Code / Codex 說「認領 {專案名} 的任務」，
   對應角色的 agent 會呼叫 `claim_next_task` 認領一筆並開工。

跑測試：

```bash
./mvnw test
```

## 接線

Claude Code：

```bash
claude mcp add --transport http board http://127.0.0.1:8080/mcp --scope project
```

Codex（`~/.codex/config.toml`）：

```toml
[mcp_servers.board]
url = "http://127.0.0.1:8080/mcp"
```

## MCP 工具

| 工具 | 用途 |
|---|---|
| `create_project(name, description?)` | 建立專案；名稱去除首尾空白且不分大小寫判重，重複時回傳既有專案 |
| `create_tasks(projectId, tasks[])` | 一次寫入 1–50 筆任務；標題最多 300 字；可用 `dependsOnIndexes`／`dependsOnTaskIds` 指定前置任務 |
| `list_tasks(projectId?/projectName?, status?, category?, includeDescription?)` | 查詢任務清單與進度；`projectId` 與 `projectName` 擇一，`includeDescription` 一併輸出描述與驗收條件 |
| `claim_next_task(projectName, category, assignee)` | 原子認領指定專案、指定類別中最早且前置皆已完成的一筆待辦任務 |
| `update_task_status(taskId, status, note?)` | 完成／阻塞／歸還任務 |

`category`：`BACKEND` / `FRONTEND` / `TEST` / `INFRA` / `DOC` / `OTHER`。
未填、空白或不在清單內的值會正規化為 `OTHER`，因此不會產生無法認領的任務。

任務必須透過 `claim_next_task` 認領後才能進入 `IN_PROGRESS`；未認領的 `TODO`
也不能直接標記為 `BLOCKED`。改回 `TODO` 會清除 `assignee` 與 `claimed_at`，
之後必須重新認領。任務狀態使用 optimistic locking，若其他 agent 已先更新同一
任務，後提交的操作會失敗；重新讀取看板後再操作即可。

同一專案的批次建立會序列化排序編號配置，`claim_next_task` 則以
`sort_order`、`id` 依序選擇任務，避免並行寫入造成不確定的認領順序。

### 任務相依

`sort_order` 只在單一 `category` 內排序，表達不了「環境設定完成才能改後端」
這種跨類別的先後。建立任務時可以指定前置：

- `dependsOnIndexes`：同批次內的前置任務，用 1-based 序號（規劃時任務還沒有 id）
- `dependsOnTaskIds`：看板上既有任務的 id

`claim_next_task` **只發放前置全部 `DONE` 的任務**，被卡住的候選會跳過，
讓沒有相依的任務可以先做；候選全被卡住時會回報在等哪些前置，而不是誤報
「沒有待辦任務」。看板卡片上以「等待 #n」標示。

這層過濾只影響候選挑選，原子認領的 compare-and-swap 語意不變。

REST 端點（`/api/projects`、`/api/projects/{id}/board`、`/api/projects/{id}/tasks/{taskId}/history`、`/api/events` SSE）全部唯讀，只給前端用，所有寫入一律經由 MCP。

## 角色 agent

`OTHER` 以外的五個 `category`（`BACKEND`、`FRONTEND`、`TEST`、`INFRA`、`DOC`）
各自對應一個角色（`backend-dev`、`frontend-dev`、`qa`、`infra`、`docs`），負責
呼叫 `claim_next_task` 認領自己類別的任務並開工；`OTHER` 不配專屬角色，由主
session 處理。

### 兩階段流程

規劃與開工分成兩個 session，因為**規劃需要你在場、開工不需要**：

```
規劃 session：你 + 模型扮 PM / SA / SD
   ├─ 規格文件 → 寫進 repo（例如 docs/），進版控
   └─ 任務清單 → create_tasks，description 指向規格路徑並標註相依
                    │
                    ▼
開工 session：主 session 擔任 leader
   1. list_tasks(projectName, status="TODO", includeDescription=true)
   2. 讀描述與規格，依相依關係決定派工波次
   3. 每個角色每次只派一件，agent 做完回報即結束
   4. 驗收後再派下一件，全部完成後彙整
```

混在同一個 session 會被進度訊息洗版，也失去「規劃定稿」這個分界點。

leader 的驗收範圍有限：只驗「該動的有動、測試有跑、`BLOCKED` 有交代」，
程式碼品質交給 `qa` 角色與既有測試。

**這些角色定義不在本 repo 裡，是使用者自己機器上的全域設定**，clone 這個
repo 不會自動取得——想用這套多 agent 認領流程，需要自己建立：

- Claude Code：在 `~/.claude/agents/` 底下為每個角色各建一個 `*.md`（[subagent
  格式文件](https://docs.claude.com/en/docs/claude-code/sub-agents)），例如
  `~/.claude/agents/backend-dev.md`：

  ```markdown
  ---
  name: backend-dev
  description: 執行看板上 category 為 BACKEND 的任務。
  tools: Read, Write, Edit, Bash, Grep, Glob, mcp__board__claim_next_task, mcp__board__update_task_status
  ---
  你是後端工程師。呼叫 claim_next_task(projectName, "BACKEND", "backend-dev") 認領任務並開工，
  完成後更新為 DONE，卡住則更新為 BLOCKED 並在 note 說明原因。
  ```

- Codex CLI：在 `~/.codex/AGENTS.md` 用同樣邏輯寫五個角色段落（Codex 沒有各角色
  獨立檔案的機制，習慣上集中寫在一份 `AGENTS.md`）。

每個角色只認領自己類別的任務、只碰自己職責內的檔案；同一類別同時只有一個角色
在跑，且**做完一件就回報結束、不自行認領下一件**——下一件由 leader 判斷時機
後另派，否則 agent 會把該類別的任務全部吃光，分波控制就失效了。

這一套是本專案作者自己機器上的慣例，不是看板功能運作的必要條件：
`claim_next_task` 是一般 MCP 工具，任何連上 `/mcp` 的 client（含主 session）
都能直接呼叫，不需要先有 subagent 定義才能認領任務。

沒有建立這些角色時，你會失去的是分工，不是功能：assignee 名稱要自己在對話中
指定（不會自動代入 `backend-dev` 這類名字），路徑權限邊界（例如「這個角色只
能碰 `src/main/java/**`」）不會自動套用，而且只有一個主 session 在跑，無法把
同一波裡沒有相依的任務分給不同角色同時開工。

## 目錄結構

```
src/main/java/dev/aiboard/
├── mcp/        # MCP 工具定義（ProjectBoardTools）
├── project/    # 專案 Entity / Repository / Service
├── task/       # 任務 Entity / Repository / Service
├── web/        # 唯讀 REST Controller
├── event/      # SSE 事件發布
├── config/     # MCP tool 註冊設定
└── common/     # 共用例外與列舉（TaskCategory 等）
src/main/resources/
├── application.yml
├── db/migration/   # Flyway migration（V1、V3、V4）
└── static/         # Vue 3 CDN 前端（index.html / app.js / tokens.css）
```

## 技術棧

Spring Boot 3.5.16、Spring AI 1.1.8（MCP server，Streamable HTTP）、Java 21、H2、Flyway、Vue 3（CDN，無建置）。

## 已知限制

- 單機使用，看板與資料庫都在本機，無跨裝置同步
- `/mcp` 端點無認證，設計前提是只在本機或私有網路使用
- SSE 連線集合是行程內單例，不支援多副本水平擴展

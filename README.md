# AI Project Board

本機 MCP server：AI coding agent 工作時把進度寫進看板，瀏覽器即時看到所有專案的狀態，不用盯著多個終端機。

```mermaid
flowchart TB
    Chat["Chat 規劃專案<br/>create_project / create_tasks"]

    subgraph Board["AI Project Board（單一 Spring Boot 行程）"]
        MCP["MCP server（/mcp）<br/>Streamable HTTP :8080"]
        REST["唯讀 REST API + SSE"]
        UI["Vue 3 CDN 看板"]
    end

    ClaudeCode["Claude Code"]
    Codex["Codex CLI"]

    Chat -->|建立專案／任務| MCP
    ClaudeCode -->|認領「個人記帳 App」| MCP
    Codex -->|認領「SMTP 監控工具」| MCP
    REST -.唯讀查詢.-> UI
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

在 chat 裡規劃專案、拆成任務卡片；到任一個專案的 repo 裡跟 Claude Code 或 Codex 說「認領 {專案名} 的任務」，對應職能的 agent（backend / frontend / qa / infra / docs）各自去看板認領一筆屬於自己類別的任務並開工；瀏覽器開著看板即時反映狀態變化，不用重整頁面。

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
| `create_project(name, description?)` | 建立專案，名稱重複時回傳既有專案（冪等） |
| `create_tasks(projectId, tasks[])` | 一次寫入多筆任務，含 `category` |
| `list_tasks(projectId, status?, category?)` | 查詢任務清單與進度，可依狀態／類別篩選 |
| `claim_next_task(projectName, category, assignee)` | 原子認領指定專案、指定類別中最早的一筆待辦任務 |
| `update_task_status(taskId, status, note?)` | 完成／阻塞／歸還任務 |

`category`：`BACKEND` / `FRONTEND` / `TEST` / `INFRA` / `DOC` / `OTHER`。

REST 端點（`/api/projects`、`/api/projects/{id}/board`、`/api/projects/{id}/tasks/{taskId}/history`、`/api/events` SSE）全部唯讀，只給前端用，所有寫入一律經由 MCP。

## 角色 agent

`~/.claude/agents/`（Claude Code）與 `~/.codex/AGENTS.md`（Codex）各定義五個角色：`backend-dev`、`frontend-dev`、`qa`、`infra`、`docs`，分別對應上面五種 `category`。每個角色只認領自己類別的任務、只碰自己職責內的檔案；同一類別同時只有一個角色在跑。`OTHER` 類別不配專屬角色，由主 session 處理。

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
├── db/migration/   # Flyway migration（V1、V3）
└── static/         # Vue 3 CDN 前端（index.html / app.js / tokens.css）
```

## 技術棧

Spring Boot 3.5.16、Spring AI 1.1.8（MCP server，Streamable HTTP）、Java 21、H2、Flyway、Vue 3（CDN，無建置）。

## 已知限制

- 單機使用，看板與資料庫都在本機，無跨裝置同步
- `/mcp` 端點無認證，設計前提是只在本機或私有網路使用
- SSE 連線集合是行程內單例，不支援多副本水平擴展

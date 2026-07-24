# AI Project Board

本機 MCP server：AI coding agent 工作時把進度寫進看板，瀏覽器即時看到所有專案的狀態，不用盯著多個終端機。

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

在 chat 裡規劃專案、拆成任務卡片；到任一個專案的 repo 裡跟 Claude Code 或 Codex 說「認領 {專案名} 的任務」，對應職能的 agent（backend / frontend / qa / infra / docs）各自去看板認領一筆屬於自己類別的任務並開工；瀏覽器開著看板即時反映狀態變化，不用重整頁面。

## 需求

JDK 21。

## 啟動

```bash
./mvnw clean package
java -jar target/ai-project-board-backend-2.0.0.jar
```

常駐執行在 `:8080`，瀏覽器開 `http://localhost:8080` 看看板。資料庫是 H2 檔案，預設路徑寫在 `application.yml`；換機器或多套環境用環境變數蓋掉：

```bash
BOARD_PORT=18080 \
BOARD_DB_URL='jdbc:h2:file:./data/dev;DB_CLOSE_ON_EXIT=FALSE' \
java -jar target/ai-project-board-backend-2.0.0.jar
```

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

## 已知限制

- 單機使用，看板與資料庫都在本機，無跨裝置同步
- `/mcp` 端點無認證，設計前提是只在本機或私有網路使用
- SSE 連線集合是行程內單例，不支援多副本水平擴展

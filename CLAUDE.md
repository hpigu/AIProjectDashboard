# AI Project Board — R5 開發規則

本 repo 是一個集中式 AI 專案看板。單一 Spring Boot 行程同時提供：

- Streamable HTTP MCP server：`http://127.0.0.1:8080/mcp`
- 唯讀 REST API 與 SSE
- 零建置 Vue 3 CDN 看板

一個看板服務所有專案。使用者明示專案名稱，client 透過
`claim_next_task(projectName, category, assignee)` 原子認領工作；不得用 git
remote 或目前目錄猜測專案。

## 啟動與連線

```bash
./mvnw clean package
java -jar target/ai-project-board-backend-2.0.0.jar
```

正式服務固定使用 `:8080` 與預設 `data/board`。開發驗證不得連入正式資料庫，
必須外部化 port、資料庫與 log：

```bash
BOARD_PORT=18080 \
BOARD_DB_URL='jdbc:h2:file:./data/dev-r5;DB_CLOSE_ON_EXIT=FALSE' \
BOARD_LOG_FILE='./logs/dev-r5.log' \
java -jar target/ai-project-board-backend-2.0.0.jar
```

Codex 全域 MCP：

```toml
[mcp_servers.board]
url = "http://127.0.0.1:8080/mcp"
```

## MCP 工具與認領協定

- `create_project`、`create_tasks`：chat 規劃階段建立看板內容。
- `list_tasks`：列出任務、category 與 assignee。
- `claim_next_task`：依專案名稱與 category 認領 `sort_order` 最小的 TODO。
- `update_task_status`：完成、阻塞或歸還任務；回 TODO 時清空認領資料。

認領必須使用條件式 UPDATE，不得改成先查後存或悲觀鎖：

```sql
UPDATE task
   SET status = 'IN_PROGRESS',
       assignee = :assignee,
       claimed_at = :now,
       updated_at = :now
 WHERE id = :id
   AND status = 'TODO'
```

影響一筆才算成功；零筆代表任務已被搶走，重新取候選任務，最多三次。
`SseEmitterRegistry` 使用 `@TransactionalEventListener(AFTER_COMMIT)`，不得在
交易提交前向瀏覽器發出狀態事件。

狀態與認領資料必須符合：

- `TODO`：`assignee`、`claimed_at` 都是 NULL。
- `IN_PROGRESS`：兩者都有值。
- `BLOCKED`、`DONE`：保留原認領者。

`task` 沒有獨立的「驗收條件」欄位。規劃階段用 `create_tasks` 時，
`description` 建議寫成「描述 + 驗收條件」兩段（例如
`實作交易 CRUD API\n驗收條件：四個端點都有、金額不接受負數`）——
`claim_next_task` 認領成功時會把整個 `description` 印給 agent，
這是 agent 唯一看得到的任務細節來源，寫清楚才知道何時算做完。
這是規劃慣例，不是 schema 要求。

## 分層

- `mcp/` 只依賴 Service，負責工具參數、呼叫與文字格式，不注入 Repository。
- Service 不得依賴 MCP 型別；業務規則與交易都在 Service。
- `web/` 是唯讀 read model，可直接查 Repository；禁止新增 POST/PUT/DELETE。
- Entity 不可外洩到 MCP 或 web 回傳，跨層使用 record DTO。
- migration 使用標準 SQL；主鍵 BIGINT、時間 TIMESTAMP。

## 前端

前端固定是 Vue 3 global CDN + `index.html`、`app.js`、`tokens.css`，不引入 npm、
Vite、SFC、TypeScript、Pinia 或 Router。維持 Andon 狀態板風格、既有字體與
design tokens。IN_PROGRESS 與 BLOCKED 卡片用 `--muted`、IBM Plex Mono 顯示
`@assignee`。

`TransitionGroup` 必須保留：

- `.column-body { position: relative; }`
- `.task-leave-active { position: absolute; }`
- `<level-two :key="currentProjectId">`

## 測試

```bash
./mvnw test
./mvnw clean package
```

認領功能至少覆蓋：大小寫不敏感的專案查找、不存在時列出專案、無任務正常回傳、
CAS 失敗重試、兩個執行緒不會認領同一任務、回 TODO 清空認領資料、REST 回傳
assignee、MCP 文字格式。

## 已知限制

`project.name` 的 UNIQUE constraint 是資料庫層級、大小寫敏感；`create_project`
與 `claim_next_task` 的查找則是 `findByNameIgnoreCase`（大小寫不敏感）。
極端並發下（兩個請求同時以「個人記帳App」「個人記帳app」呼叫 `create_project`）
理論上可能建出兩筆大小寫不同但語意重複的專案，之後 `findByNameIgnoreCase`
只會查到其中一筆。機率低、非本輪重點，暫不修，記錄於此供之後參考。

## 本輪不做

不做自動 repo 綁定、worktree/分支隔離、同角色多工人、動態 agent、Docker、
雲端部署、任務依賴或自動建立專案與任務。

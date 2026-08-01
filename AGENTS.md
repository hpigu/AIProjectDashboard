# AI Project Board — R5 開發規則

本 repo 是集中式專案看板：一個常駐 Spring Boot 行程在
`http://127.0.0.1:8080/mcp` 提供 Streamable HTTP MCP，並提供唯讀 REST/SSE
與 Vue 3 CDN 前端。一個服務管理所有專案；專案由使用者明示名稱，不做目錄或
git remote 自動偵測。

## 必守架構

- `mcp/` 只呼叫 Service，不注入 Repository，不放業務邏輯。
- Service 不得出現 MCP 型別；Entity 不外洩到 MCP/web。
- `web/` 僅可提供 GET；所有寫入只經 MCP。
- migration 使用標準 SQL、BIGINT 主鍵與 TIMESTAMP。
- 設定值使用環境變數；正式 `:8080` 和預設資料庫不得拿來跑開發測試。

## R5 認領

`claim_next_task(projectName, category, assignee)` 以不分大小寫的完整專案名稱
查找，從該 category 的 TODO 依 `sort_order` 取第一筆。認領只能用以下
compare-and-swap，影響零筆時重新取候選，最多三次：

```sql
UPDATE task
   SET status = 'IN_PROGRESS',
       assignee = :assignee,
       claimed_at = :now,
       updated_at = :now
 WHERE id = :id AND status = 'TODO'
```

不要改用悲觀鎖。成功後寫 `task_log` 並發布事件；SSE 只在 AFTER_COMMIT 送出。
TODO 的 assignee/claimed_at 必為 NULL，IN_PROGRESS 必有認領者，BLOCKED/DONE
保留認領者，改回 TODO 必須清空。

## 任務相依

`task_dependency` 記錄跨 category 的前置關係（`sort_order` 只在單一 category
內排序，表達不了「INFRA 完成才能做 BACKEND」）。

挑候選時**必須跳過前置尚未 DONE 的任務**，讓沒有相依的任務可以先做；
候選全被卡住時回報在等哪些前置，不得誤報「沒有待辦任務」。這層過濾只影響
挑選，上面的 CAS 與三次重試語意不變。

`create_tasks` 以批次內 1-based 序號或既有 task id 指定前置，建立前驗證
序號範圍、自我依賴與向前依賴。

## 分派模式

主 session 擔任 leader：盤點 → 依相依決定波次 → 每個角色每次只派一件 →
agent 完成即回報結束 → leader 驗收後再派下一件。角色 agent 不自行循序認領。

## 開發用埠號與資料庫（必讀）

正式看板常駐在 `:8080`，資料庫 `./data/board`。**任何 agent 都不得佔用或寫入這兩者**
——使用者的看板正在上面運作。

啟動應用或跑整合測試時一律帶入：

```bash
BOARD_PORT=8081 BOARD_DB_URL='jdbc:h2:file:./data/dev-<role>' ./mvnw test
```

`<role>` 用自己的角色名（backend/qa/infra…），避免多個 agent 互相覆蓋。
`./mvnw test` 會啟動 Spring context 與併發測試，即使不重啟正式看板也會寫入資料庫，
因此這條規則沒有例外。

## 前端與測試

維持零建置 Vue 3 CDN 與既有 Andon tokens、字體、動畫。不引入 npm/Vite/SFC/
TypeScript。IN_PROGRESS 與 BLOCKED 顯示 muted、IBM Plex Mono 的 `@assignee`。

執行 `./mvnw test` 與 `./mvnw clean package`。併發測試必須證明兩個 worker
同時認領時只有一個取得同一任務，且前置未完成的任務在併發下仍不得被發放。

## 角色指引的兩層來源與降級策略

角色的完整工作指引存在 `role` 表（`RoleService`/`RoleTools`），不是寫死在檔
案裡：

- **通用層**：`project_id IS NULL`，跨專案都成立的角色職責與流程。
- **專案覆寫層**：`project_id` 指定某專案，該專案專屬的路徑所有權、埠號、
  commit 規則；`get_role(name, projectName)` 優先回傳覆寫版，找不到才退回
  通用版，兩者都沒有則列出現有角色。覆寫版是整份指引（通用內容 + 專屬片段
  合併後存入），不會由呼叫端自動疊加。
- `RoleSeeder` 在啟動時用 `createRoleIfAbsent` 匯入五個角色的初始指引，
  **只在該角色（依 name + projectId 判定）尚未存在時建立，已存在就原樣保
  留**——不會覆蓋使用者透過 `upsert_role` 或看板 UI 調整過的內容，重啟幾次
  也不會重複匯入。要把新版指引推到既有看板必須呼叫 `upsert_role`，改
  `RoleSeeder` 裡的常數只影響「還沒有該角色的全新看板」。

`~/.claude/agents/*.md`、`~/.codex/AGENTS.md` 這層是使用者機器上的**薄殼**：
先呼叫 `get_role` 取得看板上的最新指引並照做；只有在 `get_role` 失敗或看板
未啟動時，才退回檔案內建的最小 fallback 規則（讀 repo 的
`CLAUDE.md`/`AGENTS.md`、呼叫對應 category 的 `claim_next_task`、單件回報不
連續認領），確保看板連不上時仍不停工，但不是常態運作路徑。

更完整的專案說明與指令同步維護在 `CLAUDE.md`。

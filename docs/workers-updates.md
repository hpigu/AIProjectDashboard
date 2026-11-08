# 更新 Cloudflare 上的看板

Cloudflare 不會因為 Git commit 或 push 就自動更新。這個 repo 尚未連接自動發布 CI；
完成開發後，需把 Workers 程式與靜態檔發布到原來的兩個 Worker。原 D1 binding
保持相同，資料不會因重新發布程式而消失，不必重新建立資料庫或購買網域。

實際網址與帳號設定只在被 Git 忽略的 `workers/wrangler.production.*.jsonc`；
以下流程不把它們寫入 Git。一般操作可以請協作者「更新雲端看板」，先完成下列
檢查，再明確同意這一次的正式發布。不要貼上 token 或管理 API key。

## 新功能需要改哪些地方

| 變更 | 雲端所需工作 |
|---|---|
| 畫面、CSS、翻譯、vendor 靜態檔 | Java 與 Workers 共用 `src/main/resources/static`，測試後發布 web Worker |
| 任務、認領、角色、MCP 工具等 Java 後端功能 | 同步實作 `workers/src` 並補測試；Java JAR 不能直接在 Workers 執行 |
| Java/H2 migration | 另寫相容的 `workers/migrations` D1 migration，不能直接套 H2 SQL |
| Workers 後端 | 測試後發布 web 與 MCP；兩者共用後端程式碼，避免版本不同 |
| skill、agent 薄殼或本機客戶端設定 | 依 plugin 安裝/更新流程處理，發布 Worker 不會自動更新客戶端 |

容量功能上線時，先發布並驗收包含容量驗證與 snapshot 的 MCP backend，再更新
leader/client 指引及排程器；web Worker 可依本次靜態檔變更一併更新。Java/H2 與
Workers/D1 的 `BOARD_CAPACITY_*` 預設及驗證規則應保持一致。舊版 Workers 尚未回傳
capacity snapshot 是預期狀態；升級前 leader 不可依新版文件假設可同時派出三個
Backend worker，應先完成 backend 升級並確認 `list_tasks` 回傳容量資料。本文件只描述
升級順序，不代表此批程式已部署或正式角色已更新。

這是維持免費方案的代價：Java 與 Workers 有兩份後端。現有整合測試會比對 Java
工具名稱與 Workers 工具名稱，但不能自動證明所有新業務語意都已移植；每次後端
改動都必須明確確認雙方的功能、輸入 schema、DTO、相依與認領規則一致。

## 1. 本機驗證與檢查變更

先保留實際正式設定、原 Worker version IDs 與目前 D1 migration 狀態在本機私有
紀錄。確認沒有更換 D1 ID、放寬 Access、開啟 preview、加入付費產品或把憑證納入 Git。
不在主工作區執行 Maven package/clean，也不使用正式 H2 或 8080。

```bash
cd workers
npm ci --ignore-scripts
npm test
npm run build:local
```

`build:local` 是 dry-run，不會發布。Java 後端有修改時，另在隔離 worktree 執行
repo 規定的 Java 測試與 package；不能用 Java 測試通過取代 Workers 驗收。
有新 D1 migration 時，先在隔離本機 DB 演練升級及還原。

## 2. 進入維護並備份

正式操作前取得這次更新的明確同意。先停止所有裝置的任務執行與其他 DB 維護，
將私有 MCP 設定中的 `vars.BOARD_MAINTENANCE` 改成字串 `"true"`，再發布 MCP：

```bash
node node_modules/wrangler/bin/wrangler.js deploy --config wrangler.production.mcp.jsonc
```

維護模式仍須通過原本 Bearer 驗證，允許 `list_tasks`、`list_roles`、`get_role`、
`preview_archive_project`、連線協商與工具清單；其他寫入工具會回報暫停寫入。
新工具預設也會被暫停。瀏覽器唯讀看板可繼續查看。

確認已發布的 MCP 確實拒絕寫入，並等所有已開始的工具呼叫完成。**私有設定檔裡
寫 true 不等於雲端已套用**；維護模式不取消先前開始的請求，也不鎖住管理員直接
執行的 SQL/DDL。備份到 preflight 結束期間不得執行其他 migration 或直接 SQL 寫入。

選擇 repo 外的新目錄，父目錄須已存在，不可是符號連結；例如：

```bash
mkdir -p "$HOME/.local/share/ai-project-board/backups"
chmod 700 "$HOME/.local/share/ai-project-board/backups"
board_backup="$HOME/.local/share/ai-project-board/backups/$(date -u +%Y%m%dT%H%M%SZ)"
node scripts/maintenance.mjs backup --remote --ack-writes-paused --out "$board_backup"
node scripts/maintenance.mjs preflight-update --remote --ack-writes-paused --backup "$board_backup"
```

工具預設 `--local`；只有明確傳 `--remote` 才連雲端，遠端操作還要求維護設定為
`"true"` 及 `--ack-writes-paused`。輸出目錄 0700、檔案 0600，拒絕 repo 內路徑、
符號連結及覆寫。SQL 不會印到畫面，也不會加入 Git。

備份包含 `schema.sql`、`data.sql` 與 checksum manifest。前後 revision、schema、
migration 及設定全部一致才標示有效；中途失敗只留下不完整產物，不會宣稱成功。
這是更新前的本機快照，**不是已完成的加密離機備份或還原演練**。資料含專案內容，
請另外選擇合適的加密離機保存位置。

兩次 SQL export 不共用交易快照；revision 也無法偵測繞過應用的直接 SQL 寫入。
因此 `--ack-writes-paused` 是實際停寫的前提，不能只為通過檢查而加入。

## 3. 遷移與發布

沒有資料庫變更就跳過 migrations apply。若有變更，確認先前本機演練通過、備份
有效，且新舊程式可相容於這次 schema。不要修改已套用的 migration 檔案。

```bash
# 只有本次確實新增 D1 migration 且已同意遷移時執行：
node node_modules/wrangler/bin/wrangler.js d1 migrations apply DB --remote --config wrangler.production.web.jsonc

# 兩個設定仍綁原 D1，MCP 維持維護模式：
node node_modules/wrangler/bin/wrangler.js deploy --config wrangler.production.web.jsonc
node node_modules/wrangler/bin/wrangler.js deploy --config wrangler.production.mcp.jsonc
```

發布後先驗匿名拒絕、本人登入、靜態檔保護、MCP 讀取與既有資料；確認完成後才將
MCP 私有設定的 `BOARD_MAINTENANCE` 改回字串 `"false"` 並再次發布 MCP。
空字串、布林 false、拼字錯誤等值都會維持停寫；首次舊設定未含此欄位時仍沿用
原本可寫行為，更新後應明確保留 `"false"`。

最後驗收新功能、MCP 寫入與 CAS、看板 SSE、重連，以及既有任務/證據/角色仍在。
記錄新的程式 commit、兩個 Worker version IDs、migration 與驗收結果，只存於
本機私有部署紀錄。測試資料不自行刪除或封存。

## 4. 出問題時

維持 MCP 停寫，保留現況，不先刪資料。只有程式問題、schema 與 bindings 仍相容時，
可以在取得回退同意後指定先前 version：

```bash
node node_modules/wrangler/bin/wrangler.js rollback PREVIOUS_VERSION_ID --config wrangler.production.web.jsonc
node node_modules/wrangler/bin/wrangler.js rollback PREVIOUS_VERSION_ID --config wrangler.production.mcp.jsonc
```

**Worker rollback 不會回復 D1 資料**。如果舊 MCP version 沒有維護模式，回退可能
重新允許寫入，必須先停止客戶端，選擇有相容停寫功能的版本或另安排復原。
bindings/資源變動也可能令 rollback 不可用。

SQL 還原需先完整 schema、再 data，保留 `PRAGMA defer_foreign_keys=TRUE`；Time
Travel 則會回滾後續資料。兩者都需另行取得明確同意、保存目前狀態，先到另一個
隔離 DB 驗證，不能直接覆蓋現在的正式 D1。詳見[部署與還原指南](workers-deployment.md#備份還原與更新)。

官方參考（查核日期 2026-11-08）：[Worker rollback](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/)、
[D1 migrations](https://developers.cloudflare.com/d1/reference/migrations/)、
[SQL import/export](https://developers.cloudflare.com/d1/best-practices/import-export-data/)。

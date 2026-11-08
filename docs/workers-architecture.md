# 單人 Workers 看板實作計畫

本機實作已通過交易、認證、SSE、重啟與 SQL 還原驗證；個別部署的驗收結果與
識別資訊只記錄在 Git 忽略的本機私有紀錄。公開的狀態與部署 gate 見
[操作指南](workers-deployment.md)與[私有紀錄規則](cloud-rollout.md)。
目標是個人多裝置使用，以 Workers Free、D1 Free 與免費 workers.dev 網址運行。
免費可行性須以真實帳號與流量量測確認，不承諾永久零費用或可用性 SLA。

## 決策

- 保留 Spring Boot/H2 版本與其 migration，另建 `workers/` JavaScript 後端。
  原 Vue、CSS、字型與 vendor 靜態檔直接共用，不增加前端建置流程。
- 分成 web 與 MCP 兩個 Worker，共用一個 D1。web 是 GET/HEAD 唯讀入口，
  MCP 是唯一寫入入口；兩者皆須先驗證，靜態檔不能繞過驗證。
- web 使用 Cloudflare Access 登入，Worker 驗證 RS256 簽章、固定 issuer、
  audience、有效期與唯一擁有者 email；不信任未驗證的 identity header。
- MCP 使用每裝置獨立、高熵 Bearer credential。Worker Secret 僅存裝置名稱與
  SHA-256 雜湊；不把瀏覽器 cookie 當機器授權，不以 Access 登入重新導向當 OAuth。
  先支援能設定 Bearer 的 Codex/Claude Code；聊天 connector 的 OAuth 另行驗證。
- 不使用 VM、Tunnel、Containers、Durable Objects、付費日誌或 AI API。
  Wrangler/npm 僅為獨立後端的本機開發工具，不改動零建置前端。
- D1 採 SQLite 方言：主鍵必須使用 `INTEGER PRIMARY KEY` 才具 rowid 自增行為。
  這是 Workers 專用 schema 的方言差異，不更動 Java 版本的 BIGINT migration。
- CAS、任務相依、claim token、結構化完成證據與角色覆寫維持既有語意。
  D1 使用原子的 batch，mutation、歷史與事件 outbox 同時提交；不依賴跨請求交易。
- SSE 從 D1 outbox 增量讀取，處理不同 Worker isolate 的更新；連線定期結束後
  由現有前端重新連線與全量同步，不依賴 isolate 內的全域連線清單。
  此策略有查詢與重連成本，需量測免費 CPU、requests 與 D1 rows read 額度。

## 工作拆分與驗收順序

1. D1 schema、完整 16 個工具的業務邏輯與既有前端 DTO；測試交易回滾、
   併發單贏、前置阻擋、token 保護、證據驗證、封存與 optimistic version。
2. HTTP/MCP、Access JWT 與機器 Bearer 驗證；測試未登入、錯誤身分、錯誤
   audience、到期與偽造 JWT、入口越界、無認證靜態檔、request size 與協定錯誤。
3. Wrangler 雙入口設定與部署操作文件；本機操作明確 local，拒絕 remote binding，
   不執行 deploy、遠端 migration、帳號變更或付費升級。
4. 在真正 workerd/D1 本機模擬環境，用 MCP SDK client 驗讀寫、認領與依賴；
   驗 SSE 跨入口更新、runtime 重啟後保留與 SQL 匯出還原。
5. 確認部署內容、成本邊界與仍待帳號驗證的項目，再取得使用者明確部署同意。

## 操作邊界

本機正式 `:8080`、`data/board*`、`logs/board.log` 與主工作區 `target/`
不參與此次開發。測試僅操作自己的暫存或 Workers 本機資料，程序由確切 PID 或
runtime dispose 收尾。不 commit、push、建立 PR 或自動遷移本機正式資料。

## 官方參考

查核日期：2026-11-08。

- [D1 batch API](https://developers.cloudflare.com/d1/worker-api/d1-database/)
- [D1 limits](https://developers.cloudflare.com/d1/platform/limits/)
- [Wrangler testing APIs](https://developers.cloudflare.com/workers/wrangler/api/)
- [Workers Streams](https://developers.cloudflare.com/workers/runtime-apis/streams/)
- [Access JWT 驗證](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/)
- [Workers Request：redirect 與 request.signal](https://developers.cloudflare.com/workers/runtime-apis/request/)
- [D1 外鍵與分階段匯入](https://developers.cloudflare.com/d1/best-practices/import-export-data/)
- [Workers 免費與付費計價](https://developers.cloudflare.com/workers/platform/pricing/)
- [D1 免費與付費計價](https://developers.cloudflare.com/d1/platform/pricing/)

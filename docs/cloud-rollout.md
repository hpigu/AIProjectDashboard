# 雲端部署狀態與私有紀錄

本文件只保留可公開的操作原則。實際部署網址、帳號識別碼、Access team/AUD、
資料庫識別碼與部署版本，保存在被 Git 忽略的本機紀錄中，不應提交或發布。

## 本機私有設定

- `workers/wrangler.production.web.jsonc`：實際 web Worker、D1 與 Access 設定。
- `workers/wrangler.production.mcp.jsonc`：實際 MCP Worker 與 D1 設定。
- `workers/.wrangler/deployment-status.md`：此裝置的實際部署進度與識別資訊。
- macOS login Keychain：各客戶端原始裝置憑證。
- Cloudflare Worker Secret：只保存各裝置 SHA-256 hash 的完整 map。

上述正式設定與部署紀錄均在 Git 忽略範圍內。原始憑證不寫入 repo、設定範本、
文件、CLI 引數、日誌或看板任務。日後部署文件只能使用以下範例形式：

- 看板：`https://WEB_WORKER.ACCOUNT_SUBDOMAIN.workers.dev/`
- MCP：`https://MCP_WORKER.ACCOUNT_SUBDOMAIN.workers.dev/mcp`

## 上線驗收項目

1. 兩個 Worker 共用同一個 D1；本機正式看板與資料保持原狀。
2. web 全路由（含靜態檔）需經 Access 登入及 MFA，Worker 另驗證 JWT 的簽章、
   issuer、audience、期限與唯一 owner email。
3. MCP 需獨立裝置 Bearer，匿名、錯誤憑證、Origin 與非 MCP 路徑均拒絕。
4. 驗證實際 Codex 與 Claude Code 連線、16 個工具、讀寫、併發單贏、相依等待、
   claim token 所有權、完成證據與 Worker 重新發布後的資料保留。
5. 瀏覽器不重新整理即可看到遠端 MCP 造成的任務狀態變更，重連後仍與資料庫一致。
6. 確認使用 Free，避免付費附加產品，另安排 CPU/配額量測、離機備份與隔離還原。

完整部署、憑證輪替與還原操作見 [Workers 部署文件](workers-deployment.md)。
進度細節只更新本機私有紀錄；Git 忽略不是存取控制，遠端入口仍必須維持驗證。

# 安全政策 / Security Policy

## 威脅模型（先讀這段）

這個看板的設計前提是**單機本地執行**：一個 Spring Boot 行程綁在
`127.0.0.1:8080`，提供 MCP、唯讀 REST/SSE 與離線前端。

**`/mcp` 沒有 server-side 身分驗證。** 任何能連到這個端點的行程都能呼叫全部
MCP 工具，包含 `archive_project`、`upsert_role`。Claude Code／Codex 對 worker
角色設定的工具白名單是 client 端邊界，不是伺服器授權。

因此以下情況**不算漏洞**，是已知且已記錄的設計取捨：

- 同一台機器上的其他行程可以呼叫全部工具
- 把 `BOARD_HOST` 改成公開位址後，任何連得到的人都能呼叫全部工具
- `/api/diagnostics` 會回傳路徑、磁碟用量等維運資訊，且沒有存取控制

要在多人或可信任邊界之外使用，必須自行在前面加上認證層（反向代理 +
Basic Auth／mTLS）。本 repo 不提供，也未驗證任何雲端部署方式。

## 已實作的防護

- **Host／Origin 驗證**（`config/LocalOriginGuardFilter`）：阻擋 DNS rebinding。
  非 loopback 的 `Host` 或 `Origin` 一律回 `403`；刻意放行的來源以
  `BOARD_ALLOWED_HOSTS` 列出。
- **安全 response headers**（`config/SecurityHeadersFilter`）：CSP 不使用
  `unsafe-inline`，`frame-ancestors 'none'`，`nosniff`，`no-referrer`。
- **前端完全離線**：Vue 執行檔與字型都 vendor 進 repo，不連任何外部來源。
- **claim token 只存雜湊**：原文只在認領當下回傳一次，DB 與日誌都只有 SHA-256
  雜湊，比對採常數時間。
- **日誌不外洩連線資訊**：Hikari 與 Flyway 的 logger 降到 `WARN`，避免預設
  `INFO` 印出含使用者名稱與路徑的完整 JDBC URL。
- **readiness 不回傳原始例外**：`/api/health/ready` 只回報各項檢查通過與否。

## 回報漏洞

請**不要**開公開 issue。改用 GitHub 的 Private vulnerability reporting
（repo 的 Security 分頁 → Report a vulnerability），或直接聯繫維護者。

回報時請附上：影響版本、重現步驟、實際影響（能讀到什麼／能改到什麼），
以及是否需要本機存取權限。

## 支援範圍

只有 `main` 分支的最新版本會收到修補。這是單人維護的專案，沒有 LTS 分支。

# 雲端部署研究與決策依據

查證日期：2026-11-08（Asia/Taipei）。下列為已實際開啟的官方文件與 repo 原始碼；文件支援能力不等於本帳號已開通，也不代表已完成真實雲端驗收。這次沒有建立雲端資源或連線本機正式資料庫。

## 建議與信任邊界

第一階段採單一 Linux VM、Java 21、單一 Spring Boot 實例、持久磁碟上的 H2，以及同機 `cloudflared`。這是沿用現有交易、CAS 認領與 SSE 行為的最小改動方案。應用程式及中介代理僅監聽 loopback；VM 不開應用程式入站埠。Tunnel 由 VM 主動向外建立連線，符合此配置。[Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/)

採兩個不同的 Access application 與 hostname：

| 入口（範例） | 認證 | 可到達範圍 |
| --- | --- | --- |
| `board.example.com` | Access 人類 Allow，明確 email／群組，建議既有具 MFA 的 IdP | 網頁、唯讀 REST、SSE；阻擋 `/mcp` |
| `mcp.example.com` | Access Service Auth，逐裝置及用途配置 service token | 僅 `/mcp` 必要方法；不開網頁、診斷、REST |

Service token 不是 Cloudflare API token，也不是 Tunnel token。前者只可進入指定 Access application；API token 管理雲端設定；Tunnel credential 讓來源端加入 Tunnel，三者分開存放且不得互用。Service Auth 以 `CF-Access-Client-Id`、`CF-Access-Client-Secret` 驗證，不能拿瀏覽器登入 redirect 當作 MCP OAuth。[Service tokens](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)

建議每個 hostname 的 ingress **各自**設定 `originRequest.access.required: true`、正確 `teamName`、該 application 唯一的 `audTag`，由 `cloudflared` 在轉送前驗證 Access JWT；未知 hostname／不符路徑回 404。不要只檢查 `Cf-Access-Jwt-Assertion` header 是否存在，也不要把兩種 audience 合併成通用許可。[Origin parameters](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/origin-parameters/)

對本次「完全封閉源站 + Tunnel JWT 驗證」方案，不必先在 Service 層引入 Cloudflare 型別。若後續來源不只 Tunnel、要記錄可信 caller、實施不同工具權限，或啟用 Cloudflare MCP application 的 Managed OAuth，應在 HTTP 邊界增加簽章、issuer、audience、有效期驗證及明確授權。Cloudflare 對非 Tunnel 來源要求驗 JWT；MCP application 的 Managed OAuth 文件另外明確要求 MCP server 驗證 Access assertion。[Application token](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/application-token/)、[Managed OAuth](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/managed-oauth/)

本方案的機器憑證持有者仍是所有 MCP 工具的受信任操作者；它沒有專案隔離、leader／worker 後端 RBAC，也無法把 `assignee` 當成可信 caller identity。瀏覽器只能讀取，不能因取得人類 Access cookie 就取得寫入能力。若需要不互信的多人共用，應先開發後端授權，再公開使用。

## 客戶端能力矩陣

| 客戶端／執行位置 | 遠端 HTTP 與驗證 | 本方案判定 |
| --- | --- | --- |
| Codex CLI／IDE，本機 executor | Streamable HTTP；`env_http_headers`、靜態 headers、Bearer、OAuth | 可用兩個 CF headers；使用專用環境變數，不在 TOML 放 secret |
| Codex Desktop 的本機工作 | 須讓實際 MCP 執行環境取得環境變數；本機設定與插件來源仍須確認 | 同樣配置 HTTP server，先驗證 GUI 啟動時確實繼承憑證；不能假設 shell export 會進 GUI |
| Codex 遠端 executor／cloud 工作 | 執行環境與本機不同 | 不把本機環境設定推論為遠端支援；另外驗證 credential 注入與實際 client 版本 |
| Claude Code CLI／本機 Code runtime | HTTP、`headers`、`${VAR}` 展開、`headersHelper`、OAuth | CF 雙 header 可行；建議專用 `BOARD_CF_*` 變數 |
| Claude Desktop **Code** 本機工作 | Code runtime 與透過 claude.ai 注入的 connector 是不同設定來源 | 本機 HTTP server 須在 Code runtime 驗證；不可把 Chat custom connector 的配置套過來 |
| Claude Desktop Chat／claude.ai／Cowork remote custom connector | 由 Anthropic 雲端連線；OAuth，另有 Request headers 限量 beta | 不能預設可用 service token；需確認 beta 及 header 名稱批准狀態，否則改 OAuth 或先限 Code 支援 |
| Claude Desktop 舊 `claude_desktop_config.json` local MCP | 本機 stdio，與帳號 remote connector 不同 | 不能直接把 remote HTTP JSON 形狀當成此機制的支援證據；必要時另做受維護 bridge，非首選 |

Codex 的 `env_http_headers` 是 header 名對環境變數名的 mapping；OAuth 需獨立執行 `codex mcp login <name>`。官方也提供本機 `http_headers_helper`，但明確限制為本機 HTTP MCP，不能推論遠端 executor 適用。[OpenAI MCP 文件](https://developers.openai.com/codex/mcp)

Codex 設定僅包含變數名稱，例如：

```toml
[mcp_servers.board_remote]
url = "https://mcp.example.com/mcp"

[mcp_servers.board_remote.env_http_headers]
CF-Access-Client-Id = "BOARD_CF_CLIENT_ID"
CF-Access-Client-Secret = "BOARD_CF_CLIENT_SECRET"
```

Claude Code 的 `.mcp.json` 支援在 `headers` 使用 `${VAR}`；應避免把 shell 展開後的 secret 寫入 CLI 引數或設定檔。專用名稱可避開新版對 `ANTHROPIC_API_KEY`、雲端供應商憑證等名稱的保護性空值替換。OAuth 與代理雙重驗證的最新行為亦依 client 版本而異。[Claude Code MCP](https://code.claude.com/docs/en/mcp)

```json
{
  "mcpServers": {
    "board_remote": {
      "type": "http",
      "url": "https://mcp.example.com/mcp",
      "headers": {
        "CF-Access-Client-Id": "${BOARD_CF_CLIENT_ID}",
        "CF-Access-Client-Secret": "${BOARD_CF_CLIENT_SECRET}"
      }
    }
  }
}
```

**Claude custom connector 的最新限制必須保留：** Request headers 還在限量組織 beta，最多 4 個，自訂 header 名須經 Anthropic 批准；標準 `Authorization` 可用，但只有 beta 開通者才能設定。CF 若設定 single-header auth，`Authorization` 值必須是 CF 要求的 JSON，並非一般 `Bearer <secret>`。此路線仍不能避開 beta。Connector 憑證是帳號／組織共用服務身分，無法逐裝置歸因；輪替可能需移除重建 connector。未開通時不得用 Access Bypass 或公開無驗證 MCP 解決。[Claude Request headers](https://claude.com/docs/connectors/custom/add-unlisted)、[CF single-header service auth](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)

Chat／Cowork remote connector 的連線來源是 Anthropic 雲端，使用者電腦上的 VPN、loopback bridge、環境變數不會自動套用；Code tab 的本機設定則須獨立驗證。[Claude remote connectors 的網路需求](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp)

## OAuth 替代方案與必要決策

Cloudflare 在 2026-11-03 更新的 **Managed OAuth（Beta）** 可將 self-hosted application 變成標準 OAuth 入口：非瀏覽器請求收到 401 與 discovery，支援 DCR，需 RFC 8707 client；access token 是 opaque，edge 轉成給 origin 的簽章 assertion。這與一般 Access 瀏覽器登入及 service token 是三種不同流程。建議作為需要 Claude Chat 的第二階段評估，先核對帳號可用性及 client callback 設定，完成真實端對端後才承諾支援。[Managed OAuth](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/managed-oauth/)

若採 OAuth，建議另設 `mcp-oauth.example.com` application，人類身分 Allow + Managed OAuth，與 service-only 入口分開。只允許實際客戶端需要的 redirect URI；不盲目開任意 wildcard。OAuth discovery／callback 路徑位於 Cloudflare，不可被 source ingress 的 `/mcp` 規則誤擋。若帳號無 Managed OAuth，可選具正式 OAuth 2.1 能力的 gateway，但會新增 token storage、更新、稽核及故障面；現階段不自行拼裝 OAuth server。

`strict_service_token_auth` 能讓帶 service credential 的請求只按 Service Auth 驗證，失敗回 401／403，不轉登入頁；但這是 **Zero Trust organization 層級**，變更前需評估對既有應用的影響並取得授權。沒有啟用時仍以 MCP application 只有 Service Auth、沒有 Allow／Bypass 作基本邊界。[Strict service-token authentication](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)

## SSE、重連與時限

Repo 的 `SseEmitterRegistry` 已立即送第一個 heartbeat，此後每 15 秒送 heartbeat，事件在 AFTER_COMMIT 發布；預設最多 32 連線、每客戶端佇列 128。它適合少量多裝置共用，不代表可無限制增加瀏覽器頁籤。Proxy 必須即時 flush、不緩衝事件、不壓縮等待整包、不快取 REST／MCP／SSE；Access session 到期後重新登入並重建 EventSource。

Cloudflare connection limits 目前列 Proxy Read Timeout **125 秒**、Write Timeout 30 秒，並特別指示 Tunnel 使用者另外查 origin 設定；不要繼續引用過時的 100／120 秒，也不能把 125 秒當成所有 Tunnel 串流的固定總壽命。15 秒 heartbeat 是降低閒置逾時風險的設計推論，仍須真實 edge 測試至少數個逾時週期、斷網重連、登入到期及服務重啟。[Connection limits](https://developers.cloudflare.com/fundamentals/reference/connection-limits/)

HTTP/MCP timeout 後不能盲目重播寫入：請求可能已 commit，只是回應遺失。先重新列任務與狀態，再決定補救；claim token 若遺失，由 leader 按既有治理流程處理，不能把 token 印出或請使用者貼回。

## 持久化及故障取捨

H2 保留在雲端單一持久磁碟，使用絕對路徑；不放容器可拋棄 writable layer、不由多個 JVM 共寫、不以同步工具同步正在運行的資料庫。沿用 `H2SnapshotService` 的線上 `BACKUP TO`；既有預設每 6 小時一次，本次雲端範本改為每小時。僅有同機備份不足以涵蓋磁碟／VM／帳號遺失，建議每小時加密複製完整備份到不同故障域，每日核對新鮮度，定期在獨立實例還原演練。設定遠端備份前需要使用者選擇儲存供應商與資料所在地。

目標 RPO 為 2 小時（每小時快照加每小時離機同步，前提是兩者均成功；離機 job 尚待選定儲存後實作），RTO 須以還原演練實測，現階段不承諾數字。單一 VM 不是 HA；VM、磁碟、Tunnel、Access、DNS／IdP 均可造成停機。兩個 cloudflared connector 只增加 Tunnel 冗餘，不會讓同一份 H2 與應用變成多實例 HA。systemd 自動重啟、磁碟空間／備份失敗／readiness 告警及 graceful shutdown 可改善復原；升版前需快照及舊 jar 留存，schema 已遷移後不可直接假設舊 jar 能讀新 DB。需要零停機、多機寫入或更小 RPO 時，再改 PostgreSQL 與可跨實例的事件傳遞。

## 成本與供應商選項

價格為查證日的官方美元牌價，未含稅、網域、離機備份、超額流量及人力；不要以 Free Tier／試用折抵作長期預算。

| 方案 | VM 月費與資源 | 取捨 |
| --- | --- | --- |
| DigitalOcean Basic Regular | US$12：1 vCPU、2 GiB RAM、50 GiB SSD、2,000 GiB transfer | 入門建議；Java heap 需留 OS／cloudflared 餘裕，建置在別處完成 |
| DigitalOcean Basic Regular | US$24：2 vCPU、4 GiB RAM、80 GiB SSD、4,000 GiB transfer | 記憶體及升版餘裕較佳，工作量未量測前屬容量選項 |
| Amazon Lightsail Linux IPv4 | US$12：2 vCPU、2 GB RAM、60 GB SSD、3 TB transfer | 適合既有 AWS 帳號；區域與超額費需確認 |

來源：[DigitalOcean Droplet pricing](https://www.digitalocean.com/pricing/droplets)、[Lightsail pricing](https://aws.amazon.com/lightsail/pricing/)。建議 VM 預算 US$12–24／月，另預留離機備份及網域費；尚未壓測，不把 2 GB 當容量保證。

Cloudflare 官方仍提供 Zero Trust Free；其官方中小企業安全指南說明最多 50 位使用者及 24 小時 log retention，但該指南較舊，現行方案頁的動態比較表未在本次擷取完整呈現，**申請前須在實際帳號核對 seat、日誌保留、SLA 與功能可用性**，不能把 Free 視為企業 SLA。現行 account limits（2026-11-05 更新）列 service token 預設 50 個；這與 50 位人類 seat 是不同限制。[方案頁](https://www.cloudflare.com/plans/zero-trust-services/)、[官方中小企業指南](https://cf-assets.www.cloudflare.com/slt3lc6tev37/7oEleWnoR1ggS7GfMUb3oO/bc05cd36279b0e4bc5fcc942f2a3264e/Cloudflare-security-guide-for-small-and-medium-enterprises.pdf)、[Account limits](https://developers.cloudflare.com/cloudflare-one/account-limits/)

## Claude skills 路徑查證

官方仍列個人 `~/.claude/skills/<name>/SKILL.md`、專案 `.claude/skills/<name>/SKILL.md`、插件 `<plugin>/skills/<name>/SKILL.md`；沒有要求把 Claude 本機 skills 全部搬到 `.agents/skills`。帳號同步新增 `~/.claude/skills/synced/` 受管理目錄，會被後續同步覆寫，不應直接修改。Cloud／Cowork 不會直接讀取本機個人 skills；須在帳號啟用或使用對應的 cloud 支援來源。[Claude skills](https://code.claude.com/docs/en/skills)

使用者指定的 `gpt-5.6-sol` → `gpt-6.1-sol`、`gpt-5.6-terra` → `gpt-6.1-terra` 是 repo skill 的明確維護要求，與本次雲端來源無關；應更新受版本控制的 skill 正本與既有同步副本，檢查兩者一致，不修改受管理快取中的單份檔案來假裝永久更新。

## 上線前仍需決定與實際驗收

需決定：VM 供應商／區域／US$12 或 24 級距、既有 Cloudflare zone 與 hostname、允許登入的使用者／IdP、是否必須支援 Chat custom connectors、離機備份供應商及保留政策、是否將本機資料遷移到雲端。資料遷移不可在本次研究中讀取或複製本機正式 DB。

在使用者同意建立資源後，以全新雲端測試資料驗收：無憑證／錯憑證／撤銷 token 拒絕；web cookie 無法寫 MCP；MCP credential 無法讀其他路徑；MCP initialize/list/read/write、雙 worker 競爭 CAS、相依阻擋、SSE 更新與 15 秒 heartbeat、斷網重連、重啟後資料保留、從離機備份還原、源站公開 IP／IPv6／替代 hostname 無法繞過、log 不含憑證。真實 Cloudflare／Codex／Claude 未驗證前，交付狀態應標示「部署素材與隔離本機驗證完成，雲端驗收待授權」。

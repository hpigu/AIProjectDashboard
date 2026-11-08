# Workers + D1：免費單人共用看板

這是 Java/H2 版本之外的 Workers 後端移植；瀏覽器仍使用 repo 既有零建置 Vue 與
vendor 字型。兩個 Worker 共用一個 D1，不需要 VM、Tunnel、本機常駐伺服器、
Durable Objects 或 R2。`workers/` 的 npm 僅用於後端開發與驗證，不引入前端建置。
這份文件說明架構與操作流程；實際部署進度見 [雲端部署狀態](cloud-rollout.md)。
本機正式看板資料未匯入雲端。

## 入口與驗證

| 入口 | 範例 | 認證與權限 |
|---|---|---|
| web Worker | `ai-project-board-web.<account>.workers.dev` | Access 人類登入；Worker 驗證 JWT 的簽章、issuer、audience、期限與唯一 OWNER_EMAIL；只開唯讀 UI/REST |
| MCP Worker | `ai-project-board-mcp.<account>.workers.dev/mcp` | 每裝置獨立隨機 Bearer token；Worker 比對 SHA-256 hash，只有 `/mcp` |
| 儲存 | 同一個 D1 `DB` binding | 兩個 Worker 共同資料；本機 `database_id` 僅為 placeholder |

web 的 `CLOUDFLARE_ACCESS_TEAM` 是 Zero Trust team **slug**，不是含協定的 URL。
`CLOUDFLARE_ACCESS_AUD` 指定 web app 的 AUD，`OWNER_EMAIL` 是唯一允許的帳號。
Access policy 也只 Allow 此人，IdP 開 MFA。缺設定、錯 audience、錯 email 或過期 JWT
一律拒絕；不能把存在 `Cf-Access-Jwt-Assertion` header 當成已驗證。

`assets.run_worker_first: true` 必須保留，所有靜態檔案也先經 Worker 驗證，不可改成
預設的 asset-first 或只放 `/api/*`。Worker 只有在驗證成功後才呼叫 `ASSETS.fetch`。
web 與 MCP 分別用 `wrangler.jsonc`、`wrangler.mcp.jsonc`；MCP 設定完全不含 assets，
避免 Wrangler environment 繼承 web 的靜態資源配置。
[官方靜態資源路由](https://developers.cloudflare.com/workers/static-assets/routing/worker-script/)

MCP 不是 Cloudflare Access service token，也不是 OAuth。`MACHINE_TOKEN_HASHES`
是 MCP Worker secret，內容格式為 `{"device-name":"sha256-hex"}`。各裝置原始 token
只能存於該裝置 OS credential store/密碼管理器；使用至少 32 bytes 密碼學隨機值，
計算 hash 後僅將 hash JSON 設成 Worker secret。不要讓 agent 列印原值或把它寫進
repo、對話、日誌、task log、CLI 引數、shell history。hash 也維持 secret binding，
不放 `wrangler.jsonc`；雲端環境未設定或空集合會拒絕全部機器請求。

每個裝置可用不同 token，遺失時只移除對應 hash；輪替採短暫並存新舊 hash、更新
客戶端後撤銷舊值。此設定授予全部 MCP 工具，沒有逐專案或 leader/worker RBAC，
只適用同一位可信使用者的裝置；claim token 仍只保護任務生命週期。

Codex 本機設定可使用：

```toml
[mcp_servers.board]
url = "https://ai-project-board-mcp.ACCOUNT.workers.dev/mcp"
bearer_token_env_var = "BOARD_MACHINE_TOKEN"
```

Claude Code 的 HTTP MCP 設定可透過環境變數展開：

```json
{"mcpServers":{"board":{"type":"http","url":"https://ai-project-board-mcp.ACCOUNT.workers.dev/mcp","headers":{"Authorization":"Bearer ${BOARD_MACHINE_TOKEN}"}}}}
```

上述是 MCP-only 設定，不會自動修改已安裝 plugin 中的 localhost connector。
先確認實際生效的 endpoint，避免同時存在兩個 `board` 而誤寫本機看板。
Claude plugin 的角色薄殼有 plugin 專屬工具白名單；不能假設它會自動改用手動新增的
MCP server。此版本先驗收直接 MCP 連線，保留 plugin 派工時須另確認工具命名與來源。

GUI 與遠端 executor 不一定繼承本機 shell 的環境；要逐一驗證實際執行位置。Claude
Chat/Cowork remote custom connector 的自訂 header 有組織 beta 限制；此版本未實作
OAuth，不承諾那些入口可連線。詳見[客戶端研究](cloud-research.md#客戶端能力矩陣)。
不要用移除驗證來解決不相容。

### macOS 本機客戶端：使用 Keychain helper

最新官方文件提供 Codex `http_headers_helper` 與 Claude Code `headersHelper`，
可以從 macOS Keychain 在連線時供應 Authorization，不必把原始 token 放進設定檔，
也不必依賴 GUI 繼承 shell 環境。這次已實測 Codex CLI 0.157.1 的 app-server 取得
16 個工具，Claude Code 2.1.229 的 `mcp get` 回報 Connected。
[Codex 官方 MCP 文件](https://learn.chatgpt.com/docs/extend/mcp)、
[Claude Code dynamic headers](https://code.claude.com/docs/en/mcp#use-dynamic-headers-for-custom-authentication)

`workers/scripts/device-credential.mjs` 的 `init SERVICE ACCOUNT` 會建立 32 bytes
隨機 token，只透過 `security -i -q` 的 stdin 存進 login Keychain。它拒絕覆寫既有
項目；不使用 `-U`、`-A`、`-v`、PTY、明文檔或將 token 放在程序引數。讀取遭拒絕
就停止，沒有明文備援。macOS 若詢問存取權限，由使用者自行確認。

本機已將 helper 複製至 `~/.local/share/ai-project-board/device-credential.mjs`；
Codex 與 Claude Code 均新增 `board-cloud` 使用者範圍設定，各自使用獨立 Keychain
account。只有命令與 URL 在設定檔中，沒有 token。既有 plugin 的本機 connector
沒有切換，日常操作請明確選 `board-cloud`；既有角色薄殼不會自動改用此名稱。

可重用的設定格式如下，命令中的 Node 與 script 路徑要替換成該裝置的實際路徑：

```toml
[mcp_servers.board-cloud]
url = "https://YOUR-MCP-WORKER.workers.dev/mcp"
http_headers_helper = "/ABSOLUTE/PATH/node /ABSOLUTE/PATH/device-credential.mjs headers ai-project-board.production DEVICE_CODEX"
startup_timeout_sec = 30
```

```json
{"mcpServers":{"board-cloud":{"type":"http","url":"https://YOUR-MCP-WORKER.workers.dev/mcp","headersHelper":"/ABSOLUTE/PATH/node /ABSOLUTE/PATH/device-credential.mjs headers ai-project-board.production DEVICE_CLAUDE"}}}
```

`headers` 只供客戶端私有管線捕捉，會輸出含原始 token 的 JSON，**不得手動執行、
tee、重導檔案或貼到對話**；它會拒絕直接輸出至終端機，但這不是防止所有不當
重導的資料外洩機制。`hash-map SERVICE ACCOUNT DEVICE_ID` 只輸出 hash JSON，
也只應經私有 pipe 傳給 secret 管理程序；該輸出是一個裝置的完整 map，**不能直接
覆蓋已含其他裝置的遠端 map**。更新 `MACHINE_TOKEN_HASHES` 前須在記憶體合併所有
仍有效裝置的 hash。`run SERVICE ACCOUNT -- TRUSTED_COMMAND ...` 可用於舊版
客戶端的環境變數模式，只啟動可信程式，不可用 `env`、`printenv` 或除錯輸出消費它。

這兩個 helper 選項皆是客戶端能力，不能推論 Claude Chat/Cowork 或遠端 executor
也支援。Codex 官方明列 helper 僅適用本機 HTTP MCP；若舊版不支援，先確認版本或
用前述環境變數方式，不移除伺服器驗證。

### 首次 Access 與 MFA 設定

保護目標是看板 web Worker，不是 Cloudflare 管理控制台；MCP Worker 保留獨立
Bearer 認證，不套用瀏覽器登入規則。只使用完整 owner email，不用 email domain
或 Everyone 規則。這次 web 應用程式設定為生產與預覽 URL 均受保護，預覽 URL
仍維持停用；應用程式與 MFA 工作階段均為 24 小時。

Independent MFA 先在 Access 設定中允許生物辨識及驗證器 App，再只對看板應用程式
設定 Custom MFA；不要把「預設套用全域 MFA」打開而影響其他應用程式。
MFA 裝置需由使用者在 App Launcher 的 Account 頁自行新增，agent 不讀取 QR code、
驗證器密鑰或復原碼。App Launcher 本身也須關聯僅允許 owner email 的規則，並避免
要求尚未註冊的 MFA 造成註冊死結；此次入口的登入工作階段為 6 小時。

若 Launcher 顯示 NoApps/contact administrator，先查看右上 Account → Add an MFA
device；Worker 範圍的應用程式未出現在 Launcher 清單，不等於看板存取遭拒絕。
不要點 Go to Dashboard 把 Cloudflare 控制台當成看板。
[官方 Independent MFA](https://developers.cloudflare.com/cloudflare-one/access-controls/policies/mfa-requirements/)、
[App Launcher](https://developers.cloudflare.com/cloudflare-one/access-controls/access-settings/app-launcher/)

## 本機工具與驗證

固定 dev tooling 版本記錄於 `workers/package.json` 與 lockfile，Node.js 需至少 22。
Wrangler 4.158.0、Miniflare 5.20261104.0-beta（與該 Wrangler 自身依賴一致）、
esbuild 0.28.2、MCP SDK 1.32.2；compatibility date 固定 2026-11-04，
避免超過所安裝 workerd 的行為日期。SDK 與打包器也都是 devDependencies。
Wrangler 與 Miniflare 使用官方 workerd/D1 本機模擬；版本資料來自官方 npm registry。
不使用已 deprecated 的 `unstable_dev`，測試直接操作 Miniflare；v5 的設定介面與
舊版不同，可用其 `convertV4MiniflareOptions` 轉換本機測試 options。
本機測試使用 v5 的 `resourcePersistencePath`，不是已失效的 `d1Persist`；所有測試
均以暫存目錄與正確的持久化設定啟動。設定另啟用 `enable_request_signal`，讓 SSE
在用戶端離線時清除輪詢，避免無效查詢繼續消耗配額。
[Wrangler npm](https://www.npmjs.com/package/wrangler)、[Miniflare npm](https://www.npmjs.com/package/miniflare)

以下只在 `workers/` 執行，npm scripts 沒有遠端部署指令：

```bash
cd workers
npm ci --ignore-scripts
npm run db:migrate:local
npm test
npm run build:local
```

`db:migrate:local` 明確帶 `--local`；`build:local` 只呼叫
`wrangler deploy --dry-run --outdir ...`，不會發布。runner 會清除程序環境中的
Cloudflare 管理憑證與關閉 Wrangler metrics，不讀取雲端 DB。設定中的 `remote:false`
不可改成 true；不使用 `wrangler dev --remote` 或 `--temporary` 發布捷徑。

需要人工操作時，各用一個 terminal：

```bash
npm run dev:web
npm run dev:mcp
```

兩者明確使用 `--local --ip 127.0.0.1`、18787/18788 與同一個
`workers/.wrangler/state`。D1 migration、dev 與資料檢查都要使用相同 persist path，
不然會誤接空白資料庫。`workers/.wrangler`、`node_modules`、`.dev.vars*` 均被忽略。
沒有使用正式 8080、H2、主工作區 target 或正式程序。
[本機 D1](https://developers.cloudflare.com/d1/best-practices/local-development/)、
[local persistence](https://developers.cloudflare.com/workers/local-development/local-data/)

沒有 Access JWT 的本機 web 回應拒絕是預期行為，不能用預設開發 bypass 省略驗證。
整合測試以測試專用金鑰、記憶體 token 與隔離 D1 驗證；不使用真實 owner、token 或
Cloudflare 金鑰。Miniflare 重建後使用同一個資料目錄驗證持久化；這不是雲端災難復原。

2026-11-08 本機驗證結果：

| 檢查 | 結果 |
|---|---|
| Workers 測試套件 | 29 項通過，涵蓋 16 工具、安全邊界、版本衝突、相依、claim token 與完成證據 |
| 真正 workerd + MCP SDK | 連線協商、讀寫、併發單贏、50 筆批次、D1 失敗整批回滾均通過 |
| 登入與即時更新 | 真 RSA JWT、錯 owner/audience/到期拒絕、靜態檔保護及跨 Worker SSE 通過 |
| 持久化與還原 | runtime 重啟保留狀態；官方 Wrangler schema/data 匯出後在另一個 D1 還原通過 |
| 部署素材 | web/MCP 分開設定均 dry-run 成功；實際 local migration 成功；MCP 未綁定靜態檔 |

最後修正本機持久化與原生匯出路徑後，另重跑實際 runtime/還原相關 7 項均通過。
所有測試僅使用 loopback 與隔離資料，未操作正式 H2、8080 或主工作區 target。
Cloudflare 真實 Access 登入、各裝置 Codex/Claude、workers.dev BIC、Free CPU 與
額度、遠端 Time Travel/還原仍是部署後的必要驗收，不把本機結果當成已上線。

## 免費額度與已知限制

查核日期 2026-11-08。保持 **Workers Free**，不升級 Paid，不啟用付費儲存或
observability。範本不設定 Paid 的 `limits.cpu_ms`；實際 Free CPU 限制仍適用。

| 項目 | 官方 Free 額度 | 影響 |
|---|---|---|
| Worker 請求 | 全帳號每天 100,000，UTC 00:00 重設 | web/MCP 及其他 Worker 共用；asset 經 run_worker_first 也會執行 Worker |
| CPU | 每請求 10 ms | JWT 驗證、大查詢與批次需實測；本機不等於真 Free CPU 限制 |
| D1 讀取 | 每天 500 萬 rows read | 掃描行數而非請求數；須有索引與合理刷新頻率 |
| D1 寫入 | 每天 100,000 rows written | task、log 與索引維護皆有成本 |
| D1 容量 | 每 DB 500 MB、帳號共 5 GB、最多 10 DB | 到限先停止寫入/整理方案，不自動升級 |
| Time Travel | 最近 7 天 | 不取代跨帳號離機備份 |

超額會錯誤或拒絕服務；這是選擇免費方案的可用性取捨，程式不會自動購買或升級。
Cloudflare 若調整方案則需重新核對；第三方 IdP、網域或額外備份可能另收費。
[Workers limits](https://developers.cloudflare.com/workers/platform/limits/)、
[D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/)、
[D1 limits](https://developers.cloudflare.com/d1/platform/limits/)

`workers.dev` 免購網域，但所有請求會經無法自行關閉的 Browser Integrity Check；
部分預設 library User-Agent 可能在 Worker 執行前收到 1010。客戶端應送具描述性的
User-Agent；若 SDK 不允許調整或仍被擋，需另選自有網域並取得使用者同意，不能
宣稱所有 MCP 客戶端在 workers.dev 必然可用。
[官方 workers.dev BIC 限制](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/#browser-integrity-check-on-workersdev)

Workers 是無常駐實例的 runtime，不能沿用 JVM 行程內的 SSE 訂閱狀態來假裝跨裝置
廣播；即時更新須以 D1 持久狀態與前端重新同步為準。驗收應確認狀態更新延遲、
重連/重新整理後與 REST 一致，不能把本機單一 isolate 的結果當成全球一致性保證。
目前每條 SSE 每 5 秒增量查 outbox、約 50 秒重連一次；空閒連線約每分鐘 12 次查詢，
每個持續開啟的分頁每天約 1,728 次連線請求，另有重連後 REST 同步。資料與分頁數
增加會提高用量；本機測試不會強制真實 Free 的 10ms CPU 限制，上線前仍要量測。
事件紀錄目前不會自動清除，需監控 D1 容量；資料清理或付費升級須另行決定。

## 尚待同意後的部署順序

下列涉及雲端寫入，每次部署前須取得使用者明確部署授權。不以本機 runner
代替審核，也不提供一鍵自動部署腳本。2026-11-08 已獲首次建立兩個 Worker、
一個 D1 與 Access 設定的授權，執行進度另記於 [雲端部署狀態](cloud-rollout.md)。
實際環境使用被 Git 忽略的 `workers/wrangler.production.web.jsonc` 與
`workers/wrangler.production.mcp.jsonc`，範本保留 placeholder。下列命令若用於這次
實際部署，須明確傳入對應正式設定檔，不能用 placeholder 範本操作。

Access Free 的實際開通頁要求付款資料、接受服務條款，並列出超額用量扣款授權。
「基礎月費為零」不等於硬性禁止帳單；不得代使用者接受超額扣款條款。由使用者
親自審閱與決定，不能承諾綁卡後一定不會扣款，也不能為避開此步驟而公開網頁。

1. 確認帳號維持 Free、owner email、Access team、登入 IdP 與 data jurisdiction，
   確認接受 workers.dev BIC 與 Free 超額停用風險。建立 D1 前選定可用 location hint。
2. 執行 `npx wrangler d1 create ai-project-board`，把取得的實際 database UUID 同時
   填入 `wrangler.jsonc` 與 `wrangler.mcp.jsonc`，兩者必須相同。此時不要把本機 H2 檔案上傳 D1。
3. 審查 SQL migrations，先跑本機驗收，之後才執行
   `npx wrangler d1 migrations apply DB --remote`。這會寫雲端資料庫。
4. 設定 web Access application（whole-host），只有 owner email Allow，記下 AUD。
   填入 web vars；先維持不公開或拒絕全部請求，避免配置期間開放資料。
5. 用不落地原始 token 的安全流程為每個裝置生成憑證，僅把 hash JSON 經互動 secret
   輸入設成 `npx wrangler secret put MACHINE_TOKEN_HASHES --config wrangler.mcp.jsonc`。不要把值當參數。
6. 人工審查兩個 Wrangler 設定檔的服務名、DB UUID、owner、team/AUD、
   `run_worker_first:true`、`observability:false`、preview 關閉及 Free plan。
7. 依次明確同意後執行 `npx wrangler deploy --config wrangler.jsonc`、
   `npx wrangler deploy --config wrangler.mcp.jsonc`。
   這兩條沒有 `--dry-run`，會建立/更新服務。不得讓 CI 或 agent 自動執行。
8. 逐台驗收匿名與錯誤身份拒絕、靜態資源不可繞驗證、Bearer 撤銷、MCP tools/list、
   讀寫/依賴/併發認領、UI 更新與重連、跨裝置相同資料。關掉個人電腦再確認可用。
9. 驗收完成才改日常客戶端 URL。本機正式看板與資料保留，不默默搬資料或刪驗收任務。

## 備份、還原與更新

不用 H2 backup 腳本操作 D1。D1 Time Travel Free 保留 7 天；另安排人工/外部排程
定期將 SQL export 存到加密離機位置。未選備份位置前不宣稱已有離機備份，也不默默
建立 R2。應保留 release/config/schema 對照、備份時間與 checksum，避免只保存資料。

匯出前停止所有 MCP 寫入與 migration，將同一次維護時段的 schema/data 配成一組，
分別記錄 checksum。不能拿尚有寫入或 migration 的兩個不同時間點檔案混用。
以下 `--local` 只讀本機測試 DB：

```bash
cd workers
npx wrangler d1 export DB --local --config wrangler.jsonc --no-data --output /private/tmp/board-schema.sql
npx wrangler d1 export DB --local --config wrangler.jsonc --no-schema --output /private/tmp/board-data.sql
```

目前固定的 Wrangler 版本中，`d1 export` 沒有 `--persist-to` 選項，會使用設定檔旁的
`.wrangler/state`；這與本機 runner 的固定路徑相同。自訂測試儲存路徑時，必須使用
相對應的隔離設定檔，不可假設 export 會讀取任意 persist-to 目錄。

以下涉及雲端資料讀取/寫入，僅作操作手冊，**目前未執行**。遠端 export 也會將專案
機密寫入指定檔案，須先取得授權、使用 repo 外受限制目錄，不能把結果輸出到對話：

```bash
npx wrangler d1 export DB --remote --no-data --output /SECURE_PATH/board-schema.sql
npx wrangler d1 export DB --remote --no-schema --output /SECURE_PATH/board-data.sql
npx wrangler d1 time-travel info ai-project-board
# 恢復會回滾現在資料，必須先暫停所有 MCP 寫入、另存當前快照並取得明確同意：
npx wrangler d1 time-travel restore ai-project-board --timestamp 2026-11-08T00:00:00Z
```

上述 timestamp 是語法範例，不能不經選擇就照用。先選定事故前時間/書籤，查看
info，確認回復會丟失哪些寫入，還原後驗證 migration、專案/任務/role 與 MCP 認領。
不要在不同 app 版本同時寫入時 restore。
[Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/)

SQL export 還原建議先建立**另一個**驗證 DB，匯入而不是覆蓋正式資料。
務必先載入完整 schema，再載入 data；task 與 blocker 有循環外鍵，合併的單一 dump
若交錯 CREATE/INSERT，可能因被參照的表尚未建立而失敗。保留 data export 開頭的
`PRAGMA defer_foreign_keys=TRUE;`，不要關閉或移除外鍵保護。

```bash
# 只有取得建立驗證資源與寫入授權後執行：
npx wrangler d1 create ai-project-board-restore-check
npx wrangler d1 execute ai-project-board-restore-check --remote --file /SECURE_PATH/board-schema.sql
npx wrangler d1 execute ai-project-board-restore-check --remote --file /SECURE_PATH/board-data.sql
```

建立隔離 Worker 綁定還原 DB，配置同樣驗證邊界，確認資料與全部 lifecycle 驗收後
再決定是否切換。切換 DB UUID、刪驗證 DB 或刪舊資料都需另取明確同意。
本機已用官方 Wrangler 匯出／匯入至獨立 D1，包含非空 blocker 循環外鍵並核對
來源與還原資料；真實遠端 export/restore 仍需在獲准後演練。
[官方 import/export](https://developers.cloudflare.com/d1/best-practices/import-export-data/)

更新先備份與記錄現行 Worker version，再對本機 D1 套 migrations、跑測試與 dry-run。
正式 migrations/發布前仍需同意。程式回退可使用 Cloudflare versions/rollback，但
schema 已變更時不能假設舊 Worker 可讀新 DB；須確認相容或搭配選定備份還原。
Workers 不需手動重啟服務；重新部署/隔離 instance 都不應讓資料消失，因為權威資料
在 D1。雲端故障、配額耗盡或 Access/JWK 取不到時應拒絕服務，不放寬授權。

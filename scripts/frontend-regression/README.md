# 前端手動回歸驗證工具（非 CI，非建置流程）

## 這是什麼

#126 曾用瀏覽器自動化（Chrome DevTools Protocol，簡稱 CDP）手動跑過 87 項前端
行為驗證（RWD、鍵盤焦點、URL state、篩選、相依圖、SSE 重連、CSP、離線載入），
全數通過，但腳本只存在該次工作階段的臨時目錄，沒有進 repo，事後無法重跑。

`check.mjs` 是 #140 重建的等價工具：只用 Node.js 內建模組（`node:child_process`、
`node:http`）啟動本機 Chrome 並透過 CDP WebSocket 協定操控，**不使用任何 npm 套件
（不執行 `npm install`，沒有 `package.json`）**，因此定位為「需要時手動執行的
診斷工具」，不進 `src/test`、不掛進 `./mvnw test`、不是 CI 的必要條件、
不影響零建置 Vue 3 CDN 架構。

## 為什麼不放進 Maven 測試生命週期

`AGENTS.md` 明訂維持零建置 Vue 3 CDN、不引入 npm/Vite/SFC/TypeScript。這個腳本
需要本機已安裝 Chrome/Chromium 且透過 `--remote-debugging-port` 啟動，這類環境
依賴不適合放進 `./mvnw test`（會讓沒裝 Chrome 的機器/CI 容器整套測試失敗）。
已能在伺服器端驗證的部分（CSP header、零跨 origin 資源、SSE 端點可達性）已寫成
JUnit 測試，見 `src/test/java/dev/aiboard/web/FrontendStaticAssetsTest.java` 與
`src/test/java/dev/aiboard/config/SecurityHeadersFilterTest.java`、
`OperationalSafetyConfigurationTest`。本腳本只補「必須真的用瀏覽器渲染、量測、
互動」才能驗證的那一小部分。

## 何時該重跑

不是每次改動都需要。建議在以下情況手動跑一次：

- 大幅改動 `app.js` 的版面、CSS breakpoint、或詳情面板的 focus 管理邏輯
- 改動 `SecurityHeadersFilter` 的 CSP 設定
- 改動 SSE 重連相關程式碼（`EventStreamController`、`SseEmitterRegistry`、
  `app.js` 內的 `EventSource` 處理）
- 發版前的手動抽查

日常小改動（文字調整、單一 API 欄位）不需要跑，改看 `docs/frontend-regression-checklist.md`
挑對應項目手動檢查即可。

## 使用方式

前置需求：本機已安裝 Chrome 或 Chromium，且 `node` 可用（v18+，用到
`fetch`/`node:test` 皆為內建）。

```bash
# 1. 用開發用埠號與資料庫啟動看板（比照 AGENTS.md，不得用 8080/data/board）
BOARD_PORT=8091 BOARD_DB_URL=jdbc:h2:file:./data/dev-qa-next4 \
  java -jar target/ai-project-board-backend-*.jar &

# 2. 啟動 Chrome headless 並開放 CDP 除錯埠
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --headless=new --remote-debugging-port=9222 --no-first-run about:blank &

# 3. 執行驗證腳本，指向看板網址
node scripts/frontend-regression/check.mjs --base-url http://127.0.0.1:8091
```

腳本會依序執行各項檢查並印出 PASS/FAIL，非零 exit code 代表至少一項失敗。

## 涵蓋範圍對照（原 87 項 vs. 本腳本 vs. JUnit 測試）

| 驗證項目 | 伺服器端 JUnit（已在 repo） | 本腳本（手動執行） | 純人工檢查清單 |
| --- | --- | --- | --- |
| CSP header 存在且內容正確 | `SecurityHeadersFilterTest` | - | - |
| 零跨 origin 資源（vendor/字型/CSS 皆同源） | `OperationalSafetyConfigurationTest`、`FrontendStaticAssetsTest` | CSP violation 事件監聽（雙重確認） | - |
| API/vendor 資源可實際同源取得 | `FrontendStaticAssetsTest` | - | - |
| SSE 端點可達、回傳正確 content-type | `SecurityHeadersFilterTest` | 斷線/重連情境 | - |
| RWD 三尺寸無水平溢出 | - | `checkNoHorizontalOverflow()` | 極端內容（超長 title 等）建議人工複查渲染 |
| console 無錯誤 | - | `checkNoConsoleErrors()` | - |
| Escape 關閉詳情並歸還焦點 | - | `checkEscapeReturnsFocus()` | - |
| URL 篩選狀態同步與 reload 還原 | - | `checkFilterUrlRoundTrip()` | - |
| SSE kill/restart 後重連且狀態保留 | - | 需要手動重啟後端，腳本僅檢測前端重連 UI 狀態 | 實際 kill/restart 後端程序需人工操作 |
| 初次載入各 API 僅呼叫 1 次 | - | `checkApiCallCounts()`（Network 事件計數） | - |
| DAG／大型相依圖展開收合 | - | 需要視覺比對 | 建議人工複查 |

## 已知限制

- 這是骨架與範例，涵蓋 #126 驗證項目中最容易腳本化的部分；DAG 版面視覺正確性、
  極端內容的實際渲染美觀程度仍需人工判斷。
- 未內建 CI 執行；沒有人主動跑就不會發現回歸。這是方案 C 的已知取捨，
  詳見 #140 的方案評估。

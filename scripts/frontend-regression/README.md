# Frontend browser regression tool

## Purpose

`check.mjs` drives a local Chrome or Chromium instance through the Chrome DevTools
Protocol. It uses only Node.js built-in modules: there is no `package.json`, npm
install, or frontend build step. Run it manually when a change needs real browser
rendering or interaction.

## Test boundary

The script requires a locally installed browser and a remote-debugging port, so it
is not part of `./mvnw test` or CI. Server-side checks remain in
`FrontendStaticAssetsTest`, `SecurityHeadersFilterTest`, and
`OperationalSafetyConfigurationTest`.

## When to run it

- 大幅改動 `app.js` 的版面、CSS breakpoint、或詳情面板的 focus 管理邏輯
- 改動 `SecurityHeadersFilter` 的 CSP 設定
- 改動 SSE 重連相關程式碼（`EventStreamController`、`SseEmitterRegistry`、
  `app.js` 內的 `EventSource` 處理）
- 發版前的手動抽查

For smaller changes, use `docs/frontend-regression-checklist.md` and select only
the relevant checks.

## Run

Requirements: Chrome or Chromium and Node.js 18 or newer.

```bash
# Use an isolated development port and database. Never use 8080 or data/board.
BOARD_PORT=8091 BOARD_DB_URL=jdbc:h2:file:./data/dev-qa-next4 \
  java -jar target/ai-project-board-backend-*.jar &

# Start Chrome with a CDP port.
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --headless=new --remote-debugging-port=9222 --no-first-run about:blank &

# Run the checks.
node scripts/frontend-regression/check.mjs --base-url http://127.0.0.1:8091
```

The script prints PASS/FAIL for each check and exits non-zero on failure.

## Coverage

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
| i18n 字典 key parity／插值 placeholder 一致／無空值/未翻譯殘留 | `I18nDictionaryTest` | - | - |
| app.js／index.html 靜態 `t('key')` 呼叫皆指向存在的 key | `I18nDictionaryTest` | - | - |
| 手動切換語言即時更新 html lang、reload 後保留 | - | `checkManualSwitchUpdatesHtmlLangAndPersists()` | - |
| 不支援的 stored locale（如殘留舊值）fallback 到 zh-TW | - | `checkUnsupportedStoredLocaleFallsBackToDefault()` | - |
| 無 stored 偏好時依 `navigator.languages` 偵測（含 zh-CN 等變體 fallback） | - | `checkBrowserLanguageDetectionWithoutStoredPreference()` | - |
| 實際渲染畫面無 raw key fallback（`⚠key`） | - | `checkNoRawKeyFallbackVisibleInRenderedDom()` | 更換語言後人工掃視畫面文字建議一併檢查 |
| 插值（`{n}`／`{title}` 等）正確代入、不殘留樣板字面值 | - | `checkInterpolationRendersActualValues()` | - |
| 主要看板／詳情／空態／錯誤／確認流程雙語下文案完整可讀 | - | - | 見 `docs/frontend-regression-checklist.md` 的 i18n 一節 |

## Limits

- DAG layout quality and extreme-content rendering still require visual review.
- The script runs only when invoked manually.

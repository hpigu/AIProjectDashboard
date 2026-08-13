# 前端手動回歸清單

## 背景

`AGENTS.md` 規定維持零建置 Vue 3（vendor 資源，不引入
npm/Vite/SFC/TypeScript）。前端
（`src/main/resources/static/**`）沒有元件框架層級的單元測試基礎設施，因此前端
的回歸保護分三層，依可自動化程度由高到低：

1. **JUnit（`src/test/java`，隨 `./mvnw test` 自動跑，CI 必要）**——能在伺服器端
   驗證的部分：CSP header、零跨 origin 靜態資源引用、靜態資源可實際同源取得。
   見 `SecurityHeadersFilterTest`、`OperationalSafetyConfigurationTest`、
   `FrontendStaticAssetsTest`。
2. **`scripts/frontend-regression/check.mjs`（手動執行，非 CI）**——需要真的用
   瀏覽器渲染才能驗證的部分：RWD 三尺寸無水平溢出、console 無錯誤、無 CSP
   violation、零跨 origin 請求（執行期二次確認）。見該目錄 `README.md`。
3. **本清單（純人工）**——需要人判斷「畫面對不對」、或需要手動操作（重啟後端
   程序）才能驗證的部分。

## 使用方式

改動前端後，依改動範圍挑選對應項目人工檢查。用開發用埠號/資料庫啟動
（參考 `AGENTS.md` 的「開發用埠號與資料庫（必讀）」），**不得使用正式 `:8080`
與 `data/board`**。

## 清單

### 版面與 RWD

- [ ] 桌面（約 1440×900）／平板（768×1024）／手機（375×812）三種尺寸下，
      放入極長 title（100+ 字）、極長 description、極長 URL 的任務卡片，
      確認文字換行或截斷正常、無版面撐破
- [ ] 37 節點以上的大型相依圖在三種尺寸下可正常捲動/縮放檢視，節點不重疊到
      無法辨識

### 鍵盤與焦點

- [ ] 開啟任務詳情面板 → 按 Escape → 面板關閉，焦點回到原本觸發開啟的元素
      （用 Tab 確認下一個可聚焦元素是原開啟者之後的元素，而非跳回頁首）

### 篩選與 URL State

- [ ] project 名稱前綴搜尋、category／assignee／claimable 篩選，網址列的
      query string 隨篩選即時更新
- [ ] 帶著篩選 query string 的網址整頁 reload，篩選狀態與畫面正確還原

### History／Evidence 渲染

- [ ] 有完整多筆狀態變更歷史的任務，History 區塊按時間序完整顯示
- [ ] BLOCKED 任務的 evidence（含 `blockingTasks`）正確渲染，連結/文字可讀
- [ ] DONE 任務的結構化 evidence（`complete_task` 產生的欄位）正確渲染

### 相依圖

- [ ] DAG 呈現正確的邊方向（前置 → 依賴）
- [ ] 孤立節點（無相依）正常顯示、不影響版面
- [ ] 展開/收合互動正常，category 篩選能正確隱藏/顯示對應節點與邊

### SSE 即時更新

- [ ] 看板開著時，另開一個視窗完成/認領任務，畫面即時更新（不需重整）
- [ ] **手動 kill 後端程序、間隔數秒後重啟**（用同一組隔離 port/DB），確認
      前端連線狀態指示從「重新連線中」恢復為「連線中」，且恢復後**不遺失
      使用者當下的 filter/URL 狀態**、**不需整頁重整**
      （此項腳本只能觀察前端 UI 反應，實際 kill/restart 動作必須人工執行）

### 網路行為

- [ ] 開啟瀏覽器 DevTools Network 分頁，清空後整頁載入一次，確認每個 REST
      API（`/api/projects`、`/api/projects/{id}/board` 等）**恰好呼叫一次**，
      沒有重複請求（常見肇因：`onMounted` 與某個 `watch` 同時觸發載入）

## 已被自動化涵蓋、不需再人工檢查的項目

以下項目已有 JUnit 測試常駐 CI，除非測試本身失敗，不需要再人工複查：

- CSP header 存在且內容正確（`SecurityHeadersFilterTest`）
- index.html/app.js/CSS 不含任何 `http(s)://` 外部資源引用
  （`OperationalSafetyConfigurationTest`）
- vendor Vue/字型檔案與 app.js/CSS 皆可透過同源相對路徑實際以 HTTP 取得
  （`FrontendStaticAssetsTest`）
- app.js 內所有 API 呼叫與 SSE 連線目標皆為 `/api` 開頭的同源相對路徑
  （`FrontendStaticAssetsTest`）
- i18n 字典（`i18n.js` 的 `dictionaries['zh-TW']`/`dictionaries['en']`）key
  parity（互不缺漏）、插值 placeholder 集合一致、無空字串、en 字典無殘留未
  翻譯中文（`lang.zh-TW` 例外）、app.js／index.html 靜態 `t('key')` 呼叫皆
  指向存在的 key（`I18nDictionaryTest`）
- 手動切換語言後 html lang 即時更新且 reload 後保留、不支援的 stored locale
  fallback 到 zh-TW、無 stored 偏好時依瀏覽器語言偵測（含 zh-CN 等變體
  fallback）、實際渲染畫面無 raw key fallback（`⚠key`）、插值正確代入不殘留
  樣板字面值——以上由 `scripts/frontend-regression/check.mjs` 的 i18n 檢查
  覆蓋（手動執行，非 CI，見該目錄 README.md）

### i18n

以下項目需要人判斷「雙語文案讀起來是否正確、有沒有跑版」，`check.mjs` 只能
確認「有沒有 raw key／console warning」這類機械性錯誤，無法判斷語意或版面：

- [ ] 切換為 English 後，Level 1（專案列表）、Level 2（任務看板/相依圖）、
      空看板 onboarding 三步驟、任務詳情面板（含 BLOCKED evidence、DONE
      completion evidence、History）在英文下文案通順、無破版（過長英文單字
      導致按鈕/badge 撐破版面，中文較短英文較長是常見風險）
- [ ] BLOCKED 原因（`blockerReason.*`）、通知（`notify.*`）文案中英文皆語意
      正確，尤其後端傳來的自由格式 `blockedReason` 落在
      `blockerReason.unknown` 時中英文都可讀
      （見 `app.js` 的 `formatBlockerReason()`）
- [ ] 「清除條件」「確認」等操作型按鈕在兩語言下文字長度不同，不影響按鈕
      可點擊區域與版面對齊
- [ ] 桌面通知（`Notification` API 實際彈出的視窗，非頁面內文字）標題與內文
      在 en/zh-TW 下皆正確帶入 `{title}`/`{id}`/`{project}` 等插值（需要瀏覽
      器實際觸發通知權限與 BLOCKED 事件，`check.mjs` 未涵蓋原生通知彈窗渲染）
- [ ] aria-label／title 屬性（螢幕閱讀器/滑鼠 hover tooltip）在兩語言下皆為
      對應語言、語意正確可用（`I18nDictionaryTest` 只驗證 key 存在與 value
      非空，未驗證螢幕閱讀器實際朗讀效果）

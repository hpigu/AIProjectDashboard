# 前端回歸驗證清單（#140）

## 背景

`AGENTS.md` 規定維持零建置 Vue 3 CDN（不引入 npm/Vite/SFC/TypeScript）。前端
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
（參考 `docs/dev-isolation.md`），**不得使用正式 `:8080` 與 `data/board`**。

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

# 專案結構與效能評估

以本機 Spring Boot／H2、Workers／D1 和共用 Vue 前端為範圍。看板的寫入走 MCP，瀏覽器使用唯讀 REST 與 SSE。

## 這次已實作

| 面向 | 現況與改善 |
| --- | --- |
| 執行速度 | Workers 在 SQL 先篩選專案，再彙總符合條件的任務。認領以索引縮小候選與相依查詢，只檢查到第一個合格候選。Java 用一批查詢讀取前置，每個已完成的前置在同一批只重驗一次。 |
| 連線穩定性 | 瀏覽器監看 SSE 心跳。前景斷線持續 15 秒後，定期用 GET 讀取狀態，重連後立刻補讀。Workers 的 SSE 緩衝區設上限，D1 查詢卡住時串流仍會在期限關閉。 |
| 可用性 | 連線指示器顯示定期重新整理狀態。編輯、封存／還原及驗收更新會刷新對應畫面。任務詳細資料與驗收摘要並行載入，切換任務時取消前一次請求。 |
| 可擴展性 | 預設專案清單共用根元件的查詢。100 毫秒內的事件合併刷新，前景輪詢只在即時連線降級時執行。全專案摘要仍會讀取全部任務，Workers 的每條 SSE 連線仍各自輪詢 D1。 |
| 可維護性 | 前端把監聽、重新連線、輪詢與事件合併集中到 `live-updates.js`。Workers 把 SSE 生命週期從 HTTP 路由移到 `events.js`。Java 持續由 Service 處理任務規則，Repository 批次提供相依資料。 |

- 瀏覽器入口與更新模組。 [index.html](../src/main/resources/static/index.html) · [live-updates.js](../src/main/resources/static/live-updates.js)
- Workers 路由、SSE 與 D1 任務服務。 [index.js](../workers/src/index.js) · [events.js](../workers/src/events.js) · [service.js](../workers/src/service.js)
- Java 任務邏輯與相依查詢。 [TaskService.java](../src/main/java/dev/aiboard/task/TaskService.java) · [TaskDependencyRepository.java](../src/main/java/dev/aiboard/task/TaskDependencyRepository.java)

## 本機基準

用相同的 120 個專案、24,000 件任務，在 Miniflare／D1 比較 `a12c948` 和工作副本。各情境暖機 5 次、量測 20 次，並比較清單結果、排列和實際取得的任務。延遲包括本機 D1 呼叫開銷，正式環境的端對端延遲、流量與費用需另行量測。D1 讀取列數取自 `meta.rows_read`。[D1 回傳欄位](https://developers.cloudflare.com/d1/worker-api/return-object/)

| 情境 | D1 讀取列數（修改前 → 後） | p50（毫秒） | p95（毫秒） |
| --- | ---: | ---: | ---: |
| 依前綴找一個專案 | 24,239 → 321 | 12.90 → 3.86 | 29.25 → 4.72 |
| 列出啟用專案 | 24,239 → 12,180 | 13.55 → 7.63 | 15.84 → 8.80 |
| 列出所有專案 | 24,239 → 24,239 | 12.60 → 11.01 | 14.01 → 12.46 |
| 跳過被相依卡住的候選後認領 | 476 → 217–255 | 397.52 → 40.85 | 716.62 → 63.22 |

認領情境的讀取 SQL 從每次 109–128 條降到 7 條。瀏覽器回歸也驗證 3 則連續事件只產生 1 次看板查詢，並用 Chrome 阻擋 SSE 路徑，確認 REST 仍能刷新且解除阻擋後回到即時模式。[瀏覽器回歸腳本](../scripts/frontend-regression/check.mjs) · [EventSource 規格](https://html.spec.whatwg.org/multipage/server-sent-events.html)

### 重跑方式

需要 Node.js 22 以上，以及 `workers/` 依鎖定檔安裝的開發套件。腳本會自行建立兩套獨立的合成資料。

```bash
(cd workers && npm ci --ignore-scripts)
baseline_dir=$(mktemp -d)
git archive a12c948 | tar -x -C "$baseline_dir"
node workers/scripts/benchmark.mjs --baseline "$baseline_dir" --samples 20
```

只量目前程式時可省略 `--baseline`。瀏覽器回歸與本機環境設定見 [操作方式](../scripts/frontend-regression/README.md)。

## 後續功能與腳本評估

| 優先 | 建議 | 判斷方式與腳本適用性 |
| --- | --- | --- |
| P1 | 接線自檢 | 適合做成唯讀 CLI。把 REST readiness、SSE 心跳、MCP `initialize` 與 `tools/list` 分開回報，可補足 `bin/board status` 主要檢查本機程序與健康端點的範圍。Codex／Claude 裡的實際登入仍以各 client 驗證為準。 |
| P1 | Java／Workers 行為對照 | 適合共用固定測資與腳本輸出。優先驗證認領、全部卡住時的原因、無效驗收與相依圖的可認領標記是否一致，再逐步拆分 Workers 的任務生命週期與唯讀查詢。 |
| P1 | 備份與隔離還原演練 | 已有 H2 與 D1 工具。把成功啟動、專案／任務筆數和 DB 指紋列為重跑檢查，實際還原目標與時機維持由人確認。 [備份與還原](backup-recovery.md) |
| P2 | 專案摘要分頁或預先彙總、相依圖按需載入 | 適合以腳本量不同資料量的讀取列數、回應大小與 p95。以全專案摘要的量測判斷投入順序，分頁與快取失效由後端維護。 |
| P2 | 進階長連線與多裝置容量 | 適合用腳本測量多分頁、斷線與重連次數。先取得實際使用量與 D1 讀取成本，再評估集中推播。產品端繼續負責畫面狀態與降級更新。 |

驗收、認領、封存與相依規則由服務端執行。腳本適合處理預檢、固定測資、輸出比較和報告，實際資料仍透過既有授權邊界。

行為對照可先從相依圖著手。兩個後端的相依圖都只在進行中專案標記可認領。Java 依前置任務的 DONE 狀態判斷，Workers 還會檢查前置任務的可信驗收。兩個後端認領時另會檢查候選任務的需求是否待決。這些條件可能讓畫面標記和實際認領結果不同。 [Java 相依圖](../src/main/java/dev/aiboard/task/DependencyGraphService.java) · [Workers 認領與相依圖](../workers/src/service.js)

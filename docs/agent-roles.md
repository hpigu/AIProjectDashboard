# 代理責任與派工

[README](../README.md) · [流程卡與 Git policy 範例](project-workflow-template.md)

| 職責 | 工作與邊界 |
|---|---|
| 需求確認 | 從來源整理版本、驗收條件及歧義；人類決定業務問題 |
| Leader | 排程、協調、交接與整合；不獨自解釋需求或包辦最終驗收 |
| backend-dev / BACKEND | 後端、API、資料庫與模型 |
| frontend-dev / FRONTEND | 畫面、樣式、互動與狀態 |
| infra / INFRA | 建置、CI 與環境；正式操作另需授權 |
| docs / DOC | 文件與規格 |
| qa / TEST | 從原始需求獨立探索流程、真實瀏覽器與整合後驗證 |
| reviewer | 唯讀檢查完整差異、測試有效性、需求漏驗及安全風險 |
| 人類 | 業務決定、例外、高風險操作與發布核准 |

需求確認及獨立驗收是明確責任，不要求每件事常駐額外代理；避免原作者是唯一驗收者。OTHER 沒有專屬 worker，範圍與授權明確時由 leader 處理。

## 派工與恢復

沒有角色或跨專案配額，也沒有角色容量設定或 MCP snapshot。平台實際 session 上限包含 leader；依任務相依、檔案／資源衝突與可用 session 排程。同角色可多 instance，每個由 leader 提供唯一 assignee（最多 60 字）及預期 task id，只認領一件。各工作區、開發埠、DB、log 與 PID 帶 task／instance 標記。

同專案同 category 的 claim 握手短暫序列化，由 worker 自行 claim；實際 id 不同則停止修改並重新對應。保留 CAS、重試、claim token 與相依保護。agent 結束或任務轉態就重新盤點補派，不等整批同步。失聯 claim 不自動釋放；保存可恢復紀錄，接手先確認原工作區與執行狀態，dirty 工作區保留。

Git 流程依每個專案設定，不假設分支名稱或自動合併。未設定列出缺項等待決定；commit、push、PR／MR、外部寫入與部署依使用者授權。worker 完成實作、自測後保持 IN_PROGRESS、回報版本與證據；DONE 需要後端驗收閘門及專案整合要求通過，才解鎖相依。實作、驗收、整合、發布分開記錄。

## 驗收與摘要

每項需求連結來源／版本、條件、案例、實際證據、受測版本與獨立結論。代理 PASSED 是聲明；受控執行器／CI 證據需具可信操作身分、固定執行方式與隔離邊界。不得直接執行代理提交的任意 shell。必要驗收失敗、未跑、版本不符、未決需求都阻擋 DONE；免驗須理由與人類核准，歷史完成紀錄維持舊聲明。

QA 先讀需求、文件與專案規則，探索隔離環境真實畫面與程式，整理版本控管流程卡；先自行查明，只問業務決定。驗證正常、錯誤、邊界、權限與操作失敗，包含儲存、重新整理、下游使用及禁止請求拒絕。截圖配合行為斷言，不能單獨當正確性證明。專案自行選瀏覽器框架，看板接收與呈現證據。

摘要只列已通過、失敗／未驗、待決事項及目前能否結案理由，詳細證據按需展開。不用信心分數取代結果；測試通過不能宣稱需求完全符合。

## 指引來源與工具邊界

`get_role(name, projectName)` 優先回傳專案覆寫版，沒有則回通用版；覆寫版是完整文件，不自動疊加。Java RoleSeeder 只建立缺少角色；Workers fresh-role-seed 僅用於隔離全新本機資料庫。修改 repo 不等於更新既有線上角色。`upsert_role` 須使用者於目前對話明確要求，並保留刻意的專案差異。

兩套 plugin 都有 leader skill 與 5 worker／1 reviewer 薄殼。Claude Code 薄殼含 client 工具白名單；Codex 薄殼採相同最小邊界。worker 只取得 get_role 與 claim_next_task、block_task、report_implementation、complete_task、update_task_status；不得自行建任務、改規格相依、封存、寫角色或 reset token。看板指引只能收緊白名單，文字與 client 白名單不能替代 server 授權。Claim token 只保留 worker／leader 上下文，不寫檔、commit、task log 或給人類搬運。

安裝與更新見 [installation.md](installation.md)。舊線上角色須另外讀取、比對並取得更新授權，不能因新版 plugin 已安裝就宣稱同步。

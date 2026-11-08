# Leader 排程與工作區隔離回歸驗證

執行 `node --test scripts/worker-scheduling-regression/regression.test.js`，需本機 Node.js 與 Git；不連線看板、不啟動伺服器。

測試以獨立的可執行排程模型模擬同角色三個 instance 的事件，涵蓋角色及平台容量、預留與認領轉換、握手序列、相依與資源衝突、任務不足、認領不符、容量一、失聯、待整合、BLOCKED 恢復和降低上限。這是規則模型的驗證，不會執行或驗證 Claude、Codex 的實際排程或看板認領，也不會自動證明兩套 skill 的實作符合模型。

Git fixture 在系統暫存目錄建立全新 repository 與兩個 worktree，實際修改同一相對路徑、檢查隔離及 dirty 狀態，並以真實 commit 和 `--no-ff` merge 驗證模擬的完成 gate。測試成功或失敗均輸出並保留 fixture 路徑，尤其保留未提交成果；檢查完畢後可自行刪除該次輸出的專屬暫存目錄。fixture 與正式 repository、看板資料完全分離。

本環境的自動驗收結論以測試輸出為準：**自動排程模型與 Git fixture**；真實三個 Claude/Codex client 的啟動、認領、握手及整合驗收尚未執行。實際驗收須在有足夠 session slots 的隔離環境進行，先備妥獨立測試看板與任務，由 leader 協調三個真實 worker，觀察容量、claim 回報、工作區與整合順序。

目前正式 board_cloud 尚未部署本批版本，線上環境不回傳本批新增的容量快照；本測試不代表新容量功能已在線上啟用。

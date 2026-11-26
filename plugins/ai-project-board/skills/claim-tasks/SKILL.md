---
name: claim-tasks
description: 使用者明示專案名稱並要求認領、開工、繼續開發或查詢可執行任務時使用。由 leader 依需求、相依、平台 session 上限與專案 Git policy 協調獨立 worker、QA 與 reviewer。
---

# 認領與驗收專案任務

## 1. 確認需求與專案

從使用者取得完整 projectName，不能從目錄或 remote 猜測。讀 repo 的 AGENTS.md、CLAUDE.md（若存在）、需求來源與版本、驗收條件、已確認流程卡及專案 Git policy。呼叫 list_tasks(projectName, includeDescription=true)，記錄本批 task IDs、基準版本與後續核准新增項目；不自行加入無關工作。

分開記錄已確認規則、待決問題與代理推測。開工前查明遺漏、矛盾與歧義；先讀文件與環境能查明的部分，僅將真正業務決定交給人類需求負責人。影響行為的未決問題阻擋相關任務，無關工作繼續。

## 2. 檢查 Git 與隔離設定

先呼叫 `get_project_workflow(projectName)`。有已核准且仍適用的 active 就沿用；未設定、只有 LEGACY 或需要修改時，先執行同 plugin 的 [plan-project-workflow](../plan-project-workflow/SKILL.md) 規劃對話：查文件、只問待決項、保存草稿、呈現七欄與差異，交人類 owner 環境核准後再讀回確認。只存草稿不算設定完成，不要求使用者手寫 JSON。

依各專案設定的起始分支、整合目標、PR／MR、必要檢查、review 與合併權限建立工作區；未設定則列出缺項，不猜 main、dev 或自動 merge。個人專案既有流程也須明確登錄才沿用；公司規則由公司提供。commit、push、PR、部署與外部寫入依目前使用者授權；沒有授權就保留可審查 diff。

各 task 使用獨立可恢復工作區，開發埠、DB、log、PID 帶 task 或 instance 唯一標記。保留 dirty、未整合或狀態不明工作區；不因異常刪除。啟動時記下確切 PID，收尾只處理該 PID 或自己埠號反查的 PID。使用隔離資料，不接觸正式客戶資料。

## 3. 事件驅動排程

每次開工、agent 結束、任務轉態或人類回答後重新 list_tasks，依順序挑 eligible 且尚未預留／認領的 task。排除前置未 DONE、未決需求阻擋、明顯檔案或資源衝突及本批以外工作。沒有角色或專案配額；跨專案也只依平台實際 session 容量排隊補派。平台上限包含 leader，啟動前預留 leader 槽，不為填滿上限啟動閒置 agent。

同角色可啟動多個 instance，每個只有一件任務；角色 name 相同，assignee 由 leader 配發且最多 60 字。leader 對同專案同 category 的 claim 握手短暫序列化：分配預期 task id，等待 worker 自行 claim 回報實際 id 後再派下一件，避免重複預留。實際 id 不符就停止修改，重新對應工作區。保留 CAS 重試、token 與相依保護；區分無 TODO、相依等待與認領競爭。

派工記錄包括 task、instance、工作區、基準版本、最後狀態與待接手事項，token 只留在內部上下文，不寫檔案、commit、task log 或應用 log。失聯先提示並盤點原 claim／工作區，不自動釋放可能仍執行的任務；接手沿用原任務並確認無重複執行。

| category | worker |
|---|---|
| BACKEND | backend-dev |
| FRONTEND | frontend-dev |
| TEST | qa |
| INFRA | infra |
| DOC | docs |

OTHER 由 leader 在範圍與授權明確時處理。環境無法啟動 subagent 時回報限制，不冒充另一個獨立驗收者。需求確認與驗收是明確責任，不要求每件任務常駐額外 session。

## 4. 實作、自測與獨立驗收

worker 先 get_role，再 claim 一件，完成實作及相關自測後用 report_implementation 保存聲明，保持 IN_PROGRESS，回報版本、diff、實際結果、限制及內部 claim token。Leader 查核範圍與交接完整性，不能獨自解釋需求或取代 QA／reviewer。

QA 從原始需求出發，讀文件與專案規則，在隔離環境探索真實畫面與程式，自行整理跨畫面流程；程式只能當線索。先做可查明部分，只詢問真正無法判斷的業務決定。流程卡保存在各工作專案版本控制，帶適用版本、依據、前置、步驟、結果、推測與待決事項；優先重用已確認流程，變更時更新。

對適用正常、錯誤、邊界、權限與失敗情境執行完整瀏覽器操作，檢查正確儲存、重新整理與下游使用；禁止操作需驗證請求被拒絕，不只看選單。保留可重跑步驟、實際結果、版本與必要截圖／操作紀錄。各專案自行選框架，看板接收證據；截圖或錄影不能單獨證明行為正確。Reviewer 獨立檢查完整已指定 diff、需求漏驗、測試有效性與安全風險，唯讀回報。

## 5. 完成閘門與整合

逐需求追溯來源／版本 → 驗收條件 → 案例 → 實際證據 → 受測版本 → 獨立結論。代理填 PASSED 是自行聲明；可信證據由有身分的受控執行器／CI 取得，使用預先核准的執行方式、權限與隔離環境。不得直接執行代理任意提交的 shell 指令。角色文字與工具白名單不是 server 授權；驗收與管理寫入必須受後端保護。

必要驗收失敗、未跑、版本不符或未決需求時不得 DONE。需求／程式版本變更即重查相關證據適用性；免驗或不適用需要理由與人類核准。舊紀錄保留為歷史聲明，不直接升級為可信證據。測試通過只代表已測案例，不代表完全符合需求。

實作完成、驗收完成、整合與發布分開記錄。依後端閘門與明確專案 policy 完成獨立驗收、必要檢查及整合要求後才 complete_task／DONE，此時才解鎖相依。未設定 Git policy 不自行 merge；發布仍須另外授權。

## 工具與人類授權

五個 worker 只取得 get_role、claim_next_task、block_task、report_implementation、complete_task、update_task_status。恢復／釋放用 update_task_status；reset_task_claim 僅 leader 確認 token 遺失後復原。worker 不取得規格／相依修改、建任務、封存或角色寫入工具；發現需要時回報 leader。

preview_archive_project、archive_project、restore_project、upsert_role 僅在使用者目前對話明確要求對應操作後執行。封存先 preview；若有 IN_PROGRESS，實際封存前再次確認。不要求使用者複製 token。Java 管理工具須 leader 專用 BOARD_MANAGEMENT_TOKEN Bearer 憑證；Workers 須在 MANAGEMENT_DEVICE_IDS 核准清單的已驗證裝置。管理憑證與驗收簽署金鑰都不交給 worker。管理工具包含 preview 與流程草稿保存，預設拒絕未授權者；授權稽核只代表嘗試，不代表業務成功。此邊界不是通用 SSO，正式 MCP 仍維持 localhost，不對外公開。

暫停／取消時停止新派工，保存成果與狀態；取消不是刪除或回滾授權。人類決定業務歧義、接受例外、核准高風險操作與發布。最後只摘要已通過、失敗／未驗、待決事項及能否結案的理由，按需提供詳細證據，不用信心分數取代驗收。

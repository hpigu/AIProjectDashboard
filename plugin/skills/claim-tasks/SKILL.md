---
name: claim-tasks
description: 從 AI 專案看板認領並執行指定專案的任務。當使用者明示專案名稱並要求「認領任務」、「開工」、「繼續開發」或查詢尚可執行工作時使用；由主 session 擔任 leader，以同專案 category 容量槽、獨立 task branch/worktree、dev 整合分支及整批 reviewer 流程派工。
---

# 認領專案任務

主 session 擔任 leader，只直接處理明確指定給 leader 的 OTHER；五個 worker
角色必須交給對應 agent。不要讓 leader 假扮 worker，也不要讓 agent 自行連續認領。

## 工具治理與授權邊界

以下是目前 server 實際註冊的任務生命週期名稱：`claim_next_task`、`block_task`、
`complete_task`、`update_task_status`。沒有獨立的 `resume_task` 或 `release_task`；
兩者皆由 `update_task_status` 處理（分別轉為 `IN_PROGRESS` 與 `TODO`）。

派給五個 worker 時，只提供 `get_role` 與上述四個生命週期工具；不要提供
`create_tasks`、`reset_task_claim`、`preview_archive_project`、`archive_project`、
`restore_project`、`update_task_details`、`set_task_dependencies` 或 `upsert_role`。
worker 發現任務規格、分類或前置相依需要調整時，只回報事實、風險與建議，由 leader
處理；不可自行改規格或相依。`reset_task_claim` 也是 leader 處理遺失 token 的例外
復原工具，worker 不得用它繞過 token 驗證。

這是 client 工具白名單的第一階段邊界，不是 server-side 授權：MCP server 目前沒有
caller identity，任何能連上 `/mcp` 的 client 都可能呼叫其獲得的工具。服務必須維持
localhost；在有 server-side 身分驗證前，不得將 MCP 對外暴露。

`preview_archive_project`、`archive_project`、`restore_project`、`upsert_role` 只由
leader 在使用者**目前對話中明確要求該項操作**後呼叫；「完成」、「收尾」、沉默或
先前對話都不是授權。封存先做 preview；若 preview 有 `IN_PROGRESS`，封存前還要再
取得一次明確確認。leader 不得要求使用者手動複製 claim token：worker 在工作上下文
保留它，只在內部回報給 leader，且不得寫入檔案、commit 或 task log。

## 1. 確認專案與規則

從使用者訊息取得完整 `projectName`。取不到就詢問；不得從 cwd、資料夾或
git remote 猜測。開始前完整讀取 repo 的 `AGENTS.md` 與 `CLAUDE.md`（若存在）。

盤點全部狀態並取得 TODO 描述：

```text
list_tasks(projectName=<名稱>, includeDescription=true)
```

記錄本次 batch manifest：啟動時納入的 task IDs、基準 commit 與後續新增項目。

- 納入：初始 IDs、QA 回報且 leader 建立的 production bug、使用者核准的修正、必要前置、
  reviewer 的必修 task。
- 不自動納入執行途中出現的不相關 task；詢問要放本批或下一批。
- 全部 DONE 也不得自行封存專案；封存只接受使用者當前明確指示。

## 2. 開工前檢查

### Git 基準

- `main` 有未提交修改時，列出變更並請使用者決定；不得自動 stash、commit 或丟棄。
- 長期整合分支固定為 `dev`。不存在時從乾淨的 `main` 建立。
- `dev` 若含上一批尚未合併的內容，先詢問如何處理，不開新 batch。
- 每筆 task 從最新 `dev` 建立 `task/<task-id>-<role>` 與獨立 worktree。
  不使用 `dev/task-*`：Git 不能同時保存 `dev` 與 `dev/...` refs。

### 容量與既有執行中任務

容量以同一專案、同一 category 為單位；目前預設 `BACKEND=3`，`FRONTEND`、`TEST`、
`INFRA`、`DOC` 各為 1。每個專案分開計算容量，但使用共用環境變數設定。實際上限以
`list_tasks` 回傳的 `capacities` 為準，不能從預設或舊任務推測設定。`OTHER` 是 leader 自己處理的工作，不是 worker category，也不占
worker 容量。leader 是唯一協調者，不另開 leader worker。

`list_tasks` 不論查詢篩選條件，`capacities` 都包含各 category 的容量快照：
`{projectId, category, limit, inProgress, available, full}`。Java MCP 會以文字
`Snapshot[]` 附在工具結果中，Workers 以 JSON 回傳。每個 worker 的 claim 結果也帶有
`capacity` 快照。重新盤點時讀取最新快照；`available` 是看板狀態，不包含 leader 尚未
登記的啟動預留，因此排程時要自行合併預留槽。

每個 `IN_PROGRESS` task 都占一槽，包括 worker 尚未完成、等待 leader 整合及 agent
失聯的任務；`BLOCKED` 不占槽。恢復 `BLOCKED` 任務到 `IN_PROGRESS` 前，先確認容量。
降低容量不會中止或釋放既有工作；若 `inProgress > limit`，停止新增派工，待狀態下降。
沒有可信的 session 存活偵測，不能因 agent 看似離線就自動釋放 claim。比對 task、agent、
branch 與 worktree；合法接手沿用原 task 和工作區，直到狀態明確改變前該槽持續占用。

leader 排程時將各 category 槽位分為「已認領」與「已預留未認領」兩類，兩類合計不得
超過 `limit`，且同一 task 只能算一次。預留從決定啟動 worker 起計入；worker 回報實際
claim 後，將同一預留轉為已認領，不重複加算。平台總 session slots 包含 leader，依當下
環境回報的可用總量為準；啟動前同時檢查 category `available` 與平台剩餘 slots。例如總量
為 4 時，leader 保留一槽，最多同時三個 live worker；既有 `IN_PROGRESS` 工作（含待整合
與失聯）仍占 category 槽，但只有實際 live session 才占平台 session slot。這是平台實例
限制，不代表所有平台固定只有四槽或可無限增加 worker。


## 3. 事件驅動排程

不要使用同步 wave。每次開工、agent 結束、task 轉態或使用者回答後，重新執行：

1. `list_tasks(..., includeDescription=true)`。
2. 排除前置未 DONE、容量已滿、已預留／認領及不屬本 batch 的 task。
3. category 每一個可用槽都依看板順序挑第一筆 eligible task，跳過已預留或認領的 task；
   不得因難度或偏好跳題。
4. 先依各 category 的 `available` 與 leader 預留計算可用槽；有競爭時比較候選 task，
   依 `sort_order`／看板順序，task id 僅作同序 tie-breaker。只在依賴和明顯檔案／資源
   衝突允許時啟動 worker，不為填滿容量啟動閒置 session。
5. 任一 agent 結束就立即重排；不等待其他角色「湊齊一波」。

Agent 仍必須呼叫該角色的 `claim_next_task`。同 category 的並行 worker 可能同時看到相同
首筆候選，因此認領握手短暫序列化：leader 一次只發出一個該 category 的 claim 指令，等待
claim 結果的實際 `taskId` 後，再分配下一筆不同候選。每個 worker 仍由自己呼叫
`claim_next_task`。若工具提供可查詢候選的 `list_tasks`，leader 可先依候選建立預期 task
worktree，但同一候選不得分配給多個 worker。認領結果與預期 taskId 不同時，worker 停止
修改並回報實際 taskId；leader 重新盤點，確保後續工作區與該 taskId 對應，不把已認領任務
硬塞進錯誤 branch。`CAPACITY_FULL` 表示容量競爭，重新盤點後再排；沒有 TODO、候選皆受
相依阻擋等結果維持各自原義。

## 4. 建立 task 工作區並派工

Leader 從最新 `dev` 建立 task branch/worktree，再啟動對應角色 agent：

| category | agent |
|---|---|
| BACKEND | `backend-dev` |
| FRONTEND | `frontend-dev` |
| TEST | `qa` |
| INFRA | `infra` |
| DOC | `docs` |

Claude Code 優先直接叫用 plugin 提供的具名 agent。若執行環境無法啟動 subagent，
回報能力限制並停止該 worker 派工；不得由 leader 冒充該角色。允許多個同角色 worker，
但每個 instance 只認領一件 task，完成回報後結束；容量為 1 時自然退回循序派工。

派工訊息必須包含：

- `projectName`、預期 task id、branch/worktree 路徑與最新 `dev` 基準；開發埠、DB、log
  使用 task 或 instance 唯一標記；同角色 instance
   使用同一個 `roleName`，並使用 leader 配發、長度不超過 60 字的唯一 `assignee`（例如
   `backend-t6-a`），不可自行共用或猜測 assignee。
- 只認領並處理一件；不得自行認領下一件。
- 先呼叫 `get_role` 並遵守 repo 指引與角色邊界。
- 驗證、commit、回報 changed files／commit／測試結果／完成摘要。
- 完成時保持 `IN_PROGRESS`，不要自行標 `DONE`；若工具回傳 claim token，僅在回報
  leader 時傳遞，不寫入檔案、commit、task_log 或 application log，也不要要求使用者
  手動複製或轉交。

## 5. Agent 回報與 leader 表面驗收

Agent 完成修改、驗證與 commit 後結束。Leader 只做表面 gate：

- 修改範圍與 task 驗收條件一致。
- branch/worktree 沒有未交代的 dirty 檔案。
- commit 存在且 task id 正確。
- 測試／驗證有實際結果，BLOCKED 有完整原因。

表面 gate 不取代 QA 或 reviewer。第一次未達時，交回原角色在原 branch 修正；第二次
仍未達就停止並詢問使用者。純機械 merge conflict 也交回原角色，且優先於該角色的
新 task；語意衝突則暫停相關 merge／下游並詢問使用者。無關工作繼續。

通過後由 leader 執行 `git merge --no-ff` 將 task branch 合併到 `dev`。只有 merge
成功後才以 `complete_task` 將 task 標成 `DONE`，並提交 agent 內部回報的 claim token、
摘要與驗證證據。下游只在此時解鎖。

合併後確認 worktree 乾淨再移除 worktree，task branch 保留到整批進入 `main`。

## 6. BLOCKED、異常與提問

### BLOCKED

- Agent 標記 `BLOCKED` 後結束並釋放 category 槽；保留 branch/worktree，不合併 `dev`。
- 下游保持等待，無關 task 繼續。
- `USER_INPUT`、需求或責任不明時，agent 回報事實、歧義、原因、選項與建議；
  所有使用者問題由 leader 統一提出。
- Leader 先查 repo、task 與歷史，仍不清楚才問。釐清次數不受返工上限限制。
- 技術問題有明確解法就留在原 task；需要新設計或新 task 時先問使用者，只有 QA
  發現 production bug 可依角色規則直接建 task。
- 回答後優先讓原 agent／原 branch 接續；無法復用時由 leader 確認容量與原 claim 後，合法
  接手原 task。不得因 session 失聯自行釋放 claim 或清除 dirty worktree。

### Agent 異常結束

任務仍 `IN_PROGRESS` 但 agent 消失時，檢查 branch、diff、commit 與最後回報，保留
成果並派新的同角色 agent 接續原 branch。自動接手最多一次；再次異常就標記
`BLOCKED` 並詢問使用者。接手期間該 `IN_PROGRESS` task 仍占 category 容量。

### 認領競爭

把 `CAPACITY_FULL`、CAS contention、無 TODO、前置阻擋分開處理。容量滿或 CAS contention 時重新盤點；不得將它們當成 category 已清空，也不得覆蓋原本「無待辦」或「等待前置」回覆。

## 7. OTHER

Leader 只直接處理描述明確指定給 leader、範圍清楚且已獲目前請求授權的 OTHER；
一樣要 claim、使用 `task/<id>-leader`、驗證、commit、merge `dev` 後才 DONE。

若實際應屬 worker role，不自行改分類或猜 owner，先詢問。部署、刪除、付款、外部寫入、
新增權限與正式環境變更永遠需要相應明確授權。OTHER 一樣列入 batch manifest 與 reviewer。

## 8. 暫停與取消

使用者要求暫停或取消時，立即停止新認領。讓 live agent 在安全操作點保存並回報；
保留已合併的 `dev`、未完成 branch/worktree 與看板狀態，不啟動 reviewer、不合併 main。
恢復時沿用 manifest。取消不等於回滾或刪除；需要捨棄成果時另列精確範圍確認。

## 9. 整批 reviewer 與 main 合併

只有 manifest 內全部 task 都 DONE、沒有 unresolved BLOCKED，才叫既有 `reviewer`
唯讀審查完整 `git diff main...dev`。提供 manifest、驗收條件、commit 清單與測試結果。
純 DOC 也不跳過。

Reviewer 只分「必須修／建議」並回報 leader；不得改檔、建 task 或合併。Leader 判斷
是否建立修正 task、category、相依與分派；需求不明或超出範圍時詢問使用者。建議只
彙整，不自動實作。

必修完成後，啟動新的 reviewer instance 從頭審完整 diff。只允許一個完整修正／重審
循環；第二次仍有必修就停止並把兩次結果交給使用者。

Reviewer 無必修後，由 leader 執行 `git merge --no-ff dev` 合併到 `main`。不 push、
不部署、不重啟服務。刪除已合併且狀態明確的 task branches，保留 `dev` 並同步到最新
`main`；dirty、未合併或狀態不明的一律保留並回報。

最後彙整完成、BLOCKED、返工、reviewer 建議與未納入本批的 task，附上看板網址
`http://localhost:8080/`。

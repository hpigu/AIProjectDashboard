---
name: claim-tasks
description: 從 AI 專案看板認領並執行指定專案的任務。當使用者明示專案名稱並要求「認領任務」、「開工」、「繼續開發」或查詢尚可執行工作時使用；由主 session 擔任 leader，以單角色鎖、獨立 task branch/worktree、dev 整合分支及整批 reviewer 流程派工。
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

### 既有執行中任務

若某 category 已有 `IN_PROGRESS`，先把該角色鎖視為占用並比對 live agent、branch
與 worktree。找不到對應工作時保留現況並詢問要接手、重開或停止；不得另派同角色。
無關角色仍可繼續。

### 角色鎖

固定五把鎖：`BACKEND`、`FRONTEND`、`TEST`、`INFRA`、`DOC`。同一時間每個
category 最多一個 live agent；每個 agent 只處理一件 task，回報後結束。
`OTHER` 不占 worker 鎖，但 leader 同一時間也只直接處理一件 OTHER。

## 3. 事件驅動排程

不要使用同步 wave。每次開工、agent 結束、task 轉態或使用者回答後，重新執行：

1. `list_tasks(..., includeDescription=true)`。
2. 排除前置未 DONE、角色鎖占用、已在處理及不屬本 batch 的 task。
3. 每個空閒 category 只看依看板順序第一筆 eligible task；不得因難度或偏好跳題。
4. 在平台容量內盡量填滿不同角色。容量不足時，比較各 category 的第一筆候選，
   依 `sort_order`／看板順序，task id 僅作同序 tie-breaker。
5. 任一 agent 結束就立即重排；不等待其他角色「湊齊一波」。

Agent 仍必須呼叫該角色的 `claim_next_task`。Leader 預期的 task id 與實際認領不符時，
agent 不修改檔案，立即回報；leader 重新盤點，不把錯誤任務硬塞進既有 branch。

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
回報能力限制並停止該 worker 派工；不得由 leader 冒充該角色。平台容量低時循序派工，
但角色鎖與一 agent 一 task 不變。

派工訊息必須包含：

- `projectName`、預期 task id、branch/worktree 路徑與最新 `dev` 基準。
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

- Agent 標記 `BLOCKED` 後結束並釋放角色鎖；保留 branch/worktree，不合併 `dev`。
- 下游保持等待，無關 task 繼續。
- `USER_INPUT`、需求或責任不明時，agent 回報事實、歧義、原因、選項與建議；
  所有使用者問題由 leader 統一提出。
- Leader 先查 repo、task 與歷史，仍不清楚才問。釐清次數不受返工上限限制。
- 技術問題有明確解法就留在原 task；需要新設計或新 task 時先問使用者，只有 QA
  發現 production bug 可依角色規則直接建 task。
- 回答後優先讓原 agent／原 branch 接續；無法復用時開新的同角色 agent，仍不可並行。

### Agent 異常結束

任務仍 `IN_PROGRESS` 但 agent 消失時，檢查 branch、diff、commit 與最後回報，保留
成果並派新的同角色 agent 接續原 branch。自動接手最多一次；再次異常就標記
`BLOCKED` 並詢問使用者。接手期間角色鎖仍占用。

### 認領競爭

把 contention 與 no-task 分開處理。競爭時重新盤點後再排，不把它當成 category 已清空。

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

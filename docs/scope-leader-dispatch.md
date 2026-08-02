# Leader 派工架構

本文件說明 AgentDashboard 3.0 的派工責任與 Git 邊界，供人員理解架構。
可執行規則以兩套 plugin 的 `skills/claim-tasks/SKILL.md` 為準；worker 的完整職責
以看板 `role` 表為準，repo `AGENTS.md` 則保存專案限制。

## 目標

- 同角色同時最多一個 agent。
- 每個 agent 只處理一件 task，完成後結束。
- 前置完成且角色空閒就派工，不等待固定 wave。
- 每筆 task 在獨立 branch/worktree 工作，不讓 agent 共享 index、target 或測試資料。
- 程式碼進入 `dev` 後才把看板 task 標成 DONE。
- 本次 batch 全部完成後只做一次完整 reviewer；通過才合併 `main`。
- Claude Code 與 Codex 採同一治理語意，只保留啟動 subagent 的平台差異。

## 設定責任

| 來源 | 責任 |
|---|---|
| 看板 `role` 表 | 五個 worker 的完整工作指引；專案覆寫優先於通用版 |
| `RoleSeeder` | 新資料庫的初始角色內容，不覆蓋既有資料 |
| `plugin/agents`、`.codex-plugin/agents` | client 薄殼與看板失效時的最低 fallback |
| 兩份 `claim-tasks` skill | leader 排程、Git、驗收、review 與例外流程 |
| repo `AGENTS.md` | 本專案架構、測試與正式環境限制 |

安裝後的 plugin cache 是產物，不是另一份正本；更新應透過 plugin 更新流程完成。

## 事件驅動排程

五個 worker category 各有一把 live-agent 鎖：

```text
BACKEND   → backend-dev
FRONTEND  → frontend-dev
TEST      → qa
INFRA     → infra
DOC       → docs
```

Leader 在開工、agent 結束、task 轉態或使用者回答後重新盤點。每個空閒 category
只看依看板順序第一筆前置皆 DONE 的 task；平台容量不足時，再比較這些第一候選的
`sort_order`／看板順序與 task id。任一角色結束就填補可用名額，不等待其他角色。

`claim_next_task` 仍是實際認領入口與原子守衛。Leader 不因難度挑過前面的 eligible
task；若預期 id 與實際認領不符，停止修改並重新盤點。

## Git 模型

```text
main                         穩定、已通過整批 reviewer
└── dev                      leader 維護的長期整合分支
    ├── task/123-backend-dev 單一 task branch/worktree
    ├── task/124-frontend-dev
    └── task/129-leader      明確授權的 OTHER
```

Git 不能同時保存 `dev` 與 `dev/...` refs，因此 task branch 使用 `task/`，不是
`dev/task-`。每筆 task 從最新 `dev` 建立；agent 只 commit 自己的 branch，不合併、
不 push。Leader 表面驗收後以 `--no-ff` 合併 task branch 到 `dev`，成功後才完成
看板 task。下游因此只會在所需程式碼已進入整合分支後解鎖。

Task worktree 在合併且乾淨後可移除，branch 保留到整批進入 `main`。未合併、dirty、
BLOCKED 或狀態不明的工作區一律保留。整批完成後保留 `dev` 並同步 `main`，清除已
合併且狀態明確的 task branches。

## 完成狀態所有權

Worker 負責：

- 認領一件 task。
- 修改、驗證、commit。
- 回報摘要、changed files、commit、驗證結果，以及 claim token（若工具有提供）。
- 保持 `IN_PROGRESS` 並結束；卡住才標 `BLOCKED`。

Worker 只取得 `get_role`、`claim_next_task`、`block_task`、`complete_task` 與
`update_task_status`。目前沒有獨立 `resume_task`／`release_task`：兩者都透過
`update_task_status` 改為 `IN_PROGRESS`／`TODO`。claim token 在 worker 的工作上下文
保留並內部傳給 leader，不能寫入檔案、commit 或 task log，使用者也不需要手動複製。

Worker 不取得 `create_tasks`、`reset_task_claim`、`preview_archive_project`、
`archive_project`、`restore_project`、`update_task_details`、`set_task_dependencies` 或
`upsert_role`。發現任務規格、分類或前置相依需要調整時，只回報 leader；不得自己改。

Leader 負責：

- 檢查修改範圍、commit、驗證證據與 worktree 狀態。
- 合併 task branch 到 `dev`。
- 合併成功後以 `complete_task` 標記 `DONE`。

Leader 以 worker 內部回報的 token、摘要與驗證證據呼叫 `complete_task`。僅在使用者
於**目前對話**明確要求時，leader 才能呼叫 `preview_archive_project`、
`archive_project`、`restore_project` 或 `upsert_role`；「完成」、「收尾」、沉默和
先前對話都不算授權。封存一律先 preview，preview 有 `IN_PROGRESS` 時，封存前再向
使用者取得一次明確確認。

MCP server 沒有 caller identity，因此上述白名單只是 Claude/Codex 的第一階段 client
邊界，不是 server-side 存取控制。服務必須維持 localhost，直到有 server-side 認證。

這個順序避免「看板已 DONE、下游已開始，但程式碼尚未進入 `dev`」的競態。

## Batch 與 reviewer

開工時記錄 batch manifest 與基準 commit。QA 實際測試失敗建立的 production bug、
使用者核准的修正、必要前置與 reviewer 必修都留在同一批；執行途中出現的不相關
task 不自動加入。

只有 manifest 全部 DONE 且沒有 unresolved BLOCKED 時，才呼叫既有 reviewer
唯讀審查完整 `main...dev`。Reviewer 只分「必須修／建議」並通報 leader，不改檔、
不建 task、不合併。Leader 決定必修的 category、相依與分派；範圍或需求不明就問
使用者。建議只彙整。

必修只允許一個完整修正與重新審查循環；第二次仍有必修就停止並詢問使用者。
通過後 leader 才以 `--no-ff` 合併 `dev` 到 `main`。不自動 push、部署、重啟或封存。

## 例外處理

- **BLOCKED**：agent 結束並釋放角色鎖；branch/worktree 保留、不進 `dev`；下游等待，
  無關工作繼續。
- **使用者輸入**：subagent 只整理事實、歧義、選項與建議，由 leader 統一詢問。
- **異常中斷**：保留成果並由同角色新 agent 接續一次；再次異常就停止並詢問。
- **Merge conflict**：機械衝突回原角色處理；語意衝突暫停相關工作並詢問。
- **表面驗收失敗**：只允許一次返工；第二次仍失敗就詢問。
- **認領競爭**：重新盤點，不誤報成沒有任務。
- **暫停／取消**：停止新派工並保存所有成果；不等於回滾或刪除。
- **既有 IN_PROGRESS**：先占用角色鎖並找 live agent／branch；找不到就詢問，不重複派工。
- **Dirty main 或殘留 dev**：不得自動 stash、commit、丟棄或覆蓋，先取得使用者決定。

## OTHER

OTHER 不占五個 worker 鎖，由 leader 一次處理一件，但必須明確指定給 leader、範圍
清楚且目前請求已授權；同樣 claim、使用獨立 branch/worktree、驗證、commit、整合
與 review。若實際應屬 worker role，不自行猜測或改分類。

部署、刪除、外部寫入、付款、新權限與正式環境變更仍需要個別明確授權；「完成」或
「收尾」不會隱含這些權限。專案封存只能由使用者決定，leader 與 subagent 不得自動封存。

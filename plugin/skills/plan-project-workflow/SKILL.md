---
name: plan-project-workflow
description: 使用者要求規劃、確認或修改專案 Git 流程，或開工前缺少已核准設定時使用。由規劃對話整理規則、保存草稿、呈現差異並交給人類需求負責人核准。
---

# 規劃專案 Git 流程

1. 取得使用者明示的完整 projectName。呼叫 `get_project_workflow(projectName)`；讀工作專案 AGENTS.md、既有需求與 Git／CI 文件，記下文件版本。已核准且規則未變時直接重用；不要求每張任務重填。
2. 整理七欄：`baseBranch` 起始分支、`integrationTarget` 整合目標、`pullRequestPolicy` PR／MR 規則、`requiredChecks` 必要檢查、`reviewPolicy` 審查規則、`mergeAuthority` 合併權限、`doneWhen` DONE 時機（`ACCEPTED` 或 `INTEGRATED`）。每欄附 `sources`：`field/kind/ref/version/detail`；kind 分為 `PROJECT_RULE`（專案規則）、`USER_DECISION`（目前人類決定）、`SUGGESTION`（代理建議）。程式或 remote 只是線索，未確認的猜測記為建議。
3. 能從文件查明的先查明；只詢問缺少、矛盾或真正需要業務決定的欄位。以簡短選項與白話取捨提問，不要求使用者填 JSON 或逐條抄工具參數。未決項記到 `questions`（`field/question`）；可逆、無關工作繼續，涉及未決分支或整合的動作等待。
4. 用 leader 的管理身分呼叫 `prepare_project_workflow(projectName, expectedRevision, gitPolicy, sources, questions)` 保存草稿。`gitPolicy` 可先部分填寫；每次以最新 revision 防止覆寫別人的修改。衝突時重新讀取並呈現差異，絕不盲目重送。保存草稿不代表生效，也不建立看板任務。
5. 草稿完整且無待決問題時，向人類顯示完整七欄、來源分類與相較有效設定的差異，說明 DONE 對相依解鎖的影響。將這份具體版本交給下一步 owner 工具，集中做一次正式確認，不另要求對話重複核准。變更任何欄位後須重新預覽；先前同意不適用新 digest。
6. 由人類在獨立 owner 環境執行固定核准 helper：`node bin/approve-project-workflow.mjs --project '<完整專案名稱>' --credentials /path/to/owner-credentials.json`（實際參數見該工具 `--help` 與 repo 的 `docs/project-git-workflow.md`）。helper 讀取並顯示同一草稿，要求 TTY 輸入 `確認 <projectName> #<draft.version>`、重讀防止草稿過期，再用已配置、具專案範圍 `PROJECT_POLICY` 權限的 owner 身分呼叫 `confirm_project_workflow(actor, payload, signature)`。首次金鑰配置由人類完成；leader、開發代理與瀏覽器均不取得 owner secret。人類不用手寫 payload、複製 token 或 signature。只有對話同意但 helper 尚未成功時，明確回報「草稿已保存，尚未生效」。
7. 再次 `get_project_workflow`，確認 active 的 version、七欄、confirmedBy、approvalRef 與預覽 digest；匹配才回報已生效。網頁專案頁顯示設定、草稿與待決事項，使用者可重新整理查核。後續 CONTRACT 的 body.gitPolicy 沿用已核准七欄，body.workflowVersion 必須等於 active.version；核准新版後重新確認舊契約適用性。

## 邊界與相容

- 未配置時保存草稿、列出缺項；不猜 main／dev，也不執行 Git 合併。`LEGACY` 只作遷移依據，需重新核准才升級為有效設定。
- 草稿不撤銷現有 active；未生效時繼續明列目前有效設定與待確認差異。
- 管理身分只能保存草稿，不能代替 owner 核准。不可將管理憑證或簽署金鑰交給 worker、放進網頁、檔案或對話輸出；不得讓使用者手動搬運 secret。
- 核准流程不授權 commit、push、PR／MR、合併、發布、部署或更新線上角色；這些操作依目前使用者的明確授權。
- 若 owner 環境未配置或不能簽署，保存完整可審查草稿並指出唯一阻礙，不宣稱已設定完成。

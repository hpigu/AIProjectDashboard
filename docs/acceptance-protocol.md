# 需求、證據與結案協定

本機 Java 與 Workers 後端接收版本化簽署事件，並在 `complete_task` 檢查結案條件。看板不執行測試指令、不下載 artifact、不替工作專案選測試框架。隔離範例只能證明本機機制與範例流程；公司系統、公司 Git flow、Amazon Bedrock 的模型、日誌、區域及保留設定均未實際驗證。

## 升級時的行為變更

- 角色及跨專案配額已移除；leader 依實際平台 session 上限、任務相依及檔案／資源衝突排程，槽位不足時補派。
- 舊 DONE 保留歷史狀態，沒有新版契約與證據的紀錄顯示未驗證，不自動提升信任，也不自動改寫歷史狀態；沒有有效驗收的舊 DONE 不可解鎖新的下游認領。
- 所有新的完成操作，包括舊任務，都必須走 `complete_task` 的新版閘門。`update_task_status(..., DONE)` 已取消；`require_evidence=false` 不再提供繞過路徑。
- 舊 `verificationResults` 是開發代理的自測聲明，填 PASSED 不足以結案。完成需 `commitRef` 與契約 `codeRef` 完全一致、目前需求／Git 設定有效、必要案例執行或人類核准例外，以及獨立審查。
- 未配置可信簽署者或專案 Git policy 時，保留實作與自測成果，任務仍未完成。這是阻擋未驗收結案的預設行為，不會自動授權部署、合併或更新角色。

## 信任與權限邊界

HMAC 證明封包由持有指定金鑰、且被設定授權的身分送出，並驗證傳輸內容未變；它**不會自動證明封包敘述真實、測試真的執行或 artifact 內容正確**。artifact SHA-256 是比對識別，後端不主動抓取或查核檔案。`codeRef` 與需求版本也是簽署者負責確認的識別，後端不連外查 Git。`codeRef` 只接受完整 40／64 字元小寫 Git SHA、64 字元 SHA-256 或 `sha256:` 加 64 字元 digest，不接受分支或標籤。來源與流程版本也應使用不可變識別；此格式限制目前針對 codeRef 強制。

可信 CI／執行器必須從自身執行結果產生報告；簽署端獨立於開發代理、受測程式與可修改的測試作業。受測程式不可讀取金鑰、呼叫可任意簽署封包的介面，或直接控制簽署輸入。只執行核准的固定 runner profile，使用隔離環境與測試資料；不要直接執行代理提交的任意 shell。若把金鑰注入不受信任程式所在的同一個 job，簽章不能提供所需的證據邊界。

`BOARD_ACCEPTANCE_AUTHORITIES` 是 JSON 物件，每個 actor 設定：

| 欄位 | 設定原則 |
|---|---|
| `secret` | 至少 32 字元；每個身分各自使用不同隨機金鑰，只留在受控簽署端與 server 設定 |
| `projectIds` | 明列授權的專案 ID 陣列；依最小範圍授權，不能把個人專案身分直接套到公司專案 |
| `permissions` | 僅列必要事件種類；需求確認、RUN、REVIEW、例外及發布應分工 |
| `runnerProfiles` | RUN 身分可使用的固定執行器 profile 名稱陣列；其他身分可為空 |

建議需求負責人持有 PROJECT_POLICY／CONTRACT，隔離 CI 只持有 RUN，獨立驗收者只持有 REVIEW，人類例外核准者持有 EXCEPTION；INTEGRATION、RELEASE、DISPATCH 依專案責任分開授權。後端拒絕不同 actor 共用同一 secret；持有 RUN 的身分也不能兼有 CONTRACT、REVIEW、EXCEPTION 或 PROJECT_POLICY。開發與協調代理不能取得簽署金鑰。server 強制 RUN 身分不同於 implementationActor，REVIEW 身分不同於原作者、目前認領者及該契約的 RUN 身分；金鑰隔離與實際人員分工仍由部署者建立。

一般 MCP 工具仍沒有通用 caller identity；事件簽章只保護此事件接收入口。管理工具（包含流程草稿）另以 Java leader 專用 Bearer 管理憑證／Workers 已核准裝置清單保護，見[管理授權](authorization-boundaries.md)；兩者不是公司 SSO，不能互相代用。本機 server 維持 localhost，不能因此將 `/mcp` 公開。

## 寫入與讀取

`record_acceptance_event(actor, payload, signature)` 的 `payload` 是原始 JSON 字串：

```json
{
  "eventId": "<全域唯一事件 ID>",
  "projectId": 123,
  "taskId": 456,
  "expectedRevision": 0,
  "kind": "PROJECT_POLICY",
  "body": { "gitPolicy": { "baseBranch": "<專案起始分支>", "integrationTarget": "<整合目標>", "reviewPolicy": "<獨立審查規則>", "mergeAuthority": "<合併權限>", "pullRequestPolicy": "<PR 或 MR 規則>", "requiredChecks": "<必要檢查>", "doneWhen": "INTEGRATED" } }
}
```

以該 actor 的 secret 對**原始 payload UTF-8 bytes**計算 HMAC-SHA256，signature 為 64 字元小寫 hex；簽好後不可重排 JSON 或改空白。拒絕未知 actor、簽章錯誤、超出 project scope／permissions、重複 eventId 或過期 expectedRevision。事件追加保存，不覆寫舊事件。每次寫入也更新 task version，後續完成前須重新讀取最新版本。

讀取 `get_acceptance(taskId)` 或 `GET /api/projects/{projectId}/tasks/{taskId}/acceptance`，取得 `revision`、`taskDigest`、`subjectDigest`、`canClose`、`passedCases`、`blockers` 與詳細 `events`。每次追加前使用最新 revision；REVIEW 使用當下回傳的 subjectDigest，不自行推算。taskDigest 對 `[title, description或空字串, category]` 的緊湊 JSON 陣列 UTF-8 bytes 計算 SHA-256，避免欄位換行拼接歧義；subjectDigest 則對按序追加的 CONTRACT／RUN／EXCEPTION eventId 加換行後串接計算 SHA-256。升級後依 server 最新回傳值重新確認，不能沿用舊 taskDigest。


## 事件順序與責任

1. **專案 Git 流程**：新版使用 `get_project_workflow`、`prepare_project_workflow`、`confirm_project_workflow`，先保存專案草稿，再由具 `PROJECT_POLICY` 權限的 owner 核准特定版本；不需要先建任務。舊 **PROJECT_POLICY** 事件保留為 LEGACY 遷移依據，不能直接升級成新版已核准設定。有效版本是後續契約的依據。操作與相容性見[規劃對話](project-git-workflow.md)。公司流程尚未提供時等待確認，不猜分支或自動合併。
2. **CONTRACT**：需求負責人確認 `sourceRef`、`sourceVersion`、`codeRef`、`taskDigest`、`flowRef`、`flowVersion`、`implementationActor`；並提供 `requirements`、`questions`、`assumptions`、`cases`、與目前有效專案流程完全一致的 `gitPolicy`。已使用新版流程時，`body.workflowVersion` 必須等於 `get_project_workflow` 的 `active.version`；核准新版本後，即使七欄相同，舊契約也需重新確認。尚未遷移的舊 PROJECT_POLICY 仍依既有相容規則處理。TODO 可預先指定 implementationActor；認領後須與實際 assignee 一致。需求每項含 `id/state/acceptance`，state 是 CONFIRMED、PENDING 或 ASSUMPTION；案例每項含 `id/kind/scenario/requirementIds`。建議 kind 使用 BROWSER、API 或 UNIT。questions、assumptions 即使為空也明列。
3. **實作與自測**：開發代理使用 `report_implementation(taskId, claimToken?, summary, verificationResults, changedFiles?, commitRef?, expectedVersion?)` 保存變更、自測結果與受測版本。它需要認領權限，可在 IN_PROGRESS／BLOCKED 回報，維持原狀態、不解鎖相依、不要求驗收閘門通過。FAILED／NOT_RUN 必須附 detail，允許誠實保留失敗或未跑結果。這是實作聲明階段，不等於驗收。後續 `complete_task.verificationResults` 仍屬自測聲明。
4. **RUN**：受控執行器提供 `contractId/codeRef/caseId/runId/result/artifactUri/artifactSha256/steps/actual/environment/runnerProfile`。result 使用 PASSED、FAILED 或 NOT_RUN；environment 必須 ISOLATED。須屬契約案例、版本相符、artifactSha256 為 64 字元小寫 SHA-256，且 runnerProfile 在該身分核准清單。每個案例以最新結果為準；後續重跑會取代前次核准例外的適用性。
5. **EXCEPTION**：人類核准者提供 `contractId/codeRef/caseId/reason/approvalRef`，記錄免驗或不適用的理由與核准依據。開發代理不能自行核准略過。非 UI 專案可由需求負責人在 CONTRACT 明訂 `browserApplicable:false` 及非空白 `browserExemptionReason`；未明訂時需要真實 BROWSER 案例。
6. **REVIEW**：不同於原作者及 RUN 執行者的獨立驗收者檢查目前所有證據，提供 `contractId/codeRef/subjectDigest/decision/reason`；decision 為 APPROVED 或 REJECTED。新增契約、執行或例外事件後，舊 digest 不再涵蓋最新證據，必須重新審查。QA 應從原始需求獨立探索，reviewer 檢查測試有效性、安全與漏驗，不能只接受開發提供的案例。
7. **INTEGRATION／RELEASE**：各自提供 `contractId/codeRef/ref`。INTEGRATION 記錄核准的整合版本；RELEASE 是獨立發布紀錄，不是 DONE 的同義字，也不代表 server 已部署。實際 Git／發布操作仍遵守人類授權。
8. **complete_task**：帶 claim token、summary、自測聲明與符合契約 codeRef 的 commitRef；必要驗收、獨立核准及 Git policy 閘門通過才 DONE，相依此時解鎖。`doneWhen=ACCEPTED` 以驗收為門檻；`INTEGRATED` 再要求整合事件。下游需整合程式才可開發時應使用 INTEGRATED。

需求未確認、questions 未解、漏連案例、必要案例失敗／未跑、缺瀏覽器流程或核准免驗、缺獨立 review、任務內容或 Git policy 改變、版本不符，都不能結案。版本變更後以新 CONTRACT 重新確認受影響需求與證據，不能把舊證據直接套用到新程式。

## 派工交接與摘要

DISPATCH 是可恢復的協調紀錄，body 明列 `sessionId/worktree/branch/baseRef/lastSeen/handoff`。leader 可追加同任務的新交接事件，保留先前紀錄。`lastSeen` 是上次回報時間提示，**不是可信 session 存活偵測**；逾時只提示人工盤點，不能據此自動釋放 claim、刪除工作區或推定程式已停止。token 保留於 worker／leader 工作上下文，不寫入 DISPATCH 或檔案。

看板摘要先顯示通過案例、未驗／失敗、待人類決定事項及能否結案的理由，詳細事件按需展開。免驗、簽章核准或測試通過均不代表所有需求已完全符合，也不代表公司環境或發布已驗證。

延伸：[流程卡與 Git policy 模板](project-workflow-template.md) · [備份與隔離還原](backup-recovery.md) · [代理責任](agent-roles.md)

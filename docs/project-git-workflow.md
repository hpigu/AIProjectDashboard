# 在規劃對話設定專案 Git 流程

每個專案第一次規劃時確認一次，之後沿用。流程變更時再修改，不必每張卡重填。看板網頁維持唯讀，設定保存於該專案的資料庫紀錄，與是否已建立任務無關。

## 你怎麼操作

向裝有新版 plugin 的 Codex 或 Claude Code 說：「請為專案『完整名稱』規劃 Git 流程」。也可在專案看板的 **Git 流程設定** 區塊複製這句指令，貼回規劃對話。

代理會先讀取現有設定、工作專案的 AGENTS.md 與 Git／CI 文件，整理能自行查明的內容，只詢問缺項、矛盾及需要你決定的規則。每項來源標為「專案規則」、「人類決定」或「代理建議」，建議不會直接變成已核准規則。

代理保存草稿後，會顯示以下七欄與修改差異，讓你確認具體內容：

| 欄位 | 需要決定什麼 |
|---|---|
| 起始分支 `baseBranch` | 開發從哪個分支開始 |
| 整合目標 `integrationTarget` | 完成後整合到哪個分支 |
| PR／MR 規則 `pullRequestPolicy` | 是否開 PR／MR，以及相關要求 |
| 必要檢查 `requiredChecks` | 必須通過哪些測試／CI 檢查 |
| 審查規則 `reviewPolicy` | QA、獨立審查及核准要求 |
| 合併權限 `mergeAuthority` | 誰能核准、誰能執行合併 |
| DONE 時機 `doneWhen` | `ACCEPTED`：驗收完成；`INTEGRATED`：驗收通過且有整合紀錄 |

DONE 會解鎖相依。下游必須使用整合後程式時，應選 `INTEGRATED`。核准這份設定不代表授權代理執行 commit、push、PR／MR、合併或發布；操作仍需原有授權。

## 如何核准生效

人類需求負責人在獨立 owner 環境使用固定工具：

```bash
node bin/approve-project-workflow.mjs --project '完整專案名稱' --credentials /path/to/owner-credentials.json
```

工具會自行取得草稿、顯示七欄／差異／依據，要求人類在終端機輸入 `確認 <完整專案名稱> #<草稿版本>`，再讀一次以防草稿已變更，簽署並保存核准，最後讀回檢查生效。你不必寫 JSON、複製 token 或搬運 signature。owner 憑證只配置在你控制的獨立環境，不放瀏覽器，也不交給 leader 或開發代理。實際憑證欄位、一次性配置與隔離邊界見[owner 核准工具](project-workflow-approval.md)、工具 `--help`、[管理授權](authorization-boundaries.md)及[驗收協定](acceptance-protocol.md)。

若尚未配置 owner 憑證，代理仍可完成文件探索、提問與草稿；此時會明確顯示「草稿已保存，尚未生效」，不把對話同意冒充後端核准。工具需要互動終端機，不提供自動同意選項。

核准後，代理重新讀回設定，網頁按「重新整理」即可看見版本、核准者與核准紀錄。下一次開工直接沿用已核准版本。修改時另存草稿，不會立即撤銷原有效設定；核准新版本後，既有驗收契約需重新確認是否仍適用。

## 網頁顯示什麼

專案頁即使沒有任何任務，也會顯示 Git 流程設定。可查看目前有效的七欄、版本、核准者與核准紀錄，或展開草稿的缺項、待決問題、來源及差異。讀取失敗可重試，頁面不保存管理憑證或簽署金鑰，也不提供寫入 REST API。

- `UNCONFIGURED`：未設定，先在規劃對話整理。
- `DRAFT`：已有待確認草稿；若另有 active，原設定仍有效。
- `CONFIRMED`：目前設定已經 owner 核准。
- `LEGACY`：找到舊版事件，可作遷移依據，但不直接視為新版已核准設定。

## 給整合者的介面

`get_project_workflow(projectName)` 與 `GET /api/projects/{id}/workflow` 讀取同一摘要。leader 使用管理授權呼叫 `prepare_project_workflow(projectName, expectedRevision, gitPolicy, sources, questions)` 儲存草稿；部分欄位可先留缺，未決問題須明列。revision 不符時重讀再整理，避免覆寫另一人的草稿。

`confirm_project_workflow(actor, payload, signature)` 使用專案範圍 `PROJECT_POLICY` owner 簽署，核准的是特定草稿版本與 digest，管理憑證不能代替。完整欄位、來源與問題由 MCP schema 取得；使用者不必直接呼叫這些工具。草稿與核准記錄存在各後端資料庫，HMAC 識別授權簽署者，仍不取代公司身分治理或實際 Git branch protection。

兩套 plugin 的 `plan-project-workflow` 提供相同對話流程；`claim-tasks` 在缺少已核准設定時會轉入規劃。此變更不會更新既有線上資料庫角色，也不會自動部署 plugin 或重啟正式看板。

## 隔離畫面驗證

`scripts/project-workflow-check/browser.mjs` 使用真 Chrome／Chromium、HTTP MCP 與隔離 Java 看板，覆蓋無任務設定入口、自然語言指令複製、部分草稿／待決問題、補齊、核准 helper 往返、重新整理持久化、重用、修改草稿不影響 active、新版本生效、後端拒絕未授權寫入及畫面讀取失敗重試。使用 Node 內建 CDP，不新增前端建置或測試框架。

此腳本**只能連到已由維護者啟動的隔離 `:8093` fixture**，且需要明確的 `BOARD_WORKFLOW_FIXTURE_CONFIRM=isolated`。fixture 使用固定測試管理 token 與 owner key，詳細值在腳本中，絕不能用於正式服務；owner 專案範圍要涵蓋 fixture 新建的專案。不得以正式資料庫啟動。測試結束只關閉自己啟動的瀏覽器 PID，不重啟或停止看板。

```bash
BOARD_WORKFLOW_FIXTURE_CONFIRM=isolated node scripts/project-workflow-check/browser.mjs --base-url http://127.0.0.1:8093
```

`CHROME_BIN` 可指定已安裝的 Chrome／Chromium 執行檔。輸出保存在臨時目錄，包含操作結果、內容 hash 與桌機／手機截圖。核准 helper 的 TTY 回覆由固定 fixture 模擬，測的是協定與狀態轉換，**不是取得真實人類核准**；也不表示公司流程或正式環境已驗收。

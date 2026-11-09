# 工作專案流程卡與 Git 設定範例

將確認後的流程卡放在各工作專案的版本控制，例如 `docs/qa/flows/`。公司專屬行為由公司需求負責人確認，不存進通用角色指引。下方是模板，範例通過不代表任何真實公司系統已驗收。

## 流程卡模板

```yaml
id: user-role-permission
status: DRAFT # CONFIRMED 僅在需求負責人確認後使用
sourceRef: docs/requirements/access-control.md
sourceVersion: <需求檔的 commit 或不可變版本>
flowVersion: <本卡的 commit 或不可變版本>
applicableCodeRef: <完整受測 commit／內容 digest>
owner: <需求負責人>
confirmedBy: <確認紀錄>
confirmedRules:
  - <附需求出處的已確認規則>
assumptions:
  - <代理推測，不能視為需求>
questions:
  - <尚需人類決定的業務問題及受影響條件>
preconditions:
  - <隔離環境、測試資料、必要權限>
steps:
  - 新增使用者
  - 指派角色
  - 設定角色權限
  - 用新帳號登入
  - 執行允許的操作，確認資料儲存、重新整理仍正確及下游可使用
  - 直接送出禁止操作的請求，確認拒絕且資料未變動
cases:
  - <對應需求 ID、正常／錯誤／邊界／權限／失敗情境與具體預期>
evidence:
  - <caseId、runId、實際結果、步驟、環境、版本、artifact URI 及 SHA-256>
```

QA 先自行探索需求、文件、畫面與程式，整理出以上卡片；實作只能作線索。尚未確認的業務問題阻擋對應驗收。截圖輔助說明，必須配合資料與後續行為斷言；錄影不能單獨當正確性證明。變更需求或程式後，重新確認案例與舊證據是否適用。

## 專案 Git policy

透過[規劃對話](project-git-workflow.md)整理七欄、保存草稿並由 owner 核准；CONTRACT 的 `gitPolicy` 必須與最新已核准設定一致。這是個人專案沿用既有流程的可審查範例，**不是公司的規則，也不是授權執行 Git 操作**：

```json
{
  "baseBranch": "dev",
  "integrationTarget": "dev",
  "pullRequestPolicy": "本機整合；PR 另需使用者授權",
  "requiredChecks": "單元、API、瀏覽器流程與獨立審查",
  "reviewPolicy": "QA 獨立驗證整合候選版本；reviewer 唯讀審查完整差異",
  "mergeAuthority": "人類授權的 leader",
  "doneWhen": "INTEGRATED"
}
```

公司專案先請負責人提供各欄位，不猜起始分支、PR／MR、審查或合併權限。`doneWhen=ACCEPTED` 表示獨立驗收通過即可 DONE；`INTEGRATED` 再要求記錄整合版本。DONE 才解鎖相依，因此需要整合後程式的下游應用 `INTEGRATED`。發布另以 RELEASE 記錄，不能把 DONE 當發布授權。

## 證據接收與授權

`record_acceptance_event(actor, payload, signature)` 接收簽署事件；payload 是原始 JSON，含 `eventId`、`projectId`、`taskId`、`expectedRevision`、`kind`、`body`。讀取使用 `get_acceptance` 或 `GET /api/projects/{id}/tasks/{taskId}/acceptance`。PROJECT_POLICY 是舊版專案設定事件；新版使用專案草稿／核准介面，詳見[規劃對話](project-git-workflow.md)。CONTRACT 連結來源版本、受測 codeRef、taskDigest、流程卡、需求、案例與 Git policy；RUN、REVIEW、EXCEPTION、INTEGRATION 分別保留執行、獨立結論、核准例外與整合紀錄。

`BOARD_ACCEPTANCE_AUTHORITIES` 設定可信 actor 的 secret（至少 32 字元）、projectIds、permissions 與 runnerProfiles。金鑰只放獨立受控環境，開發代理不可取得。受控執行器使用預先核准的固定執行方式與隔離資料；看板不直接執行代理提交的 shell。部署前應由人類配置權責不同的簽署者；單純填寫 PASSED、簽一份自述，或附一張截圖都不能提升成可信執行證據。

完整事件、權限與舊任務相容性見 [驗收協定](acceptance-protocol.md)。HMAC 證明設定的簽署身分，不自動證明敘述真實；CI 簽署端需隔離於開發代理與受測程式。

# 管理授權與驗收簽署邊界

這份說明是可審查的設定指引；本次開發沒有修改正式服務、外部帳戶、公司 AWS 或既有線上角色。管理憑證與驗收 HMAC 是不同用途，均不代表公司 SSO 或完整多使用者授權。本機服務維持 localhost，不將 `/mcp` 公開。

## 管理工具預設拒絕

`create_project`、`create_tasks`、`reset_task_claim`、`update_task_details`、`set_task_dependencies`、`preview_archive_project`、`archive_project`、`restore_project`、`upsert_role`、`prepare_project_workflow` 需要獨立管理授權。即使 preview 不變更資料，也屬管理邊界。未設定或授權不符時預設拒絕；claim token 不提供管理權限，角色文字也不能擴權。

Java 本機服務由維護者設定至少 32 字元的隨機 `BOARD_MANAGEMENT_TOKEN`，只有 leader 專用 client 在管理請求帶 `Authorization: Bearer <管理token>`。不要將 token 寫進 repo、角色內容、派工訊息、一般 worker 共用的 connector 或 shell 指令歷史。若 client 無法將 leader 與 worker 的憑證隔離，不應把管理憑證配給該共用 client；先使用分開設定的 client。設定憑證不是授權所有操作，封存、更新角色、部署與外部寫入仍需使用者對應授權。

Workers 使用 `MANAGEMENT_DEVICE_IDS` JSON 字串陣列，與既有驗證流程取得的 deviceId 比對。例如 `["<已核准leader裝置的deviceId>"]` 只是格式示意，不是有效公司設定。缺少、格式錯誤、空陣列或未列入的裝置都沒有管理權限；不能從請求參數自稱 deviceId 取得權限。只有經既有裝置驗證成功且明列的 leader 裝置可管理，worker 裝置不得加入。這個裝置清單不是通用 SSO、專案級角色系統或公司身分整合。

## 可稽核的授權嘗試

資料庫 `management_authorization_audit` 記錄 actor、tool_name、decision 與時間；不保存 secret 或完整工具 args。Java 有效憑證記為 `configured-management-credential`，代表共用設定憑證，不冒稱辨識到某位自然人。Workers 保存驗證過的 deviceId。無法確認的呼叫者記為 `unverified-caller`。

- `AUTHORIZED_ATTEMPT`：呼叫通過管理權限檢查，後續業務動作仍可能驗證失敗或回滾。
- `DENIED`：此次管理授權未通過。

授權嘗試不等於業務成功。判斷資料是否修改，須另查任務／專案歷史與實際結果；也不能把這份管理稽核當成 RUN 或獨立驗收證據。

## 驗收身分與 worker

驗收 HMAC 另由 `BOARD_ACCEPTANCE_AUTHORITIES` 設定專案範圍、事件權限、runner profile 與每個 actor 的不同金鑰。管理 token 不可簽署驗收；驗收金鑰也不提供管理工具權限。RUN 執行器與需求核准、REVIEW、例外核准分離，詳見[驗收協定](acceptance-protocol.md)。

worker 只取得最小生命週期工具，不能取得 leader 管理憑證、核准裝置身分或驗收簽署金鑰。需要改規格、相依、角色或復原 claim 時，只回報事實與建議，由已授權 leader 操作。repo 指引更新不會同步既有線上角色；請維護者先閱讀、比對，取得當前明確授權後再更新。

專案流程由 leader 保存草稿、人類 owner 獨立核准。`confirm_project_workflow` 需要專案範圍 PROJECT_POLICY HMAC，不接受管理 token 代簽。固定 owner 工具的互動流程見[專案 Git 流程](project-git-workflow.md)；憑證留在獨立 owner 環境，網頁只取得唯讀摘要。

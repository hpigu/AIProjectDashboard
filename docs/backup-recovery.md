# 加密輸出與隔離還原演練

看板現有的冷備份／H2 `BACKUP TO` 快照不等於離機備份。新增維護工具只接受明確選擇的完成快照，不會讀取看板設定、操作執行中資料庫、啟動服務或連線任何埠號。需要 Node 22 以上；ZIP 演練另需 Python 3。

## 明確指定金鑰、來源及目的地

由人類維護者在核准的環境產生 32 bytes 隨機金鑰，保存在備份目的地以外的安全位置；不得將金鑰、客戶資料或加密備份加入 Git。POSIX 金鑰檔須由目前使用者持有且權限為 `600`。Windows 的檔案 ACL 由維護者另行控管。金鑰遺失無法復原。

```sh
# 以下路徑都是示意，不能直接當作已設定的公司環境。
node scripts/backup-check/maintenance.mjs encrypt \
  /approved/snapshots/board-scheduled-20261109.zip \
  /approved/offsite-mount \
  /secure/board-backup.key
```

來源限定 `board-startup-*.mv.db`、`board-shutdown-*.zip` 或 `board-scheduled-*.zip`，最大 128 MiB。請選擇已完成的一致性快照，不能把執行中的資料庫改名後當成冷備份。工具以 AES-256-GCM 加密，使用每份備份獨立的 96-bit 隨機 nonce；來源名稱、格式、時間、大小與 SHA-256 都包含在驗證資料內，修改內容或 metadata 都無法通過還原驗證。

目的目錄必須已存在且明確指定。可以是經核准的離機掛載點，但工具無法判斷它是否真的在另一台機器，也無法確認遠端儲存 SLA、持久性、版本鎖定及留存政策。因此輸出固定標記 `offsiteVerified: false`，只表示指定路徑已寫入一份加密檔；尚未指定目的地時，離機備份狀態就是「未設定」。不提供上傳 shell hook，也不自動排程、清除舊備份或連線 AWS。

備份失敗會回傳非零 exit code，stderr 顯示「未完成」，不印出金鑰或資料內容。若接入經核准的排程／CI，必須以非零 exit code 觸發失敗通知，並監控最後成功時間，不能只檢查目錄內有沒有舊檔。中斷留下的不完整加密檔會在驗證時被拒絕；不應視為有效備份。現有 Java 排程備份的告警仍需由實際部署的日誌收集／通知管道承接。

## 只寫入新隔離目錄的演練

```sh
node scripts/backup-check/maintenance.mjs drill \
  /approved/offsite-mount/<加密檔名>.abbackup \
  /secure/board-backup.key
```

工具先完成 GCM 驗證、大小及 SHA-256 比對，再於系統暫存目錄建立全新的 `ai-board-restore-drill-*`。不接受自訂還原資料庫路徑，也不覆寫現有資料。ZIP 只允許一個無目錄的 `.mv.db`、拒絕符號連結／路徑穿越／超量解壓，固定寫入隔離目錄的 `restored.mv.db`。失敗不會回報通過。

結果及 `report.json` 明確標記：

- `FILE_RESTORE_VERIFIED`：完成驗證解密、來源雜湊及 H2 檔頭檢查。
- `databaseOpened: false`：尚未以 H2 開啟、檢查資料列與 migration。
- `businessAcceptance: NOT_EXECUTED`：尚未啟動測試看板或確認業務流程。
- `offsiteVerified: false`：未證明實際離機。

這是可重現的檔案還原階段，不能宣稱已完成完整災難復原。後續由維護者在核准的隔離環境，用隔離埠號／DB／log 啟動相同受測版本，核對專案、任務、相依、驗收及身分紀錄，再執行瀏覽器流程。演練資料與報告留在暫存目錄，不自動刪除，應依組織資料留存規則收回。報告本身是本機工具輸出，不是已驗證操作者身分的管理核准紀錄。

## 自動驗證與維護

```sh
node --test scripts/backup-check/*.test.mjs
```

測試覆蓋加密往返、來源不變、金鑰權限、未設定目的地、拒絕 live DB 檔名、錯誤金鑰、內容／metadata 篡改、ZIP 路徑穿越及失敗 exit code。範例資料只有 H2 檔頭，刻意不聲稱能開啟成實際 H2 資料庫。Workers 另有以 Wrangler `--local` 匯出／還原兩份獨立 D1 的測試；兩者都納入 CI。這些測試不會替公司資料庫背書。

CI 使用 `npm ci --ignore-scripts` 安裝鎖定相依並執行 Workers 測試；Dependabot 每週檢查 `/workers` npm 相依。這些設定提交並由 GitHub 實際執行後才會產生遠端結果，本次本機測試不等於 CI 已通過。

技術依據：[Node Crypto GCM 與驗證資料](https://nodejs.org/api/crypto.html)、[GitHub Dependabot 設定](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference)。

## 真的開啟 H2 的可重現範例

另提供固定測試資料的整段演練（需 JDK 21、Node 22、Python 3 及從正式相依來源取得的 H2 driver；不要執行代理證據中附帶的未知 JAR）：

```sh
JAVA_HOME=/path/to/jdk21 node scripts/backup-check/real-h2-drill.mjs /path/to/trusted/h2-driver.jar
```

這支程式自行建立全新暫存資料庫，新增一筆固定測試資料，以 H2 `BACKUP TO` 產生 ZIP，再加密、隔離還原，最後用 H2 JDBC 分別重開來源與還原資料庫，查詢同一筆資料。只有兩邊都找到預期資料才輸出 `REAL_H2_ROW_REOPEN_VERIFIED`，並將結果存入新暫存目錄的 `actual-h2-result.json`。沒有埠號、應用服務或正式資料庫路徑參數，也不會自動清除任何既有檔案。

本機已用 H2 2.4.240、JDK 21 跑過此流程，來源與還原皆查到 `1 / isolated-data-survives`。這進一步證明範例資料可在 H2 重開，仍不代表真實公司資料、看板完整 schema、migration、權限或業務流程已驗收。`offsiteVerified` 仍為 `false`。

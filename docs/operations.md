# 維運手冊：啟停、備份還原、疑難排解

給實際在用這個看板的人。安裝方式見 [installation.md](installation.md)，
開發規則見 [../AGENTS.md](../AGENTS.md)。

## 服務生命週期

一律用 `bin/board`，不要自己 `java -jar` 或 `ps | grep | kill`：

```bash
bin/board start      # 啟動（背景執行，脫離終端機）
bin/board status     # PID、埠號、/api/health 版本資訊
bin/board stop       # SIGTERM 並等待關閉前備份完成
bin/board restart
bin/board logs -n 200
```

### 為什麼不要自己 kill

`bin/board stop` 送的是 `SIGTERM`，Spring 的 `ContextClosedEvent` 會觸發
`ShutdownBackupService`，用 H2 的 `BACKUP TO` 產生一份一致性快照。
`kill -9`（SIGKILL）不給行程任何機會執行 shutdown hook，那份快照就不會存在。
因此 `stop` 逾時後**不會**自動升級成 SIGKILL；確定要強制終止時才手動加：

```bash
bin/board stop --force   # 會失去這一次的關閉前備份
```

`stop` 動手前會確認 PID 檔指向的行程真的是看板（PID 會被作業系統回收），
不是就直接說「未在執行」並清掉殘留 PID 檔，不會誤殺別的行程。

### Windows

Windows 沒有 bash，改用同名的 `.ps1`（預設值、啟動前備份、保留策略都與 bash 版一致）：

```powershell
.\bin\board.ps1 start
.\bin\board.ps1 status
.\bin\board.ps1 stop
.\bin\board.ps1 restart
.\bin\board.ps1 logs -Lines 200
```

**停止的機制不同，必須知道**：Windows 沒有 SIGTERM，`Stop-Process` 等同
`kill -9`，會跳過 JVM shutdown hook，關閉前備份就不會產生。因此 `stop` 改用
console control event（`CTRL_C_EVENT`）通知行程，效果等同 mac/Linux 的 SIGTERM。

兩個 Windows 專屬情況：

- **看板是你手動 `java -jar` 起的**：它與那個終端機視窗共用 console，送 Ctrl+C
  會連視窗一起打斷。`stop` 會偵測到並要求你直接到那個視窗按 Ctrl+C（效果相同，
  同樣會產生關閉前備份）。改用 `.\bin\board.ps1 start` 之後就沒有這個問題。
- **啟動失敗但日誌沒東西**：背景模式下看板有自己的隱藏 console，logback 初始化
  前的輸出不會落檔。改用 `.\bin\board.ps1 start -Foreground` 在當前視窗執行，
  直接看到那段輸出。

還原用 `.\bin\restore-db.ps1 -List` 與 `.\bin\restore-db.ps1 latest`，
語意與 bash 版相同（拒絕在執行中還原、保留現有資料庫、驗證後才原子改名）。

改動這些腳本後可用內建檢查驗證（不需要看板在跑，也不會碰到正式資料）：

```powershell
pwsh -NoProfile -File scripts\windows-check\check.ps1
```

它會做語法檢查，並用假的 H2 檔案跑完整的備份／保留策略／兩種格式還原／拒絕路徑。
CI 也會在 windows-latest 上跑同一支腳本（Windows PowerShell 5.1 與 pwsh 7 各一次）。
JDK 偵測、埠號反查與 CTRL_C 的實際效果無法自動驗證，需依下方演練清單手動確認。

### 開機自動啟動

**macOS（launchd）**：建立 `~/Library/LaunchAgents/dev.aiboard.board.plist`，
把 `<repo>` 換成實際路徑：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>dev.aiboard.board</string>
  <key>ProgramArguments</key>
  <array>
    <string><repo>/bin/board</string>
    <string>start</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>StandardErrorPath</key><string>/tmp/ai-project-board.launchd.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/dev.aiboard.board.plist
```

**Linux（systemd user unit）**：`~/.config/systemd/user/ai-project-board.service`

```ini
[Unit]
Description=AI Project Board
After=network.target

[Service]
Type=forking
ExecStart=%h/path/to/repo/bin/board start
ExecStop=%h/path/to/repo/bin/board stop
# stop 要等關閉前備份跑完，逾時要比 BOARD_STOP_TIMEOUT_SEC 寬鬆
TimeoutStopSec=120
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now ai-project-board
loginctl enable-linger "$USER"   # 沒登入時也保持執行
```

## 備份

三種備份，共用同一個目錄（`BOARD_BACKUP_DIR`，預設
`~/.ai-project-board/backups`）：

| 檔名 | 產生時機 | 方式 |
|---|---|---|
| `board-startup-<UTC>-<TZ>.mv.db` | 每次啟動、Flyway migration 之前 | 檔案複製 + H2 檔頭驗證；失敗會中止啟動 |
| `board-shutdown-<UTC>-<TZ>.zip` | 正常關閉時（SIGTERM／Ctrl+C） | H2 `BACKUP TO` 一致性快照；失敗只記 ERROR，不阻塞關閉 |
| `board-scheduled-<UTC>-<TZ>.zip` | 執行中，預設每 6 小時 | 同上；失敗只記 ERROR，不影響後續排程 |

保留策略是 30 天內全留；超過 30 天的只在刪除後仍剩 ≥ 7 份時才刪。
**這個額度是各階段獨立計算的**：`scheduled` 再頻繁也不會把 `shutdown` 那份
一致性快照擠掉——長時間運行下，那份往往正是最值得留的。

### 排程備份

| 環境變數 | 預設 | 說明 |
|---|---|---|
| `BOARD_BACKUP_INTERVAL` | `6h` | 支援 `6h`／`90m`／`PT6H` 等寫法 |
| `BOARD_BACKUP_SCHEDULE_ENABLED` | `true` | 設 `false` 完全停用 |

間隔從「上一次執行結束」起算（`fixedDelay`），大型資料庫備份較久時任務不會
堆疊。啟動後會先等一個間隔才跑第一次——啟動前備份剛做過，不需要立刻再一份。

**涵蓋不到的情境**：`kill -9`、JVM crash、斷電。這些情況下能還原到的最近狀態，
就是上一份排程備份（預設最多損失 6 小時），或上一次啟動前的備份。

## 還原

還原前先確認要還原到哪個時間點：

```bash
bin/restore-db.sh --list
```

```
[restore-db] 備份目錄：/Users/you/.ai-project-board/backups
  2026-08-06 02:10:51 CST        8626 bytes  定期（一致性快照）    .../board-scheduled-20260805T181051Z-UTC.zip
  2026-08-05 20:53:56 CST        9984 bytes  關閉前（一致性快照）  .../board-shutdown-20260805T125356Z-UTC.zip
  2026-08-05 20:54:06 CST       53248 bytes  啟動前（冷備份）      .../board-startup-20260805T125406Z-UTC.mv.db
```

`latest` 取的是這份清單的第一列。看板長時間運行時那通常是一份排程備份，
而不是關閉前備份。

然後停掉看板再還原（還原中的看板會寫壞資料庫，腳本會直接拒絕）：

```bash
bin/board stop
bin/restore-db.sh latest              # 或指定某個備份檔的完整路徑
bin/board start
```

還原做的事，依序：

1. 確認看板沒在跑、資料庫檔沒被任何行程持有；
2. 解出備份內容（`.zip` 會取出裡面的 `.mv.db`），驗證 H2 檔頭；
3. **現有資料庫改名保留**成 `board.mv.db.pre-restore-<UTC>`，不刪除；
4. 以 `.tmp` → 驗證 → 原子改名的方式寫入新資料庫；
5. 清掉 `.trace.db` / `.lock.db` 殘留。

還錯了備份的話，把保留檔改回原檔名即可：

```bash
mv ~/.ai-project-board/data/board.mv.db.pre-restore-20260805T125358Z \
   ~/.ai-project-board/data/board.mv.db
```

還原後務必啟動並確認資料是預期的時間點：

```bash
bin/board start
curl -s http://127.0.0.1:8080/api/health/ready   # 三項檢查都要 pass
curl -s http://127.0.0.1:8080/api/projects
```

### 發版前的手動演練清單

備份沒演練過等於沒備份。改動備份／還原／啟停相關程式碼後，至少跑一次：

1. `bin/board start`，經 MCP 建立一個測試專案
2. `bin/board stop`，確認 `backups/` 出現新的 `board-shutdown-*.zip`
3. 故意破壞資料庫：`echo garbage > <data-dir>/board.mv.db`
4. `bin/restore-db.sh latest --yes`
5. `bin/board start`，確認 `/api/health/ready` 為 `UP` 且測試專案還在

用開發用的埠號與資料目錄跑（`BOARD_PORT`、`BOARD_HOME_DIR`），不要動正式看板。

## 疑難排解

| 症狀 | 原因與處理 |
|---|---|
| `找不到 JDK 21` 但明明裝了 | 舊版腳本只看 `java -version` 第一行，`JAVA_TOOL_OPTIONS` 的 "Picked up ..." 提示會排在版本字串前。已修正；若仍發生，用 `BOARD_JAR` 搭配自己的 java 啟動 |
| 啟動時 `MVStoreException` | 舊行程還持有 H2 檔案。`bin/board status` 看 PID，或依腳本印出的 PID 結束該行程 |
| 瀏覽器一片空白、console 有 403 | Host/Origin guard 擋下了非 loopback 的來源。用 `localhost`／`127.0.0.1` 開啟；若刻意要從區網存取，設定 `BOARD_ALLOWED_HOSTS` |
| 停止後看板還在 | PID 檔遺失時 `stop` 會改用埠號反查；都找不到就是行程不是本腳本啟動的，用 `lsof -i :8080` 確認 |
| 啟動失敗但日誌沒東西 | 看 `<BOARD_LOG_FILE>.console`：logback 初始化前的錯誤只會出現在那裡 |
| 看板空的、沒有任何專案 | 正常。寫入只走 MCP，REST 唯讀，沒有種子資料；照首頁的三步操作 |
| `/api/events` 回 503 | 已達 SSE 連線上限（`BOARD_SSE_MAX_CONNECTIONS`，預設 32）。關掉沒在用的看板分頁；持續發生代表有東西一直開新連線卻沒關舊的 |
| 分頁的畫面突然重新整理了一次 | 該連線的事件佇列滿了（跟不上），伺服器主動斷線讓它重連並整批重抓。偶爾發生是正常的自我修復；頻繁發生可調高 `BOARD_SSE_CLIENT_QUEUE_CAPACITY` |
| Windows：`stop` 說與視窗共用 console | 看板是手動 `java -jar` 起的，直接到那個視窗按 Ctrl+C；之後改用 `.\bin\board.ps1 start` |
| Windows：`board.ps1` 無法執行（執行原則） | 以 `powershell -ExecutionPolicy Bypass -File .\bin\board.ps1 start` 執行，或為目前使用者放寬：`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |

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

兩種備份，共用同一個目錄（`BOARD_BACKUP_DIR`，預設
`~/.ai-project-board/backups`）與同一套保留策略（30 天內全留；超過 30 天的
只在刪除後仍剩 ≥ 7 份時才刪）：

| 檔名 | 產生時機 | 方式 |
|---|---|---|
| `board-startup-<UTC>-<TZ>.mv.db` | 每次啟動、Flyway migration 之前 | 檔案複製 + H2 檔頭驗證；失敗會中止啟動 |
| `board-shutdown-<UTC>-<TZ>.zip` | 正常關閉時（SIGTERM／Ctrl+C） | H2 `BACKUP TO` 一致性快照；失敗只記 ERROR，不阻塞關閉 |

**涵蓋不到的情境**：`kill -9`、JVM crash、斷電。這些只能靠上一次啟動前備份。
如果看板連續跑好幾天不重啟，就等於好幾天沒有新快照——長時間運行時建議定期
手動 `bin/board restart`，或自行加排程。

## 還原

還原前先確認要還原到哪個時間點：

```bash
bin/restore-db.sh --list
```

```
[restore-db] 備份目錄：/Users/you/.ai-project-board/backups
  2026-08-05 20:53:56 CST        9984 bytes  關閉前（一致性快照）  .../board-shutdown-20260805T125356Z-UTC.zip
  2026-08-05 20:54:06 CST       53248 bytes  啟動前（冷備份）      .../board-startup-20260805T125406Z-UTC.mv.db
```

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

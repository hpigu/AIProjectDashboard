# Changelog

本檔記錄對使用者可見的變更。格式參考
[Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/)，版號遵循
[語意化版本](https://semver.org/lang/zh-TW/)。

「使用者可見」的判準是：會不會改變別人安裝、啟動、升級或呼叫這個看板的方式。
純內部重構不進這份清單，留在 git log 即可。

## [Unreleased]

### 新增

- **排程備份。** 執行中每 6 小時產生一份 H2 一致性快照
  （`board-scheduled-<UTC>-<TZ>.zip`），可用 `BOARD_BACKUP_INTERVAL` 調整、
  `BOARD_BACKUP_SCHEDULE_ENABLED=false` 停用。

  在此之前備份只在**啟動**與**正常關閉**兩個時間點發生，而這個看板的賣點正是
  「讓 agent 跑好幾天、人不用盯著」——也就是行程長時間不重啟。連續跑兩週等於
  兩週沒有新快照，期間所有由 MCP 寫入的資料都只存在於單一個 `.mv.db` 檔案裡；
  遇到 `kill -9`、crash 或斷電，能還原到的最近狀態是兩週前開機那一刻。

  保留策略改為**依階段分桶**：`startup`／`shutdown`／`scheduled` 各自擁有獨立的
  份數額度。若共用一個桶，頻繁的排程備份會把關閉前那份一致性快照擠出保留範圍，
  而那份往往是最值得留的一份。

### 修正

- **`qa` 的看板指引叫它呼叫一個自己拿不到的工具。** `RoleSeeder` 的通用 QA 指引
  寫著「發現 production bug 時呼叫 `create_tasks` 建立新任務」，但 worker 的工具
  白名單從來就沒有 `create_tasks`，plugin 薄殼也明文禁止。agent 讀到看板指引後
  照做，只會撞上一個不存在的工具。指引改為與薄殼一致：只回報 leader，由 leader
  決定要不要建任務。
- **README 的「已知限制」宣告了一個已經不存在的缺陷。** 上一版說「備份只在啟動與
  關閉觸發，沒有排程機制」，但排程備份已經隨本次變更加入。同樣沒進 README 的還有
  SSE 連線上限與背壓行為，以及 `/api/projects/{id}/dependencies`、
  `/api/projects/{id}/tasks/{taskId}` 兩個端點。已全部補上。

- **一個讀取太慢的瀏覽器分頁會拖慢 agent 的 MCP 呼叫。** 事件廣播跑在
  `AFTER_COMMIT` 的同步監聽器上，也就是 agent 的 tool call 執行緒；廣播時逐一對
  每條 SSE 連線寫入，任何一條 socket 阻塞都會讓 `create_tasks` 這類呼叫跟著卡住。
  一個開著沒人在看的分頁，可以拖慢真正在做事的 agent。

  改為：commit 執行緒只把事件放進各連線的佇列（不做 I/O），實際寫入交給背景
  執行緒。單一連線的事件順序仍然保證。

### 變更

- **跟不上的 SSE 客戶端會被斷線，而不是無限緩衝。** 每條連線有自己的事件佇列
  （`BOARD_SSE_CLIENT_QUEUE_CAPACITY`，預設 128）；滿了就結束該連線。前端本來
  就會在斷線重連後整批重抓，因此斷線反而讓它自我修復到正確狀態——默默丟事件則
  會讓畫面停在一個沒人知道是錯的狀態。
- **`/api/events` 新增連線上限**（`BOARD_SSE_MAX_CONNECTIONS`，預設 32），
  超過回 503。每條連線都佔用一個永不結束的 async request，無上限收下去只會讓
  行程慢慢被拖垮且毫無跡象。正常使用（單機、個位數分頁）不會碰到。
- `bin/restore-db.sh` 與 `bin\restore-db.ps1` 的 `--list`／`latest` 納入排程備份。
  看板長時間運行時最新的一份幾乎必然是排程備份，漏掉它會讓 `latest` 安靜地挑到
  一份舊得多的快照。`/api/diagnostics` 的 `latestBackup` 同步涵蓋。
- **`bin/start-board.sh` 已移除，內容併入 `bin/board`。** 啟動一律用
  `bin/board start`（Windows 不變，仍是 `.\bin\board.ps1 start`）。原本只有 bash
  側把「啟動」切成獨立腳本，Windows 側從第一天就是合併的；兩邊形狀不一致沒有帶來
  任何好處，只讓「我該執行哪一支」變成需要查文件的問題。現在每個平台各四支、
  一一對應：`board`／`board-env`／`backup-db`／`restore-db`。
  還原仍是獨立的 `restore-db.*`：它必須在看板停止時執行且會覆寫資料庫檔，刻意不
  放進天天使用的入口。
- **README 重整。** 原本單檔 34KB 把入門與治理混在一起，第一次看的人要捲過工具
  治理、claim token 與備份保留策略才會看到怎麼裝。現在 README 是入門路徑
  （安裝 → 接 agent → 第一次使用 → 設定），Windows 與 mac／Linux 並列而非附註；
  完整的介面與角色參考移到 [docs/mcp-tools.md](docs/mcp-tools.md) 與
  [docs/agent-roles.md](docs/agent-roles.md)。
- **CI 不再對同一份程式碼跑兩輪。** `on.push` 從 `['**']` 改為 `[main]`。分支
  push 與該分支的 PR 會各觸發一次，三個 job 變成六個檢查、兩兩內容完全相同；
  `concurrency` 擋不掉，因為兩者的 `github.ref` 分屬不同 group。現在分支由
  `pull_request` 跑、合進 main 由 `push` 跑，覆蓋率不變。

## [3.1.0] — 2026-08-06

3.0.0 之後的第一個正式發布版。3.0.0 從未推過 tag，因此這也是本專案第一次有可
下載的 release 產物。

### 新增

- **BLOCKED 桌面通知。** 標題列多一個開關，開啟後任務轉為 BLOCKED 會發送系統
  通知，點擊直接跳到該專案。預設關閉，偏好記在瀏覽器；看板視窗在前景時不發
  （畫面上已經看得到）。BLOCKED 是唯一一定需要人介入的狀態，原本只反映在畫面
  上，人不在看板前面就等於沒通知到。
- `task.status_changed` 事件的 payload 新增 `title`（任務標題）。少了它，通知
  內容只會是「任務 #42 → BLOCKED」，讀了還是得回看板查是哪一個任務。
- `/api/health` 與 `/api/diagnostics` 回傳 `commit` 欄位（build 來源的 short
  commit hash）。同一個版號在 tag 前後可能對應好幾份不同的 build，只有版號無法
  分辨手上跑的是哪一份。
- 建置期產生 `META-INF/build-info.properties` 與 `git.properties`
  （spring-boot-maven-plugin 的 `build-info` goal 與 git-commit-id-maven-plugin）。

### 修正

- **關閉時若有瀏覽器開著看板，行程不會結束。** SSE 連線是永不逾時的請求，
  Spring Boot 的 graceful shutdown 會一直等它；逾時後 JVM 依然不退出，留下一個
  持續持有 H2 `.mv.db` 鎖的行程，下一次啟動直接 MVStoreException——而
  `bin/board stop` 早已回報「已停止」。關閉時改為主動結束所有 SSE 連線。
  實測同樣情境下，graceful shutdown 從「30 秒逾時後仍卡住」變成 3 毫秒完成。
  這個問題不限 Windows，Linux/macOS 走 SIGTERM 也一樣。
- MCP `initialize` 回應的 `serverInfo.version` 寫死在 `application.yml`，跳版時
  必然忘記改（3.1.0 的 build 仍回報 3.0.0）。改由 `pom.xml` 的版本帶入。

- **Windows：`bin\board.ps1 start` 在 Windows PowerShell 5.1 上完全無法啟動。**
  JDK 偵測一律回報「找不到 JDK 21」，即使系統上裝著。`java -version` 寫的是
  stderr，而 5.1 在 `$ErrorActionPreference='Stop'` 之下會把 native 指令的
  stderr 包成終止性的 `NativeCommandError`，使偵測函式必定落進 catch。
  pwsh 7 沒有這個行為，所以只在 7 上測不會發現。
- **Windows：`bin\board.ps1 stop` 可能在關閉前備份寫完之前就回報成功。**
  Oracle JDK 官方安裝檔建立的 `javapath\java.exe` 是 launcher stub，會再 spawn
  一個真正跑 JVM 與 shutdown hook 的子行程，PID 檔記到的是 stub。等待條件已改為
  「行程消失且埠號釋放」；`-Force` 路徑也會一併終止仍持有埠號的看板子行程
  （動手前以命令列比對確認該行程真的是看板）。
- `/api/health` 的 `version` 在 `mvnw spring-boot:run` 或 IDE 啟動時一律回報
  `unknown`。原本讀的是 jar manifest 的 `Implementation-Version`，只有打包後才
  存在。改由建置期產生的 `build-info.properties` 提供，三種啟動方式一致。
- **Windows：`bin\board.ps1 logs` 的中文日誌顯示為亂碼。** logback 以 UTF-8 寫檔，
  但 Windows PowerShell 5.1 的 `Get-Content` 預設用系統 ANSI 代碼頁解碼。

### 相容性

- `/api/health`、`/api/diagnostics` 與 `task.status_changed` 事件的內容都是
  **新增**欄位，既有欄位的名稱、型別與語意都未更動。只讀取既有欄位的呼叫方
  （含 `bin/start-board.sh` 對 `"version"` 的檢查）不受影響。
- 資料庫 schema 無變更，從 3.0.0 升級不需要額外步驟：換掉 jar 重啟即可。

## [3.0.0] — 2026-08-05

產品化基礎的一批改動（PR #1）。此版本未推 tag，沒有 release 產物。

### 新增

- `bin/board`（start／stop／restart／status／logs）與 Windows 對應的
  `bin\board.ps1`，取代直接 `java -jar`。`stop` 會等待關閉前備份完成。
- `bin/restore-db.sh` 與 `bin\restore-db.ps1` 還原路徑，支援啟動前（`.mv.db`）與
  關閉前（`.zip`）兩種備份格式。
- 首次啟動引導：空看板顯示三步驟說明與可複製的 MCP 端點。
- CI：每次 push／PR 跑測試與打包，另以 shellcheck 檢查 `bin/` 腳本，並在
  windows-latest 上以 PowerShell 5.1 與 pwsh 7 各跑一次腳本檢查。
- release workflow：推 `v*` tag 會產出可下載的 jar。
- `SECURITY.md`、`README.en.md`、`docs/operations.md`。

### 安全性

- `LocalOriginGuardFilter`：非 loopback 的 `Host`／`Origin` 一律回 403，阻擋
  DNS rebinding。`BOARD_ALLOWED_HOSTS` 為刻意放行的逃生門。
- `/api/health` 不再回傳絕對資料庫路徑等檔案系統資訊。

### 修正

- 備份保留策略在 Linux 上排序鍵取得錯誤，可能刪掉最新的備份而非最舊的。
- JDK 21 在設有 `JAVA_TOOL_OPTIONS` 的環境下被誤判為未安裝。
- 備份路徑含空白時保留策略失效。
- 關掉終端機的 SIGHUP 會殺掉看板，連帶失去關閉前備份。

[Unreleased]: https://github.com/hpigu/AIProjectDashboard/compare/v3.1.0...HEAD
[3.1.0]: https://github.com/hpigu/AIProjectDashboard/releases/tag/v3.1.0
<!-- 3.0.0 沒有 tag，只能指向當時的 PR。 -->
[3.0.0]: https://github.com/hpigu/AIProjectDashboard/pull/1

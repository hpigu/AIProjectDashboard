# 開發隔離基線

本文件由 task #104 建立，規範「正式看板持續運作」前提下的開發環境隔離。

## 為什麼需要隔離

正式看板是使用者實際在用的服務，開發期間**不得中斷**。它佔用：

| 資源 | 正式值 |
| --- | --- |
| 埠號 | `8080` |
| 資料庫 | `<repo>/data/board.mv.db` |
| 日誌 | `<repo>/logs/board.log` |
| 執行中的 JAR | 主工作區 `target/*.jar` |

過去實際發生過的事故：agent 在正式工作區跑 `mvnw clean package`，刪掉了
正在運行的 jar；以及誤連到不同路徑的空資料庫，導致看板上所有專案「消失」。

## 分支結構

- `main`：穩定線
- `integration/board-hardening`：本輪開發的整合分支，各角色分支從此長出
- `task/<role>`：五個角色各自的工作分支

## 角色隔離對照表

每個角色有獨立 worktree，位於 `/Users/daniel/專案/.agentdashboard-worktrees/<role>`。

| 角色 | 分支 | 埠號 | H2 資料庫 | 日誌 |
| --- | --- | --- | --- | --- |
| backend-dev | `task/backend` | `18101` | `<wt>/data/dev-backend` | `<wt>/logs/dev-backend.log` |
| frontend-dev | `task/frontend` | `18102` | `<wt>/data/dev-frontend` | `<wt>/logs/dev-frontend.log` |
| qa | `task/qa` | `18103` | `<wt>/data/dev-qa` | `<wt>/logs/dev-qa.log` |
| infra | `task/infra` | `18104` | `<wt>/data/dev-infra` | `<wt>/logs/dev-infra.log` |
| docs | `task/docs` | `18105` | `<wt>/data/dev-docs` | `<wt>/logs/dev-docs.log` |

`<wt>` 指該角色自己的 worktree 根目錄。

## 使用方式

在自己的 worktree 根目錄：

```bash
source ./.dev-env.sh
```

這會設定 `BOARD_PORT`、`BOARD_DB_URL`、`BOARD_LOG_FILE` 三個環境變數
（`application.yml` 中三者皆為可覆寫的預設值）。之後的 `./mvnw test`、
`./mvnw package`、`java -jar` 都會落在隔離資源上。

`target/`、`data/`、`logs/` 都在 `.gitignore` 內，因此各 worktree 天然
擁有各自的建置產物與資料，不會互相覆蓋，也不會碰到主工作區。

## 硬性禁止事項

- 不得 kill 正式看板 PID
- 不得使用 `:8080`
- 不得讀寫 `<repo>/data/board*`
- 不得寫入正式 `logs/board.log`
- 不得在主工作區執行 `mvnw clean` 或 `mvnw package`
  （正在運行的 jar 就在主工作區 `target/`）

## 已驗證事項（#104 實測）

1. qa worktree 執行 `./mvnw test` → 122 tests 全過，`BUILD SUCCESS`
2. 該次建置產物落在 qa worktree 自己的 `target/`，主工作區 `target/` 無寫入
3. 以 `.dev-env.sh` 啟動第二份看板於 `:18104`，`/api/health` 顯示
   `databasePath` 為 `dev-infra`，與正式的 `data/board` 完全分離
4. 全程正式看板 PID、`startedAt`、資料庫 inode、jar 皆未變動

註：單元／整合測試在原始碼中以 `jdbc:h2:mem:` 硬編碼，本來就不會碰到
檔案資料庫；`BOARD_DB_URL` 的隔離主要在「實際啟動應用」時生效。

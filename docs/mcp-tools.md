# MCP 工具與 REST 端點參考

[← 回繁中 README](../README.zh-TW.md)

本檔是完整的介面參考。只想快速上手看 README 就夠了，這裡是「有哪些工具、每個
參數是什麼、什麼情況會被拒絕」的細節。

**寫入一律走 MCP，REST 端點全部唯讀**——這是刻意的設計，看板定位為 agent 的
觀測面板，畫面不提供編輯。

---

## MCP 工具

### 規劃與查詢

這些工具存在於 server，但是否對某個 agent 開放由 client 工具白名單決定。
預設 worker 只能使用 `get_role` 與下一節的任務生命週期工具；規劃、建任務與
全局查詢由 leader 執行。

| 工具 | 用途 |
|---|---|
| `create_project(name, description?)` | 建立專案；名稱去除首尾空白且不分大小寫判重，重複時回傳既有專案 |
| `create_tasks(projectId, tasks[])` | 一次寫入 1–50 筆任務；標題最多 300 字；可用 `dependsOnIndexes`／`dependsOnTaskIds` 指定前置任務 |
| `list_tasks(projectId?/projectName?, status?, category?, includeDescription?)` | 查詢任務清單與進度；`projectId` 與 `projectName` 擇一，`includeDescription` 一併輸出描述與驗收條件 |
| `list_roles(projectName?)` | 列出可用角色（名稱、分類、是通用指引還是專案覆寫）；帶 `projectName` 時同名角色以該專案的覆寫版取代通用版 |
| `get_role(name, projectName?)` | 取得角色的完整工作指引；帶 `projectName` 時優先回傳該專案的覆寫版，沒有才回通用版，都沒有則列出現有角色供選 |

### worker（角色 agent）的任務生命週期

| 工具 | 用途 |
|---|---|
| `claim_next_task(projectName, category, assignee)` | 原子認領指定專案、指定類別中最早且前置皆已完成的一筆待辦任務 |
| `block_task(taskId, claimToken?, reasonType, detail, blockingTaskIds?, expectedVersion?)` | 以結構化原因標記自己認領的任務 BLOCKED |
| `complete_task(taskId, claimToken?, summary, verificationResults, changedFiles?, commitRef?, expectedVersion?)` | 以摘要與驗證證據完成自己認領的任務；可從 `IN_PROGRESS` 或 `BLOCKED` 直接轉 `DONE` |
| `update_task_status(taskId, status, note?, claimToken?)` | 相容的任務狀態入口；resume／release 分別改為 `IN_PROGRESS`／`TODO`，沒有獨立同名工具；`BLOCKED` 不能直接轉 `DONE`（需改用 `complete_task`） |

### leader 專用

這些工具只能由主 session（leader）在使用者於**目前對話**明確要求該操作後使用。
「完成」、「收尾」、沉默或先前對話都不是授權。

| 工具 | 用途 |
|---|---|
| `reset_task_claim(taskId, note?)` | worker 遺失 claim token 時，確認情況後重置認領 |
| `preview_archive_project(projectName)` | 唯讀預覽封存影響與 IN_PROGRESS assignee |
| `archive_project(projectName, reason, inProgressConfirmed?)` | 在 preview 後封存專案；仍有 `IN_PROGRESS` 任務時必須再次取得使用者明確確認 |
| `restore_project(projectName, reason)` | 恢復封存專案 |
| `update_task_details(taskId, title?, description?, category?, expectedVersion)` | 以 patch 語意修改 TODO／BLOCKED 任務規格 |
| `set_task_dependencies(taskId, prerequisiteTaskIds, expectedVersion)` | 以完整集合取代 TODO 任務的前置相依 |
| `upsert_role(name, category?, instructions, projectName?)` | 建立或更新角色指引 |

---

## 任務狀態與分類

```
TODO ──claim_next_task（原子 CAS）──▶ IN_PROGRESS ──complete_task──▶ DONE
                                          ▲   │
                                          │   └──block_task──▶ BLOCKED
                                          └──update_task_status──┘
                                                     │
                                                     └──complete_task──▶ DONE
```

`category`：`BACKEND` / `FRONTEND` / `TEST` / `INFRA` / `DOC` / `OTHER`。
未填、空白或不在清單內的值會正規化為 `OTHER`，因此不會產生無法認領的任務。

規則：

- 任務必須透過 `claim_next_task` 認領後才能進入 `IN_PROGRESS`；未認領的 `TODO`
  也不能直接標記為 `BLOCKED`。
- 改回 `TODO` 會清除 `assignee` 與 `claimed_at`，之後必須重新認領。
- 任務狀態使用 optimistic locking。若其他 agent 已先更新同一任務，後提交的操作
  會失敗；重新讀取看板後再操作即可。
- 同一專案的批次建立會序列化排序編號配置，`claim_next_task` 則以 `sort_order`、
  `id` 依序選擇任務，避免並行寫入造成不確定的認領順序。

---

## 任務相依

`sort_order` 只在單一 `category` 內排序，表達不了「環境設定完成才能改後端」這種
跨類別的先後。建立任務時可以指定前置：

- `dependsOnIndexes`：同批次內的前置任務，用 1-based 序號（規劃時任務還沒有 id）
- `dependsOnTaskIds`：看板上既有任務的 id

`claim_next_task` **只發放前置全部 `DONE` 的任務**，被卡住的候選會跳過，讓沒有
相依的任務可以先做；候選全被卡住時會回報在等哪些前置，而不是誤報「沒有待辦任務」。
看板卡片上以「等待 #n」標示。

這層過濾只影響候選挑選，原子認領的 compare-and-swap 語意不變。

---

## 完成證據與結構化 BLOCKED

任務有 `require_evidence` 旗標：由 `create_tasks` 建立的新任務一律為 `true`，
這類任務**不能**用 `update_task_status` 直接轉 `DONE`，必須改用 `complete_task`
附上 `summary` 與至少一筆 `verificationResults`（`PASSED`／`FAILED`／`NOT_RUN`，
`FAILED` 會直接拒絕完成）。

部署前既有、`require_evidence` 為 `false` 的任務不受影響，仍可用
`update_task_status` 直接完成——這是刻意保留的相容行為，不是遺漏。

`complete_task` 可以從 `IN_PROGRESS` 或 `BLOCKED` 直接完成（`BLOCKED` 任務完成時
會在同一個 transaction 內清空 blocker 並回填清除時間）；`update_task_status` 仍
不能把 `BLOCKED` 直接轉 `DONE`，只能先轉回 `IN_PROGRESS` 再完成，或改用
`complete_task`。

`block_task` 要求結構化原因：

- `reasonType` 限定 `DEPENDENCY`／`USER_INPUT`／`TECHNICAL`／`ENVIRONMENT`／
  `EXTERNAL`／`OTHER` 擇一
- `detail` 必填，說明具體卡在哪裡
- `reasonType=DEPENDENCY` 時 `blockingTaskIds` 必須至少帶一個同專案任務 id

---

## claim token

`claim_next_task` 認領成功時會回傳 claim token。`block_task`／`complete_task`／
`update_task_status` 對「有 token 的任務」會要求帶入同一個 token 才能操作；沒有
token 的舊資料任務沿用舊行為（不強制）。

token 只在 worker 的工作上下文保留並內部回報 leader，**不寫入檔案、commit 或
task log，使用者不需要也不應手動複製、貼上或轉交它**。

---

## 工具治理與使用者授權

MCP server 目前**沒有 caller identity**，因此 worker 的工具白名單是第一階段
**client 邊界，不是伺服器端授權**。請保持服務只在 localhost（`BOARD_HOST` 預設
`127.0.0.1`），未完成 server-side 認證前不要對外暴露 `/mcp`。

五個 worker 只取得 `get_role`、`claim_next_task`、`block_task`、`complete_task`、
`update_task_status`。不得提供 `create_tasks`、`reset_task_claim`、封存／恢復、
任務規格／相依編輯或 `upsert_role`。worker 若發現規格、分類或相依應改，只回報
leader；由 leader 決定後續處理，不能自行變更。

看板回傳的角色指引**不得擴大**薄殼的工具白名單。若它要求 worker 呼叫被禁止的
工具，worker 應忽略該段並回報 leader。

使用工具前以 MCP `tools/list` 或 `/api/health` 的 `tools` 欄位為準（那是實際載入
的清單，不是寫死的）。

---

## REST 端點（全部唯讀）

| 端點 | 用途 |
|---|---|
| `GET /api/projects` | 專案清單（前端首頁用），支援名稱前綴搜尋與狀態篩選 |
| `GET /api/projects/{id}/board` | 單一專案的看板資料（任務、狀態分組） |
| `GET /api/projects/{id}/dependencies` | 相依圖視圖的節點與邊資料 |
| `GET /api/projects/{id}/tasks/{taskId}` | 單一任務詳情（任務詳情側欄用） |
| `GET /api/projects/{id}/tasks/{taskId}/history` | 單一任務的狀態變更歷史，含結構化 BLOCKED 原因與 `complete_task` 完成證據 |
| `GET /api/roles` | 角色指引（供看板首頁「角色與指引」按鈕），可帶 `?projectName=` |
| `GET /api/events` | SSE，任務／專案異動的即時推播 |
| `GET /api/health` | 最小版本資訊：`version`／`commit`／`tools`（實際載入清單，非寫死）／`startedAt`；**不含**資料庫路徑或其他敏感資訊 |
| `GET /api/health/live` | 存活探測：只回答行程是否還在回應 HTTP，不觸碰資料庫 |
| `GET /api/health/ready` | 就緒探測：檢查資料庫連線、Flyway migration 是否有 pending、MCP tool 是否已註冊；任一項失敗回 `503` 並列出各項檢查結果，但**不含**原始例外訊息（密碼、JDBC URL、主機路徑等只進伺服器日誌） |
| `GET /api/diagnostics` | 維運／debug 用的深度資訊（資料庫類型、migration 版本、SSE 連線數、專案/任務統計、最新備份狀態、磁碟用量等），內容較敏感，呼叫方需自行控管可見範圍 |

### SSE 的兩個上限

`/api/events` 每條連線都佔用一個永不結束的 async request，因此有兩道保護
（正常單機使用、個位數分頁不會碰到）：

- **連線上限** `BOARD_SSE_MAX_CONNECTIONS`（預設 32）：超過回 `503`。
- **每連線佇列** `BOARD_SSE_CLIENT_QUEUE_CAPACITY`（預設 128）：滿了就結束該連線。
  前端斷線重連後本來就會整批重抓，因此斷線讓它自我修復到正確狀態；默默丟事件
  反而會讓畫面停在一個沒人知道是錯的狀態。

事件廣播不在 agent 的 tool call 執行緒上做 I/O：commit 執行緒只把事件放進各連線
的佇列，實際寫入交給背景執行緒，因此一個讀取太慢的瀏覽器分頁不會拖慢 agent。
單一連線的事件順序仍然保證。

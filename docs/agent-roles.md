# 角色 agent 與派工流程

[← 回 README](../README.md)

看板本身不需要角色 agent 就能運作——`claim_next_task`、`get_role` 都是一般 MCP
工具，任何連上 `/mcp` 的 client（含主 session）都能直接呼叫。本檔說明的是「怎麼
讓多個角色同時開工」這一層慣例。

**沒有建立角色 agent 時你失去的是分工，不是功能**：assignee 名稱要自己在對話中
指定（不會自動代入 `backend-dev` 這類名字），而且只有一個主 session 在跑，無法把
沒有相依的任務分給不同角色同時開工。

---

## 六個角色

`OTHER` 以外的五個 `category` 各自對應一個角色，負責呼叫 `claim_next_task` 認領
自己類別的任務並開工。`OTHER` 不配專屬角色，由主 session 處理。

| 角色 | category | 職責 |
|---|---|---|
| `backend-dev` | `BACKEND` | 伺服器端邏輯、API、資料庫、資料模型 |
| `frontend-dev` | `FRONTEND` | UI、樣式、前端狀態與互動 |
| `qa` | `TEST` | 撰寫測試、執行驗證、回報失敗 |
| `infra` | `INFRA` | CI/CD、容器、部署、建置與環境設定 |
| `docs` | `DOC` | README、規格、註解、CHANGELOG |
| `reviewer` | —（不認領） | 驗收階段由 leader 直接叫用，唯讀審查後只回報 leader |

每個角色只認領自己類別的任務、只碰自己職責內的檔案；同一類別同時只有一個角色在
跑，且**做完一件就提交、回報、結束，不自行 DONE 或認領下一件**。

`reviewer` 不認領 category 任務，因此 `RoleSeeder` 只初始化上述五個 worker 角色。
兩套 plugin 的 reviewer 薄殼本身包含不可覆蓋的唯讀審查邊界。

---

## 兩階段流程

規劃與開工分成兩個 session，因為**規劃需要你在場、開工不需要**：

```
規劃 session：你 + 模型扮 PM / SA / SD
   ├─ 規格文件 → 寫進 repo（例如 docs/），進版控
   └─ 任務清單 → create_tasks，description 指向規格路徑並標註相依
                    │
                    ▼
開工 session：主 session 擔任 leader
   1. list_tasks(projectName, includeDescription=true) 並記錄 batch manifest
   2. 為每筆 task 從 dev 建立 task/<id>-<role> branch/worktree
   3. 每個空閒角色派一件；任一 agent 結束就重新盤點，不等待同步 wave
   4. agent 驗證、commit、回報後維持 IN_PROGRESS
   5. leader 表面驗收並 merge task branch → dev，成功後才標 DONE
   6. 整批完成後 reviewer 審 main...dev，通過才由 leader merge dev → main
```

混在同一個 session 會被進度訊息洗版，也失去「規劃定稿」這個分界點。

leader 的表面驗收只確認範圍、commit、驗證證據與 `BLOCKED` 說明，不取代 QA 或
reviewer。Reviewer 唯讀且只回報 leader；是否建立修正 task 由 leader 決定。

Leader 將 task branch 合併到 `dev` 後才完成看板任務；這樣相依任務不會早於實際
程式碼解鎖。

### Git 模型

```text
main                         穩定、已通過整批 reviewer
└── dev                      leader 維護的長期整合分支
    ├── task/123-backend-dev 單一 task branch/worktree
    ├── task/124-frontend-dev
    └── task/129-leader      明確授權的 OTHER
```

Git 不能同時保存 `dev` 與 `dev/...` refs，因此 task branch 使用 `task/`，不是
`dev/task-`。每筆 task 從最新 `dev` 建立；agent 只 commit 自己的 branch，不合併、
不 push。Leader 表面驗收後以 `--no-ff` 合併 task branch 到 `dev`，成功後才完成
看板 task——下游因此只會在所需程式碼已進入整合分支後解鎖。

Task worktree 在合併且乾淨後可移除，branch 保留到整批進入 `main`。未合併、dirty、
BLOCKED 或狀態不明的工作區一律保留。

### Batch 與 reviewer

開工時記錄 batch manifest 與基準 commit。QA 實際測試失敗建立的 production bug、
使用者核准的修正、必要前置與 reviewer 必修都留在同一批；執行途中出現的不相關
task 不自動加入。

只有 manifest 全部 DONE 且沒有 unresolved BLOCKED 時，才叫 reviewer 唯讀審查完整
`main...dev`。必修只允許一個完整修正與重新審查循環；第二次仍有必修就停止並詢問
使用者。通過後 leader 才以 `--no-ff` 合併 `dev` 到 `main`，不自動 push、部署或重啟。

### 例外處理

| 情況 | 處置 |
|---|---|
| **BLOCKED** | agent 結束並釋放角色鎖；branch/worktree 保留、不進 `dev`；下游等待，無關工作繼續 |
| **需要使用者輸入** | subagent 只整理事實、歧義、選項與建議，由 leader 統一詢問 |
| **異常中斷** | 保留成果並由同角色新 agent 接續一次；再次異常就停止並詢問 |
| **Merge conflict** | 機械衝突回原角色處理；語意衝突暫停相關工作並詢問 |
| **表面驗收失敗** | 只允許一次返工；第二次仍失敗就詢問 |
| **認領競爭** | 重新盤點，不誤報成沒有任務 |
| **暫停／取消** | 停止新派工並保存所有成果；不等於回滾或刪除 |
| **既有 IN_PROGRESS** | 先占用角色鎖並找 live agent／branch；找不到就詢問，不重複派工 |
| **Dirty main 或殘留 dev** | 不得自動 stash、commit、丟棄或覆蓋，先取得使用者決定 |

### OTHER 類別

`OTHER` 不占五個 worker 鎖，由 leader 一次處理一件，但必須明確指定給 leader、
範圍清楚且目前請求已授權；同樣 claim、使用獨立 branch/worktree、驗證、commit、
整合與 review。若實際應屬某個 worker role，不自行猜測或改分類。

部署、刪除、外部寫入、付款、新權限與正式環境變更仍需要個別明確授權；「完成」或
「收尾」不會隱含這些權限。專案封存只能由使用者決定，leader 與 subagent 不得自動封存。

### 誰是哪件事的正本

| 來源 | 負責 |
|---|---|
| 看板 `role` 表 | 五個 worker 的完整工作指引；專案覆寫優先於通用版 |
| `RoleSeeder` | 全新資料庫的初始角色內容，不覆蓋既有資料 |
| `plugin/agents`、`.codex-plugin/agents` | client 薄殼：工具白名單、硬邊界、看板失效時的最低 fallback |
| 兩份 `claim-tasks` skill | leader 的排程、Git、驗收、review 與例外流程 |
| repo `AGENTS.md` | 本專案的架構、測試與正式環境限制 |

安裝後的 plugin cache 是產物，不是另一份正本；更新應透過 plugin 更新流程完成。

---

## 兩層來源：檔案是薄殼，指引在看板

這是最容易搞混的一點，判準只有一條：

> **Claude Code / Codex 在「載入 agent 的那一刻」就需要知道的，只能放檔案；
> 「開工當下」才需要的，放看板。**

| | `plugin/agents/*.md`（檔案薄殼） | `role` 表（`get_role` 取得） |
|---|---|---|
| 放什麼 | `name`、`description`、**`tools:` 白名單**、`model`，加上工具硬邊界與看板連不上時的求生規則 | 完整工作指引：路徑所有權、開發用埠號、commit 格式、分支規則、收尾流程 |
| 誰在什麼時候讀 | Claude Code 啟動載入 subagent 時 | agent 開工時呼叫 `get_role` |
| 改了要做什麼 | 重裝 plugin 或 `/reload-plugins` | **什麼都不用做，下次呼叫就生效** |
| 能不能分專案 | ❌ 一份走天下 | ✅ 通用層 + 專案覆寫層 |

**關鍵在 `tools:` 那一行。** Claude Code 在載入 subagent 時就用它決定這個 agent
拿得到哪些工具——那時候還沒有人呼叫過 `get_role`。**這個白名單只有檔案能給，看板
永遠給不了**，所以 worker 拿不到 `archive_project` 是真的拿不到，而不是靠指引裡
寫「請不要呼叫」。

反過來說，**不要把完整指引複製一份進檔案**——那就是兩份會漂移的真相來源。

### 看板上的兩層指引

`get_role` 依優先序回傳**整份**（不會自動疊加，覆寫版必須包含通用版的全部內容加上
專屬規則）：

1. **通用層**（`project_id` 為 `NULL`）：跨專案都成立的角色職責、開工流程、
   BLOCKED 判準。
2. **專案覆寫層**（`project_id` 指定某專案）：該專案專屬的路徑所有權、埠號、
   commit 規則等，優先於通用層。

`get_role(name, projectName?)` 帶 `projectName` 時，若該專案有覆寫版就回傳覆寫版，
否則退回通用版；都沒有則列出現有角色供選。

只有 leader 在使用者**目前對話**明確授權後才可用 `upsert_role` 建立／更新任一層；
不帶 `projectName` 動的是通用版，帶了則只動該專案的覆寫版，不影響通用版或其他專案。

看板首頁的「角色與指引」按鈕（呼叫唯讀的 `GET /api/roles?projectName=`）可以直接
看到目前每個角色的完整指引與覆寫狀態。

---

## 取得薄殼的兩種方式

### 1. 透過 plugin 安裝（建議）

`plugin/agents/*.md`（Claude Code）與 `.codex-plugin/agents/*.md`（Codex）已經是
薄殼，裝了 plugin 就自動取得六個角色，不需要再手動建立。安裝方式見 README 的
「接上你的 AI agent」與 [docs/installation.md](installation.md)。

家目錄若已有同名檔案會蓋掉 plugin 版本，處理方式見
[docs/installation.md](installation.md)。

### 2. 手動建立（不進版控，clone 這個 repo 不會自動取得）

在自己機器的 `~/.claude/agents/` 底下為每個角色各建一個 `*.md`
（[subagent 格式文件](https://docs.claude.com/en/docs/claude-code/sub-agents)），
例如 `~/.claude/agents/backend-dev.md`：

```markdown
---
name: backend-dev
description: 執行看板上 category 為 BACKEND 的任務。
tools: Read, Write, Edit, Bash, Grep, Glob, mcp__plugin_ai-project-board_board__get_role, mcp__plugin_ai-project-board_board__claim_next_task, mcp__plugin_ai-project-board_board__block_task, mcp__plugin_ai-project-board_board__complete_task, mcp__plugin_ai-project-board_board__update_task_status
---
你是後端工程師。呼叫 get_role("backend-dev", projectName) 取得最新指引並照做；
拿不到時退回：讀 repo 的 CLAUDE.md/AGENTS.md、呼叫
claim_next_task(projectName, "BACKEND", "backend-dev") 認領任務並開工，
完成後提交並保持 IN_PROGRESS，把證據與 claim token 內部回報 leader；卡住才以
block_task 更新為 BLOCKED。不得要求使用者手動轉交 token，也不得取得任務規格／
相依、封存／恢復或角色指引寫入工具。
```

> **`tools:` 的名稱必須跟 session 實際載入的完全一致**，否則會被 allowlist 濾掉，
> subagent 會拿不到看板工具。上面用的是**透過 plugin 載入**時的完整命名
> `mcp__plugin_<plugin-name>_<server-name>__<tool>`（本 repo 即
> `mcp__plugin_ai-project-board_board__*`）；裸寫 `mcp__board__*` 不會命中。
> 若你是自己在 `~/.claude.json` 或專案 `.mcp.json` 註冊 board（不經 plugin），
> 就沒有 `plugin_` 前綴，應改寫成 `mcp__board__*`。

Codex 端建議直接安裝本 repo 的 Codex plugin 使用 `.codex-plugin/agents/*.md`。
不使用 plugin 時仍可手動接 MCP，但不建議在 `~/.codex/AGENTS.md` 再維護一份完整
角色規則，以免與看板及 plugin 漂移。

---

## `AGENTS.md` 進版控

本 repo 根目錄的 `AGENTS.md` 記錄了給 agent 看的開發規則（埠號、資料庫、分派模式、
認領 SQL、角色指引的兩層來源等），與程式碼綁在一起，因此進版控。`.gitignore`
明確排除的是 `.claude/settings.local.json`、`.claude/worktrees/` 這類使用者機器上
的個人設定，不含 `AGENTS.md`。clone 這個 repo 就會拿到它，不需要另外重建。

角色的「工作指引」本身**不在** `AGENTS.md` 裡，而是存在看板的 `role` 表，由
`get_role` 取得。`AGENTS.md` 只是給 Claude Code / Codex 一份寫死在 repo 裡的開發
規範參考，兩者是分開的兩件事。

---

## 已知限制：角色指引不跟著 plugin 走

角色的完整工作指引存在看板的 H2 資料庫（`role` 表），plugin 只是程式碼與薄殼檔案
的散布單位。新裝的看板會由 `RoleSeeder` 建立初始通用指引，但使用者自己用
`upsert_role` 調整過的內容不會跟著 plugin 一起帶走，換機器或重建資料庫要重新灌
一次。細節見 [docs/installation.md](installation.md)。

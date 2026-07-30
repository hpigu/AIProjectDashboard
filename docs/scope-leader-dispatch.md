# Scope 重新定位：Leader 分派架構

> 狀態：階段 0、1、1.5 已實作完成（2026-07-30）；階段 2～4 未動工
> 日期：2026-07-30
>
> **已完成**：`list_tasks` 支援 projectName 與 description 輸出、
> `task_dependency` 與認領守衛、前端相依標示、`claim-tasks` skill 改為
> leader 分派模式、五個角色 agent 改單件回報、資料庫路徑偵測。
> 90 個測試全綠，並以真實 MCP 協定完成端到端驗證。
>
> **未做**：階段 2（角色指引收斂到看板）、階段 3（打包 plugin）、
> 階段 4（Codex 對齊）。

## 1. 事實盤點（已驗證，非推測）

先釐清一件事：**Codex 和 Claude Code 的能力其實是對稱的**，兩邊都有 skill、agent、plugin 機制。這推翻了「Codex 只能當單一 worker」的假設。

| 機制 | Claude Code | Codex | 現況 |
|---|---|---|---|
| MCP | `.mcp.json` | `.codex/config.toml` | ✅ 兩邊都連到 `127.0.0.1:8080/mcp` |
| Skill | `~/.claude/skills/claim-tasks/SKILL.md` | `~/.codex/skills/claim-tasks/SKILL.md` | ⚠️ 兩份幾乎相同、手工同步中 |
| 角色定義 | `~/.claude/agents/*.md`（5 個，全域） | `.codex/agents/*.toml`（3 個，專案內） | ❌ **不對稱**：內容、數量、位置都不同 |
| Plugin | `.claude-plugin/plugin.json` | `.codex-plugin/plugin.json` + marketplace | ❌ 兩邊都還沒做 |

### 已經存在的漂移（這是重構的真正動機）

- Claude 有 5 個角色（backend/frontend/qa/infra/docs），Codex 只有 3 個（缺 infra、docs）
- Codex 的 `backend-dev.toml` 寫了**路徑所有權**（`src/main/java/**` 只有我能改）、**測試埠號**（`BOARD_PORT=8081`）、**commit 格式**；Claude 的 `backend-dev.md` 完全沒有這些
- Codex 版寫死了 Java/Spring 專案結構，Claude 版是通用的

**結論：同一個角色在兩個 client 上的行為已經不一樣了。** 不處理這件事，加角色只會讓漂移加倍。

### 現有看板 MCP tools

`create_project` / `create_tasks` / `update_task_status` / `claim_next_task` / `list_tasks`

看板目前只存**任務**，不存規格文件、不存角色定義。

---

## 2. 問題定義

現在的流程是這樣：

```
使用者：「認領 X 的任務」
  └─ skill 寫死：平行叫 5 個 agent，且「不要先盤點」
       └─ 每個 agent 各自 claim_next_task(X, 自己的 category)
```

三個限制：

1. **沒有調配可言。** 分派邏輯硬編碼在 skill 裡，主 session 只是排程器。skill 甚至明文禁止盤點——不盤點就無從判斷。
2. **category 是唯一的分派依據。** 一個任務只能對應一個角色，跨領域任務（例如「加一個 API 並在前端顯示」）沒有正確的歸屬。
3. **沒有收尾判斷。** 五個 agent 各自跑完就結束，沒人檢查產出是否符合當初的規格。

---

## 3. 目標架構

```
Session A（規劃）：你 + Opus 扮演 PM / SA / SD
   產出：規格文件 + 任務清單
        │  規格文件 → repo 內（docs/specs/）
        │  任務清單 → 看板（create_tasks）
        ▼
Session B（開工）：主 session 擔任 leader
   1. list_tasks 盤點 TODO
   2. 讀規格文件，判斷相依與順序
   3. 決定叫哪些角色、分幾批
        ├─ backend-dev  ← 3 件，做完一件回報，繼續下一件
        ├─ frontend-dev ← 2 件
        └─ qa           ← 1 件
   4. 收到回報 → 對照規格驗收
   5. 彙整 → 回報給你
```

### 為什麼 leader = 主 session，而不是一個 leader subagent

- Claude Code 的 subagent 預設拿不到 Agent tool，leader subagent 無法再開 sub agent。要巢狀得改設定，且多一層 context 隔離。
- 「彙整後回報給你」本來就是主 session 在做的事。
- 你要能中途插話改方向——leader 在 subagent 裡就做不到。
- 實作成本：只改 skill 內容，不動 MCP、不動角色定義。

---

## 4. 三個機制各自負責什麼

判斷準則一句話：**MCP 決定「能做什麼」，Skill 決定「怎麼做」，Plugin 只是裝箱。**

Plugin 不會帶來新功能，只帶來可攜性。

### 歸 MCP（看板要改）

| 新增 | 為什麼非 MCP 不可 |
|---|---|
| `list_roles(projectId)` | 角色定義要跨 client 共用。放檔案就是兩份，放看板就是一份。**這是解決漂移的關鍵。** |
| `get_role(name)` | 回傳該角色的工作指引，Claude 與 Codex 拿到同一份文字 |
| `create_spec` / `get_spec` | Session A 的規格產出要能被 Session B 讀到（見 §5 的取捨） |
| `list_tasks` 加 `includeDescription` | leader 要靠描述判斷相依，不能只看標題 |

### 歸 Skill（兩邊各一份，內容同步）

- leader 的**判斷準則**：怎麼盤點、怎麼決定順序、怎麼驗收、怎麼彙整
- 這是「做法」，不是「能力」，本質上就該放 skill
- 現行 skill 要刪掉的一行：「不要先盤點」——與 leader 角色直接衝突

### 歸 Plugin（打包）

你的需求（跨機器、分享給別人、未來加角色）正中 plugin 的用途。

```
ai-board-plugin/
├── .claude-plugin/plugin.json     ← Claude Code 入口
├── .codex-plugin/plugin.json      ← Codex 入口
├── skills/claim-tasks/SKILL.md    ← leader 判斷準則（共用）
├── agents/                        ← 角色定義（見下方取捨）
└── mcp/                           ← 看板連線設定
```

---

## 5. 流程斷點檢查（決策後複查）

決定 A=混合、B=repo 之後，把流程從頭走一遍，發現六個問題。前三個會讓流程**走不通**，後三個是設計缺口。

### 🔴 斷點 1：leader 拿不到 projectId

`list_tasks(projectId, ...)` 要的是**數字 ID**，但使用者只會說專案名稱。現行 skill 靠 agent 認領成功後回傳的 `#id` 反推——可是 leader 必須**先盤點才能分派**，這時候還沒有任何 agent 跑過。

雞生蛋問題：沒有 agent 跑 → 沒有 projectId → 不能 list_tasks → 不能分派。

**解法（三選一）：**
1. 新增 `resolve_project(projectName)` tool，回傳 id
2. `list_tasks` 改成接受 `projectName` 或 `projectId` 其一
3. leader 先打唯讀 REST（`web/` 已有 GET endpoint）查 id

建議 2，因為對既有呼叫者零破壞，且 `claim_next_task` 本來就是用名稱查。

### 🔴 斷點 2：`list_tasks` 不回傳 description

看 `ProjectBoardTools.java:145-150`，輸出只有 `#id 標題 [category] @assignee`。**描述完全沒有出現在輸出裡。**

但你的 B 決定是「規格文件放 repo，task description 指向路徑」——leader 盤點時看不到 description，就拿不到規格文件的路徑，也無從判斷相依關係。

**這使得 B 方案目前無法運作。** 必須讓 `list_tasks` 能吐出描述（加參數或直接改輸出）。

### 🔴 斷點 3：跨角色相依無法表達（已釐清，比原本想的嚴重）

Daniel 的實際需求：**task A(INFRA) 做完才能做 task B(BACKEND)，沒有相依的可以先做。**

這是**跨 category 相依**，而現有模型完全表達不了：

- `sort_order` 只在 category 內排序，`claim_next_task(projectName, category)` 依它取第一筆
- 跨 category 的先後關係沒有任何欄位可以記錄
- leader 就算看得出 A 要先於 B，也沒辦法阻止 backend-dev 提前認領 B

**這不是排序問題，是相依圖問題。** 需要新的資料模型。

**解法（見 §6 取捨 D）**，但要注意：一旦有相依，`claim_next_task` 的語意必須改成「取第一筆**且前置任務都已 DONE** 的」，否則 agent 還是會搶跑。這會動到 R5 認領那段 compare-and-swap SQL 的 WHERE 條件。

### 🟡 斷點 4：agent 的「循序認領」會架空 leader

所有五個 agent 定義最後一行都是「完成後循序認領下一個，直到無任務」。

這代表 leader 就算只想派一件，agent 也會自己把該 category 的**全部**任務吃光。leader 的分批控制無效。

**這跟你描述的流程其實不衝突**——你說「做完一件回報給 leader 並繼續下一件」。但要注意：目前 agent 是**做完全部才回報一次**，不是每件都回報。如果你要中途可見，得改 agent 定義。

**需要你決定：** 每完成一件就回報（leader 可中途介入、但來回變多），還是做完全部才回報（現況，簡單但不透明）。

### 🟡 斷點 5：驗收缺乏依據

你要 leader「驗收」，但 leader 手上有什麼可以對照？

- task description（斷點 2 解掉才拿得到）
- 規格文件（B 方案，agent 和 leader 讀同一份）
- agent 的自述回報（不可靠，agent 說 DONE 就是 DONE）

**現實：leader 無法真正驗收程式碼品質**，除非它自己去讀 diff。務實的做法是 leader 只驗「該做的都動到了、測試有跑、沒有 BLOCKED 沒交代」，程式碼品質交給 qa 角色與既有測試。

**建議在 skill 裡明講 leader 的驗收範圍**，避免它假裝驗過。

### 🟡 斷點 6：混合方案的分界要寫死

你選了 A=混合（骨架在檔案、指引在看板）。但「骨架」與「指引」的界線如果沒定義，兩邊又會漂移。

**建議的硬性分界：**

| 留在檔案（client 專屬） | 存看板（共用） |
|---|---|
| name、description、tools/權限 | 路徑所有權 |
| model 選擇 | 測試指令與埠號 |
| Claude 的 `md` / Codex 的 `toml` 格式 | commit 訊息格式 |
| — | 邊界規則（不能碰什麼） |
| — | BLOCKED 的判準 |

原則：**凡是「這個專案怎麼做事」的知識都進看板，凡是「這個 client 怎麼設定 agent」的都留檔案。**

---

## 5b. MCP 2026-07-28 stateless 規格的影響

新規格**兩天前（2026-07-28）才定案**。查證結果：

### 對你的影響：目前近乎為零，但有一項與本設計直接相關

你的 server 是 Spring AI 1.1.8 的 `STREAMABLE` transport（`application.yml`）。Spring AI 尚未跟進新規格，Claude Code / Codex 也還在舊版協定。**協定升級是 SDK 的事，不是你的事**——你的 tool 定義（`@Tool` 方法）在新舊規格下都不用改。

### 唯一需要注意的一條

> 「Servers that need cross-call state use explicit, server-minted handles passed as ordinary tool arguments.」

新規格移除了協定層 session，**跨呼叫的狀態必須由 server 發出明確 handle，當成普通參數傳回來**。

你的看板本來就是這樣設計的——`projectId`、`taskId` 就是 server-minted handle，靠參數傳遞，狀態存資料庫。**你的架構天生符合新規格**，不用改。

反過來說，這條也**否定了一個誘人的錯誤設計**：不要讓 leader 和 sub agent 之間依賴「MCP 連線記得我是誰」。每次呼叫都要自帶 projectName/assignee。現行設計已經是這樣，維持住就好。

### 其他變更對你的影響

| 變更 | 影響 |
|---|---|
| 移除 `initialize` 握手 | SDK 層，無感 |
| 移除 `Mcp-Session-Id` | 你沒用 session，無感 |
| SSE 改 `subscriptions/listen` | ⚠️ 你的 `EventStreamController` 是**自己的 REST SSE**，不是 MCP SSE，不受影響 |
| 移除 SSE 斷線重送 | 同上，不受影響 |
| `tools/list` 建議固定順序 | 小優化，可做可不做 |
| Roots / Sampling / Logging 標為 deprecated | 你都沒用 |

**結論：不需要為了新規格做任何事。** 等 Spring AI 出對應版本再跟即可。真要說有什麼行動項，是**別在未來的設計裡引入 session 依賴**。

---

## 6. 決策紀錄（已鎖定）

| # | 決策 | 選擇 | 影響 |
|---|---|---|---|
| A | 角色定義歸屬 | **混合**：骨架留檔案、指引存看板 | 階段 2 |
| B | 規格文件 | **repo 內**（`docs/specs/`），task description 指向路徑 | 階段 1 前提 |
| C | Leader 形態 | **主 session 當 leader** | 階段 1 |
| D | 跨角色相依 | **task 加 `depends_on`**，認領時檢查前置 | 階段 1.5 |
| E | 回報頻率 | **每完成一件回報**，每件開一個新 subagent | 階段 1 |
| F | Codex 對齊 | 先在 Claude Code 跑通再移植 | 階段 4 |

### 被否決的選項與理由

**用資料夾名稱推斷 projectName** — 與現行三份規則直接衝突（`AGENTS.md`、兩份 skill 都明文禁止目錄猜測）。理由依然成立：資料夾名與看板專案名是獨立的兩件事，一個 repo 可能對應多個看板專案；猜錯會讓 agent 去做別的專案的任務，而 `claim_next_task` 是原子寫入，錯了就已改到資料庫。**且此問題本不存在**——使用者說「認領 X 的任務」時已給名稱，只需讓 `list_tasks` 接受名稱。

**SendMessage 延續同一個 agent** — Claude Code 專屬，Codex 無對應機制，會讓雙平台行為分歧。

---

## 7. 決策後的架構

```
Session A（規劃）：你 + Opus 扮 PM / SA / SD
   ├─ 規格文件 → docs/specs/<feature>.md（進 git）
   └─ 任務清單 → create_tasks，description 指向規格路徑
                 並標註 depends_on
        │
        ▼
Session B（開工）：主 session = leader
   1. list_tasks(projectName, status=TODO)   ← 含 description
   2. 讀規格文件，建相依圖
   3. 分波派工：
        波次 1：無前置的任務（infra #12、docs #15）
          └─ 每件開一個 subagent，做完回報即結束
        波次 2：前置已 DONE 的任務（backend #13）
        波次 3：...
   4. 每波回報後驗收（範圍見 §5 斷點 5）
   5. 全部完成 → 彙整 → 回報給你
```

### 波次調度的兩層保險

- **硬保險（資料庫）**：`claim_next_task` 只發放前置全 DONE 的任務。就算 leader 判斷錯、或有人手動認領，也搶跑不了。
- **軟調度（leader）**：leader 讀相依圖決定這一波派誰，避免無謂的空認領往返。

兩層都要，因為 Codex 與人工認領不會經過 leader。

---

## 8. 分階段計畫

### 階段 0：解掉 leader 的兩個硬阻塞（看板，小改）

沒有這兩項，leader 連盤點都做不到。

1. **`list_tasks` 接受 `projectName`**（與 `projectId` 二擇一）
   - 現況：只吃數字 ID，但使用者只給名稱，形成雞生蛋
2. **`list_tasks` 輸出加入 description**
   - 現況：[`ProjectBoardTools.java:148`](../src/main/java/dev/aiboard/mcp/ProjectBoardTools.java) 只輸出 `#id 標題 [category] @assignee`
   - 沒有 description，決策 B（規格路徑寫在 description）就無法運作
   - 建議加 `includeDescription` 參數，預設 false，避免既有呼叫的輸出暴增

**驗收**：leader 能只憑專案名稱盤點出含描述的 TODO 清單。

### 階段 1：Leader 邏輯（只改 skill）

改寫 `claim-tasks` skill：

- **刪掉**「不要先盤點」——與 leader 角色直接衝突
- **加入**盤點：`list_tasks(projectName, status="TODO", includeDescription=true)`
- **加入**相依判斷：讀 description 指向的規格文件，決定波次
- **改為**每次每個角色只派一件，做完回報即結束，下一件開新 subagent
- **加入**驗收範圍的明確界定（見 §5 斷點 5）：只驗「該動的有動、測試有跑、BLOCKED 有交代」，不假裝驗程式碼品質
- **同步修改**五個 agent 定義：拿掉「循序認領直到無任務」，改為做完一件即回報結束

**驗收**：拿一個有明顯前後相依的專案跑一次，確認 leader 排出波次而非五個一起衝。

**這階段做完就能感受到差別。體感沒變好，後面都不用做。**

### 階段 1.5：`depends_on`（看板）

- migration：`task_dependency` 表（task_id, depends_on_task_id）或 task 表加欄位
- `create_tasks` 支援帶入相依
- **`claim_next_task` 的 WHERE 條件加上「前置全 DONE」**——注意這會動到 `AGENTS.md` 記載的 R5 compare-and-swap SQL，併發測試要同步更新
- 前端顯示相依（被卡住的任務要看得出來在等誰）

**驗收**：前置未完成時 `claim_next_task` 不發放該任務；前置一 DONE 就自動可認領。

### 階段 2：角色指引收斂到看板

- migration：`role` 表（name, category, instructions, project_id 可為 NULL = 通用）
- MCP：`list_roles`、`get_role`、`upsert_role`
- 兩邊 agent 定義改薄殼：開工先 `get_role(自己的名字)` 拉指引
- **初始資料以 Codex 版為準**——它的路徑所有權、測試埠號、commit 格式寫得比 Claude 版完整

**驗收**：改一次看板角色指引，確認 Claude 與 Codex 下次開工都吃到新版。

### 階段 3：打包 plugin

- `.claude-plugin/plugin.json` + `.codex-plugin/plugin.json`
- skills、agents 骨架、mcp 設定收進去，推上 git
- 在第二台機器裝一次驗證

### 階段 4：Codex 對齊

移植 leader skill，補上缺的 infra/docs 角色，實測平行能力。

---

## 9. 尚未驗證的假設

以下是這份文件依賴、但**沒有實測過**的事，做之前要先確認：

1. **Codex 的 skill 能否平行啟動多個 agent**，還是只能循序。影響階段 4 的可行性。
2. **每件開新 subagent 的 token 成本**。每次都要重讀 `CLAUDE.md`/`AGENTS.md`，任務多時成本可能明顯上升。階段 1 跑完要實測。
3. **Spring AI 何時支援 MCP 2026-07-28**。目前無需行動（見 §5b），但升級時要留意 `subscriptions/listen` 取代 SSE 的部分。

---

## 附錄：與現行規則的衝突清單

實作時必須同步更新，否則文件與行為不一致：

| 位置 | 現行內容 | 需改為 |
|---|---|---|
| Claude `claim-tasks` skill | 「不要先盤點」 | leader 必須先盤點 |
| Codex `claim-tasks` skill | 「不要預先盤點」 | 同上（階段 4） |
| 五個 agent 定義 | 「循序認領直到無任務」 | 做完一件即回報結束 |
| `AGENTS.md` R5 認領段 | compare-and-swap 只檢查 `status='TODO'` | 加上前置全 DONE 的條件 |
| 併發測試 | 證明兩 worker 只有一個取得同任務 | 增加：前置未完成時不得發放 |

以上五項在 2026-07-30 的實作中都已完成。註：`AGENTS.md` 在 `.gitignore`
內，屬本機檔案，不隨 repo 分發——階段 3 打包 plugin 時要留意這件事，
否則別人裝了 plugin 也拿不到這些規則。

---

## 10. 實作中發現的事（2026-07-30）

文件先前沒料到、實作時才浮現的問題，記在這裡供後續階段參考。

### 認領守衛不能只加在 CAS 條件上

原本以為「WHERE 加上前置全 DONE」就好。實際上舊實作是
`findFirst...` 只取**第一筆** TODO——若第一筆被前置卡住，就會直接回報
「沒有待辦任務」，即使後面還有可做的。

正確做法是**取候選清單、過濾掉被卡住的、再挑第一筆**。CAS 本身不動，
只是挑選階段多一層過濾。舊的 `findFirst...` 方法因此成為死碼，已移除。

### 「沒有任務」與「全部被卡住」必須分開回報

兩者對 leader 的意義完全不同：前者代表這個角色收工了，後者代表要等別人。
混為一談會讓 leader 誤判波次已經跑完。新增 `blockedByDependency()` 與
`blockedCandidates` 區分，訊息會列出在等哪些前置。

### 批次內相依需要序號，不能只用 task id

規劃階段任務還沒有 id，無法用 id 表達「第二筆要等第一筆」。
`dependsOnIndexes`（批次內 1-based 序號）與 `dependsOnTaskIds`（既有任務）
分開兩個欄位，避免 `3` 到底是「第 3 筆」還是「任務 #3」的歧義。
建立前驗證：序號範圍、自我依賴、向前依賴（前置必須排在自己之前）。

### 看板是 leader 流程的單點故障

實測時看板中途掛掉，兩個 agent 都完成了工作卻無法把狀態寫回，
任務永遠停在 IN_PROGRESS。**工作成果與看板狀態脫節**，而 leader 完全
依賴看板判斷進度。

已在 skill 加上處理：agent 回報更新失敗時，leader 要記下哪幾筆、
實際做到哪裡，在彙整中明講並提醒補標記。但這只是止血，
根本問題（看板不可用時整套流程停擺）沒有解。

### 相對路徑資料庫是真的會踩到的坑

`BOARD_DB_URL` 預設 `jdbc:h2:file:./data/board` 是相對路徑，
jar 從 `target/` 啟動就會連到全新空庫，前端顯示所有專案消失。
這在實作當天真的發生了。已加啟動期檢查（`DatabaseLocationCheck`），
會記錄實際解析到的絕對路徑，並在「即將建立空庫但上層目錄有同名資料檔」
時發出警告。

實作細節值得留意：檢查必須用 `EnvironmentPostProcessor` 而非
`ApplicationRunner`（後者執行時 H2 已經把空檔建好了），
且該時機早於 logging system 初始化，要用 `DeferredLogFactory`
否則訊息會被靜默吞掉。

### 前端「等待前置」不該用停線紅

`--st-blocked` 是故障語意。等待前置是正常排程，不是出事。
改用 muted 加左側細線，與 BLOCKED 明確區分。

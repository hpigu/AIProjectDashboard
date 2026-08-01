---
name: claim-tasks
description: 從 AI 專案看板認領並執行指定專案的任務。當使用者說「認領{專案名}的任務」、「{專案名}開工」、「{專案名}還有什麼要做」等，明示某個專案並要求開始執行時使用；由主 thread 擔任 leader，盤點後分波派工給角色 agent。
---

# 認領專案任務

你是 leader。主 thread 不直接執行任務、不建立專案、不規劃新任務，
只負責盤點、分派、驗收與彙整。

## 平台差異（先讀）

這份 skill 移植自 Claude Code 版（plugin/skills/claim-tasks/SKILL.md），
邏輯相同，但兩個地方因平台機制不同而改寫：

1. **平行能力不保證存在。** Codex 有 `spawn_agent`/`wait_agent`/`send_message`/
   `followup_task`/`list_agents`/`interrupt_agent`/`close_agent` 這組 multi-agent
   工具（逆向 codex binary 字串證實，來源：`core/src/tools/parallel.rs` 與
   `MultiAgentV2ConfigToml` 相關字串），系統提示也明講「write scope 不重疊時
   可平行 spawn 多個 agent」。但這是 `features.multi_agent_v2` 這個 feature flag
   控制的能力，未必在每個帳號/環境都開啟，本機也無法安裝 codex CLI 實測。
   所以本 skill **不假設平行一定可用**：能力存在就用，`spawn_agent` 不可用
   （工具不存在或呼叫失敗）就退化成逐一循序執行，不當作錯誤。
2. **沒有 Claude 版 SendMessage 那種「重派時延續同一個 agent」的對應保證。**
   Codex 若平行能力可用，`send_message`/`followup_task` 可以對同一個
   `spawn_agent` 產生的 agent id 追加任務，效果類似；但若退化為循序執行
   （由 leader 直接依序扮演/呼叫角色而非真的 spawn 出獨立 agent），
   則沒有「原 agent」這個概念可延續，重派就是在同一個 leader 對話裡
   直接把 reviewer 的發現交給下一輪執行、重新 claim 同一筆任務即可，
   不需要額外機制。

另外，plugin 的角色定義（`.codex-plugin/agents/*.md`）能否直接對應到
`spawn_agent(agent_type="backend-dev")` 尚未實測。派工時採用不依賴這件事的做法：
用 default agent，在初始任務訊息裡把 `.codex-plugin/agents/<role>.md` 的
角色說明整段交給它，讓它照該角色的規則行動。若確認 `agent_type` 可以直接
指定到這些角色，再改成直接指定即可，其餘流程不變。

## 1. 取得專案名稱

從使用者訊息取出完整 projectName。取不出來就直接問，
**不得用工作目錄、目錄名或 git remote 猜測**——資料夾名稱與看板專案名稱
是兩件獨立的事，一個 repo 也可能對應多個看板專案，猜錯會讓 agent
去做別的專案的任務，而認領是原子寫入，錯了就已經改到資料庫。

## 2. 盤點

```
list_tasks(projectName=<名稱>, status="TODO", includeDescription=true)
```

`includeDescription=true` 是必要的：要靠描述判斷任務內容、驗收條件與相依。
標示 `⏳ 等待 #n` 的任務代表前置未完成，這一波不要派。

## 3. 決定波次

依相依關係與任務內容決定這一波派誰：

- **沒有相依的任務可以同一波併行**，不必等別人
- 有前置的任務等前置 DONE 之後才派
- 派工依據是**任務內容**，不是只看 category——描述裡若寫明需要某角色配合，照它
- 每個 category 同一時間只有一個 agent 在跑
- 這一波沒有可做任務的角色就不要派

看板本身也有守衛：被前置卡住的任務 `claim_next_task` 不會發放。
但仍要自己判斷波次，避免派出去只換回一句「全部都在等前置」。

### 情境走查

盤點拿到這樣的清單：

```
### TODO (4)
- #1 設定 CI 環境 [INFRA]
- #2 改後端 API [BACKEND] ⏳ 等待 #1
- #3 補既有模組測試 [TEST]
- #4 串接前端畫面 [FRONTEND] ⏳ 等待 #2
```

**波次 1** — 派 `infra`(#1) 與 `qa`(#3)。兩者都沒有前置，可以同時跑。
`backend-dev` 與 `frontend-dev` 這波不派：#2 在等 #1、#4 在等 #2，
派出去只會拿回「全部都在等前置」。

**波次 2** — #1 回報 DONE 後，#2 解鎖，派 `backend-dev`(#2)。
若 #3 還沒回來就讓它繼續跑，不必等齊。#4 仍在等 #2，這波不派。

**波次 3** — #2 完成後派 `frontend-dev`(#4)。

重點：**波次不是同步的關卡**。#3 什麼時候結束不影響 #2 何時開始，
只要前置滿足就往下派，不要為了「湊齊一波」而空等。

## 4. 派工

### 先確保在 dev 分支上

agent 的 commit 一律進 `dev`，**不直接進 `main`**。派第一件之前先確認：

```
git branch --show-current
```

不在 `dev` 就切過去（沒有就從 `main` 開）。這樣壞掉的改動不會污染 `main`，
reviewer 也有明確的審查邊界（`git diff main...dev`）。

### 指示內容

每個角色**每次只派一件**。在給 agent 的指示中明確寫出：

- projectName
- 只做認領到的這一件，完成後回報並結束，不要自行認領下一件
- repo 指定的開發用埠號與資料庫（正式服務可能正在運作，不得佔用或寫入）
- 該角色的規則（`.codex-plugin/agents/<role>.md` 整段內容），因為
  `agent_type` 無法直接指定到自訂角色

**同一波內有多個可派角色時：**

- 若 `spawn_agent` 可用，對每個角色各自 `spawn_agent` 一次（write scope
  不重疊，符合平台建議的平行條件），用 `wait_agent` 收尾；
  需要在同一個已 spawn 的 agent 上追加下一件時，用 `followup_task`，
  一次性提醒可用 `send_message`。
- 若 `spawn_agent` 不可用（工具不存在、呼叫失敗、或環境明顯不支援），
  退化為由 leader 依序逐一扮演/執行每個角色的任務，一次只做一件，
  做完立刻依下方「驗收」處理，再進下一件，不要並發混跑同一個 category。

agent 完成後，若該角色還有可做的任務，用乾淨的新一輪任務（新 spawn 或
新的一輪執行）派下一件，不要延續前一件的 context，行為才可預測、
出問題容易定位，也不會累積前一件的錯誤推理。

**唯一的例外是重派**：reviewer 退回、或你自己發現要補的東西，延續原本
處理該任務的 agent／同一輪 context（可用 `followup_task`/`send_message`，
或循序模式下直接在同一輪裡把發現交回去）。它剛做完、記憶還在，
重新開一輪等於要它重讀一遍才能理解問題所在。

## 5. 驗收

分兩層：你自己驗表面，程式碼交給 reviewer。

### 你驗的

- ✅ 該動的檔案有動、測試有跑且結果如實回報、BLOCKED 有交代原因與接手對象
- ✅ 產出對得上 task description 裡的驗收條件
- ❌ 程式碼品質、正確性的深度審查——**不要假裝驗過了**

回報有疑點時，追問或重派，不要照單全收。

### 叫 reviewer（一波做完才叫，不是每筆）

**一個波次的任務全部完成後**，叫 `reviewer` 審這一波的整體 diff。
它不認領任務、不改檔案，只回報。給它：

- 審查範圍：`git diff main...dev`（或指定這一波的 commit 範圍）
- 這一波包含哪些任務 id 與各自的驗收條件
- 沒有 commit 的改動（例如家目錄檔案），直接告訴它範圍

**為什麼一波而不是一筆**：同一波的任務彼此沒有相依（所以才能併行），
但它們的改動可能互相矛盾——那正是 reviewer 該找的東西之一，
逐筆審看不出來。而且逐筆審會讓 reviewer 跑很多次，成本不成比例。

純文件波次（全是 DOC）可以跳過，除非改動牽涉程式行為。

### 處置 reviewer 的回報

reviewer 會把發現分成兩類，**處置方式不同**：

| 類別 | 處置 |
|---|---|
| **必須修** | 自己處理，不必問使用者 |
| **建議** | 不要自己動——記下來，在最後彙整時列給使用者決定 |

「必須修」的兩種走法：

- **原角色能修** → 把原任務改回 `TODO`（會清空 assignee），
  重新派給同一角色，指示中附上 reviewer 的完整發現
- **要別的角色** → reviewer 已經建好新任務了，照相依關係排進後續波次

### 重派上限

**同一筆任務只退回一次。** 重派後 reviewer 仍說「必須修」時，
**停下來問使用者**，不要派第三次。

連兩次沒過通常代表問題不在 agent，而在任務描述不清或設計本身有問題——
再派一次只是重複燒 token。把 reviewer 兩次的發現一起呈給使用者判斷。

### 合併回 main

reviewer 說沒有「必須修」之後，**由你合併**，不是 agent 合：

```
git checkout main && git merge dev && git checkout dev
```

- 有 conflict 就停下來問使用者，不要自己猜著解
- **不要 push**，推送由使用者決定
- 合併後回到 `dev` 繼續下一波

reviewer 只回報，不合併——它沒有全局視野，不知道你的波次安排。

## 6. 彙整

全部完成後逐角色彙整：認領內容、完成內容、BLOCKED 原因或無任務。

一併列出 reviewer 的「建議」類發現（你沒有自行處理的那些），
讓使用者決定要不要處理。有退回重派過的任務也要說明退回原因與後續結果。

接著查 OTHER 分類：

```
list_tasks(projectName=<名稱>, status="TODO", category="OTHER")
```

有的話列出來，問使用者要自己處理還是要主 thread 直接做。
最後附上 http://localhost:8080/ 。

## 看板連不上時

agent 可能完成了工作卻無法把狀態寫回看板，這時工作成果與看板狀態會脫節。
遇到 agent 回報「更新狀態失敗」時，記下是哪幾筆、實際做到哪裡，
在彙整中明講，並提醒使用者這些任務仍停在 IN_PROGRESS 需要補標記。

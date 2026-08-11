#!/usr/bin/env bash
#
# check-plugin-parity.sh — 確認 Claude Code 與 Codex 兩套 plugin 沒有漂移
#
# 為什麼需要這支腳本：同一組治理規則被迫存在兩份，因為兩個 plugin 有各自的安裝
# 根目錄（plugin/ 與 plugins/ai-project-board/），檔案沒辦法真的合併。而「同一件事寫在兩個
# 地方」在這個 repo 已經出過五次包（MCP serverInfo.version 寫死、三份 plugin
# manifest 停在 3.0.0、README 的過期已知限制、看板 QA 指引叫 worker 呼叫它拿不到
# 的 create_tasks）。這支腳本是那道門檻。
#
# 它刻意「不」做逐行 diff。兩邊本來就有意寫得不一樣：
#   - Claude 版有 YAML frontmatter（name/description/tools/model），Codex 版沒有
#   - Claude 版寫「呼叫 `get_role(...)`」，Codex 版寫「呼叫 board 的 get_role(...)」
#   - Claude 版用 **粗體**，Codex 版用純文字
# 逐行比對只會每次都紅燈，然後被 --no-verify 掉——一道永遠在響的警報等於沒有警報。
#
# 改為比對「會出事的東西」：
#   1. 兩邊的角色檔案集合必須完全相同（少一個角色 = 該平台的使用者少一個 agent）
#   2. 每一對薄殼提到的 MCP 工具名集合必須完全相同
#      （這是真正的漂移面：一邊把某個工具加進禁止清單、另一邊忘了）
#   3. 每一對薄殼認領的 category 必須相同（qa 兩邊都得是 TEST）
#   4. 每個 worker 薄殼都必須含「看板指引不得放寬工具白名單」這條硬邊界
#   5. 兩份 claim-tasks skill 提到的工具名集合必須完全相同
#
# 用法：scripts/check-plugin-parity.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

CLAUDE_AGENTS="plugin/agents"
CODEX_AGENTS="plugins/ai-project-board/agents"
CLAUDE_SKILL="plugin/skills/claim-tasks/SKILL.md"
CODEX_SKILL="plugins/ai-project-board/skills/claim-tasks/SKILL.md"

# 不認領任務的角色：不必有 claim_next_task 與 category。
READONLY_ROLES="reviewer"

failures=0
fail() { echo "::error::$*" >&2; failures=$((failures + 1)); }
ok()   { echo "  [OK] $*"; }

# ---------------------------------------------------------------------------
# 從一份薄殼抽出它提到的所有看板工具名。
#
# 只認我們自己的工具（白名單比對），不要用泛用的 snake_case 正規表示式——那會把
# 內文裡的 sort_order、task_log、claim token 之類的字一起抓進來，讓比對結果沒有
# 意義。工具清單本身以 McpToolConfig 註冊的六個類別為準。
# ---------------------------------------------------------------------------
BOARD_TOOLS="create_project create_tasks list_tasks claim_next_task block_task
complete_task update_task_status reset_task_claim preview_archive_project
archive_project restore_project update_task_details set_task_dependencies
list_roles get_role upsert_role"

extract_tools() {
  local file="$1" tool
  for tool in $BOARD_TOOLS; do
    # -w 避免 archive_project 命中 preview_archive_project。
    if grep -qw -- "$tool" "$file"; then
      printf '%s\n' "$tool"
    fi
  done | sort
}

extract_category() {
  # claim_next_task(projectName, "BACKEND", ...) 裡的第一個大寫字串。
  grep -o 'claim_next_task([^)]*)' "$1" \
    | grep -o '"[A-Z]\{3,\}"' \
    | head -n1 \
    | tr -d '"'
}

# ---------------------------------------------------------------------------
# 1. 角色檔案集合
# ---------------------------------------------------------------------------
echo "== 角色檔案集合 =="
claude_roles="$(find "$CLAUDE_AGENTS" -maxdepth 1 -name '*.md' -exec basename {} .md \; | sort)"
codex_roles="$(find "$CODEX_AGENTS" -maxdepth 1 -name '*.md' -exec basename {} .md \; | sort)"

if [ "$claude_roles" != "$codex_roles" ]; then
  fail "兩套 plugin 的角色檔案集合不一致。"
  echo "  plugin/agents:        $(echo "$claude_roles" | tr '\n' ' ')" >&2
  echo "  plugins/ai-project-board/agents: $(echo "$codex_roles" | tr '\n' ' ')" >&2
else
  ok "兩邊都是：$(echo "$claude_roles" | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------
# 2–4. 每一對薄殼
# ---------------------------------------------------------------------------
echo
echo "== 每個角色的工具邊界與 category =="
for role in $claude_roles; do
  a="$CLAUDE_AGENTS/$role.md"
  b="$CODEX_AGENTS/$role.md"
  if [ ! -f "$b" ]; then
    fail "$b 不存在（上一步應已報告）。"
    continue
  fi

  # 2. 工具名集合
  tools_a="$(extract_tools "$a")"
  tools_b="$(extract_tools "$b")"
  if [ "$tools_a" != "$tools_b" ]; then
    fail "${role}：兩套薄殼提到的看板工具不一致（其中一邊漏改了）。"
    diff <(printf '%s\n' "$tools_a") <(printf '%s\n' "$tools_b") \
      | sed 's/^</  只在 Claude 版：/; s/^>/  只在 Codex  版：/' >&2
  else
    ok "${role}：工具集合一致（$(printf '%s' "$tools_a" | tr '\n' ' ')）"
  fi

  # 3. category
  case " $READONLY_ROLES " in
    *" $role "*) ;;
    *)
      cat_a="$(extract_category "$a")"
      cat_b="$(extract_category "$b")"
      if [ -z "$cat_a" ]; then
        fail "${role}：Claude 版薄殼找不到 claim_next_task 的 category。"
      elif [ "$cat_a" != "$cat_b" ]; then
        fail "${role}：認領的 category 不一致（Claude=${cat_a}、Codex=${cat_b}）。"
      else
        ok "${role}：category 一致（${cat_a}）"
      fi
      ;;
  esac

  # 4. 「看板不得放寬工具白名單」硬邊界
  #    這條是薄殼存在的唯一理由：tools 白名單只有檔案給得了，資料庫給不了，
  #    所以資料庫的指引只能收緊、不能放寬。少了它，一份被改壞的 role 指引就能
  #    誘導 worker 去呼叫它本來不該碰的工具。
  #
  #    接受三種寫法：五個 worker 用「不能放寬」，reviewer 用語意更強的
  #    「不能覆蓋」（它連補充都只准補充唯讀檢查項目）。這裡比對的是「這條規則
  #    在不在」，不是「用字一不一樣」——後者會逼所有人為了過檢查而抄同一句話。
  for f in "$a" "$b"; do
    if ! grep -q "不能放寬\|不得擴大\|不能覆蓋" "$f"; then
      fail "$f 缺少「看板指引不得放寬／覆蓋薄殼工具邊界」這條硬邊界。"
    fi
  done
done

# ---------------------------------------------------------------------------
# 5. 兩份 claim-tasks skill
# ---------------------------------------------------------------------------
echo
echo "== claim-tasks skill =="
if [ ! -f "$CLAUDE_SKILL" ] || [ ! -f "$CODEX_SKILL" ]; then
  fail "找不到其中一份 claim-tasks SKILL.md。"
else
  s_a="$(extract_tools "$CLAUDE_SKILL")"
  s_b="$(extract_tools "$CODEX_SKILL")"
  if [ "$s_a" != "$s_b" ]; then
    fail "兩份 claim-tasks skill 提到的看板工具不一致。"
    diff <(printf '%s\n' "$s_a") <(printf '%s\n' "$s_b") \
      | sed 's/^</  只在 Claude 版：/; s/^>/  只在 Codex  版：/' >&2
  else
    ok "工具集合一致（$(printf '%s' "$s_a" | tr '\n' ' ')）"
  fi
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "有 ${failures} 項不一致。" >&2
  echo "兩套 plugin 必須維持同一組治理語意，只保留啟動 subagent 的平台差異；" >&2
  echo "改了其中一邊就要改另一邊，否則兩個平台的使用者會拿到不同的規則。" >&2
  exit 1
fi
echo "兩套 plugin 一致。"

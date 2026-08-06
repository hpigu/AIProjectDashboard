#!/usr/bin/env bash
#
# check.sh — bin/*.sh 裡純函式的行為檢查
#
# 為什麼需要這支腳本：bin/ 下的 bash 腳本原本只有 `bash -n` 與 shellcheck 把關，
# 兩者都只看語法與靜態問題，看不出「排序鍵算錯」這類邏輯錯誤。而 bin/ 是使用者
# 的第一個接觸點，算錯的後果是啟動成功但跑到錯的 jar，沒有任何錯誤訊息。
#
# 這裡只測不碰檔案系統、不啟動行程的純函式；會動到使用者資料的部分（備份、還原、
# 保留策略）由 JUnit 的 *ScriptTest 以 ProcessBuilder 覆蓋，Windows 側則是
# scripts/windows-check/check.ps1。
#
# 用法：
#   scripts/shell-check/check.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
failures=0

assert_eq() {
  local expected="$1" actual="$2" description="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  [PASS] ${description}"
  else
    echo "  [FAIL] ${description}"
    echo "         預期：${expected}"
    echo "         實際：${actual}"
    failures=$((failures + 1))
  fi
}

# board-env.sh 只定義函式與預設值，source 進來不會有副作用。
# shellcheck source=bin/board-env.sh
source "${REPO_ROOT}/bin/board-env.sh"

echo '=== board_sort_jars_by_version ==='

# 這一項就是 #30 的回歸測試。字典序會把 3.10.0 排在 3.9.0 之前，於是跳到
# 3.10.0 的那一刻 find_jar 就會挑到舊 jar——而且啟動會成功，沒有任何徵兆。
latest="$(printf '%s\n' \
  'target/ai-project-board-backend-3.9.0.jar' \
  'target/ai-project-board-backend-3.10.0.jar' \
  | board_sort_jars_by_version | tail -n1)"
assert_eq 'target/ai-project-board-backend-3.10.0.jar' "$latest" \
  '3.10.0 比 3.9.0 新（字典序會弄反）'

latest="$(printf '%s\n' \
  'target/ai-project-board-backend-3.2.0.jar' \
  'target/ai-project-board-backend-3.10.0.jar' \
  'target/ai-project-board-backend-3.1.0.jar' \
  | board_sort_jars_by_version | tail -n1)"
assert_eq 'target/ai-project-board-backend-3.10.0.jar' "$latest" \
  '多筆混雜時仍挑到最新的一份'

# 路徑中的數字不得參與比較：版號只從檔名裡第一個「-數字」之後開始算。
latest="$(printf '%s\n' \
  '/home/user9/target/ai-project-board-backend-3.1.0.jar' \
  '/home/user2/target/ai-project-board-backend-3.2.0.jar' \
  | board_sort_jars_by_version | tail -n1)"
assert_eq '/home/user2/target/ai-project-board-backend-3.2.0.jar' "$latest" \
  '路徑裡的數字不會污染排序鍵'

# 預發布版比同版號的正式版舊，否則本機留著一份 SNAPSHOT 就會蓋過正式版。
latest="$(printf '%s\n' \
  'target/ai-project-board-backend-3.2.0-SNAPSHOT.jar' \
  'target/ai-project-board-backend-3.2.0.jar' \
  | board_sort_jars_by_version | tail -n1)"
assert_eq 'target/ai-project-board-backend-3.2.0.jar' "$latest" \
  '3.2.0 比 3.2.0-SNAPSHOT 新'

# 主版號跳躍也要正確：4.0.0 比 3.99.0 新。
latest="$(printf '%s\n' \
  'target/ai-project-board-backend-3.99.0.jar' \
  'target/ai-project-board-backend-4.0.0.jar' \
  | board_sort_jars_by_version | tail -n1)"
assert_eq 'target/ai-project-board-backend-4.0.0.jar' "$latest" \
  '4.0.0 比 3.99.0 新'

# 單筆輸入必須原樣輸出，不能因為排序邏輯而消失或被改寫。
latest="$(printf '%s\n' 'target/ai-project-board-backend-3.1.0.jar' \
  | board_sort_jars_by_version)"
assert_eq 'target/ai-project-board-backend-3.1.0.jar' "$latest" \
  '單筆輸入原樣輸出'

echo ""
if [ "$failures" -gt 0 ]; then
  echo "${failures} 項檢查失敗。"
  exit 1
fi
echo "全部通過。"

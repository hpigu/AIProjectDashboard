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

mode() {
  local value
  value="$(stat -c '%a' "$1" 2>/dev/null)"
  case "$value" in ''|*[!0-9]*) ;; *) printf '%s' "$value"; return 0 ;; esac
  stat -f '%Lp' "$1"
}

# 把一組 jar 路徑餵進排序函式，斷言最後一筆（最新的那份）是預期的檔案。
# 每個案例只差在輸入與預期，管線本身抽出來，才不會有人複製貼上時忘了改。
assert_latest() {
  local expected="$1" description="$2"
  shift 2
  assert_eq "$expected" "$(printf '%s\n' "$@" | board_sort_jars_by_version | tail -n1)" "$description"
}

# board-env.sh 只定義函式與預設值，source 進來不會有副作用。
# shellcheck source=bin/board-env.sh
source "${REPO_ROOT}/bin/board-env.sh"

echo '=== board_sort_jars_by_version ==='

# 這一項就是 #30 的回歸測試。字典序會把 3.10.0 排在 3.9.0 之前，於是跳到
# 3.10.0 的那一刻 find_jar 就會挑到舊 jar——而且啟動會成功，沒有任何徵兆。
assert_latest 'target/ai-project-board-backend-3.10.0.jar' \
  '3.10.0 比 3.9.0 新（字典序會弄反）' \
  'target/ai-project-board-backend-3.9.0.jar' \
  'target/ai-project-board-backend-3.10.0.jar'

assert_latest 'target/ai-project-board-backend-3.10.0.jar' \
  '多筆混雜時仍挑到最新的一份' \
  'target/ai-project-board-backend-3.2.0.jar' \
  'target/ai-project-board-backend-3.10.0.jar' \
  'target/ai-project-board-backend-3.1.0.jar'

# 路徑中的數字不得參與比較：版號只從檔名裡第一個「-數字」之後開始算。
assert_latest '/home/user2/target/ai-project-board-backend-3.2.0.jar' \
  '路徑裡的數字不會污染排序鍵' \
  '/home/user9/target/ai-project-board-backend-3.1.0.jar' \
  '/home/user2/target/ai-project-board-backend-3.2.0.jar'

# 預發布版比同版號的正式版舊，否則本機留著一份 SNAPSHOT 就會蓋過正式版。
assert_latest 'target/ai-project-board-backend-3.2.0.jar' \
  '3.2.0 比 3.2.0-SNAPSHOT 新' \
  'target/ai-project-board-backend-3.2.0-SNAPSHOT.jar' \
  'target/ai-project-board-backend-3.2.0.jar'

# 主版號跳躍也要正確：4.0.0 比 3.99.0 新。
assert_latest 'target/ai-project-board-backend-4.0.0.jar' \
  '4.0.0 比 3.99.0 新' \
  'target/ai-project-board-backend-3.99.0.jar' \
  'target/ai-project-board-backend-4.0.0.jar'

# 單筆輸入必須原樣輸出，不能因為排序邏輯而消失或被改寫。
latest="$(printf '%s\n' 'target/ai-project-board-backend-3.1.0.jar' \
  | board_sort_jars_by_version)"
assert_eq 'target/ai-project-board-backend-3.1.0.jar' "$latest" \
  '單筆輸入原樣輸出'

echo ""
echo '=== board_mask_db_url ==='

assert_eq 'jdbc:h2:file:./data/board;USER=***;PASSWORD=***' \
  "$(board_mask_db_url 'jdbc:h2:file:./data/board;USER=sa;PASSWORD=secret')" \
  '同時內嵌 USER 與 PASSWORD 都要被遮罩'

assert_eq 'jdbc:h2:file:./data/board;user=***;password=***' \
  "$(board_mask_db_url 'jdbc:h2:file:./data/board;user=sa;password=secret')" \
  '大小寫不敏感（H2 參數名不區分大小寫）'

assert_eq 'jdbc:h2:file:./data/board;DB_CLOSE_ON_EXIT=FALSE' \
  "$(board_mask_db_url 'jdbc:h2:file:./data/board;DB_CLOSE_ON_EXIT=FALSE')" \
  '沒有內嵌帳密時原樣輸出，不誤傷其他參數'

assert_eq 'jdbc:h2:file:./data/board;PASSWORD=***;DB_CLOSE_ON_EXIT=FALSE' \
  "$(board_mask_db_url 'jdbc:h2:file:./data/board;PASSWORD=secret;DB_CLOSE_ON_EXIT=FALSE')" \
  '只遮罩帳密參數本身，同一 URL 中其他參數維持可見'

echo ""
echo '=== runtime sensitive file permissions ==='

permission_work="$(mktemp -d "${TMPDIR:-/tmp}/board-permission-check.XXXXXX")"
trap 'rm -rf "$permission_work"' EXIT HUP INT TERM
db_file="$permission_work/board.mv.db"
log_file="$permission_work/board.log"
rolled_log="$permission_work/board.log.2026-08-11.0.gz"
console_file="$permission_work/custom-console.log"
printf 'H:2 fixture\n' > "$db_file"
printf 'active log\n' > "$log_file"
printf 'rolled log\n' > "$rolled_log"
printf 'console log\n' > "$console_file"
chmod 644 "$db_file" "$log_file" "$rolled_log" "$console_file"

board_secure_runtime_files "$db_file" "$log_file" "$console_file"
assert_eq 600 "$(mode "$db_file")" '既有 .mv.db 從過寬權限收斂為 0600'
assert_eq 600 "$(mode "$log_file")" '既有 active log 從過寬權限收斂為 0600'
assert_eq 600 "$(mode "$rolled_log")" '既有 rolling log 從過寬權限收斂為 0600'
assert_eq 600 "$(mode "$console_file")" '自訂 console log 從過寬權限收斂為 0600'

( board_set_secure_umask; printf 'new H2 file\n' > "$permission_work/rebuilt.mv.db" )
( board_set_secure_umask; printf 'new rolling log\n' > "$permission_work/board.log.2026-08-11.1.gz" )
assert_eq 600 "$(mode "$permission_work/rebuilt.mv.db")" 'H2 重建檔繼承 077 umask，建立即為 0600'
assert_eq 600 "$(mode "$permission_work/board.log.2026-08-11.1.gz")" '新 rolling log 繼承 077 umask，建立即為 0600'

chmod 644 "$db_file"
before_content="$(cat "$db_file")"
unsupported_output="$({
  chmod() { return 1; }
  board_secure_runtime_files "$db_file" "$log_file" "$console_file"
} 2>&1)"
assert_eq "$before_content" "$(cat "$db_file")" '不支援／拒絕 chmod 時不損毀既有資料'
case "$unsupported_output" in
  *'警告：無法修正既有檔案的權限'*)
    echo '  [PASS] 不支援／拒絕 chmod 時只警告並繼續' ;;
  *)
    echo '  [FAIL] 不支援／拒絕 chmod 時應輸出警告'
    failures=$((failures + 1)) ;;
esac

echo ""
if [ "$failures" -gt 0 ]; then
  echo "${failures} 項檢查失敗。"
  exit 1
fi
echo "全部通過。"

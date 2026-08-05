#!/usr/bin/env bash
# board-env.sh — 看板路徑與環境變數的單一事實來源
#
# 由 bin/board、bin/start-board.sh、bin/restore-db.sh 共同 source。抽出來的原因：
# 這三個腳本必須對「PID 檔在哪、資料庫在哪、日誌在哪、埠號是多少」有完全一致的
# 認知，否則會出現「start 起在 A 資料庫、stop 找不到行程、restore 還原到 B」這種
# 最難查的錯誤。任一預設值只在這裡定義一次。
#
# 使用方式（呼叫端需先算好 REPO_ROOT）：
#   REPO_ROOT=/path/to/repo
#   source "${SCRIPT_DIR}/board-env.sh"
#
# 所有值都尊重呼叫端既有的環境變數，只在未設定時才填入預設值，因此
# `BOARD_PORT=18080 bin/board status` 這種一次性覆寫照常有效。

set -u

: "${REPO_ROOT:?board-env.sh 需要呼叫端先設定 REPO_ROOT}"

# ---------------------------------------------------------------------------
# 家目錄與資料目錄
#
# 資料目錄預設值有兩種情境（沿用 bin/start-board.sh 既有策略，不可任意更動，
# 否則既有使用者的看板會「看起來資料不見了」）：
#   a) 既有使用者：repo 內 <repo>/data/board.mv.db 已存在，代表這個 repo 位置
#      本來就是資料所在地，沿用該路徑向下相容。
#   b) 全新環境：改用 ~/.ai-project-board/data/board。plugin 目錄可能因更新
#      （重新 clone／覆蓋）而遺失內容，H2 檔案不能放在會被覆蓋的路徑下。
# ---------------------------------------------------------------------------
BOARD_HOME_DIR="${BOARD_HOME_DIR:-$HOME/.ai-project-board}"

if [ -f "${REPO_ROOT}/data/board.mv.db" ]; then
  BOARD_DEFAULT_DB_DIR="${REPO_ROOT}/data"
else
  BOARD_DEFAULT_DB_DIR="${BOARD_HOME_DIR}/data"
fi

BOARD_PORT="${BOARD_PORT:-8080}"
BOARD_DB_URL="${BOARD_DB_URL:-jdbc:h2:file:${BOARD_DEFAULT_DB_DIR}/board;DB_CLOSE_ON_EXIT=FALSE}"
BOARD_LOG_FILE="${BOARD_LOG_FILE:-${REPO_ROOT}/logs/board.log}"
BOARD_BACKUP_DIR="${BOARD_BACKUP_DIR:-${BOARD_HOME_DIR}/backups}"

# PID 檔放在家目錄而非 repo 內：repo 可能被 plugin 更新覆蓋，且同一份 repo 可能
# 被多個 worktree 共用，而「正在跑的看板」在一台機器上只有一個。
BOARD_PID_FILE="${BOARD_PID_FILE:-${BOARD_HOME_DIR}/board.pid}"

# 行程的 stdout/stderr。logback 已經寫 BOARD_LOG_FILE，但 logback 初始化「之前」
# 的失敗（JVM 參數錯誤、classpath 壞掉、port 綁不上的原始堆疊）只會出現在
# stdout；背景啟動時若不留這個檔，那類錯誤會完全消失，變成「啟動失敗但沒有訊息」。
BOARD_CONSOLE_LOG="${BOARD_CONSOLE_LOG:-${BOARD_LOG_FILE}.console}"

export BOARD_HOME_DIR BOARD_PORT BOARD_DB_URL BOARD_LOG_FILE BOARD_BACKUP_DIR
export BOARD_PID_FILE BOARD_CONSOLE_LOG

# ---------------------------------------------------------------------------
# 由 BOARD_DB_URL 反推 H2 檔案路徑（不含 .mv.db 副檔名）。非 file 模式（mem:、
# tcp:）回傳空字串，呼叫端需自行判斷。
# ---------------------------------------------------------------------------
board_db_file_path() {
  printf '%s' "$BOARD_DB_URL" | sed -n 's#^jdbc:h2:file:##p' | cut -d';' -f1
}

# ---------------------------------------------------------------------------
# 確認某個 PID 真的是本看板的 Java 行程，而不是 PID 被回收後的無關行程。
# stop/restart 前一定要問過這一題：對著陌生行程送 SIGTERM 是這類腳本最典型、
# 也最不可原諒的災難。
# ---------------------------------------------------------------------------
board_pid_is_board() {
  local pid="$1"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1

  local cmdline
  cmdline="$(ps -p "$pid" -o command= 2>/dev/null || ps -p "$pid" -o args= 2>/dev/null)"
  [ -n "$cmdline" ] || return 1

  case "$cmdline" in
    *ai-project-board*|*project-board*) return 0 ;;
    *) return 1 ;;
  esac
}

# 讀 PID 檔並驗證；檔案不存在、內容不是數字、行程已不在或不是看板都回非 0。
board_read_pid_file() {
  [ -f "$BOARD_PID_FILE" ] || return 1

  local pid
  pid="$(head -n1 "$BOARD_PID_FILE" 2>/dev/null | tr -d '[:space:]')"
  case "$pid" in
    ''|*[!0-9]*) return 1 ;;
  esac

  board_pid_is_board "$pid" || return 1
  printf '%s' "$pid"
  return 0
}

# 從埠號反查目前正在聽的 PID（PID 檔遺失時的後備手段，例如看板是手動
# `java -jar` 起的）。沒有 lsof 時回傳空字串。
board_pid_from_port() {
  command -v lsof >/dev/null 2>&1 || return 1
  local pid
  pid="$(lsof -nP -iTCP:"${BOARD_PORT}" -sTCP:LISTEN -t 2>/dev/null | head -n1)"
  [ -n "$pid" ] || return 1
  printf '%s' "$pid"
  return 0
}

# 看板是否正在回應 HTTP（不看行程，只看服務）。
board_http_ready() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -s -o /dev/null -w '%{http_code}' --max-time "${1:-3}" \
    "http://127.0.0.1:${BOARD_PORT}/api/health/live" 2>/dev/null | grep -q '^2'
}

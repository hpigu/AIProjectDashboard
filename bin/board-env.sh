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
# 取得檔案的 mtime（epoch 秒）。
#
# 不可以寫成 `stat -f '%m' "$f" || stat -c '%Y' "$f"`：那是本專案原本的寫法，
# 只在 macOS 正確。GNU coreutils 的 `-f` 是「顯示檔案系統狀態」而不是時間格式，
# 餵它 '%m' 會印出多行檔案系統資訊並以 exit 0 結束，於是 `||` 後面那個 GNU 寫法
# 永遠不會被執行，呼叫端拿到一坨垃圾字串當時間戳。實測後果：備份保留策略在
# Linux 上完全不是「保留最新 N 份」，而是任意順序——有可能刪掉最新的備份。
#
# 因此順序改為先 GNU（-c '%Y'）、再 BSD（-f '%m'），並且一律驗證結果是純數字；
# 兩者都不可用時退回 perl。取不到時回傳非 0，呼叫端必須保守處理（保留檔案）。
# ---------------------------------------------------------------------------
board_file_mtime() {
  local file="$1" value

  value="$(stat -c '%Y' "$file" 2>/dev/null)"
  case "$value" in ''|*[!0-9]*) ;; *) printf '%s' "$value"; return 0 ;; esac

  value="$(stat -f '%m' "$file" 2>/dev/null)"
  case "$value" in ''|*[!0-9]*) ;; *) printf '%s' "$value"; return 0 ;; esac

  value="$(perl -e 'print((stat($ARGV[0]))[9])' "$file" 2>/dev/null)"
  case "$value" in ''|*[!0-9]*) ;; *) printf '%s' "$value"; return 0 ;; esac

  return 1
}

# ---------------------------------------------------------------------------
# 從 stdin 讀入 jar 路徑，依版號由舊到新排序後輸出。
#
# 原本 start-board.sh 用 `sort | tail -n1`、board.ps1 用 `Sort-Object Name`，
# 兩者都是字典序：「3.10.0」排在「3.9.0」之前，所以跳到 3.10.0 的那一刻就會挑
# 到舊 jar。症狀是啟動成功、版本錯誤、全程沒有任何訊息——最難發現的那一種。
#
# 不用 `sort -V`：它在 GNU coreutils 上沒問題，但不是每個平台都有（本專案已為
# macOS 的 bash 3.2 讓過一次步）。改成自己組排序鍵，把版號每段數字補零到固定
# 寬度，字典序就等同版號序，任何 POSIX awk 都跑得動。
#
# 版號取自檔名中第一個「-數字」之後的部分（Maven 的 <artifactId>-<version>.jar），
# 因此路徑裡的數字（例如 /home/user2/）不會污染排序鍵。
# 3.2.0-SNAPSHOT 排在 3.2.0 之前：預發布版比同版號的正式版舊。
# ---------------------------------------------------------------------------
board_sort_jars_by_version() {
  awk '
    {
      base = $0
      sub(/.*\//, "", base)
      sub(/\.jar$/, "", base)
      version = match(base, /-[0-9]/) ? substr(base, RSTART + 1) : base

      # 固定產出 6 段，不足的補 0——「3.2」與「3.2.0」必須算出同一把鍵，
      # 否則兩者在第一個相異位元組上就被分開，等長比較才成立。
      key = ""
      rest = version
      for (i = 0; i < 6; i++) {
        if (match(rest, /[0-9]+/)) {
          key = key sprintf("%08d.", substr(rest, RSTART, RLENGTH) + 0)
          rest = substr(rest, RSTART + RLENGTH)
        } else {
          key = key sprintf("%08d.", 0)
        }
      }
      key = key (version ~ /-/ ? "0" : "1")

      printf "%s\t%s\n", key, $0
    }
  ' | sort | cut -f2-
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

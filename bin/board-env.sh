#!/usr/bin/env bash
# board-env.sh — 看板路徑與環境變數的單一事實來源
#
# 由 bin/board 與 bin/restore-db.sh 共同 source。抽出來的原因：
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

# 已安裝的 release 會在 <install-root>/config/board.env 寫入這兩個固定鍵。
# 這不是一般 shell 設定檔，刻意不用 source：安裝目錄中的資料不應取得啟動／
# 備份／還原腳本的任意 shell 執行權。此處而不是 bin/board 讀取，確保獨立執行
# backup-db.sh 與 restore-db.sh 時也能得到相同的已安裝資料根目錄。
INSTALL_CONFIG="${REPO_ROOT}/config/board.env"
if [ -f "$INSTALL_CONFIG" ]; then
  BOARD_INSTALLED_HOME="$(sed -n 's/^BOARD_INSTALLED_HOME=//p' "$INSTALL_CONFIG")"
  BOARD_INSTALLED_JAR="$(sed -n 's/^BOARD_INSTALLED_JAR=//p' "$INSTALL_CONFIG")"
  if [ -z "$BOARD_INSTALLED_HOME" ] || [ -z "$BOARD_INSTALLED_JAR" ] \
      || [ "$(printf '%s\n' "$BOARD_INSTALLED_HOME" | wc -l | tr -d '[:space:]')" != "1" ] \
      || [ "$(printf '%s\n' "$BOARD_INSTALLED_JAR" | wc -l | tr -d '[:space:]')" != "1" ]; then
    printf '[board-env][錯誤] 安裝設定格式無效：%s\n' "$INSTALL_CONFIG" >&2
    return 1 2>/dev/null || exit 1
  fi
  export BOARD_INSTALLED_HOME BOARD_INSTALLED_JAR
fi

# ---------------------------------------------------------------------------
# 家目錄與資料目錄
#
# 資料目錄預設值有兩種情境（沿用啟動流程的既有策略，不可任意更動，
# 否則既有使用者的看板會「看起來資料不見了」）：
#   a) 既有使用者：repo 內 <repo>/data/board.mv.db 已存在，代表這個 repo 位置
#      本來就是資料所在地，沿用該路徑向下相容。
#   b) 全新環境：改用 ~/.ai-project-board/data/board。plugin 目錄可能因更新
#      （重新 clone／覆蓋）而遺失內容，H2 檔案不能放在會被覆蓋的路徑下。
# ---------------------------------------------------------------------------
BOARD_HOME_DIR="${BOARD_HOME_DIR:-${BOARD_INSTALLED_HOME:-$HOME/.ai-project-board}}"

if [ -f "${REPO_ROOT}/data/board.mv.db" ]; then
  BOARD_DEFAULT_DB_DIR="${REPO_ROOT}/data"
else
  BOARD_DEFAULT_DB_DIR="${BOARD_HOME_DIR}/data"
fi

BOARD_PORT="${BOARD_PORT:-8080}"
BOARD_DB_URL="${BOARD_DB_URL:-jdbc:h2:file:${BOARD_DEFAULT_DB_DIR}/board;DB_CLOSE_ON_EXIT=FALSE}"
BOARD_LOG_FILE="${BOARD_LOG_FILE:-${REPO_ROOT}/logs/board.log}"
BOARD_BACKUP_DIR="${BOARD_BACKUP_DIR:-${BOARD_HOME_DIR}/backups}"

# install/install.sh 的已安裝版以此絕對路徑指定 release JAR。使用者顯式設定
# BOARD_JAR 時仍優先，方便診斷或回復到保留的舊 release；沒有安裝設定的 repo
# checkout 則完全不受影響。
if [ -n "${BOARD_INSTALLED_JAR:-}" ]; then
  : "${BOARD_JAR:=${BOARD_INSTALLED_JAR}}"
fi

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
# 遮罩 BOARD_DB_URL 中可能內嵌的連線憑證，供任何要印到 console／log 的地方使用。
#
# H2 的 JDBC URL 語法允許把帳密當成分號分隔的參數內嵌在 URL 裡（例如
# jdbc:h2:file:./data/board;USER=sa;PASSWORD=secret），而 BOARD_DB_URL 整串
# 都來自使用者可控的環境變數。啟動、status 等指令原本直接把整串 URL 印到
# console／BOARD_LOG_FILE，一旦使用者這樣設定，密碼就會明文留在終端機捲動
# 記錄與日誌檔裡。這裡只遮罩 USER/PASSWORD 兩個參數的值，其餘部分（路徑、
# DB_CLOSE_ON_EXIT 等非敏感設定）維持可見，仍足以除錯。
# ---------------------------------------------------------------------------
board_mask_db_url() {
  printf '%s' "$1" | sed -E \
    -e 's/([Uu][Ss][Ee][Rr]=)[^;]*/\1***/g' \
    -e 's/([Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]=)[^;]*/\1***/g'
}

# 不依賴呼叫端是否已定義 err()：board-env.sh 會被 bin/backup-db.sh 獨立執行時
# 直接 source（此時呼叫端的 err() 尚未定義），因此這裡自帶一份最小輸出函式，
# 不覆蓋呼叫端已定義的同名函式（bin/board、bin/restore-db.sh 都會先定義好
# 自己的 err() 才 source 本檔，沿用它們的前綴，不被這裡蓋掉）。
if ! declare -f err >/dev/null 2>&1; then
  err() { printf '[board-env][錯誤] %s\n' "$*" >&2; }
fi

# ---------------------------------------------------------------------------
# 建立目錄（若不存在）並收斂為僅目前使用者可讀寫執行（0700）。
#
# 對稱於 Java 側的 dev.aiboard.config.SensitiveDirectories：新建目錄一律
# fail closed（chmod 失敗即整個函式失敗，呼叫端必須中止，因為此時目錄裡還
# 沒有任何使用者資料，放行只會留下一個從一開始就權限過寬的目錄）；已存在的
# 目錄則盡力修正過寬權限，chmod 失敗只印警告並放行，不阻擋腳本繼續執行、
# 也不刪除或搬動任何既有資料。
#
# 這裡管的是 shell 腳本自己會建立的路徑（PID 目錄、日誌目錄、啟動前備份目錄、
# 資料庫目錄），與 Java 行程啟動後由 EnvironmentPostProcessor／
# H2SnapshotService 收斂的是同一批路徑、同一套語意，只是腳本先於 JVM 執行時
# 先做一次，避免行程啟動前的短暫窗口仍是預設 umask。
#
# 沿路每一層「本次呼叫才新建」的父目錄都要收斂，不是只顧最內層：全新環境
# 首次啟動時 ~/.ai-project-board 與 ~/.ai-project-board/data 可能一次都不
# 存在，`mkdir -p` 會一次建立兩層，若只 chmod 最內層，外層仍是預設 umask，
# 同機其他使用者依然能列出裡面有哪些檔案。與 Java 側
# SensitiveDirectories.ensureSecureDirectory 的鏈式收斂邏輯對稱。
# ---------------------------------------------------------------------------
board_secure_dir() {
  local dir="$1"

  # 先找出「這次呼叫之前不存在、待會會被 mkdir -p 一併建立」的父目錄鏈，
  # 由外往內收集，才能在建立後依序收斂每一層。
  local missing_ancestors="" cursor
  cursor="$(dirname "$dir")"
  while [ "$cursor" != "/" ] && [ ! -d "$cursor" ]; do
    missing_ancestors="$cursor
$missing_ancestors"
    cursor="$(dirname "$cursor")"
  done

  local dir_was_new=0
  [ -d "$dir" ] || dir_was_new=1

  if ! mkdir -p "$dir" 2>/dev/null; then
    err "無法建立目錄：$dir"
    return 1
  fi

  local ancestor
  if [ -n "$missing_ancestors" ]; then
    while IFS= read -r ancestor; do
      [ -n "$ancestor" ] || continue
      if ! chmod 700 "$ancestor" 2>/dev/null; then
        err "無法將新建目錄收斂為僅目前使用者可存取（0700），為避免留下權限過寬的敏感目錄，已中止：$ancestor"
        return 1
      fi
    done <<EOF
$missing_ancestors
EOF
  fi

  if ! chmod 700 "$dir" 2>/dev/null; then
    if [ "$dir_was_new" -eq 1 ]; then
      err "無法將新建目錄收斂為僅目前使用者可存取（0700），為避免留下權限過寬的敏感目錄，已中止：$dir"
      return 1
    fi
    err "警告：無法修正既有目錄的權限（不影響服務運作、未變更任何資料）：$dir"
  fi

  return 0
}

# 收斂既有檔案為僅目前使用者可讀寫（0600）。與 board_secure_dir 相同的
# fail-closed／安全放行語意，由呼叫端傳入 $2=1 表示該檔案是本次新建。
board_secure_file() {
  local file="$1"
  local newly_created="${2:-0}"

  if ! chmod 600 "$file" 2>/dev/null; then
    if [ "$newly_created" -eq 1 ]; then
      err "無法將新建檔案收斂為僅目前使用者可讀寫（0600），為避免留下權限過寬的敏感檔案，已中止：$file"
      return 1
    fi
    err "警告：無法修正既有檔案的權限（不影響服務運作、未變更任何資料）：$file"
  fi

  return 0
}

# POSIX 子行程繼承呼叫端的 umask。啟動 JVM 前先設成 077，H2 重建 .mv.db、
# Logback 建立 active／rolling log 時，核心會從建立當下就移除 group／other 權限，
# 不必等到事後 chmod 才補救，也不需要用高頻輪詢浪費資源。
board_set_secure_umask() {
  umask 077
}

# 啟動前補修既有的 H2 資料庫與 Logback 檔案。這是 `umask 077` 的升級路徑：
# umask 只影響之後新建的檔案，舊版留下的 0644 檔案仍需顯式收斂。
#
# 失敗語意沿用 board_secure_file：既有資料只警告、絕不阻擋啟動或刪改內容。
# Logback 的 rolling pattern 是 <active-log>.<date>.<index>.gz，因此只掃描 active
# log 的精確名稱與同 basename 前綴，不會遞迴碰觸日誌目錄中的其他檔案。
board_secure_runtime_files() {
  local db_file="${1:-}"
  local log_file="${2:-}"
  local console_file="${3:-}"
  local file

  if [ -n "$db_file" ] && [ -f "$db_file" ] && [ ! -L "$db_file" ]; then
    board_secure_file "$db_file" 0
  fi

  if [ -n "$log_file" ]; then
    for file in "$log_file" "$log_file".*; do
      [ -f "$file" ] || continue
      [ -L "$file" ] && continue
      board_secure_file "$file" 0
    done
  fi

  # 自訂 BOARD_CONSOLE_LOG 不一定以 BOARD_LOG_FILE 為前綴，需獨立處理。
  if [ -n "$console_file" ] && [ "$console_file" != "$log_file" ] \
      && [ -f "$console_file" ] && [ ! -L "$console_file" ]; then
    board_secure_file "$console_file" 0
  fi

  return 0
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
# 原本 bash 側用 `sort | tail -n1`、board.ps1 用 `Sort-Object Name`，
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

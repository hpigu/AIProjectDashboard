#!/usr/bin/env bash
# install-launchd.sh — 安裝 macOS launchd job，讓看板登入時自動啟動、
# 意外終止時自動重啟。
#
# 用法：
#   bin/install-launchd.sh                  安裝正式 job（label
#                                            dev.aiboard.board，埠號 8080，
#                                            資料庫沿用 start-board.sh 的
#                                            預設推斷邏輯）
#   BOARD_LAUNCHD_LABEL=<label> BOARD_PORT=<port> BOARD_DB_URL=<jdbc-url> \
#     bin/install-launchd.sh                安裝自訂 label／埠號／資料庫的
#                                            job（驗證、多實例情境用）。
#                                            驗證時務必另外指定 BOARD_DB_URL，
#                                            否則 start-board.sh 會因為
#                                            <repo>/data/board.mv.db 已存在
#                                            （正式看板的資料庫）而「向下
#                                            相容」選中同一個檔案，即使埠號
#                                            不同也一樣，會有多個行程共用
#                                            同一個 H2 檔案而鎖檔衝突的風險。
#
# 這支腳本只負責「產生 plist 並載入」，不會動到任何已在執行中的行程——
# 如果 __BOARD_PORT__ 當下已有服務在跑（例如使用者手動啟動的正式看板），
# RunAtLoad 觸發後 wrapper 會呼叫 start-board.sh，其埠號檢查邏輯偵測到
# 「已有看板在跑」會直接 exit 0，不會搶埠號、不會動到既有行程。

set -u

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -P "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

log() { printf '[install-launchd] %s\n' "$*"; }
err() { printf '[install-launchd][錯誤] %s\n' "$*" >&2; }

if [ "$(uname -s)" != "Darwin" ]; then
  err "本腳本僅支援 macOS（launchd）。"
  exit 1
fi

LABEL="${BOARD_LAUNCHD_LABEL:-dev.aiboard.board}"
PORT="${BOARD_PORT:-8080}"
DB_URL="${BOARD_DB_URL:-}"
TEMPLATE="$REPO_ROOT/bin/launchd/dev.aiboard.board.plist.template"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST_PATH="$LAUNCH_AGENTS_DIR/${LABEL}.plist"
LOG_DIR="$REPO_ROOT/logs"
STDOUT_LOG="$LOG_DIR/launchd-${LABEL}.out.log"
STDERR_LOG="$LOG_DIR/launchd-${LABEL}.err.log"

if [ ! -f "$TEMPLATE" ]; then
  err "找不到模板：$TEMPLATE"
  exit 1
fi

mkdir -p "$LAUNCH_AGENTS_DIR" "$LOG_DIR"

log "Label:          $LABEL"
log "埠號:           $PORT"
log "資料庫:         ${DB_URL:-（沿用 start-board.sh 預設推斷）}"
log "repo 根目錄:    $REPO_ROOT"
log "plist 輸出位置: $PLIST_PATH"

# __BOARD_DB_URL_ENTRY__ 在模板裡獨佔一行，逐行處理：該行若無 BOARD_DB_URL
# 就整行移除，若有就換成對應的 <key>/<string> 兩行 XML。用 while read 逐行
# 組出最終內容，避免依賴 sed/perl 在不同平台對多行替換與跳脫字元的差異行為。
: > "$PLIST_PATH"
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    *__BOARD_DB_URL_ENTRY__*)
      if [ -n "$DB_URL" ]; then
        printf '        <key>BOARD_DB_URL</key>\n        <string>%s</string>\n' "$DB_URL" >> "$PLIST_PATH"
      fi
      ;;
    *)
      printf '%s\n' "$line" \
        | sed \
            -e "s#__LABEL__#${LABEL}#g" \
            -e "s#__REPO_ROOT__#${REPO_ROOT}#g" \
            -e "s#__BOARD_PORT__#${PORT}#g" \
            -e "s#__STDOUT_LOG__#${STDOUT_LOG}#g" \
            -e "s#__STDERR_LOG__#${STDERR_LOG}#g" \
        >> "$PLIST_PATH"
      ;;
  esac
done < "$TEMPLATE"

chmod +x "$REPO_ROOT/bin/board-agent-wrapper.sh" "$REPO_ROOT/bin/start-board.sh" "$REPO_ROOT/bin/stop-board.sh" 2>/dev/null || true

# 若已存在同 label 的 job（例如重新安裝），先卸載乾淨再重新載入，
# 避免 launchctl load 對已載入 job 報錯或吃到舊設定。
if launchctl print "gui/$(id -u)/${LABEL}" >/dev/null 2>&1; then
  log "偵測到既有 job（${LABEL}），先卸載再重新載入……"
  launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true
fi

log "載入 launchd job……"
if ! launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH"; then
  err "launchctl bootstrap 失敗，請檢查 $PLIST_PATH 內容與上方錯誤訊息。"
  exit 1
fi

launchctl enable "gui/$(id -u)/${LABEL}" >/dev/null 2>&1 || true

log "安裝完成。"
log "  查看狀態：launchctl print gui/$(id -u)/${LABEL}"
log "  launchd 自身日誌：$STDOUT_LOG / $STDERR_LOG"
log "  應用日誌（Spring Boot）：$LOG_DIR/board.log（依 BOARD_LOG_FILE 設定）"
log "  立即手動觸發一次（不等下次登入）：launchctl kickstart -k gui/$(id -u)/${LABEL}"
log "  暫停自動重啟：bin/stop-board.sh"
log "  完全移除：bin/uninstall-launchd.sh"
if [ "$LABEL" != "dev.aiboard.board" ] || [ "$PORT" != "8080" ]; then
  log "注意：本次安裝使用非預設 label/埠號，僅供驗證用，記得驗證完執行 uninstall-launchd.sh 卸載。"
fi

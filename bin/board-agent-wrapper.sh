#!/usr/bin/env bash
# board-agent-wrapper.sh — launchd 常駐用的入口，包住 start-board.sh。
#
# 為什麼需要這一層，不直接讓 launchd 呼叫 start-board.sh：
#
#   1. 手動停用旗標：使用者想暫時或永久不要被自動重啟時（例如手動維護、
#      除錯、或決定改用其他方式啟動），需要一個「明確、不必刪 plist 也
#      生效」的開關。這裡用旗標檔 $BOARD_DISABLE_FLAG，存在就直接
#      exit 0（對 launchd 而言是「正常執行完畢」，不會被視為異常而更快
#      重試，也不會真的去 fork java）。開關方式見 stop-board.sh。
#
#   2. Crash-loop 保護：launchd 的 KeepAlive 只知道「行程結束了」，不知道
#      「為什麼結束」。如果原因是不可恢復的（例如資料庫檔案損毀、埠號被
#      別的服務永久佔用、编译失敗），沒有這層保護就會無限快速重試、
#      狂寫 log。這裡用「失敗次數 + 時間窗」判斷：在 $LOOP_WINDOW_SEC 秒
#      內累計失敗達 $LOOP_MAX_FAILURES 次，就自動寫入停用旗標並停止嘗試，
#      同時把原因記進 log，讓使用者下次登入或查看 log 時能一眼看到，而
#      不是一直默默重試到天荒地老。
#
#      注意：launchd 本身有 ThrottleInterval 可以限制「兩次啟動之間至少
#      間隔幾秒」，本腳本另外疊加次數上限，兩者互補：ThrottleInterval 防
#      止 CPU 被瞬間狂打，次數上限防止「雖然有節流、但仍然無止盡地每隔
#      N 秒重試一個永遠不會成功的狀況」。
#
#   3. 前景執行：把 BOARD_FOREGROUND=1 帶給 start-board.sh，讓它在確認
#      就緒後阻塞於 java 行程，使 launchd 對「本腳本」的存活監控等同於對
#      java 行程本身的存活監控。

set -u

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -P "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

STATE_DIR="${BOARD_HOME_DIR:-$HOME/.ai-project-board}"
mkdir -p "$STATE_DIR" 2>/dev/null || true

BOARD_DISABLE_FLAG="${BOARD_DISABLE_FLAG:-$STATE_DIR/board-disabled}"
FAILURE_LOG="${BOARD_FAILURE_LOG:-$STATE_DIR/board-launchd-failures.log}"
LOOP_WINDOW_SEC="${BOARD_LOOP_WINDOW_SEC:-120}"
LOOP_MAX_FAILURES="${BOARD_LOOP_MAX_FAILURES:-5}"

log() { printf '[board-agent-wrapper] %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. 停用旗標檢查
# ---------------------------------------------------------------------------
if [ -e "$BOARD_DISABLE_FLAG" ]; then
  log "偵測到停用旗標 $BOARD_DISABLE_FLAG，略過啟動。"
  log "如需恢復自動啟動，執行：bin/stop-board.sh --enable"
  exit 0
fi

# ---------------------------------------------------------------------------
# 2. crash-loop 偵測：記錄本次啟動嘗試時間，若時間窗內失敗次數超標就停用
# ---------------------------------------------------------------------------
NOW_EPOCH="$(date +%s)"
touch "$FAILURE_LOG" 2>/dev/null || true

if [ -s "$FAILURE_LOG" ]; then
  RECENT_COUNT=0
  TMP_LOG="$(mktemp 2>/dev/null || echo "$FAILURE_LOG.tmp")"
  : > "$TMP_LOG"
  while IFS= read -r ts; do
    [ -n "$ts" ] || continue
    if [ $(( NOW_EPOCH - ts )) -le "$LOOP_WINDOW_SEC" ]; then
      echo "$ts" >> "$TMP_LOG"
      RECENT_COUNT=$((RECENT_COUNT + 1))
    fi
  done < "$FAILURE_LOG"
  mv "$TMP_LOG" "$FAILURE_LOG" 2>/dev/null || true

  if [ "$RECENT_COUNT" -ge "$LOOP_MAX_FAILURES" ]; then
    log "最近 ${LOOP_WINDOW_SEC} 秒內已失敗 ${RECENT_COUNT} 次（門檻 ${LOOP_MAX_FAILURES}），判定為 crash loop。"
    log "自動寫入停用旗標 $BOARD_DISABLE_FLAG，停止自動重試，避免無限循環。"
    date -u +"%Y-%m-%dT%H:%M:%SZ crash-loop-detected" > "$BOARD_DISABLE_FLAG"
    log "請查看 ${BOARD_LOG_FILE:-$REPO_ROOT/logs/board.log} 排除問題後，執行 bin/stop-board.sh --enable 恢復。"
    exit 0
  fi
fi

# ---------------------------------------------------------------------------
# 3. 呼叫 start-board.sh（前景模式）
# ---------------------------------------------------------------------------
export BOARD_FOREGROUND=1
"$SCRIPT_DIR/start-board.sh"
EXIT_CODE=$?

if [ "$EXIT_CODE" -ne 0 ]; then
  echo "$NOW_EPOCH" >> "$FAILURE_LOG"
  log "start-board.sh 結束碼非 0（${EXIT_CODE}），已記錄失敗時間。"
else
  # 正常結束（例如 java 行程被使用者手動 kill）不算「無法啟動」，
  # 清空失敗記錄，避免「曾經失敗過幾次、後來又正常跑了很久」被誤判。
  : > "$FAILURE_LOG" 2>/dev/null || true
fi

exit "$EXIT_CODE"

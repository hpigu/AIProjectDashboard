#!/usr/bin/env bash
# stop-board.sh — 使用者手動停止／恢復 launchd 自動管理的看板行程。
#
# 背景問題：一旦裝了 launchd KeepAlive，單純 `kill` 掉 java 行程幾秒後就會
# 被自動拉起來，讓人以為「殺不掉」。這支腳本提供明確、不需要記 launchctl
# 指令細節的操作方式：
#
#   bin/stop-board.sh            暫時停止：kill 掉目前行程 + 寫入停用旗標，
#                                 之後即使登出登入、或行程被其他方式終止，
#                                 launchd 觸發 wrapper 時都會直接略過啟動。
#                                 launchd job 本身仍是 loaded 狀態。
#   bin/stop-board.sh --enable   恢復：移除停用旗標，下次 launchd 觸發
#                                （或手動 kickstart）就會正常啟動。
#   bin/stop-board.sh --status   查看目前是否處於停用狀態、行程是否在跑。
#
# 這支腳本只處理「旗標檔」與「kill 現有行程」，不處理 launchd job 的安裝
# 與移除（那是 install-launchd.sh / uninstall-launchd.sh 的職責）——停用
# 旗標是「先不要自動啟動」，卸載 launchd job 才是「以後也不要自動啟動」。

set -u

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

STATE_DIR="${BOARD_HOME_DIR:-$HOME/.ai-project-board}"
mkdir -p "$STATE_DIR" 2>/dev/null || true
BOARD_DISABLE_FLAG="${BOARD_DISABLE_FLAG:-$STATE_DIR/board-disabled}"
FAILURE_LOG="${BOARD_FAILURE_LOG:-$STATE_DIR/board-launchd-failures.log}"
BOARD_PORT="${BOARD_PORT:-8080}"

log() { printf '[stop-board] %s\n' "$*"; }
err() { printf '[stop-board][錯誤] %s\n' "$*" >&2; }

usage() {
  cat <<EOF
用法：
  bin/stop-board.sh            暫時停止看板並停用自動重啟
  bin/stop-board.sh --enable   恢復自動重啟（不會立刻啟動，等下次觸發）
  bin/stop-board.sh --status   查看目前狀態
EOF
}

status() {
  if [ -e "$BOARD_DISABLE_FLAG" ]; then
    log "自動重啟：已停用（$(cat "$BOARD_DISABLE_FLAG" 2>/dev/null)）"
  else
    log "自動重啟：啟用中"
  fi
  local pid=""
  if command -v lsof >/dev/null 2>&1; then
    pid="$(lsof -nP -iTCP:"${BOARD_PORT}" -sTCP:LISTEN -t 2>/dev/null | head -n1)"
  fi
  if [ -n "$pid" ]; then
    log "行程狀態：:${BOARD_PORT} 上有行程在跑（PID ${pid}）"
  else
    log "行程狀態：:${BOARD_PORT} 目前沒有行程"
  fi
}

case "${1:-}" in
  --enable)
    if [ -e "$BOARD_DISABLE_FLAG" ]; then
      rm -f "$BOARD_DISABLE_FLAG"
      log "已移除停用旗標，下次 launchd 觸發時會恢復自動啟動。"
    else
      log "目前本來就是啟用狀態，無需操作。"
    fi
    : > "$FAILURE_LOG" 2>/dev/null || true
    status
    ;;
  --status)
    status
    ;;
  "")
    date -u +"%Y-%m-%dT%H:%M:%SZ manual-stop" > "$BOARD_DISABLE_FLAG"
    log "已寫入停用旗標：$BOARD_DISABLE_FLAG"

    PID=""
    if command -v lsof >/dev/null 2>&1; then
      PID="$(lsof -nP -iTCP:"${BOARD_PORT}" -sTCP:LISTEN -t 2>/dev/null | head -n1)"
    fi
    if [ -n "$PID" ]; then
      log "終止目前佔用 :${BOARD_PORT} 的行程（PID ${PID}）……"
      kill "$PID" 2>/dev/null
      log "已送出 kill，若 launchd 有管理該行程，因為已寫入停用旗標，不會被重新拉起。"
    else
      log ":${BOARD_PORT} 目前沒有偵測到行程，僅寫入停用旗標。"
    fi
    status
    ;;
  -h|--help)
    usage
    ;;
  *)
    err "未知參數：$1"
    usage
    exit 1
    ;;
esac

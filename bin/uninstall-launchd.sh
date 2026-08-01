#!/usr/bin/env bash
# uninstall-launchd.sh — 卸載 install-launchd.sh 安裝的 launchd job。
#
# 用法：
#   bin/uninstall-launchd.sh                      卸載預設 job（dev.aiboard.board）
#   BOARD_LAUNCHD_LABEL=<label> bin/uninstall-launchd.sh   卸載指定 label 的 job
#
# 只移除 launchd job 定義本身（bootout + 刪除 plist），不會 kill 掉目前
# 正在跑的看板行程——job 是 KeepAlive 的「以後怎麼重啟」，行程是「現在
# 有沒有在跑」，兩者分開處理；如果也想同時停掉目前行程，另外執行
# bin/stop-board.sh。

set -u

LABEL="${BOARD_LAUNCHD_LABEL:-dev.aiboard.board}"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST_PATH="$LAUNCH_AGENTS_DIR/${LABEL}.plist"

log() { printf '[uninstall-launchd] %s\n' "$*"; }
err() { printf '[uninstall-launchd][錯誤] %s\n' "$*" >&2; }

if [ "$(uname -s)" != "Darwin" ]; then
  err "本腳本僅支援 macOS（launchd）。"
  exit 1
fi

if [ ! -f "$PLIST_PATH" ]; then
  log "找不到 $PLIST_PATH，可能尚未安裝或已卸載，無需操作。"
  exit 0
fi

log "卸載 launchd job（${LABEL}）……"
launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true

rm -f "$PLIST_PATH"
log "已刪除 $PLIST_PATH"
log "完成。目前看板行程（若在跑）不受影響，僅移除了「未來自動重啟」的設定。"

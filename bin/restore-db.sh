#!/usr/bin/env bash
# restore-db.sh — 從備份還原看板資料庫
#
# 補上備份流程缺的另一半：備份若沒有演練過的還原程序，實務上等於沒有備份。
# 三種來源都能還原，格式只有兩種：
#   board-startup-*.mv.db    啟動前冷備份（bin/backup-db.sh，直接複製檔案）
#   board-shutdown-*.zip     關閉前一致性備份（ShutdownBackupService）
#   board-scheduled-*.zip    定期一致性備份（ScheduledBackupService）
# 後兩者都是 H2 BACKUP TO 產生的 zip，內含一份 .mv.db，還原方式完全相同。
#
# 用法：
#   bin/restore-db.sh --list                  列出可用備份（新到舊）
#   bin/restore-db.sh latest [--yes]          還原最新一份備份
#   bin/restore-db.sh <備份檔路徑> [--yes]     還原指定備份
#   bin/restore-db.sh latest --db /path/board 還原到指定資料庫（不含副檔名）
#
# 安全設計（每一條都對應一種會真的弄丟資料的情境）：
#   1. 看板還在跑就拒絕還原。H2 檔案被行程持有時覆寫它必然損毀資料庫，
#      而且應用記憶體中的狀態還會把舊內容寫回去。
#   2. 現有資料庫不刪除，改名保留成 <db>.mv.db.pre-restore-<UTC 時間戳>。
#      還原本身就是高風險操作，「還原錯備份」必須可以救回來。
#   3. 寫入走 .tmp → 驗證 → 原子改名，與 backup-db.sh 相同，避免解壓或複製
#      到一半留下半成品被當成正式資料庫。
#   4. 非互動環境（沒有 TTY）必須明確加 --yes，避免被腳本或 CI 意外觸發。
#
# 離開碼：0 成功／1 失敗或被拒絕／2 用法錯誤

set -u

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SCRIPT_SOURCE" ]; do
  SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" >/dev/null 2>&1 && pwd)"
  SCRIPT_SOURCE="$(readlink "$SCRIPT_SOURCE")"
  [[ $SCRIPT_SOURCE != /* ]] && SCRIPT_SOURCE="$SCRIPT_DIR/$SCRIPT_SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd -P "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)}"

log() { printf '[restore-db] %s\n' "$*"; }
err() { printf '[restore-db][錯誤] %s\n' "$*" >&2; }

# shellcheck source=bin/board-env.sh
source "${SCRIPT_DIR}/board-env.sh"

# ---------------------------------------------------------------------------
# 參數解析
# ---------------------------------------------------------------------------
BACKUP_ARG=""
ASSUME_YES=0
LIST_ONLY=0
DB_TARGET=""

while [ $# -gt 0 ]; do
  case "$1" in
    --list) LIST_ONLY=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --db) shift; DB_TARGET="${1:-}" ;;
    --backup-dir) shift; BOARD_BACKUP_DIR="${1:-}" ;;
    -h|--help)
      sed -n '2,30p' "$SCRIPT_SOURCE" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    -*) err "不認識的選項：$1"; exit 2 ;;
    *) BACKUP_ARG="$1" ;;
  esac
  shift
done

if [ -z "$DB_TARGET" ]; then
  DB_TARGET="$(board_db_file_path)"
fi

if [ -z "$DB_TARGET" ]; then
  err "無法從 BOARD_DB_URL 取得 H2 檔案路徑（目前為：${BOARD_DB_URL}）。"
  err "非 file 模式（mem:／tcp:）不適用本腳本，或請用 --db 明確指定。"
  exit 2
fi

DB_MV_FILE="${DB_TARGET}.mv.db"

# ---------------------------------------------------------------------------
# 列出備份：三種階段一起列，依 mtime 新到舊
#
# scheduled 一定要列進來：看板長時間運行時，最新的一份備份幾乎必然是排程備份，
# 漏掉它會讓 `latest` 挑到一份舊得多的快照，而使用者不會察覺。
# ---------------------------------------------------------------------------
list_backups() {
  [ -d "$BOARD_BACKUP_DIR" ] || return 0
  local f mtime
  while IFS= read -r -d '' f; do
    # 時間戳決定 latest 挑哪一份備份，不能用不可攜的 stat 寫法（見 board-env.sh
    # 的 board_file_mtime；用錯會讓 latest 在 Linux 上挑到任意一份）。
    if ! mtime="$(board_file_mtime "$f")"; then
      mtime=0
    fi
    printf '%s\t%s\n' "$mtime" "$f"
  done < <(find "$BOARD_BACKUP_DIR" -maxdepth 1 -type f \
             \( -name 'board-startup-*.mv.db' \
                -o -name 'board-shutdown-*.zip' \
                -o -name 'board-scheduled-*.zip' \) -print0 2>/dev/null) \
    | sort -k1,1nr
}

if [ "$LIST_ONLY" -eq 1 ]; then
  log "備份目錄：${BOARD_BACKUP_DIR}"
  found=0
  while IFS=$'\t' read -r mtime path; do
    [ -n "${path:-}" ] || continue
    found=1
    size="$(wc -c < "$path" 2>/dev/null | tr -d '[:space:]')"
    when="$(date -r "$mtime" '+%Y-%m-%d %H:%M:%S %Z' 2>/dev/null \
            || date -d "@$mtime" '+%Y-%m-%d %H:%M:%S %Z' 2>/dev/null || echo '?')"
    case "$(basename "$path")" in
      board-shutdown-*.zip)  kind="關閉前（一致性快照）" ;;
      board-scheduled-*.zip) kind="定期（一致性快照）" ;;
      *)                     kind="啟動前（冷備份）" ;;
    esac
    printf '  %s  %10s bytes  %s  %s\n' "$when" "${size:-?}" "$kind" "$path"
  done < <(list_backups)
  [ "$found" -eq 1 ] || log "（沒有找到任何備份）"
  exit 0
fi

if [ -z "$BACKUP_ARG" ]; then
  err "請指定要還原的備份檔，或用 latest 取用最新一份；先看清單可執行：$0 --list"
  exit 2
fi

# ---------------------------------------------------------------------------
# 決定來源備份檔
# ---------------------------------------------------------------------------
if [ "$BACKUP_ARG" = "latest" ]; then
  SOURCE_BACKUP="$(list_backups | head -n1 | cut -f2-)"
  if [ -z "$SOURCE_BACKUP" ]; then
    err "備份目錄沒有可用備份：${BOARD_BACKUP_DIR}"
    exit 1
  fi
  log "latest 解析為：${SOURCE_BACKUP}"
else
  SOURCE_BACKUP="$BACKUP_ARG"
fi

if [ ! -f "$SOURCE_BACKUP" ]; then
  err "備份檔不存在：${SOURCE_BACKUP}"
  exit 1
fi

# ---------------------------------------------------------------------------
# 安全檢查 1：看板不能在執行中
# ---------------------------------------------------------------------------
if board_http_ready 2; then
  err "看板正在 :${BOARD_PORT} 執行中，不能在執行中還原資料庫。"
  err "請先停止：bin/board stop（會順便產生一份關閉前備份），再重新執行本指令。"
  exit 1
fi

if [ -e "$DB_MV_FILE" ] && command -v lsof >/dev/null 2>&1; then
  LOCK_PIDS="$(lsof -nP -t -- "$DB_MV_FILE" 2>/dev/null | sort -u | tr '\n' ' ')"
  if [ -n "${LOCK_PIDS// /}" ]; then
    err "資料庫檔 ${DB_MV_FILE} 正被下列 PID 持有：${LOCK_PIDS}"
    err "請先結束該行程再還原，否則會寫出損壞的資料庫。"
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# 解壓工具：關閉前備份是 H2 BACKUP TO 產生的 zip，內含一份 .mv.db。
# 依序嘗試 unzip / python3 / jar，三者都沒有才放棄——刻意不引入新依賴，
# 這三個至少會有一個存在（jar 必然有，因為跑看板本來就需要 JDK）。
# ---------------------------------------------------------------------------
extract_zip() {
  local zip_file="$1" dest_dir="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -qq -o "$zip_file" -d "$dest_dir" && return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$zip_file" "$dest_dir" && return 0
  fi
  if command -v jar >/dev/null 2>&1; then
    ( cd "$dest_dir" && jar xf "$zip_file" ) && return 0
  fi
  err "找不到可用的解壓工具（unzip／python3／jar），無法還原 zip 備份。"
  return 1
}

# H2 MVStore 檔頭固定為 "H:2"；與 bin/backup-db.sh 的驗證方式一致。
verify_mv_db() {
  local file="$1"
  [ -s "$file" ] || { err "檔案為空：$file"; return 1; }
  local magic
  magic="$(dd if="$file" bs=1 count=3 2>/dev/null)"
  if [ "$magic" != "H:2" ]; then
    err "檔案缺少 H2 MVStore 檔頭（H:2），不是有效的 H2 資料庫：$file"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 確認：還原會取代現有資料庫（雖然會保留改名副本，仍要使用者點頭）
# ---------------------------------------------------------------------------
log "來源備份：${SOURCE_BACKUP}"
log "還原目標：${DB_MV_FILE}"
if [ -e "$DB_MV_FILE" ]; then
  log "現有資料庫會先改名保留，不會被刪除。"
else
  log "目標資料庫目前不存在（全新環境），將直接建立。"
fi

if [ "$ASSUME_YES" -ne 1 ]; then
  if [ ! -t 0 ]; then
    err "非互動環境需明確加上 --yes 才會執行還原，已中止。"
    exit 1
  fi
  printf '[restore-db] 確認要還原嗎？輸入 yes 繼續：'
  read -r answer
  if [ "$answer" != "yes" ]; then
    log "已取消，未變更任何檔案。"
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# 還原本體：先產生 .tmp、驗證通過後才動現有資料庫
# ---------------------------------------------------------------------------
mkdir -p "$(dirname "$DB_MV_FILE")" || { err "無法建立資料庫目錄"; exit 1; }

TMP_RESTORE="${DB_MV_FILE}.restore.tmp"
WORK_DIR=""
cleanup() {
  rm -f "$TMP_RESTORE" 2>/dev/null || true
  [ -n "$WORK_DIR" ] && rm -rf "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT

case "$SOURCE_BACKUP" in
  *.zip)
    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/board-restore.XXXXXX")" || { err "無法建立暫存目錄"; exit 1; }
    log "解壓一致性備份（zip）……"
    extract_zip "$SOURCE_BACKUP" "$WORK_DIR" || exit 1

    EXTRACTED="$(find "$WORK_DIR" -type f -name '*.mv.db' | head -n1)"
    if [ -z "$EXTRACTED" ]; then
      err "zip 內找不到任何 .mv.db，這不是 H2 BACKUP TO 產生的備份：${SOURCE_BACKUP}"
      exit 1
    fi
    log "取出：$(basename "$EXTRACTED")"
    cp -p -- "$EXTRACTED" "$TMP_RESTORE" || { err "複製解壓結果失敗"; exit 1; }
    ;;
  *)
    cp -p -- "$SOURCE_BACKUP" "$TMP_RESTORE" || { err "複製備份檔失敗"; exit 1; }
    ;;
esac

verify_mv_db "$TMP_RESTORE" || exit 1

# 現有資料庫改名保留。這一步失敗就直接中止，絕不強行覆蓋。
if [ -e "$DB_MV_FILE" ]; then
  PRESERVE_TS="$(TZ=UTC date -u +'%Y%m%dT%H%M%SZ')"
  PRESERVED="${DB_MV_FILE}.pre-restore-${PRESERVE_TS}"
  if ! mv -- "$DB_MV_FILE" "$PRESERVED"; then
    err "無法保留現有資料庫（改名失敗），已中止，未變更任何檔案。"
    exit 1
  fi
  log "現有資料庫已保留為：${PRESERVED}"
fi

if ! mv -f -- "$TMP_RESTORE" "$DB_MV_FILE"; then
  err "還原改名（原子提交）失敗：${TMP_RESTORE} -> ${DB_MV_FILE}"
  err "現有資料庫仍保留在 ${PRESERVED:-<無>}，可手動改回原檔名。"
  exit 1
fi

verify_mv_db "$DB_MV_FILE" || exit 1

# H2 的 trace/lock 殘留檔會讓下次啟動誤判狀態，還原後一併清掉。
rm -f "${DB_TARGET}.trace.db" "${DB_TARGET}.lock.db" 2>/dev/null || true

log "還原完成：${DB_MV_FILE}"
log "下一步：bin/board start，啟動後確認看板資料是否為預期的時間點。"
log "若還原錯了備份，改回原檔名即可：mv ${PRESERVED:-<保留檔>} ${DB_MV_FILE}"
exit 0

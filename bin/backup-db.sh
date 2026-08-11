#!/usr/bin/env bash
# backup-db.sh — 啟動前資料庫冷備份與保留策略
#
# 由 bin/board start 在確認 H2 檔案未被其他行程持有之後、啟動 jar
# （進而觸發 Flyway migration）之前呼叫。目的：一旦某次啟動的 migration
# 或後續操作把資料庫弄壞，還有啟動當下的快照可以還原。
#
# 用法（被 bin/board source 後呼叫函式，也可獨立執行）：
#   source bin/backup-db.sh
#   backup_database "$DB_MV_FILE" "$BACKUP_DIR"
#
# 獨立執行（主要供測試使用）：
#   BOARD_HOME_DIR=/path ./bin/backup-db.sh <db-mv-file> [backup-dir]
#
# 設計原則：
#   1. 資料庫不存在（全新環境首次啟動）不是錯誤，直接跳過，回傳成功。
#   2. 備份先寫到 <backup-dir>/*.tmp，複製完成且通過驗證後才 mv 成正式檔名，
#      避免半成品備份被誤認為可用備份，也避免與保留策略清理邏輯互相干擾。
#   3. 驗證方式：H2 的 .mv.db 是單一檔案的 MVStore 格式，檔首固定是
#      "H:2" 三個 magic bytes（後面接版本號等 metadata）。複製後檔案大小
#      需與來源一致，且檔首 magic bytes 需存在，才視為有效備份。這裡不引入
#      對 H2 Driver／JDBC 的依賴（開機腳本不應該還要拉 classpath），只做
#      檔案層級的完整性檢查。
#   4. 備份失敗（複製失敗、驗證失敗、目錄建立失敗等）一律回傳非 0，呼叫端
#      （bin/board start）必須因此中止啟動，不得繼續 migrate。
#   5. 保留策略：刪除 30 天前的備份，但刪除後至少要留最新 7 份——即使
#      所有備份都超過 30 天，也只刪到剩 7 份為止；不足 7 份時完全不刪。
#   6. 不引入常駐、排程（cron/launchd）或跨 OS 互動 CLI；純粹是啟動流程中
#      的一個同步步驟。

set -u

# 需要 board_file_mtime()（見 board-env.sh 內對 stat 可攜性的完整說明：
# GNU 與 BSD 的 stat 時間格式選項不同，寫錯會讓保留策略拿到垃圾時間戳）。
# bin/board 會先 source board-env.sh，此時不必重複載入；獨立執行時自行載入。
if ! declare -f board_file_mtime >/dev/null 2>&1; then
  _BACKUP_SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
  REPO_ROOT="${REPO_ROOT:-$(cd -P "${_BACKUP_SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)}"
  # shellcheck source=bin/board-env.sh
  source "${_BACKUP_SCRIPT_DIR}/board-env.sh"
fi

_backup_log()  { printf '[backup-db] %s\n' "$*"; }
_backup_err()  { printf '[backup-db][錯誤] %s\n' "$*" >&2; }

# 保留規則參數（保留天數 / 至少保留份數），維持可覆寫以利測試。
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
BACKUP_RETENTION_MIN_COUNT="${BACKUP_RETENTION_MIN_COUNT:-7}"

# ---------------------------------------------------------------------------
# 驗證單一備份檔：大小需與來源相同，且檔首需有 H2 MVStore 的 "H:2" magic
# bytes。避免複製到一半、磁碟滿了等情況產生的半成品被當成可用備份。
# ---------------------------------------------------------------------------
_backup_verify_file() {
  local src="$1" dst="$2"

  [ -f "$dst" ] || { _backup_err "備份檔案不存在：$dst"; return 1; }

  local src_size dst_size
  src_size="$(wc -c < "$src" 2>/dev/null | tr -d '[:space:]')"
  dst_size="$(wc -c < "$dst" 2>/dev/null | tr -d '[:space:]')"
  if [ -z "$src_size" ] || [ -z "$dst_size" ] || [ "$src_size" != "$dst_size" ]; then
    _backup_err "備份檔案大小與來源不一致（來源 ${src_size:-?} bytes，備份 ${dst_size:-?} bytes）"
    return 1
  fi

  local magic
  magic="$(dd if="$dst" bs=1 count=3 2>/dev/null)"
  if [ "$magic" != "H:2" ]; then
    _backup_err "備份檔案缺少 H2 MVStore 檔頭（H:2），可能已損壞：$dst"
    return 1
  fi

  return 0
}

# ---------------------------------------------------------------------------
# 清理由前次中斷留下的啟動備份暫存檔。只處理 backup_dir 第一層、一般檔案，
# 且檔名必須符合本腳本產生的 board-startup-*.mv.db.tmp；正式 .mv.db 備份、
# 子目錄、符號連結與其他 .tmp 檔都不在清理範圍內。
# ---------------------------------------------------------------------------
_backup_cleanup_stale_tmp() {
  local backup_dir="$1"
  [ -d "$backup_dir" ] || return 0

  local stale_tmp
  while IFS= read -r -d '' stale_tmp; do
    _backup_log "清理前次中斷留下的暫存備份：${stale_tmp}"
    if ! rm -f -- "$stale_tmp"; then
      _backup_err "無法清理暫存備份：$stale_tmp"
      return 1
    fi
  done < <(find "$backup_dir" -maxdepth 1 -type f -name 'board-startup-*.mv.db.tmp' -print0 2>/dev/null)

  return 0
}

# ---------------------------------------------------------------------------
# 保留策略：
#   - 保留 30 天內（含）的所有備份。
#   - 30 天前的備份，只要刪除後剩餘總份數仍 >= 7，就可以刪；
#     一旦刪到只剩 7 份就停手，即使還有更舊的備份。
#   - candidates 依「檔名時間戳」新到舊排序，確保優先保留新的備份。
# ---------------------------------------------------------------------------
_backup_apply_retention() {
  local backup_dir="$1"
  [ -d "$backup_dir" ] || return 0

  local now_epoch cutoff_epoch
  now_epoch="$(date +%s)"
  cutoff_epoch=$((now_epoch - BACKUP_RETENTION_DAYS * 86400))

  # 依 mtime 新到舊列出所有正式備份檔（*.mv.db，排除 .tmp 半成品）。
  local files=()
  while IFS= read -r -d '' f; do
    files+=("$f")
  done < <(find "$backup_dir" -maxdepth 1 -type f -name 'board-startup-*.mv.db' -print0 2>/dev/null)

  local total="${#files[@]}"
  [ "$total" -eq 0 ] && return 0

  # 依 mtime 新到舊排序（用 stat 取 mtime 後交給 sort，避免依賴 find -newer
  # 之類非可攜選項）。時間戳一律經 board_file_mtime 取得，它會處理 GNU 與 BSD
  # 的 stat 差異並保證回傳純數字。
  local sortable=()
  local f mtime
  for f in "${files[@]}"; do
    if ! mtime="$(board_file_mtime "$f")"; then
      # 取不到時間戳就無法判斷新舊；保守起見保留該檔案，不納入刪除候選。
      _backup_err "無法取得備份檔時間戳，保留不刪：$f"
      continue
    fi
    sortable+=("${mtime}:${f}")
  done

  [ "${#sortable[@]}" -eq 0 ] && return 0

  # 逐行讀進陣列，不用 sorted=($(...))：後者會依 IFS 對命令輸出做 word splitting，
  # 備份目錄路徑一旦含有空白（macOS 的家目錄很常見），單一項目會被拆成兩筆，
  # 保留策略就會對著不存在的路徑做判斷。也刻意不用 mapfile——macOS 內建的
  # bash 3.2 沒有這個 builtin，而本腳本必須能在原生 bash 上跑。
  local sorted=()
  local sorted_entry
  while IFS= read -r sorted_entry; do
    [ -n "$sorted_entry" ] || continue
    sorted+=("$sorted_entry")
  done < <(printf '%s\n' "${sortable[@]}" | sort -t: -k1,1nr)

  # remaining 追蹤「目前為止仍會保留的實際份數」，不是走訪位置。30 天內的
  # 檔案一律保留並計入 remaining；30 天外的檔案，只有在刪除後 remaining
  # 仍 >= 最低保留份數時才刪除，否則保留並計入 remaining。
  local remaining=0
  local entry entry_mtime entry_file
  for entry in "${sorted[@]}"; do
    entry_mtime="${entry%%:*}"
    entry_file="${entry#*:}"

    # 縱深防禦：任何非純數字的時間戳都不可以走到 rm。這裡曾因 stat 的可攜性
    # 問題而收到檔案系統資訊字串，導致刪除判斷完全失效。
    case "$entry_mtime" in
      ''|*[!0-9]*)
        _backup_err "略過無法判讀時間戳的項目：$entry_file"
        remaining=$((remaining + 1))
        continue
        ;;
    esac

    if [ "$entry_mtime" -ge "$cutoff_epoch" ]; then
      # 30 天內，一律保留。
      remaining=$((remaining + 1))
      continue
    fi

    if [ "$remaining" -ge "$BACKUP_RETENTION_MIN_COUNT" ]; then
      _backup_log "刪除超過保留期限的舊備份：${entry_file}"
      rm -f -- "$entry_file"
      continue
    fi

    # 超過 30 天，但目前保留數還沒達到最低保留份數，留下並計入 remaining。
    remaining=$((remaining + 1))
  done

  return 0
}

# ---------------------------------------------------------------------------
# 主流程：backup_database <db-mv-file> <backup-dir>
#   db-mv-file  H2 的 .mv.db 檔案完整路徑（例如 /path/to/data/board.mv.db）
#   backup-dir  備份輸出目錄（例如 $BOARD_HOME_DIR/backups）
#
# 回傳值：
#   0 — 備份成功，或資料庫本來就不存在（無需備份，視為成功不阻擋啟動）
#   非 0 — 備份失敗，呼叫端必須中止啟動
# ---------------------------------------------------------------------------
backup_database() {
  local db_mv_file="$1"
  local backup_dir="$2"

  if [ -z "$db_mv_file" ] || [ ! -e "$db_mv_file" ]; then
    _backup_log "資料庫檔不存在（首次啟動或全新環境），略過備份：${db_mv_file:-<empty>}"
    return 0
  fi

  # 建立（若不存在）並收斂為僅目前使用者可存取；board_secure_dir 對新建目錄
  # fail closed，對既有目錄則安全修正、失敗只警告，語意見 board-env.sh。
  if ! board_secure_dir "$backup_dir"; then
    return 1
  fi

  if ! _backup_cleanup_stale_tmp "$backup_dir"; then
    _backup_err "清理前次中斷留下的暫存備份失敗，中止啟動。"
    return 1
  fi

  # 檔名標示 startup 與時區：board-startup-<UTC 時間戳>-<時區縮寫>.mv.db
  # 時間戳用 UTC（Z）避免主機時區變動或 DST 造成檔名歧義；額外附上主機當地
  # 時區縮寫（例如 CST/JST）方便人工判讀，兩者並存、以 UTC 為排序與比較基準。
  local ts_utc tz_abbr
  ts_utc="$(TZ=UTC date -u +'%Y%m%dT%H%M%SZ')"
  tz_abbr="$(date +'%Z' 2>/dev/null || echo 'local')"

  local final_name="board-startup-${ts_utc}-${tz_abbr}.mv.db"
  local tmp_path="${backup_dir}/${final_name}.tmp"
  local final_path="${backup_dir}/${final_name}"

  if ! cp -p -- "$db_mv_file" "$tmp_path" 2>/dev/null; then
    _backup_err "複製資料庫到暫存備份檔失敗：${db_mv_file} -> ${tmp_path}"
    rm -f -- "$tmp_path" 2>/dev/null || true
    return 1
  fi

  if ! _backup_verify_file "$db_mv_file" "$tmp_path"; then
    _backup_err "備份驗證失敗，中止啟動。"
    rm -f -- "$tmp_path" 2>/dev/null || true
    return 1
  fi

  # 在原子改名成正式備份檔名之前，先收斂為僅目前使用者可讀寫；`cp -p`
  # 通常已從來源繼承嚴格權限，這裡是明確保證，不依賴來源檔案權限剛好正確。
  if ! board_secure_file "$tmp_path" 1; then
    rm -f -- "$tmp_path" 2>/dev/null || true
    return 1
  fi

  if ! mv -f -- "$tmp_path" "$final_path" 2>/dev/null; then
    _backup_err "備份改名（原子提交）失敗：${tmp_path} -> ${final_path}"
    rm -f -- "$tmp_path" 2>/dev/null || true
    return 1
  fi

  _backup_log "已建立啟動前備份：${final_path}"

  _backup_apply_retention "$backup_dir"

  return 0
}

# 允許獨立執行本腳本以利手動測試：
#   ./bin/backup-db.sh <db-mv-file> [backup-dir]
if [ "${BASH_SOURCE[0]:-}" = "${0}" ]; then
  _DB_MV_FILE="${1:-}"
  _BACKUP_DIR="${2:-${BOARD_HOME_DIR:-$HOME/.ai-project-board}/backups}"
  if [ -z "$_DB_MV_FILE" ]; then
    _backup_err "用法：$0 <db-mv-file> [backup-dir]"
    exit 2
  fi
  backup_database "$_DB_MV_FILE" "$_BACKUP_DIR"
  exit $?
fi

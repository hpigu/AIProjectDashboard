#!/usr/bin/env bash
# start-board.sh — 啟動 AI Project Board（Spring Boot / Streamable HTTP MCP）
#
# 用途：從任意工作目錄啟動看板，並在啟動前防住三個實測踩過的坑：
#   1. jar 從 target/ 啟動時，相對路徑 ./data/board 會解析成 target/data/board，
#      連到全新空庫 —— 一律用「絕對路徑指向 repo 根目錄的 data/」。
#   2. 舊行程沒退乾淨，H2 檔案被鎖，只拋 MVStoreException 堆疊 —— 啟動前先
#      偵測鎖檔行程並直接告知 PID，不要讓 Java 丟一坨堆疊給使用者猜。
#   3. 系統預設 Java 版本不是 21（本機 /usr/libexec/java_home 預設是 11）——
#      掃過所有已安裝 JDK 找 21，找不到才給出對應平台的安裝指令。
#
# 本腳本之後可能被放進 Claude Code plugin 的 bin/ 目錄執行（該目錄會被加入
# PATH），因此不可假設目前工作目錄是 repo 根目錄；一律用腳本自身路徑
# （BASH_SOURCE）反推 repo 根目錄與 jar 路徑。plugin/bin/start-board.sh 是指回
# 本檔案的相對 symlink（../../bin/start-board.sh），確保兩邊永遠是同一份腳本、
# REPO_ROOT 永遠解到同一個真正的 repo 根目錄，不會因為複製而多算一層目錄。
#
# 資料目錄與 jar 皆不放進 plugin/ 目錄本身，理由見下方環境變數說明與第 5 節。
#
# 可用環境變數（與 application.yml 對應）：
#   BOARD_PORT     預設 8080
#   BOARD_DB_URL   預設 jdbc:h2:file:<db-dir>/board;DB_CLOSE_ON_EXIT=FALSE
#                  <db-dir> 預設為 <repo>/data（如果該路徑已有既有資料庫，
#                  向下相容用），否則為 ~/.ai-project-board/data（全新環境，
#                  避免資料放在可能被 plugin 更新覆蓋的目錄下）
#   BOARD_HOME_DIR 預設 ~/.ai-project-board，決定上述全新環境的資料目錄位置
#   BOARD_DB_USER / BOARD_DB_PASSWORD
#   BOARD_LOG_FILE 預設 <repo>/logs/board.log
#   BOARD_JAR      指定要啟動的 jar 路徑（預設自動尋找 target/*.jar，
#                  找不到且 repo 根目錄有 pom.xml + mvnw 時會現場組裝一次）
#   BOARD_START_TIMEOUT_SEC  等待啟動完成的逾時秒數（預設 60）

set -u

# ---------------------------------------------------------------------------
# 0. 定位 repo 根目錄（腳本放在 <repo>/bin/ 下）
# ---------------------------------------------------------------------------
SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SCRIPT_SOURCE" ]; do
  SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" >/dev/null 2>&1 && pwd)"
  SCRIPT_SOURCE="$(readlink "$SCRIPT_SOURCE")"
  [[ $SCRIPT_SOURCE != /* ]] && SCRIPT_SOURCE="$SCRIPT_DIR/$SCRIPT_SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -P "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

log()  { printf '[start-board] %s\n' "$*"; }
err()  { printf '[start-board][錯誤] %s\n' "$*" >&2; }

log "repo 根目錄：$REPO_ROOT"

# ---------------------------------------------------------------------------
# 1. JDK 偵測：找 21，找不到給出對應平台安裝指令
# ---------------------------------------------------------------------------
JAVA_BIN=""

version_is_21() {
  # 傳入 java 執行檔路徑，回傳 0 代表版本為 21.x
  local candidate="$1"
  [ -x "$candidate" ] || return 1
  local ver
  ver="$("$candidate" -version 2>&1 | head -n1)"
  [[ "$ver" == *'"21'* ]]
}

find_java21_macos() {
  local home
  # 1) java_home -v 21 最準
  if home="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    if version_is_21 "$home/bin/java"; then
      echo "$home/bin/java"
      return 0
    fi
  fi
  # 2) java_home -V 掃全部已註冊的 JDK（涵蓋 java_home 預設回傳非 21 的情況）
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local line jhome
    while IFS= read -r line; do
      jhome="$(printf '%s' "$line" | sed -n 's/.*" \(\/[^"]*\)$/\1/p')"
      [ -n "$jhome" ] || continue
      if version_is_21 "$jhome/bin/java"; then
        echo "$jhome/bin/java"
        return 0
      fi
    done < <(/usr/libexec/java_home -V 2>&1 | grep -E '^\s+[0-9]')
  fi
  # 3) Homebrew 常見安裝路徑（java_home 未必有註冊到，例如僅 brew install 未 link）
  local brew_candidates=(
    "/opt/homebrew/opt/openjdk@21/bin/java"
    "/opt/homebrew/opt/openjdk/bin/java"
    "/usr/local/opt/openjdk@21/bin/java"
    "/usr/local/opt/openjdk/bin/java"
  )
  local c
  for c in "${brew_candidates[@]}"; do
    if version_is_21 "$c"; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

find_java21_linux() {
  local c
  # update-alternatives 列出的候選
  if command -v update-alternatives >/dev/null 2>&1; then
    while IFS= read -r c; do
      [ -n "$c" ] || continue
      if version_is_21 "$c"; then
        echo "$c"
        return 0
      fi
    done < <(update-alternatives --list java 2>/dev/null)
  fi
  # 常見安裝路徑
  local dir
  for dir in /usr/lib/jvm/*21* /usr/lib/jvm/java-21* /opt/java/*21*; do
    [ -d "$dir" ] || continue
    if version_is_21 "$dir/bin/java"; then
      echo "$dir/bin/java"
      return 0
    fi
  done
  return 1
}

suggest_install_cmd() {
  case "$(uname -s)" in
    Darwin)
      cat >&2 <<'EOF'
找不到 JDK 21。安裝方式（擇一）：
  brew install openjdk@21
  # 安裝後如果 /usr/libexec/java_home -V 仍未列出，可執行：
  sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
    /Library/Java/JavaVirtualMachines/openjdk-21.jdk
EOF
      ;;
    Linux)
      cat >&2 <<'EOF'
找不到 JDK 21。安裝方式（依發行版擇一）：
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk   # Debian/Ubuntu
  sudo dnf install -y java-21-openjdk-devel                       # Fedora/RHEL
  sudo pacman -S jdk21-openjdk                                    # Arch
EOF
      ;;
    *)
      echo "找不到 JDK 21，請至 https://adoptium.net/ 下載對應平台的 21 版本。" >&2
      ;;
  esac
}

# 先看目前 PATH 上的 java 是不是剛好就是 21（多數情況最快）
if command -v java >/dev/null 2>&1 && version_is_21 "$(command -v java)"; then
  JAVA_BIN="$(command -v java)"
else
  case "$(uname -s)" in
    Darwin) JAVA_BIN="$(find_java21_macos || true)" ;;
    Linux)  JAVA_BIN="$(find_java21_linux || true)" ;;
    *) ;;
  esac
fi

if [ -z "$JAVA_BIN" ]; then
  err "系統上找不到 JDK 21（專案 pom.xml 要求 java.version=21）。"
  suggest_install_cmd
  exit 1
fi

log "使用 JDK 21：$JAVA_BIN（$("$JAVA_BIN" -version 2>&1 | head -n1)）"

# ---------------------------------------------------------------------------
# 2. 環境變數與絕對路徑資料庫
#
#    資料目錄預設值有兩種情境：
#    a) 既有使用者：repo 內 ${REPO_ROOT}/data/board.mv.db 已存在，代表這個
#       repo 位置本來就是資料所在地（例如正式看板）——沿用該路徑，向下相容，
#       不因為這次改動就讓既有看板「看起來資料不見了」。
#    b) 全新環境（例如透過 Claude Code plugin 全新安裝、尚未在這個 repo
#       路徑啟動過）：預設改用家目錄 ~/.ai-project-board/data/board。
#       原因：plugin 目錄可能因為更新（重新 clone / 覆蓋）而遺失內容，
#       H2 資料庫檔案不能放在會被覆蓋的路徑下。
# ---------------------------------------------------------------------------
BOARD_PORT="${BOARD_PORT:-8080}"

BOARD_HOME_DIR="${BOARD_HOME_DIR:-$HOME/.ai-project-board}"
if [ -f "${REPO_ROOT}/data/board.mv.db" ]; then
  DEFAULT_DB_DIR="${REPO_ROOT}/data"
else
  DEFAULT_DB_DIR="${BOARD_HOME_DIR}/data"
fi

BOARD_DB_URL="${BOARD_DB_URL:-jdbc:h2:file:${DEFAULT_DB_DIR}/board;DB_CLOSE_ON_EXIT=FALSE}"
BOARD_LOG_FILE="${BOARD_LOG_FILE:-${REPO_ROOT}/logs/board.log}"
export BOARD_PORT BOARD_DB_URL BOARD_LOG_FILE
[ -n "${BOARD_DB_USER:-}" ] && export BOARD_DB_USER
[ -n "${BOARD_DB_PASSWORD:-}" ] && export BOARD_DB_PASSWORD

mkdir -p "$DEFAULT_DB_DIR" 2>/dev/null || true

log "BOARD_PORT=${BOARD_PORT}"
log "BOARD_DB_URL=${BOARD_DB_URL}"
log "BOARD_LOG_FILE=${BOARD_LOG_FILE}"

# ---------------------------------------------------------------------------
# 3. 埠號檢查：被佔用時判斷是不是看板自己
# ---------------------------------------------------------------------------
PORT_PID=""
if command -v lsof >/dev/null 2>&1; then
  PORT_PID="$(lsof -nP -iTCP:"${BOARD_PORT}" -sTCP:LISTEN -t 2>/dev/null | head -n1)"
fi

if [ -n "$PORT_PID" ]; then
  log "埠號 ${BOARD_PORT} 已被 PID ${PORT_PID} 佔用，檢查是否為看板本身……"
  if command -v curl >/dev/null 2>&1 && curl -s -o /dev/null -w '%{http_code}' \
      --max-time 3 "http://127.0.0.1:${BOARD_PORT}/api/projects" 2>/dev/null | grep -q '^2'; then
    log "偵測到看板已在 :${BOARD_PORT} 正常運作（PID ${PORT_PID}），不重複啟動。"
    exit 0
  else
    err "埠號 ${BOARD_PORT} 被其他行程佔用（PID ${PORT_PID}，非本看板服務）。"
    err "請先確認該行程用途，或改用 BOARD_PORT 指定其他埠號再重試。"
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# 4. H2 檔案鎖偵測：不要讓 MVStoreException 堆疊糊弄使用者
# ---------------------------------------------------------------------------
DB_FILE_PATH="$(printf '%s' "$BOARD_DB_URL" | sed -n 's#^jdbc:h2:file:##p' | cut -d';' -f1)"
if [ -n "$DB_FILE_PATH" ]; then
  DB_MV_FILE="${DB_FILE_PATH}.mv.db"
  if [ -e "$DB_MV_FILE" ] && command -v lsof >/dev/null 2>&1; then
    LOCK_PIDS="$(lsof -nP -t -- "$DB_MV_FILE" 2>/dev/null | sort -u | tr '\n' ' ')"
    if [ -n "${LOCK_PIDS// /}" ]; then
      err "資料庫檔 ${DB_MV_FILE} 目前被下列 PID 持有中，啟動會失敗（MVStoreException）："
      err "  PID: ${LOCK_PIDS}"
      err "請先確認並結束該行程（例如：kill ${LOCK_PIDS}），再重新執行本腳本。"
      exit 1
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 5. 找 jar 並啟動
#
#    jar 不進版控（59MB 二進位檔不適合放進 git），因此 target/*.jar 在全新
#    clone（例如透過 Claude Code plugin 安裝）下不存在。找不到時，若 repo
#    根目錄有 pom.xml 與 mvnw（代表這是完整 repo，不是零散的 plugin 目錄），
#    自動執行一次 `./mvnw package -DskipTests` 現場組裝，不需要另外維護
#    release 組裝流程或把二進位檔提交進 git。
# ---------------------------------------------------------------------------
find_jar() {
  find "${REPO_ROOT}/target" -maxdepth 1 -name '*.jar' ! -name '*.original' 2>/dev/null | sort | tail -n1
}

if [ -n "${BOARD_JAR:-}" ]; then
  JAR_PATH="$BOARD_JAR"
else
  JAR_PATH="$(find_jar)"
fi

if [ -z "${JAR_PATH:-}" ] || [ ! -f "$JAR_PATH" ]; then
  if [ -f "${REPO_ROOT}/pom.xml" ] && [ -x "${REPO_ROOT}/mvnw" ]; then
    log "找不到可執行 jar，偵測到完整 repo（pom.xml + mvnw），現場組裝一次……"
    log "（首次啟動需下載 Maven 依賴並編譯，可能需要數分鐘）"
    if ! (cd "${REPO_ROOT}" && ./mvnw package -DskipTests); then
      err "自動組裝失敗，請至 ${REPO_ROOT} 手動執行：./mvnw package -DskipTests"
      exit 1
    fi
    JAR_PATH="$(find_jar)"
  fi
fi

if [ -z "${JAR_PATH:-}" ] || [ ! -f "$JAR_PATH" ]; then
  err "找不到可執行 jar（${REPO_ROOT}/target/*.jar），自動組裝亦不可用。"
  err "請先在 repo 根目錄執行：./mvnw package -DskipTests"
  exit 1
fi

log "啟動 jar：${JAR_PATH}"
mkdir -p "$(dirname "$BOARD_LOG_FILE")"

"$JAVA_BIN" -jar "$JAR_PATH" &
APP_PID=$!
log "已啟動子行程 PID=${APP_PID}，等待服務就緒……"

# ---------------------------------------------------------------------------
# 6. 啟動確認：輪詢 /api/projects 直到回應或逾時
# ---------------------------------------------------------------------------
TIMEOUT_SEC="${BOARD_START_TIMEOUT_SEC:-60}"
ELAPSED=0
READY=0
while [ "$ELAPSED" -lt "$TIMEOUT_SEC" ]; do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    err "行程 PID=${APP_PID} 已提前結束，啟動失敗。請查看 ${BOARD_LOG_FILE}。"
    exit 1
  fi
  if command -v curl >/dev/null 2>&1 && curl -s -o /dev/null -w '%{http_code}' \
      --max-time 2 "http://127.0.0.1:${BOARD_PORT}/api/projects" 2>/dev/null | grep -q '^2'; then
    READY=1
    break
  fi
  sleep 1
  ELAPSED=$((ELAPSED + 1))
done

if [ "$READY" -ne 1 ]; then
  err "等待 ${TIMEOUT_SEC} 秒後仍未就緒（PID=${APP_PID}）。請查看 ${BOARD_LOG_FILE}。"
  exit 1
fi

log "看板已就緒：http://127.0.0.1:${BOARD_PORT}（PID=${APP_PID}）"

# ---------------------------------------------------------------------------
# 7. 版本自我檢查：印出 /api/health，讓「plugin 裡的 jar 跟不上 repo 程式碼」
#    這類問題一眼可辨，不必事後才發現行程其實是舊 build（實測踩過：行程
#    啟動時間早於某次 commit，導致新端點回 404 卻讓人誤以為端點沒做）。
#    舊版行程沒有 /api/health（404）是正常情況，僅提示、不視為啟動失敗。
# ---------------------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  HEALTH_JSON="$(curl -s --max-time 3 "http://127.0.0.1:${BOARD_PORT}/api/health" 2>/dev/null)"
  if [ -n "$HEALTH_JSON" ] && printf '%s' "$HEALTH_JSON" | grep -q '"version"'; then
    log "版本資訊：${HEALTH_JSON}"
  else
    log "此行程沒有 /api/health（可能是較舊的 build），略過版本檢查。"
  fi
fi

exit 0

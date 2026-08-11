#!/usr/bin/env bash
# Exercise the portable macOS/Linux release path with the JAR built by this job.
# This is intentionally a real lifecycle/update gate; fake-JDK transaction fixtures
# remain useful unit checks but are not a substitute for starting the release artifact.

set -euo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALLER="${ROOT}/install/install.sh"
RELEASE_ASSETS="${ROOT}/scripts/release/release-assets.sh"

usage() {
  cat >&2 <<'EOF'
Usage: posix-release-smoke.sh --version V --platform PLATFORM --jar PATH

PLATFORM is linux-x64, macos-arm64, or macos-x64. BOARD_PORT must name an
isolated non-production port; every other runtime path is created below TMPDIR.
EOF
  exit 64
}

die() { printf '[posix-release-smoke][FAIL] %s\n' "$*" >&2; exit 1; }
pass() { printf '[posix-release-smoke][PASS] %s\n' "$*"; }

version=""
platform=""
source_jar=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --version) [ "$#" -ge 2 ] || usage; version="$2"; shift 2 ;;
    --platform) [ "$#" -ge 2 ] || usage; platform="$2"; shift 2 ;;
    --jar) [ "$#" -ge 2 ] || usage; source_jar="$2"; shift 2 ;;
    *) usage ;;
  esac
done

printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die 'version must be X.Y.Z'
[ -f "$source_jar" ] || die "release JAR does not exist: $source_jar"
case "$platform" in
  linux-x64)
    [ "$(uname -s):$(uname -m)" = Linux:x86_64 ] || die 'linux-x64 smoke needs a native Linux x86_64 runner'
    ;;
  macos-arm64)
    case "$(uname -s):$(uname -m)" in Darwin:arm64|Darwin:aarch64) ;; *) die 'macos-arm64 smoke needs a native macOS arm64 runner' ;; esac
    ;;
  macos-x64)
    case "$(uname -s):$(uname -m)" in Darwin:x86_64|Darwin:amd64) ;; *) die 'macos-x64 smoke needs a native macOS x86_64 runner' ;; esac
    ;;
  *) die "unsupported POSIX platform: $platform" ;;
esac

port="${BOARD_PORT:-}"
case "$port" in ''|*[!0-9]*) die 'BOARD_PORT must be an isolated numeric port' ;; esac
[ "$port" -ne 8080 ] || die 'refusing to use the production port 8080'
[ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || die 'BOARD_PORT must be between 1024 and 65535'

java_home="${JAVA_HOME:-}"
java_bin=""
[ -z "$java_home" ] || java_bin="${java_home}/bin/java"
[ -n "$java_bin" ] && [ -x "$java_bin" ] || java_bin="$(command -v java 2>/dev/null || true)"
[ -x "$java_bin" ] || die 'a native JDK 21 java executable is required'
"$java_bin" -version 2>&1 | grep -Eq 'version "21([.\"]|$)' || die 'the smoke requires JDK 21'

work="$(mktemp -d "${TMPDIR:-/tmp}/ai-project-board-posix-release-smoke.XXXXXX")"
isolated_home="$work/隔離 HOME with spaces"
lifecycle_root="$isolated_home/生命週期 install root"
update_root="$isolated_home/更新 install root"
mkdir -p "$isolated_home"

terminate_pid_file() {
  local pid_file="$1" pid tries=0
  [ -f "$pid_file" ] || return 0
  pid="$(sed -n '1p' "$pid_file" | tr -d '[:space:]')"
  case "$pid" in ''|*[!0-9]*) return 0 ;; esac
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null && [ "$tries" -lt 30 ]; do
      sleep 1
      tries=$((tries + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
      printf '[posix-release-smoke][WARN] exact PID %s did not stop after TERM; sending KILL\n' "$pid" >&2
      kill -KILL "$pid" 2>/dev/null || true
      tries=0
      while kill -0 "$pid" 2>/dev/null && [ "$tries" -lt 10 ]; do
        sleep 1
        tries=$((tries + 1))
      done
    fi
  fi
  if kill -0 "$pid" 2>/dev/null; then
    printf '[posix-release-smoke][FAIL] exact PID %s survived TERM and KILL; retaining diagnostics at %s\n' "$pid" "$work" >&2
    return 1
  fi
  return 0
}

cleanup() {
  local status=$? cleanup_failed=0
  trap - EXIT HUP INT TERM
  # Both PID files belong to roots created by this invocation. Terminate only
  # those exact recorded PIDs; never match a Java command line by name.
  terminate_pid_file "$lifecycle_root/board.pid" || cleanup_failed=1
  terminate_pid_file "$update_root/board.pid" || cleanup_failed=1
  if [ "$cleanup_failed" -eq 0 ]; then
    rm -rf -- "$work"
  else
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

make_asset_set() {
  local asset_version="$1" real_platform="$2" real_jar="$3" directory="$4" name
  mkdir -p "$directory"
  for name in \
    "ai-project-board-backend-linux-x64-${asset_version}.jar" \
    "ai-project-board-backend-macos-arm64-${asset_version}.jar" \
    "ai-project-board-backend-macos-x64-${asset_version}.jar" \
    "ai-project-board-backend-windows-x64-${asset_version}.zip"; do
    case "$name" in
      "ai-project-board-backend-${real_platform}-${asset_version}.jar") cp "$real_jar" "$directory/$name" ;;
      *) printf 'non-host checksum fixture for %s\n' "$name" > "$directory/$name" ;;
    esac
  done
  "$RELEASE_ASSETS" assemble --version "$asset_version" --directory "$directory"
  "$RELEASE_ASSETS" validate --version "$asset_version" --directory "$directory"
}

run_board() {
  local root="$1"
  shift
  env \
    HOME="$isolated_home" \
    BOARD_HOME_DIR="$root" \
    BOARD_PORT="$port" \
    BOARD_DB_URL="jdbc:h2:file:$root/data/smoke-board;DB_CLOSE_ON_EXIT=FALSE" \
    BOARD_LOG_FILE="$root/logs/smoke-board.log" \
    BOARD_CONSOLE_LOG="$root/logs/smoke-board.console.log" \
    BOARD_PID_FILE="$root/board.pid" \
    BOARD_BACKUP_DIR="$root/backups" \
    BOARD_START_TIMEOUT_SEC=90 \
    BOARD_STOP_TIMEOUT_SEC=90 \
    "$root/bin/board" "$@"
}

assert_running() {
  local root="$1" pid
  run_board "$root" status >/dev/null
  pid="$(sed -n '1p' "$root/board.pid" | tr -d '[:space:]')"
  case "$pid" in ''|*[!0-9]*) die "runtime did not record an exact PID under $root" ;; esac
  kill -0 "$pid" 2>/dev/null || die "recorded PID $pid is not running"
  curl -fsS --max-time 5 "http://127.0.0.1:${port}/api/health" | grep -Fq "\"version\":\"${version}\"" \
    || die 'running release does not report the packaged version'
}

assert_stopped() {
  local root="$1" status
  if run_board "$root" status >/dev/null 2>&1; then
    die "runtime is still running under $root"
  else
    status=$?
    [ "$status" -eq 3 ] || die "could not establish stopped state under $root (status $status)"
  fi
  [ ! -f "$root/board.pid" ] || die "stopped runtime retained PID file under $root"
}

verify_snapshots() {
  local root="$1" minimum="$2" manifests manifest snapshot manifest_hash actual_hash count=0
  manifests="$(find "$root/backups" -type f -name manifest.sha256 -print | LC_ALL=C sort)"
  [ -n "$manifests" ] || die 'update rollback did not retain a database snapshot manifest'
  while IFS= read -r manifest; do
    [ -n "$manifest" ] || continue
    snapshot="$(dirname "$manifest")"
    [ -f "$snapshot/board.mv.db" ] || die "retained snapshot omits board.mv.db: $snapshot"
    manifest_hash="$(awk '$2 == "board.mv.db" { print $1 }' "$manifest")"
    [ -n "$manifest_hash" ] || die "retained snapshot manifest omits board.mv.db: $manifest"
    if command -v sha256sum >/dev/null 2>&1; then
      actual_hash="$(sha256sum "$snapshot/board.mv.db" | awk '{print $1}')"
    else
      actual_hash="$(shasum -a 256 "$snapshot/board.mv.db" | awk '{print $1}')"
    fi
    [ "$actual_hash" = "$manifest_hash" ] || die "retained rollback snapshot is not verifiable: $snapshot"
    count=$((count + 1))
  done <<EOF
$manifests
EOF
  [ "$count" -ge "$minimum" ] || die "expected at least $minimum verifiable update snapshots, found $count"
}

current_assets="$work/current-assets"
make_asset_set "$version" "$platform" "$source_jar" "$current_assets"
current_jar="$current_assets/ai-project-board-backend-${platform}-${version}.jar"
current_checksums="$current_assets/ai-project-board-backend-${version}-SHA256SUMS.txt"

expected_name="ai-project-board-backend-${platform}-${version}.jar"
[ "$(basename "$current_jar")" = "$expected_name" ] || die 'platform artifact basename drifted'
[ "$(wc -l < "$current_checksums" | tr -d '[:space:]')" = 4 ] || die 'checksum contract is not exactly four lines'
pass 'real platform JAR is covered by a strict four-line checksum set'

bad_checksums="$work/ai-project-board-backend-${version}-SHA256SUMS.txt"
cp "$current_checksums" "$bad_checksums"
first_hash_char="$(awk -v name="$expected_name" '$2 == name { print substr($1, 1, 1) }' "$bad_checksums")"
replacement=0
[ "$first_hash_char" = 0 ] && replacement=1
awk -v name="$expected_name" -v replacement="$replacement" '{ if ($2 == name) $1 = replacement substr($1, 2); print $1 "  " $2 }' \
  "$bad_checksums" > "$bad_checksums.new"
mv "$bad_checksums.new" "$bad_checksums"
bad_root="$isolated_home/拒絕 checksum root"
if env HOME="$isolated_home" "$INSTALLER" --jar "$current_jar" --checksums "$bad_checksums" \
  --java "$java_bin" --home "$bad_root" >/dev/null 2>&1; then
  die 'installer accepted a mismatched checksum'
fi
[ ! -e "$bad_root" ] || die 'checksum rejection left a partial installation'
pass 'checksum mismatch is rejected before installation'

(cd / && env HOME="$isolated_home" "$INSTALLER" --jar "$current_jar" --checksums "$current_checksums" \
  --java "$java_bin" --home "$lifecycle_root")
# 啟動失敗時 bin/board 只印出「去看 log」就 exit，而這些 log 留在拋棄式的隔離
# HOME 裡，CI 上沒有人能事後打開它們。失敗當下就把它們倒出來，否則遠端只會看到
# 一行「行程已提前結束」，完全無法診斷。
dump_board_logs() {
  for log_file in "$lifecycle_root/logs/smoke-board.console.log" "$lifecycle_root/logs/smoke-board.log"; do
    [ -f "$log_file" ] || continue
    printf '[posix-release-smoke][診斷] ===== %s =====\n' "$log_file" >&2
    tail -n 60 "$log_file" >&2 || true
  done
}
run_board "$lifecycle_root" start || { dump_board_logs; die 'real lifecycle start failed'; }
assert_running "$lifecycle_root"
run_board "$lifecycle_root" stop
assert_stopped "$lifecycle_root"
[ -f "$lifecycle_root/data/smoke-board.mv.db" ] || die 'real lifecycle did not create the isolated database'
pass 'clean install with spaces/non-ASCII completes real start/status/stop'

# A distinct synthetic activation name lets the same real current JAR exercise
# the updater transaction without manufacturing a fake target. This proves the
# rollback mechanics and isolated data usability only; it is not evidence of a
# real older binary or cross-version schema migration (that belongs to QA #150).
old_version=0.0.1
[ "$old_version" != "$version" ] || old_version=0.0.2
old_assets="$work/old-assets"
make_asset_set "$old_version" "$platform" "$source_jar" "$old_assets"
old_jar="$old_assets/ai-project-board-backend-${platform}-${old_version}.jar"
old_checksums="$old_assets/ai-project-board-backend-${old_version}-SHA256SUMS.txt"
(cd / && env HOME="$isolated_home" "$INSTALLER" --jar "$old_jar" --checksums "$old_checksums" \
  --java "$java_bin" --home "$update_root")
run_board "$update_root" start
assert_running "$update_root"
before_projects="$(curl -fsS --max-time 5 "http://127.0.0.1:${port}/api/projects")"

if env \
  HOME="$isolated_home" \
  BOARD_INSTALLED_HOME="$update_root" \
  BOARD_HOME_DIR="$update_root" \
  BOARD_PORT="$port" \
  BOARD_DB_URL="jdbc:h2:file:$update_root/data/smoke-board;DB_CLOSE_ON_EXIT=FALSE" \
  BOARD_LOG_FILE="$update_root/logs/smoke-board.log" \
  BOARD_CONSOLE_LOG="$update_root/logs/smoke-board.console.log" \
  BOARD_PID_FILE="$update_root/board.pid" \
  BOARD_BACKUP_DIR="$update_root/backups" \
  BOARD_START_TIMEOUT_SEC=90 \
  BOARD_STOP_TIMEOUT_SEC=90 \
  BOARD_UPDATE_FAIL_AT=publish \
  "$update_root/bin/board-update.sh" --version "$version" --jar "$current_jar" --checksums "$current_checksums"; then
  die 'injected update interruption unexpectedly succeeded'
fi
[ "$(readlink "$update_root/current")" = "releases/${old_version}" ] || die 'interrupted update changed the activation pointer'
assert_running "$update_root"
[ "$(curl -fsS --max-time 5 "http://127.0.0.1:${port}/api/projects")" = "$before_projects" ] \
  || die 'interrupted update did not preserve readable old data'
verify_snapshots "$update_root" 1
pass 'interrupted transaction restores the synthetic old activation and a verifiable snapshot'

if env \
  HOME="$isolated_home" \
  BOARD_INSTALLED_HOME="$update_root" \
  BOARD_HOME_DIR="$update_root" \
  BOARD_PORT="$port" \
  BOARD_DB_URL="jdbc:h2:file:$update_root/data/smoke-board;DB_CLOSE_ON_EXIT=FALSE" \
  BOARD_LOG_FILE="$update_root/logs/smoke-board.log" \
  BOARD_CONSOLE_LOG="$update_root/logs/smoke-board.console.log" \
  BOARD_PID_FILE="$update_root/board.pid" \
  BOARD_BACKUP_DIR="$update_root/backups" \
  BOARD_START_TIMEOUT_SEC=90 \
  BOARD_STOP_TIMEOUT_SEC=90 \
  BOARD_UPDATE_FAIL_AT=readiness \
  "$update_root/bin/board-update.sh" --version "$version" --jar "$current_jar" --checksums "$current_checksums"; then
  die 'injected readiness failure unexpectedly succeeded'
fi
[ "$(readlink "$update_root/current")" = "releases/${old_version}" ] || die 'readiness rollback did not restore the old activation pointer'
assert_running "$update_root"
[ "$(curl -fsS --max-time 5 "http://127.0.0.1:${port}/api/projects")" = "$before_projects" ] \
  || die 'readiness rollback did not preserve readable old data'
verify_snapshots "$update_root" 2
pass 'readiness failure restores readable isolated data and retains a verifiable snapshot'

run_board "$update_root" stop
assert_stopped "$update_root"
pass 'portable install/update release smoke completed'

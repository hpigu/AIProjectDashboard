#!/usr/bin/env bash
# board-update.sh — explicit, transactional stable-release updater for installed POSIX servers.
#
# This program intentionally has no "latest" mode and never runs by itself.  It is called only
# by `board update --version V ...`; all downloading and contract validation completes before the
# service is stopped.  The activation pointer is a same-filesystem rename of `current`.

set -eu

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SCRIPT_SOURCE" ]; do
  script_dir="$(cd -P "$(dirname "$SCRIPT_SOURCE")" >/dev/null 2>&1 && pwd)"
  SCRIPT_SOURCE="$(readlink "$SCRIPT_SOURCE")"
  [[ $SCRIPT_SOURCE != /* ]] && SCRIPT_SOURCE="$script_dir/$SCRIPT_SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -P "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

log() { printf '[board update] %s\n' "$*"; }
err() { printf '[board update][錯誤] %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

usage() {
  cat <<'EOF'
用法：board update --version V (--jar PATH --checksums PATH | --release-url URL) [--check]

只更新使用者明確指定的 immutable stable vV；不支援 latest、branch、背景輪詢、
排程或啟動時自動更新。--check 會驗證目標 artifact 並顯示 current/target，但不停止
服務、不備份也不改動安裝。

離線等價方式：先自行取得同版 platform JAR 與 ai-project-board-backend-V-SHA256SUMS.txt，
再以 --jar/--checksums 傳入。GitHub 無法連線時，--release-url 會在停止服務前失敗；
現有 runtime 與資料完全不會改動。
EOF
}

version_ok() { printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; }
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else return 1; fi
}
platform() {
  case "$(uname -s):$(uname -m)" in
    Darwin:arm64|Darwin:aarch64) printf '%s' macos-arm64 ;;
    Darwin:x86_64|Darwin:amd64) printf '%s' macos-x64 ;;
    Linux:x86_64|Linux:amd64) printf '%s' linux-x64 ;;
    *) return 1 ;;
  esac
}
# Test-only fault injection accepts one step or a comma-separated set (for example
# readiness,rollback_activate) so rollback failure can be exercised independently.
fail_at() { case ",${BOARD_UPDATE_FAIL_AT:-}," in *,"$1",*) return 0 ;; *) return 1 ;; esac; }
# BSD mv follows a destination symlink-to-directory unless -h is supplied; GNU mv instead
# provides -T.  Either variant is an atomic same-filesystem rename of the symlink itself.
replace_activation_link() {
  source_link="$1"; destination_link="$2"
  mv -h "$source_link" "$destination_link" 2>/dev/null && return 0
  mv -T "$source_link" "$destination_link"
}

requested_version=""; jar=""; checksums=""; release_url=""; check_only=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --version) shift; [ "$#" -gt 0 ] || die '--version needs V'; requested_version="$1" ;;
    --jar) shift; [ "$#" -gt 0 ] || die '--jar needs PATH'; jar="$1" ;;
    --checksums) shift; [ "$#" -gt 0 ] || die '--checksums needs PATH'; checksums="$1" ;;
    --release-url) shift; [ "$#" -gt 0 ] || die '--release-url needs URL'; release_url="$1" ;;
    --check) check_only=1 ;;
    -h|--help|help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

version_ok "$requested_version" || die '--version must be an explicit stable X.Y.Z value'
# Locate and report the installed immutable runtime before asking for any target bytes.  This makes
# an explicit same-version invocation a true no-op (including offline/GitHub-unavailable cases).
root="${BOARD_INSTALLED_HOME:-$REPO_ROOT}"
[ -d "$root/releases" ] && [ -L "$root/current" ] && [ -x "$root/bin/board" ] || die 'update is only available from a complete user-scope installation'
old_link="$(readlink "$root/current")" || die 'cannot read current activation pointer'
case "$old_link" in releases/*) ;; *) die 'current activation pointer has an unsafe layout' ;; esac
current_version="${old_link#releases/}"
version_ok "$current_version" || die 'current activation does not identify a stable release version'
log "current=v${current_version} target=v${requested_version}"
if [ "$current_version" = "$requested_version" ]; then
  log 'target is already active; no files, downloads, or service state changed'
  exit 0
fi
[ "$release_url" != *latest* ] || die 'mutable latest URLs are forbidden; use the exact vV release asset URL'
if [ -n "$release_url" ]; then
  [ -z "$jar" ] && [ -z "$checksums" ] || die 'do not combine --release-url with local artifacts'
  case "$release_url" in */v"$requested_version"|*/v"$requested_version"/) ;; *) die '--release-url must name the exact immutable vV release' ;; esac
  command -v curl >/dev/null 2>&1 || die '--release-url requires curl (use --jar/--checksums for offline update)'
  download_dir="$(mktemp -d "${TMPDIR:-/tmp}/ai-project-board-update-download.XXXXXX")" || die 'cannot create download staging'
  trap 'rm -rf -- "$download_dir"' EXIT HUP INT TERM
  release_platform="$(platform)" || die "unsupported platform: $(uname -s) $(uname -m)"
  jar="$download_dir/ai-project-board-backend-${release_platform}-${requested_version}.jar"
  checksums="$download_dir/ai-project-board-backend-${requested_version}-SHA256SUMS.txt"
  base_url="${release_url%/}"
  log "downloading explicitly requested stable v${requested_version} assets"
  curl --fail --location --show-error --silent "$base_url/$(basename "$checksums")" -o "$checksums" || die 'cannot download checksum list; existing installation was not changed'
  curl --fail --location --show-error --silent "$base_url/$(basename "$jar")" -o "$jar" || die 'cannot download platform JAR; existing installation was not changed'
else
  [ -n "$jar" ] && [ -n "$checksums" ] || die 'provide --jar/--checksums for offline input, or --release-url'
fi

release_platform="$(platform)" || die "unsupported platform: $(uname -s) $(uname -m)"
jar_base="$(basename "$jar")"
expected_jar="ai-project-board-backend-${release_platform}-${requested_version}.jar"
[ "$jar_base" = "$expected_jar" ] || die "wrong platform or version artifact: expected $expected_jar"
[ "$(basename "$checksums")" = "ai-project-board-backend-${requested_version}-SHA256SUMS.txt" ] || die 'checksum filename/version does not match target'
[ -f "$jar" ] && [ -f "$checksums" ] || die 'target artifact or checksum list is unavailable'

# Byte-level four-line release contract.  Keep this parser duplicated only where the two
# distributions cannot share a runtime; its expected basenames deliberately match the contract.
[ "$(tail -c 1 "$checksums" 2>/dev/null | od -An -t x1 | tr -d '[:space:]')" = 0a ] || die 'checksum list must end in LF'
if LC_ALL=C grep -q $'\r' "$checksums" || LC_ALL=C grep -q '[^ -~]' "$checksums"; then die 'checksum list must be ASCII UTF-8 with LF only'; fi
expected_rows="ai-project-board-backend-linux-x64-${requested_version}.jar
ai-project-board-backend-macos-arm64-${requested_version}.jar
ai-project-board-backend-macos-x64-${requested_version}.jar
ai-project-board-backend-windows-x64-${requested_version}.zip"
actual_rows="$(awk -F '  ' 'NF != 2 || $1 !~ /^[0-9a-f]{64}$/ || index($2, "/") || index($2, "\\") || $2 ~ /[[:space:]]/ || $2 == "" { exit 1 } { print $2 }' "$checksums")" || die 'checksum list has invalid rows'
[ "$(printf '%s\n' "$actual_rows" | sed '/^$/d' | wc -l | tr -d '[:space:]')" = 4 ] || die 'checksum list must contain exactly four rows'
[ "$actual_rows" = "$expected_rows" ] || die 'checksum list entries/order/version violate release contract'
expected_hash="$(awk -v name="$jar_base" -F '  ' '$2 == name { print $1 }' "$checksums")"
[ -n "$expected_hash" ] && [ "$(sha256_file "$jar")" = "$expected_hash" ] || die 'SHA-256 verification failed'
command -v jar >/dev/null 2>&1 || die 'JDK jar tool is required to inspect the downloaded JAR'
jar tf "$jar" >/dev/null 2>&1 || die 'target artifact is not a readable executable JAR'
inspect_dir="$(mktemp -d "${TMPDIR:-/tmp}/ai-project-board-update-inspect.XXXXXX")" || die 'cannot create JAR inspection stage'
if ! (cd "$inspect_dir" && jar xf "$jar" META-INF/MANIFEST.MF META-INF/build-info.properties) \
  || ! grep -Eq '^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' "$inspect_dir/META-INF/MANIFEST.MF" \
  || ! grep -Eq '^build\.version='"$requested_version"'\r?$' "$inspect_dir/META-INF/build-info.properties"; then
  rm -rf -- "$inspect_dir"
  die 'target JAR is not a Spring Boot executable release'
fi
rm -rf -- "$inspect_dir"
[ ! -e "$root/releases/$requested_version" ] || die "target v${requested_version} is already present; refusing to guess whether it is trustworthy"

if [ "$check_only" -eq 1 ]; then log 'target passed artifact/JAR/checksum validation; --check makes no change'; exit 0; fi

# Stage the fully verified payload under the installation parent before touching the service.
stage="$root/.update-stage-${requested_version}-$$"
[ ! -e "$stage" ] || die 'update staging path already exists'
umask 077; mkdir -p "$stage/app" || die 'cannot create update stage'
cp "$jar" "$stage/app/$jar_base" || die 'cannot stage verified JAR'
[ "$(sha256_file "$stage/app/$jar_base")" = "$expected_hash" ] || die 'staged JAR checksum changed'
ln -s "$jar_base" "$stage/app/server.jar" || die 'cannot create staged server alias'

was_running=0
if "$root/bin/board" status >/dev/null 2>&1; then was_running=1
else status_code=$?; [ "$status_code" = 3 ] || { rm -rf -- "$stage"; die 'cannot establish current readiness; refusing update'; }; fi
pid_before="$(sed -n '1p' "$root/board.pid" 2>/dev/null || true)"
snapshot="$root/backups/update-${current_version}-to-${requested_version}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
rollback_needed=0; published=0; activated=0; snapshot_ready=0

restore_old() {
  reason="$1"; err "update failed at ${reason}; beginning rollback"
  rollback_failed=0
  # No new service may be using an H2 schema while its snapshot is restored.
  "$root/bin/board" stop >/dev/null 2>&1 || true
  if [ "$activated" -eq 1 ]; then
    if fail_at rollback_activate || ! ln -s "$old_link" "$root/.current.rollback.$$" \
      || ! replace_activation_link "$root/.current.rollback.$$" "$root/current"; then
      err 'rollback could not restore current pointer; no runtime will be started'
      rollback_failed=1
    fi
  fi
  if [ "$snapshot_ready" -eq 1 ] && [ -f "$snapshot/manifest.sha256" ]; then
    db_base="$(printf '%s' "${BOARD_DB_URL:-}" | sed -n 's#^jdbc:h2:file:##p' | cut -d';' -f1)"
    restore_snapshot_file() {
      snapshot_name="$1"; live_file="$2"
      expected="$(awk -v name="$snapshot_name" '$2==name {print $1}' "$snapshot/manifest.sha256")"
      [ -n "$expected" ] && [ -f "$snapshot/$snapshot_name" ] || return 0
      [ "$(sha256_file "$snapshot/$snapshot_name")" = "$expected" ] || return 1
      restore_tmp="${live_file}.update-restore.$$"
      rm -f -- "$restore_tmp"
      cp -p -- "$snapshot/$snapshot_name" "$restore_tmp" || return 1
      chmod 600 "$restore_tmp" || { rm -f -- "$restore_tmp"; return 1; }
      [ "$(sha256_file "$restore_tmp")" = "$expected" ] || { rm -f -- "$restore_tmp"; return 1; }
      mv -f -- "$restore_tmp" "$live_file" || { rm -f -- "$restore_tmp"; return 1; }
      [ "$(sha256_file "$live_file")" = "$expected" ] || return 1
      return 0
    }
    if [ -z "$db_base" ] || ! restore_snapshot_file board.mv.db "${db_base}.mv.db" \
      || ! restore_snapshot_file board.trace.db "${db_base}.trace.db"; then
      err "rollback could not restore verified database snapshot; source is retained at $snapshot (manual recovery: copy its board.mv.db to ${db_base:-<BOARD_DB_URL file path>}.mv.db while stopped)"
      rollback_failed=1
    fi
  fi
  if [ "$rollback_failed" -ne 0 ]; then
    current_now="$(readlink "$root/current" 2>/dev/null || printf '<unreadable>')"
    err "rollback incomplete; service remains stopped. diagnostics: snapshot=$snapshot stage=$stage release=$root/releases/$requested_version current=$current_now old_link=$old_link"
    err "manual activation recovery (while stopped): ln -s '$old_link' '$root/.current.manual' && { mv -h '$root/.current.manual' '$root/current' 2>/dev/null || mv -T '$root/.current.manual' '$root/current'; }"
    err "manual DB recovery (while stopped, after activation): cp '$snapshot/board.mv.db' '${db_base:-<BOARD_DB_URL file path>}.mv.db'"
    return 1
  fi
  if [ "$was_running" -eq 1 ]; then
    "$root/bin/board" start >/dev/null 2>&1 || { err "rollback start failed; inspect $snapshot and $stage manually"; return 1; }
    "$root/bin/board" status >/dev/null 2>&1 || { err "rollback readiness failed; inspect $snapshot and $stage manually"; return 1; }
  fi
  err "rolled back to v${current_version}; diagnostics retained at $snapshot and $stage"
  return 0
}

if fail_at stop || { [ "$was_running" -eq 1 ] && ! "$root/bin/board" stop; }; then
  rm -rf -- "$stage"; [ "$was_running" -eq 0 ] || "$root/bin/board" start >/dev/null 2>&1 || true
  die 'service stop failed; old activation was retained'
fi

db_base="$(printf '%s' "${BOARD_DB_URL:-}" | sed -n 's#^jdbc:h2:file:##p' | cut -d';' -f1)"
mkdir -p "$snapshot" || { restore_old backup || true; die 'cannot create update backup directory'; }
if fail_at backup || { [ -n "$db_base" ] && [ -f "${db_base}.mv.db" ] && ! cp -p "${db_base}.mv.db" "$snapshot/board.mv.db"; } \
  || { [ -n "$db_base" ] && [ -f "${db_base}.trace.db" ] && ! cp -p "${db_base}.trace.db" "$snapshot/board.trace.db"; }; then
  restore_old backup || true; die 'database snapshot failed'
fi
if [ -f "$snapshot/board.mv.db" ]; then
  sha256_file "$snapshot/board.mv.db" | awk '{print $1 "  board.mv.db"}' > "$snapshot/manifest.sha256"
  if [ -f "$snapshot/board.trace.db" ]; then sha256_file "$snapshot/board.trace.db" | awk '{print $1 "  board.trace.db"}' >> "$snapshot/manifest.sha256"; fi
else
  printf '# database absent before update\n' > "$snapshot/manifest.sha256"
fi
printf 'current=%s\ntarget=%s\npid_before=%s\nactivation=%s\n' "$current_version" "$requested_version" "$pid_before" "$old_link" > "$snapshot/manifest.txt"
snapshot_ready=1

if fail_at publish || ! mv "$stage" "$root/releases/$requested_version"; then restore_old publish || true; die 'runtime publish failed'; fi
published=1
if fail_at activate || ! ln -s "releases/$requested_version" "$root/.current.new.$$" || ! replace_activation_link "$root/.current.new.$$" "$root/current"; then restore_old activate || true; die 'activation pointer switch failed'; fi
activated=1

if fail_at start || ! "$root/bin/board" start; then restore_old start || true; die 'new runtime could not start'; fi
if fail_at readiness || ! "$root/bin/board" status >/dev/null 2>&1; then restore_old readiness || true; die 'new runtime did not become ready'; fi
health="$(curl -fsS --max-time 3 "http://127.0.0.1:${BOARD_PORT:-8080}/api/health" 2>/dev/null || true)"
printf '%s' "$health" | grep -Eq '"version"[[:space:]]*:[[:space:]]*"'"$requested_version"'"' \
  || { restore_old readiness || true; die 'ready runtime did not report the requested version'; }
printf '%s' "$health" | grep -Eq '"commit"[[:space:]]*:[[:space:]]*"[0-9a-f]{7,40}"' \
  || { restore_old readiness || true; die 'ready runtime did not report a release commit'; }
if [ "$was_running" -eq 0 ]; then
  if fail_at stop_after_validation || ! "$root/bin/board" stop || "$root/bin/board" status >/dev/null 2>&1; then
    restore_old stop_after_validation || true
    die 'target validation completed but could not return service to its original stopped state'
  fi
  state_note=' (target verified, then returned to stopped)'
else
  state_note=' (service restored to ready)'
fi
log "updated atomically: v${current_version} -> v${requested_version}${state_note}"
exit 0

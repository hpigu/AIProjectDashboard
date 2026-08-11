#!/usr/bin/env bash
# Install a verified macOS/Linux stable release into a user-owned directory.
# No part of this installer needs sudo or a repository checkout at runtime.

set -eu

PROGRAM="${0##*/}"

log() { printf '[install] %s\n' "$*"; }
err() { printf '[install][error] %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

usage() {
  cat <<'EOF'
Install a verified AI Project Board macOS/Linux stable release.

Usage:
  install/install.sh --jar PATH --checksums PATH [--home DIR] [--java PATH]
  install/install.sh --release-url URL --version V [--home DIR] [--java PATH]

Required input modes (choose exactly one):
  --jar PATH          Platform JDK 21 executable JAR obtained by the user.
  --checksums PATH    Matching ai-project-board-backend-V-SHA256SUMS.txt.
  --release-url URL   Explicit vV GitHub-release asset directory. The installer downloads
                      the platform JAR and checksum list only for this invocation.
  --version V         Stable product version (for example 3.1.1); required with --release-url.

Options:
  --home DIR          Installation root (default: $HOME/.ai-project-board).
  --java PATH         JDK 21 executable used only to validate this install.
  --migrate-from DIR  Explicit old repo data directory containing board.mv.db;
                      only permitted while creating a new installation root.
  -h, --help          Show this help.

The install is staged beside the installation root and published atomically. Existing
data is preserved. To import an old repo database, use --migrate-from DIR during a
new install; the source is copied, backed up and never moved. See docs/installation.md.
EOF
}

root="${BOARD_HOME_DIR:-$HOME/.ai-project-board}"
jar=""
checksums=""
release_url=""
requested_version=""
java_bin="${JAVA_BIN:-java}"
migrate_from=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --jar) shift; [ "$#" -gt 0 ] || die '--jar needs a path'; jar="$1" ;;
    --checksums) shift; [ "$#" -gt 0 ] || die '--checksums needs a path'; checksums="$1" ;;
    --release-url) shift; [ "$#" -gt 0 ] || die '--release-url needs a URL'; release_url="$1" ;;
    --version) shift; [ "$#" -gt 0 ] || die '--version needs a version'; requested_version="$1" ;;
    --home) shift; [ "$#" -gt 0 ] || die '--home needs a directory'; root="$1" ;;
    --java) shift; [ "$#" -gt 0 ] || die '--java needs an executable'; java_bin="$1" ;;
    --migrate-from) shift; [ "$#" -gt 0 ] || die '--migrate-from needs a data directory'; migrate_from="$1" ;;
    -h|--help|help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

case "$root" in '') die 'installation root must not be empty' ;; esac
case "$root" in *$'\n'*|*$'\r'*) die 'installation root must not contain a newline' ;; esac

# The default is a PATH command while --java may be either a command or an
# absolute executable. Resolve it once so the executable/permission check is
# meaningful in both cases.
case "$java_bin" in
  */*) ;;
  *) java_bin="$(command -v "$java_bin" 2>/dev/null || true)" ;;
esac
[ -n "$java_bin" ] || die 'cannot find java; install a matching JDK 21 or use --java PATH'

platform() {
  case "$(uname -s):$(uname -m)" in
    Darwin:arm64|Darwin:aarch64) printf '%s' macos-arm64 ;;
    Darwin:x86_64|Darwin:amd64) printf '%s' macos-x64 ;;
    Linux:x86_64|Linux:amd64) printf '%s' linux-x64 ;;
    *) return 1 ;;
  esac
}

release_platform="$(platform)" || die "unsupported platform: $(uname -s) $(uname -m) (supported: macOS arm64/x64, Linux x64)"

version_ok() { printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die 'neither sha256sum nor shasum is available'
  fi
}

java_is_21_and_matches_platform() {
  [ -x "$java_bin" ] || return 1
  "$java_bin" -version 2>&1 | grep -Eq 'version "21([.\"]|$)' || return 1
  java_arch="$($java_bin -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*os\.arch = //p' | head -n1)"
  case "$release_platform:$java_arch" in
    macos-arm64:aarch64|macos-arm64:arm64|macos-x64:x86_64|macos-x64:amd64|linux-x64:x86_64|linux-x64:amd64) return 0 ;;
    *) return 1 ;;
  esac
}

if ! java_is_21_and_matches_platform; then
  die "JDK 21 is required for ${release_platform}; --java '${java_bin}' is not a JDK 21 executable"
fi

if [ -n "$release_url" ]; then
  [ -z "$jar" ] && [ -z "$checksums" ] || die 'do not combine --release-url with --jar/--checksums'
  version_ok "$requested_version" || die '--version must be a stable X.Y.Z value'
  command -v curl >/dev/null 2>&1 || die '--release-url requires curl'
  download_dir="$(mktemp -d "${TMPDIR:-/tmp}/ai-project-board-download.XXXXXX")" || die 'cannot create download directory'
  trap 'rm -rf "$download_dir"' EXIT HUP INT TERM
  jar="${download_dir}/ai-project-board-backend-${release_platform}-${requested_version}.jar"
  checksums="${download_dir}/ai-project-board-backend-${requested_version}-SHA256SUMS.txt"
  base_url="${release_url%/}"
  log "downloading explicitly requested stable v${requested_version} assets"
  curl --fail --location --show-error --silent "${base_url}/$(basename "$checksums")" -o "$checksums" || die 'cannot download checksum list'
  curl --fail --location --show-error --silent "${base_url}/$(basename "$jar")" -o "$jar" || die 'cannot download platform JAR'
else
  [ -n "$jar" ] && [ -n "$checksums" ] || die 'provide --jar and --checksums, or --release-url and --version'
fi

[ -f "$jar" ] || die "JAR does not exist: $jar"
[ -f "$checksums" ] || die "checksum list does not exist: $checksums"

jar_base="$(basename "$jar")"
case "$jar_base" in
  ai-project-board-backend-"$release_platform"-*.jar)
    version="${jar_base#ai-project-board-backend-${release_platform}-}"
    version="${version%.jar}"
    ;;
  *) die "JAR basename must be ai-project-board-backend-${release_platform}-V.jar" ;;
esac
version_ok "$version" || die "JAR version must be a stable X.Y.Z value: $jar_base"
[ -z "$requested_version" ] || [ "$requested_version" = "$version" ] || die '--version does not match the JAR basename'

checksums_base="$(basename "$checksums")"
expected_checksums="ai-project-board-backend-${version}-SHA256SUMS.txt"
[ "$checksums_base" = "$expected_checksums" ] || die "checksum basename must be ${expected_checksums}"

# Contract parser: byte-level LF/UTF-8-ASCII shape, exactly four sorted expected rows.
expected_linux="ai-project-board-backend-linux-x64-${version}.jar"
expected_arm="ai-project-board-backend-macos-arm64-${version}.jar"
expected_mac="ai-project-board-backend-macos-x64-${version}.jar"
expected_windows="ai-project-board-backend-windows-x64-${version}.zip"
expected_rows="${expected_linux}\n${expected_arm}\n${expected_mac}\n${expected_windows}"

if [ "$(tail -c 1 "$checksums" 2>/dev/null | od -An -t x1 | tr -d '[:space:]')" != '0a' ]; then
  die 'checksum list must end with one LF newline'
fi
if LC_ALL=C grep -q $'\r' "$checksums" || LC_ALL=C grep -q '[^ -~]' "$checksums"; then
  die 'checksum list must be UTF-8 ASCII text with LF line endings and no BOM'
fi
actual_rows="$(awk -F '  ' '
  NF != 2 || $1 !~ /^[0-9a-f]{64}$/ || index($2, "/") || index($2, "\\") || $2 ~ /[[:space:]]/ || $2 == "" { exit 1 }
  { print $2 }
' "$checksums")" || die 'checksum list has an invalid line format'
[ "$(printf '%s\n' "$actual_rows" | sed '/^$/d' | wc -l | tr -d '[:space:]')" = 4 ] || die 'checksum list must contain exactly four entries'
[ "$actual_rows" = "$(printf '%b' "$expected_rows")" ] || die 'checksum list entries, ordering, or version do not match the release contract'

expected_hash="$(awk -v name="$jar_base" -F '  ' '$2 == name { print $1 }' "$checksums")"
[ -n "$expected_hash" ] || die 'platform JAR is absent from checksum list'
actual_hash="$(sha256_file "$jar")"
[ "$actual_hash" = "$expected_hash" ] || die "SHA-256 mismatch for ${jar_base}"

parent="$(dirname "$root")"
leaf="$(basename "$root")"
[ "$leaf" != '.' ] && [ "$leaf" != '/' ] || die 'installation root is invalid'
mkdir -p "$parent" || die "cannot create installation parent: $parent"
[ -L "$root" ] && die "installation root must not be a symlink: $root"

new_install=0
[ -e "$root" ] || new_install=1
if [ "$new_install" -eq 0 ]; then
  [ -d "$root" ] || die "installation root is not a directory: $root"
  [ -x "$root/bin/board" ] && [ -f "$root/config/board.env" ] \
      && [ -d "$root/releases" ] && [ -L "$root/current" ] \
      || die "existing installation is incomplete; refusing to replace it: $root"
  [ -d "$root/data" ] && [ -n "$migrate_from" ] && die '--migrate-from is only allowed for a new installation root'
fi

stage="${parent}/.${leaf}.staging.$$"
[ ! -e "$stage" ] || die "staging path already exists: $stage"
cleanup_stage() { [ -n "${stage:-}" ] && [ -d "$stage" ] && rm -rf "$stage"; }
trap cleanup_stage EXIT HUP INT TERM
umask 077
mkdir -p "$stage" || die 'cannot create staging directory'
chmod 700 "$stage" || die 'cannot secure staging directory'

copy_runtime() {
  mkdir -p "$stage/bin" "$stage/config" "$stage/releases/${version}/app" "$stage/data" "$stage/backups" "$stage/logs" || return 1
  chmod 700 "$stage/bin" "$stage/config" "$stage/releases" "$stage/releases/${version}" "$stage/releases/${version}/app" "$stage/data" "$stage/backups" "$stage/logs" || return 1
  script_root="$(cd -P "$(dirname "$0")/.." >/dev/null 2>&1 && pwd)"
  for file in board board-env.sh backup-db.sh restore-db.sh; do
    [ -f "$script_root/bin/$file" ] || return 1
    cp "$script_root/bin/$file" "$stage/bin/$file" || return 1
  done
  chmod 700 "$stage/bin/board" "$stage/bin/board-env.sh" "$stage/bin/backup-db.sh" "$stage/bin/restore-db.sh" || return 1
  cp "$jar" "$stage/releases/${version}/app/${jar_base}" || return 1
  chmod 600 "$stage/releases/${version}/app/${jar_base}" || return 1
  ln -s "$jar_base" "$stage/releases/${version}/app/server.jar" || return 1
  printf 'BOARD_INSTALLED_HOME=%s\nBOARD_INSTALLED_JAR=%s/current/app/server.jar\n' "$root" "$root" > "$stage/config/board.env" || return 1
  chmod 600 "$stage/config/board.env" || return 1
  ln -s "releases/${version}" "$stage/current" || return 1
}

copy_migration() {
  [ -n "$migrate_from" ] || return 0
  [ -d "$migrate_from" ] || die "migration source is not a directory: $migrate_from"
  source_db="${migrate_from}/board.mv.db"
  [ -f "$source_db" ] || die "migration source has no board.mv.db: $migrate_from"
  migration_backup="$stage/backups/migration-source-${version}-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir "$migration_backup" || die 'cannot create migration backup directory'
  chmod 700 "$migration_backup" || die 'cannot secure migration backup directory'
  cp "$source_db" "$migration_backup/board.mv.db" || die 'cannot copy migration backup'
  cmp -s "$source_db" "$migration_backup/board.mv.db" || die 'migration backup verification failed'
  source_hash="$(sha256_file "$source_db")"
  printf '%s  board.mv.db\n' "$source_hash" > "$migration_backup/SHA256SUMS.txt" || die 'cannot write migration checksum'
  chmod 600 "$migration_backup/board.mv.db" "$migration_backup/SHA256SUMS.txt" || die 'cannot secure migration backup'
  cp "$migration_backup/board.mv.db" "$stage/data/board.mv.db" || die 'cannot stage migrated database'
  cmp -s "$migration_backup/board.mv.db" "$stage/data/board.mv.db" || die 'migration staging verification failed'
  if [ -f "${migrate_from}/board.trace.db" ]; then
    cp "${migrate_from}/board.trace.db" "$migration_backup/board.trace.db" || die 'cannot copy migration trace backup'
    cmp -s "${migrate_from}/board.trace.db" "$migration_backup/board.trace.db" || die 'migration trace backup verification failed'
    cp "$migration_backup/board.trace.db" "$stage/data/board.trace.db" || die 'cannot stage migrated trace file'
    chmod 600 "$migration_backup/board.trace.db" "$stage/data/board.trace.db" || die 'cannot secure migrated trace file'
  fi
  chmod 600 "$stage/data/board.mv.db" || die 'cannot secure migrated database'
}

copy_runtime || die 'cannot stage the installed runtime'
copy_migration

if [ "$new_install" -eq 1 ]; then
  # Same-parent rename is atomic; no visible partial installation can appear.
  mv "$stage" "$root" || die 'cannot atomically publish installation'
  stage=""
else
  # Existing data, backups and logs are deliberately untouched. A release is immutable;
  # publish its directory first, then atomically switch the current symlink.
  [ ! -e "$root/releases/$version" ] || die "release ${version} is already installed; existing installation left unchanged"
  mv "$stage/releases/$version" "$root/releases/$version" || die 'cannot publish staged release'
  if ! ln -s "releases/${version}" "$root/.current.new.$$"; then
    rm -rf "$root/releases/$version"
    die 'cannot stage current release link'
  fi
  if ! mv -f "$root/.current.new.$$" "$root/current"; then
    rm -f "$root/.current.new.$$"
    rm -rf "$root/releases/$version"
    die 'cannot atomically activate staged release'
  fi
fi

trap - EXIT HUP INT TERM
log "installed v${version} for ${release_platform} at ${root}"
log "start: ${root}/bin/board start"
log "status: ${root}/bin/board status"
log "stop: ${root}/bin/board stop"

#!/usr/bin/env bash
# Offline behavioural checks for install/install.sh. They use a fake JDK and a
# tiny fake release asset; real start/status/stop is exercised separately with
# a Maven-built JAR on the host platform.

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALLER="${REPO_ROOT}/install/install.sh"
work="$(mktemp -d /private/tmp/ai-project-board-install-check.XXXXXX)"
trap 'rm -rf "$work"' EXIT HUP INT TERM

failures=0
pass() { printf '  [PASS] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }
assert_file() { [ -f "$1" ] && pass "$2" || fail "$2"; }
assert_missing() { [ ! -e "$1" ] && pass "$2" || fail "$2"; }
assert_eq() { [ "$1" = "$2" ] && pass "$3" || fail "$3 (expected $1, got $2)"; }
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi
}
mode() { stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"; }

case "$(uname -s):$(uname -m)" in
  Darwin:arm64|Darwin:aarch64) platform=macos-arm64; java_arch=arm64 ;;
  Darwin:x86_64|Darwin:amd64) platform=macos-x64; java_arch=x86_64 ;;
  Linux:x86_64|Linux:amd64) platform=linux-x64; java_arch=x86_64 ;;
  *) echo "unsupported host for installer check: $(uname -s) $(uname -m)"; exit 0 ;;
esac

version=3.1.1
mkdir -p "$work/bin" "$work/assets"
cat > "$work/bin/java" <<EOF
#!/usr/bin/env bash
if [ "\${1:-}" = -version ]; then echo 'openjdk version "21.0.12"' >&2; exit 0; fi
if [ "\${1:-}" = -XshowSettings:properties ]; then echo '    os.arch = ${java_arch}' >&2; echo 'openjdk version "21.0.12"' >&2; exit 0; fi
exit 0
EOF
chmod 700 "$work/bin/java"

jar="$work/assets/ai-project-board-backend-${platform}-${version}.jar"
printf 'fixture server artifact\n' > "$jar"
checksum="$work/assets/ai-project-board-backend-${version}-SHA256SUMS.txt"
for name in \
  "ai-project-board-backend-linux-x64-${version}.jar" \
  "ai-project-board-backend-macos-arm64-${version}.jar" \
  "ai-project-board-backend-macos-x64-${version}.jar" \
  "ai-project-board-backend-windows-x64-${version}.zip"; do
  if [ "$name" = "$(basename "$jar")" ]; then hash="$(sha256_file "$jar")"; else hash=$(printf '%064d' 0); fi
  printf '%s  %s\n' "$hash" "$name" >> "$checksum"
done

echo '=== installer help and clean install ==='
"$INSTALLER" --help >/dev/null && pass 'help is executable' || fail 'help is executable'
root="$work/安裝 root with spaces"
(cd / && "$INSTALLER" --jar "$jar" --checksums "$checksum" --java "$work/bin/java" --home "$root")
assert_file "$root/bin/board" 'clean install includes launcher'
assert_file "$root/current/app/server.jar" 'current server path is stable and absolute-configured'
assert_eq 700 "$(mode "$root")" 'installation root is 0700'
assert_eq 700 "$(mode "$root/data")" 'data directory is 0700'
assert_eq 600 "$(mode "$root/config/board.env")" 'install configuration is 0600'
assert_eq "BOARD_INSTALLED_HOME=$root" "$(sed -n '1p' "$root/config/board.env")" 'custom root persists independent of cwd'
"$root/bin/board" --help >/dev/null && pass 'installed launcher can run outside repository' || fail 'installed launcher can run outside repository'

echo '=== bad checksum leaves no half installation ==='
mkdir -p "$work/bad-assets"
bad_checksum="$work/bad-assets/ai-project-board-backend-${version}-SHA256SUMS.txt"
cp "$checksum" "$bad_checksum"
asset_hash="$(sha256_file "$jar")"
bad_hash="0${asset_hash#?}"
[ "$bad_hash" != "$asset_hash" ] || bad_hash="1${asset_hash#?}"
sed -i.bak "/$(basename "$jar")/s/^[^ ]*/${bad_hash}/" "$bad_checksum"; rm -f "$bad_checksum.bak"
bad_root="$work/bad checksum root"
if "$INSTALLER" --jar "$jar" --checksums "$bad_checksum" --java "$work/bin/java" --home "$bad_root" >/dev/null 2>&1; then
  fail 'bad checksum is rejected'
else
  pass 'bad checksum is rejected'
fi
assert_missing "$bad_root" 'bad checksum creates no installation root'

echo '=== wrong JDK is rejected before staging ==='
cat > "$work/bin/java-not-21" <<EOF
#!/usr/bin/env bash
echo 'openjdk version "17.0.1"' >&2
EOF
chmod 700 "$work/bin/java-not-21"
wrong_jdk_root="$work/wrong jdk root"
if "$INSTALLER" --jar "$jar" --checksums "$checksum" --java "$work/bin/java-not-21" --home "$wrong_jdk_root" >/dev/null 2>&1; then
  fail 'non-21 JDK is rejected'
else
  pass 'non-21 JDK is rejected'
fi
assert_missing "$wrong_jdk_root" 'wrong JDK creates no installation root'

echo '=== failed reinstall preserves current release ==='
before="$(readlink "$root/current")"
if "$INSTALLER" --jar "$jar" --checksums "$bad_checksum" --java "$work/bin/java" --home "$root" >/dev/null 2>&1; then
  fail 'failed reinstall is rejected'
else
  pass 'failed reinstall is rejected'
fi
assert_eq "$before" "$(readlink "$root/current")" 'failed reinstall retains old current release'

echo '=== explicit migration is copied and backed up ==='
source_data="$work/old repo data"
mkdir -p "$source_data"
printf 'fake H2 data fixture\n' > "$source_data/board.mv.db"
source_hash="$(sha256_file "$source_data/board.mv.db")"
migrated_root="$work/migrated root"
"$INSTALLER" --jar "$jar" --checksums "$checksum" --java "$work/bin/java" --home "$migrated_root" --migrate-from "$source_data"
assert_eq "$source_hash" "$(sha256_file "$source_data/board.mv.db")" 'migration leaves source data unchanged'
assert_eq "$source_hash" "$(sha256_file "$migrated_root/data/board.mv.db")" 'migration stages copied H2 data'
migration_backup="$(find "$migrated_root/backups" -name board.mv.db -type f | head -n1)"
assert_file "$migration_backup" 'migration creates a verifiable source backup'
assert_eq "$source_hash" "$(sha256_file "$migration_backup")" 'migration backup matches source'

echo '=== failed migration leaves no half installation ==='
missing_root="$work/missing migration root"
if "$INSTALLER" --jar "$jar" --checksums "$checksum" --java "$work/bin/java" --home "$missing_root" --migrate-from "$work/not-present" >/dev/null 2>&1; then
  fail 'missing migration source is rejected'
else
  pass 'missing migration source is rejected'
fi
assert_missing "$missing_root" 'failed migration creates no installation root'

if [ "$failures" -gt 0 ]; then
  printf '%s installer checks failed.\n' "$failures" >&2
  exit 1
fi
echo 'All installer checks passed.'

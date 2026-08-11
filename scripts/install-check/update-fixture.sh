#!/usr/bin/env bash
# update-fixture.sh — isolated rollback assertions for board-update.sh.
# It deliberately uses a fake lifecycle and fake jar command: this verifies the transaction
# branches without claiming a real cross-version Spring Boot health smoke.

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d /private/tmp/ai-project-board-update-fixture.XXXXXX)"
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
root="$work/安裝 root"
mkdir -p "$root/bin" "$root/releases/3.1.1/app" "$root/data" "$root/backups" "$work/bin" "$work/assets"
ln -s releases/3.1.1 "$root/current"
printf 'H:2-old-snapshot\n' > "$root/data/board.mv.db"
printf '%s\n' '#!/usr/bin/env bash' 'case "$1" in status) [ -f "${BOARD_INSTALLED_HOME}/running" ] && exit 0; exit 3 ;; start) : > "${BOARD_INSTALLED_HOME}/running" ;; stop) rm -f "${BOARD_INSTALLED_HOME}/running" ;; *) exit 1 ;; esac' > "$root/bin/board"
chmod 700 "$root/bin/board"
: > "$root/running"

# board-update only needs jar tf/xf for this fixture; intercept those operations so the fixture
# can model a correctly named target artifact without pretending that it is a real release JAR.
printf '%s\n' '#!/usr/bin/env bash' 'case "$1" in tf) exit 0 ;; xf) mkdir -p META-INF; printf "Main-Class: org.springframework.boot.loader.launch.JarLauncher\n" > META-INF/MANIFEST.MF; printf "build.version=3.1.2\n" > META-INF/build-info.properties ;; *) exit 1 ;; esac' > "$work/bin/jar"
chmod 700 "$work/bin/jar"
target="$work/assets/ai-project-board-backend-macos-arm64-3.1.2.jar"
printf 'fixture target\n' > "$target"
hash="$(shasum -a 256 "$target" | awk '{print $1}')"
checksums="$work/assets/ai-project-board-backend-3.1.2-SHA256SUMS.txt"
printf '%064d  ai-project-board-backend-linux-x64-3.1.2.jar\n' 0 > "$checksums"
printf '%s  ai-project-board-backend-macos-arm64-3.1.2.jar\n' "$hash" >> "$checksums"
printf '%064d  ai-project-board-backend-macos-x64-3.1.2.jar\n' 0 >> "$checksums"
printf '%064d  ai-project-board-backend-windows-x64-3.1.2.zip\n' 0 >> "$checksums"

before_hash="$(shasum -a 256 "$root/data/board.mv.db" | awk '{print $1}')"
if PATH="$work/bin:$PATH" BOARD_INSTALLED_HOME="$root" BOARD_DB_URL="jdbc:h2:file:$root/data/board;DB_CLOSE_ON_EXIT=FALSE" \
  BOARD_PORT=18998 BOARD_UPDATE_FAIL_AT=readiness "$REPO_ROOT/bin/board-update.sh" --version 3.1.2 --jar "$target" --checksums "$checksums"; then
  echo '[FAIL] readiness injection unexpectedly succeeded' >&2; exit 1
fi

[ "$(readlink "$root/current")" = releases/3.1.1 ] || { echo '[FAIL] old activation was not restored' >&2; exit 1; }
[ "$(shasum -a 256 "$root/data/board.mv.db" | awk '{print $1}')" = "$before_hash" ] || { echo '[FAIL] live DB was not restored' >&2; exit 1; }
snapshot="$(find "$root/backups" -type d -name 'update-3.1.1-to-3.1.2-*' | head -n1)"
[ -n "$snapshot" ] && [ -f "$snapshot/board.mv.db" ] && [ -f "$snapshot/manifest.sha256" ] || { echo '[FAIL] rollback snapshot was consumed or missing' >&2; exit 1; }
[ "$(shasum -a 256 "$snapshot/board.mv.db" | awk '{print $1}')" = "$before_hash" ] || { echo '[FAIL] retained snapshot hash changed' >&2; exit 1; }
[ -f "$root/running" ] || { echo '[FAIL] originally running service was not restored' >&2; exit 1; }
echo '[PASS] rollback restores live DB and retains verifiable snapshot'

# The originally-stopped branch must still start, readiness-check and health-identify the target,
# then stop it again.  The fake curl response is only a lifecycle fixture, not a real smoke claim.
rm -f "$root/running"
rm -rf -- "$root/releases/3.1.2"
printf '%s\n' '#!/usr/bin/env bash' 'printf "{\"version\":\"3.1.2\",\"commit\":\"abcdef1\"}"' > "$work/bin/curl"
chmod 700 "$work/bin/curl"
PATH="$work/bin:$PATH" BOARD_INSTALLED_HOME="$root" BOARD_DB_URL="jdbc:h2:file:$root/data/board;DB_CLOSE_ON_EXIT=FALSE" \
  BOARD_PORT=18998 "$REPO_ROOT/bin/board-update.sh" --version 3.1.2 --jar "$target" --checksums "$checksums"
[ "$(readlink "$root/current")" = releases/3.1.2 ] || { echo '[FAIL] stopped-state fixture did not activate target' >&2; exit 1; }
[ ! -f "$root/running" ] || { echo '[FAIL] originally stopped service was left running after target validation' >&2; exit 1; }
echo '[PASS] originally stopped service verifies target then remains stopped'

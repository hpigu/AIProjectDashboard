#!/usr/bin/env bash
# update-fixture.sh — isolated rollback assertions for board-update.sh.
# It deliberately uses a fake lifecycle and fake jar command: this verifies the transaction
# branches without claiming a real cross-version Spring Boot health smoke.

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/ai-project-board-update-fixture.XXXXXX")"
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

# A mutable GitHub "latest" release URL must be rejected before any network call, service
# stop, or filesystem change — regardless of case or where "latest" appears in the path.
# This models the exact shape of GitHub's mutable download endpoint that slipped past a
# broken `[ "$x" != *latest* ]` literal-string guard (bug fixed alongside this fixture).
#
# Each bad_url deliberately ends in /v3.1.2 — the exact --version requested below — so it
# also satisfies the downstream `*/v"$requested_version"` shape check. If the latest guard
# were removed or weakened, the shape check alone would NOT stop these URLs: execution would
# reach curl and fail there instead (offline/DNS failure), which also exits non-zero and also
# leaves the activation pointer/release/service untouched. An exit-code-only assertion cannot
# tell those two failures apart, so this asserts on the exact guard message instead. This
# mirrors the #166 lesson (which repeats the #164 lesson): a bad_url, or an assertion, that a
# downstream check would also reject on its own proves nothing about the guard under test.
for bad_url in \
  'https://github.com/x/releases/latest/download/v3.1.2' \
  'https://github.com/x/releases/LATEST/download/v3.1.2' \
  'https://github.com/x/releases/download/vLatest/v3.1.2'; do
  out="$(PATH="$work/bin:$PATH" BOARD_INSTALLED_HOME="$root" BOARD_DB_URL="jdbc:h2:file:$root/data/board;DB_CLOSE_ON_EXIT=FALSE" \
    BOARD_PORT=18998 "$REPO_ROOT/bin/board-update.sh" --version 3.1.2 --release-url "$bad_url" 2>&1)" && rc=0 || rc=$?
  [ "$rc" -ne 0 ] || { echo "[FAIL] mutable latest release-url was not rejected: $bad_url" >&2; exit 1; }
  printf '%s' "$out" | grep -Fq 'mutable latest URLs are forbidden' \
    || { echo "[FAIL] mutable latest release-url failed for the wrong reason (not the latest guard): $bad_url -- $out" >&2; exit 1; }
  [ "$(readlink "$root/current")" = releases/3.1.1 ] || { echo "[FAIL] latest-url rejection changed the activation pointer: $bad_url" >&2; exit 1; }
  [ ! -e "$root/releases/3.1.2" ] || { echo "[FAIL] latest-url rejection published a release: $bad_url" >&2; exit 1; }
  [ -f "$root/running" ] || { echo "[FAIL] latest-url rejection stopped the running service: $bad_url" >&2; exit 1; }
done
echo '[PASS] mutable latest release-url is rejected case-insensitively before any change (by the latest guard specifically)'

# The exact immutable vV shape must still be accepted by the same guard and proceed past it.
# curl is not faked here, so the request will actually reach curl and fail to download from a
# fake host — proving execution passed the latest guard (no forbidden-latest message) and the
# shape check (no shape-guard message) and reached the network step. If the latest guard were
# deleted entirely, the absence of its message alone would prove nothing (it would never fire
# for any input); asserting on the specific downstream network-failure message instead proves
# the guard was actually evaluated and did not block this valid URL.
out="$(PATH="$work/bin:$PATH" BOARD_INSTALLED_HOME="$root" BOARD_DB_URL="jdbc:h2:file:$root/data/board;DB_CLOSE_ON_EXIT=FALSE" \
  BOARD_PORT=18998 "$REPO_ROOT/bin/board-update.sh" --version 3.1.2 --release-url 'https://github.com/x/releases/download/v3.1.2' 2>&1)" && rc=0 || rc=$?
[ "$rc" -ne 0 ] || { echo '[FAIL] valid immutable vV release-url unexpectedly succeeded (network should be unreachable in this fixture)' >&2; exit 1; }
if printf '%s' "$out" | grep -Fq 'mutable latest URLs are forbidden'; then
  echo "[FAIL] a valid immutable vV release-url was rejected as latest -- $out" >&2; exit 1
fi
if printf '%s' "$out" | grep -Fq 'must name the exact immutable vV release'; then
  echo "[FAIL] a valid immutable vV release-url was rejected by the shape guard -- $out" >&2; exit 1
fi
printf '%s' "$out" | grep -Fq 'cannot download checksum list' \
  || { echo "[FAIL] valid immutable vV release-url did not reach the download step as expected -- $out" >&2; exit 1; }
echo '[PASS] immutable vV release-url passes the latest guard and shape check, reaching the download step'

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

# A rollback pointer failure is a hard fail-closed condition: the updater may restore the
# verifiable DB copy, but it must not start either target or old runtime or print success.
ln -s releases/3.1.1 "$root/.current.fixture"
mv -h "$root/.current.fixture" "$root/current" 2>/dev/null || mv -T "$root/.current.fixture" "$root/current"
rm -rf -- "$root/releases/3.1.2"
: > "$root/running"
if output="$(PATH="$work/bin:$PATH" BOARD_INSTALLED_HOME="$root" BOARD_DB_URL="jdbc:h2:file:$root/data/board;DB_CLOSE_ON_EXIT=FALSE" \
  BOARD_PORT=18998 BOARD_UPDATE_FAIL_AT=readiness,rollback_activate "$REPO_ROOT/bin/board-update.sh" --version 3.1.2 --jar "$target" --checksums "$checksums" 2>&1)"; then
  echo '[FAIL] rollback activation injection unexpectedly succeeded' >&2; exit 1
fi
[ ! -f "$root/running" ] || { echo '[FAIL] pointer-restore failure started a runtime' >&2; exit 1; }
[ "$(shasum -a 256 "$root/data/board.mv.db" | awk '{print $1}')" = "$before_hash" ] || { echo '[FAIL] pointer-restore failure did not preserve old live DB' >&2; exit 1; }
snapshot="$(find "$root/backups" -type d -name 'update-3.1.1-to-3.1.2-*' | tail -n1)"
[ -f "$snapshot/board.mv.db" ] && [ -f "$snapshot/manifest.sha256" ] || { echo '[FAIL] pointer-restore fixture lost its snapshot' >&2; exit 1; }
[ "$(shasum -a 256 "$snapshot/board.mv.db" | awk '{print $1}')" = "$before_hash" ] || { echo '[FAIL] pointer-restore fixture changed snapshot hash' >&2; exit 1; }
printf '%s' "$output" | grep -F 'manual activation recovery' >/dev/null || { echo '[FAIL] hard rollback failure omitted manual recovery path' >&2; exit 1; }
if printf '%s' "$output" | grep -F 'rolled back to' >/dev/null; then echo '[FAIL] hard rollback failure claimed success' >&2; exit 1; fi
echo '[PASS] rollback pointer failure stays stopped and retains manual-recovery evidence'

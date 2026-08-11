#!/usr/bin/env bash
# Offline regression fixtures for the shared release-asset gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VALIDATOR="${ROOT}/scripts/release/release-assets.sh"
version=9.9.9
work="$(mktemp -d /private/tmp/ai-project-board-release-assets.XXXXXX)"
trap 'rm -rf -- "$work"' EXIT HUP INT TERM

pass() { printf '  [PASS] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1" >&2; exit 1; }
make_assets() {
  local directory="$1"
  mkdir -p "$directory"
  for name in \
    "ai-project-board-backend-linux-x64-${version}.jar" \
    "ai-project-board-backend-macos-arm64-${version}.jar" \
    "ai-project-board-backend-macos-x64-${version}.jar" \
    "ai-project-board-backend-windows-x64-${version}.zip"; do
    printf 'fixture %s\n' "$name" > "${directory}/${name}"
  done
  "$VALIDATOR" assemble --version "$version" --directory "$directory"
}
expect_failure() {
  local name="$1" directory="$2"
  if "$VALIDATOR" validate --version "$version" --directory "$directory" >/dev/null 2>&1; then
    fail "$name is rejected"
  fi
  pass "$name is rejected"
}

ok="$work/success"
make_assets "$ok"
"$VALIDATOR" validate --version "$version" --directory "$ok"
pass 'valid four assets and deterministic checksums are accepted'

missing="$work/missing"; cp -R "$ok" "$missing"; rm "$missing/ai-project-board-backend-linux-x64-${version}.jar"; expect_failure 'missing asset' "$missing"
extra="$work/extra"; cp -R "$ok" "$extra"; : > "$extra/unexpected.bin"; expect_failure 'extra asset' "$extra"
wrong_name="$work/wrong-name"; cp -R "$ok" "$wrong_name"; mv "$wrong_name/ai-project-board-backend-linux-x64-${version}.jar" "$wrong_name/wrong.jar"; expect_failure 'wrong asset name' "$wrong_name"
wrong_hash="$work/wrong-hash"; cp -R "$ok" "$wrong_hash"; first="$(cut -c1 "$wrong_hash/ai-project-board-backend-${version}-SHA256SUMS.txt")"; replacement=0; [ "$first" = 0 ] && replacement=1; sed -i.bak "1s/^./${replacement}/" "$wrong_hash/ai-project-board-backend-${version}-SHA256SUMS.txt"; rm "$wrong_hash/ai-project-board-backend-${version}-SHA256SUMS.txt.bak"; expect_failure 'wrong hash' "$wrong_hash"
wrong_order="$work/wrong-order"; cp -R "$ok" "$wrong_order"; { tail -n 1 "$wrong_order/ai-project-board-backend-${version}-SHA256SUMS.txt"; head -n 3 "$wrong_order/ai-project-board-backend-${version}-SHA256SUMS.txt"; } > "$wrong_order/reordered"; mv "$wrong_order/reordered" "$wrong_order/ai-project-board-backend-${version}-SHA256SUMS.txt"; expect_failure 'wrong checksum order' "$wrong_order"
wrong_format="$work/wrong-format"; cp -R "$ok" "$wrong_format"; sed -i.bak '1s/  /\t/' "$wrong_format/ai-project-board-backend-${version}-SHA256SUMS.txt"; rm "$wrong_format/ai-project-board-backend-${version}-SHA256SUMS.txt.bak"; expect_failure 'wrong checksum format' "$wrong_format"

single="$work/single"; mkdir "$single"; cp "$ok/ai-project-board-backend-linux-x64-${version}.jar" "$single/"
"$VALIDATOR" validate-platform --version "$version" --platform linux-x64 --directory "$single"
pass 'platform self-validation accepts only its contract artifact'

echo 'All release asset fixtures passed.'

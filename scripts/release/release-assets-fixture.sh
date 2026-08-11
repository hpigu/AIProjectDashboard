#!/usr/bin/env bash
# Offline regression fixtures for the shared release-asset gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VALIDATOR="${ROOT}/scripts/release/release-assets.sh"
version=9.9.9
work="$(mktemp -d "${TMPDIR:-/tmp}/ai-project-board-release-assets.XXXXXX")"
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

workflow="${ROOT}/.github/workflows/release.yml"
assert_workflow_contains() {
  local needle="$1" description="$2"
  grep -Fq -- "$needle" "$workflow" || fail "$description"
  pass "$description"
}
assert_workflow_absent() {
  local needle="$1" description="$2"
  if grep -Fq -- "$needle" "$workflow"; then fail "$description"; fi
  pass "$description"
}
assert_workflow_count() {
  local needle="$1" expected="$2" description="$3" actual
  actual="$(grep -Fc -- "$needle" "$workflow" || true)"
  [ "$actual" = "$expected" ] || fail "$description (expected ${expected}, got ${actual})"
  pass "$description"
}

echo '=== workflow static release gates ==='
assert_workflow_contains 'runs-on: macos-15' 'macOS arm64 uses the current macos-15 runner label'
assert_workflow_contains 'runs-on: macos-15-intel' 'macOS x64 uses the current macos-15-intel runner label'
assert_workflow_absent 'runs-on: macos-14' 'deprecated macos-14 runner label is absent'
assert_workflow_absent 'runs-on: macos-13' 'obsolete macos-13 runner label is absent'
assert_workflow_contains 'run: test "$(uname -m)" = arm64' 'macOS arm64 job fails closed on wrong architecture'
assert_workflow_contains 'run: test "$(uname -m)" = x86_64' 'macOS x64 job fails closed on wrong architecture'
assert_workflow_contains 'tag: ${{ steps.release_ref.outputs.tag }}' 'preflight exports canonical tag'
assert_workflow_contains 'version: ${{ steps.release_ref.outputs.version }}' 'preflight exports canonical version'
assert_workflow_contains 'commit: ${{ steps.release_ref.outputs.commit }}' 'preflight exports canonical peeled commit'
assert_workflow_contains 'git rev-parse --verify "refs/tags/${tag}^{commit}"' 'preflight verifies the tag peeled commit'
assert_workflow_contains '[ "$head_commit" = "$tag_commit" ]' 'preflight rejects a checkout that differs from the tag commit'
assert_workflow_count 'ref: ${{ needs.preflight.outputs.tag }}' 5 'all four builders and publish checkout the preflight tag'
assert_workflow_contains 'needs: [preflight, linux-x64, macos-arm64, macos-x64, windows-x64]' 'publish directly depends on preflight and all platform builds'
assert_workflow_contains 'COMMIT: ${{ needs.preflight.outputs.commit }}' 'release notes receive the preflight commit'
assert_workflow_contains 'built from ${COMMIT}' 'release notes trace the preflight commit'
assert_workflow_absent '${GITHUB_SHA}' 'release notes do not use dispatch-dependent GITHUB_SHA'
assert_workflow_contains 'gh release create "$TAG" --verify-tag --draft' 'draft creation verifies the tag still exists'
assert_workflow_count './scripts/release/posix-release-smoke.sh --version "$version" --platform' 3 'all three POSIX builders run the shared real-artifact smoke'
assert_workflow_count 'Gate portable install and update with the real release JAR' 3 'all three POSIX smoke gates run before artifact upload'
assert_workflow_count 'scripts\windows-check\update-fixture.ps1' 1 'Windows updater rollback fixture is a required release gate'
assert_workflow_count 'actions/upload-artifact@v4' 4 'platform artifacts upload only through the four required build jobs'

awk '
  function finish_job() {
    if (job == "") return
    if (!smoke || !upload || smoke >= upload) bad=1
    checked++; job=""
  }
  /^  (linux-x64|macos-arm64|macos-x64):$/ {
    finish_job()
    job=$1; sub(/:$/, "", job); smoke=0; upload=0; next
  }
  /^  [a-z0-9-]+:$/ && job != "" {
    finish_job()
  }
  job != "" && /posix-release-smoke\.sh/ { smoke=NR }
  job != "" && /actions\/upload-artifact@v4/ { upload=NR }
  END {
    finish_job()
    if (bad || checked != 3) exit 1
  }
' "$workflow" || fail 'each POSIX smoke gate precedes its upload step'
pass 'each POSIX smoke gate precedes its upload step'

awk '
  /^  windows-x64:$/ { job=1; next }
  /^  [a-z0-9-]+:$/ && job { job=0 }
  job && /scripts\\windows-check\\update-fixture\.ps1/ { fixture=NR }
  job && /actions\/upload-artifact@v4/ { upload=NR }
  END { if (!fixture || !upload || fixture >= upload) exit 1 }
' "$workflow" || fail 'Windows updater rollback fixture precedes artifact upload'
pass 'Windows updater rollback fixture precedes artifact upload'

echo 'All release asset fixtures passed.'

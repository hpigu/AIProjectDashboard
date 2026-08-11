#!/usr/bin/env bash
# Strict offline release-asset gate shared by CI and local fixtures.
set -euo pipefail
export LC_ALL=C

usage() {
  cat >&2 <<'EOF'
Usage:
  release-assets.sh validate-platform --version V --platform PLATFORM --directory DIR
  release-assets.sh assemble --version V --directory DIR
  release-assets.sh validate --version V --directory DIR

PLATFORM is linux-x64, macos-arm64, macos-x64, or windows-x64.
EOF
  exit 64
}

die() { echo "release-assets: $*" >&2; exit 1; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

expected_assets() {
  local version="$1"
  printf '%s\n' \
    "ai-project-board-backend-linux-x64-${version}.jar" \
    "ai-project-board-backend-macos-arm64-${version}.jar" \
    "ai-project-board-backend-macos-x64-${version}.jar" \
    "ai-project-board-backend-windows-x64-${version}.zip"
}

asset_for_platform() {
  local version="$1" platform="$2"
  case "$platform" in
    linux-x64) echo "ai-project-board-backend-linux-x64-${version}.jar" ;;
    macos-arm64) echo "ai-project-board-backend-macos-arm64-${version}.jar" ;;
    macos-x64) echo "ai-project-board-backend-macos-x64-${version}.jar" ;;
    windows-x64) echo "ai-project-board-backend-windows-x64-${version}.zip" ;;
    *) die "unsupported platform: ${platform}" ;;
  esac
}

list_regular_basenames() {
  local directory="$1"
  find "$directory" -maxdepth 1 -type f -print | sed 's#^.*/##' | LC_ALL=C sort
}

assert_flat_regular_directory() {
  local directory="$1" unexpected
  unexpected="$(find "$directory" -mindepth 1 -maxdepth 1 ! -type f -print -quit)"
  [ -z "$unexpected" ] || die "release asset directory may not contain directories, symlinks, or special files: ${unexpected}"
}

assert_exact_assets() {
  local version="$1" directory="$2" actual expected
  [ -d "$directory" ] || die "directory does not exist: ${directory}"
  assert_flat_regular_directory "$directory"
  actual="$(list_regular_basenames "$directory")"
  expected="$(expected_assets "$version")"
  [ "$actual" = "$expected" ] || die "product assets must be exactly the four contract names in ${directory}"
}

validate_platform() {
  local version="$1" platform="$2" directory="$3" expected actual
  expected="$(asset_for_platform "$version" "$platform")"
  [ -d "$directory" ] || die "directory does not exist: ${directory}"
  assert_flat_regular_directory "$directory"
  actual="$(list_regular_basenames "$directory")"
  [ "$actual" = "$expected" ] || die "${platform} output must contain exactly ${expected}"
}

assemble() {
  local version="$1" directory="$2" checksum_file
  assert_exact_assets "$version" "$directory"
  checksum_file="${directory}/ai-project-board-backend-${version}-SHA256SUMS.txt"
  [ ! -e "$checksum_file" ] || die "refusing to overwrite existing checksum file: ${checksum_file}"
  (
    cd "$directory"
    while IFS= read -r asset; do
      printf '%s  %s\n' "$(sha256_file "$asset")" "$asset"
    done < <(expected_assets "$version")
  ) > "$checksum_file"
}

validate_checksums() {
  local version="$1" directory="$2" checksum_file expected_files actual_files line hash name computed previous_name=""
  checksum_file="${directory}/ai-project-board-backend-${version}-SHA256SUMS.txt"
  [ -f "$checksum_file" ] || die "checksum file does not exist: ${checksum_file}"

  expected_files="$( { expected_assets "$version"; echo "ai-project-board-backend-${version}-SHA256SUMS.txt"; } | LC_ALL=C sort)"
  actual_files="$(list_regular_basenames "$directory")"
  [ "$actual_files" = "$expected_files" ] || die "directory must contain exactly four product assets and its checksum file"

  [ "$(head -c 3 "$checksum_file" | od -An -tx1 | tr -d ' \n')" != "efbbbf" ] || die "checksum file must not contain a UTF-8 BOM"
  ! grep -q $'\r' "$checksum_file" || die "checksum file must use LF line endings"
  [ "$(tail -c 1 "$checksum_file" | od -An -tx1 | tr -d ' \n')" = "0a" ] || die "checksum file must end with LF"
  [ "$(wc -l < "$checksum_file" | tr -d ' ')" = "4" ] || die "checksum file must contain exactly four lines"
  while IFS= read -r line; do
    if [[ ! "$line" =~ ^([0-9a-f]{64})\ \ ([A-Za-z0-9._-]+)$ ]]; then
      die "checksum line has invalid format"
    fi
    hash="${BASH_REMATCH[1]}"
    name="${BASH_REMATCH[2]}"
    case "$name" in
      "ai-project-board-backend-linux-x64-${version}.jar"|\
      "ai-project-board-backend-macos-arm64-${version}.jar"|\
      "ai-project-board-backend-macos-x64-${version}.jar"|\
      "ai-project-board-backend-windows-x64-${version}.zip") ;;
      *) die "checksum line has an unexpected asset name: ${name}" ;;
    esac
    if [ -n "$previous_name" ] && [[ "$previous_name" > "$name" || "$previous_name" = "$name" ]]; then
      die "checksum entries are not sorted by asset basename"
    fi
    previous_name="$name"
    computed="$(sha256_file "${directory}/${name}")"
    [ "$hash" = "$computed" ] || die "checksum mismatch for ${name}"
  done < "$checksum_file"
}

[ "$#" -ge 1 ] || usage
command="$1"
shift
version="" platform="" directory=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --version) version="${2:-}"; shift 2 ;;
    --platform) platform="${2:-}"; shift 2 ;;
    --directory) directory="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$version" ] && [ -n "$directory" ] || usage

case "$command" in
  validate-platform)
    [ -n "$platform" ] || usage
    validate_platform "$version" "$platform" "$directory"
    ;;
  assemble) assemble "$version" "$directory" ;;
  validate) validate_checksums "$version" "$directory" ;;
  *) usage ;;
esac

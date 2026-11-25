#!/usr/bin/env bash
set -euo pipefail

base="${1:-}"
head=$(git rev-parse --verify --end-of-options "${2:-}^{commit}" 2>/dev/null || true)
# Only optimize when the event base matches the first parent of the PR merge commit.
if [[ ! "$base" =~ ^[0-9a-f]{40}$ ]] ||
  [[ "$(git rev-parse --verify "$head^1" 2>/dev/null)" != "$base" ]] ||
  ! git rev-parse --verify "$head^2" >/dev/null 2>&1; then
  printf 'full\n'
  exit 0
fi

paths=$(mktemp)
trap 'rm -f -- "$paths"' EXIT
# Treat renames as deletion plus addition so both paths count toward the scope.
if ! git diff --name-only --no-renames -z "$base" "$head" -- > "$paths"; then
  printf 'full\n'
  exit 0
fi

profile=''
while IFS= read -r -d '' path; do
  case "$path" in
    README.md|README.zh-TW.md|CHANGELOG.md|docs/*.md) path_profile=docs ;;
    workers/*) path_profile=workers ;;
    *) path_profile=full ;;
  esac
  if [[ "$path_profile" == full || ( -n "$profile" && "$profile" != "$path_profile" ) ]]; then
    printf 'full\n'
    exit 0
  fi
  profile="$path_profile"
done < "$paths"
printf '%s\n' "${profile:-full}"

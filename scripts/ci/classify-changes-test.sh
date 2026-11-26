#!/usr/bin/env bash
set -euo pipefail

classifier="$(cd -- "$(dirname -- "$0")" && pwd)/classify-changes.sh"
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
cd "$fixture"

git init --quiet
git config user.name 'CI fixture'
git config user.email 'ci-fixture@example.invalid'
git config commit.gpgsign false
git config core.hooksPath /dev/null
mkdir -p docs workers
printf 'original\n' > README.md
printf 'original\n' > docs/user-guide.md
printf 'original\n' > workers/index.js
git add .
git commit --quiet -m 'Base'
base=$(git rev-parse HEAD)

start_case() {
  git switch --quiet --detach "$base"
}

expect_profile() {
  local expected="$1" topic merge actual
  git add -A
  git commit --quiet --allow-empty -m 'Topic'
  topic=$(git rev-parse HEAD)
  merge=$(git commit-tree "$topic^{tree}" -p "$base" -p "$topic" -m 'PR merge')
  actual=$(bash "$classifier" "$base" "$merge")
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected %s, got %s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

start_case
printf 'changed\n' >> README.md
printf 'new guide\n' > "docs/"$'line\nbreak.md'
expect_profile docs
git update-ref refs/heads/fixture-pr-merge \
  "$(git commit-tree 'HEAD^{tree}' -p "$base" -p HEAD -m 'Shallow PR merge')"

start_case
git rm --quiet docs/user-guide.md
expect_profile docs

start_case
printf 'changed\n' >> workers/index.js
expect_profile workers

start_case
printf 'changed\n' >> README.md
printf 'changed\n' >> workers/index.js
expect_profile full

start_case
git mv README.md workers/moved.md
expect_profile full

start_case
printf 'shared\n' > pom.xml
expect_profile full

start_case
printf 'fixture\n' > docs/fixture.json
expect_profile full

start_case
expect_profile full

[[ "$(bash "$classifier" "$base" HEAD)" == full ]]
[[ "$(bash "$classifier" invalid-base HEAD)" == full ]]
checkout=$(mktemp -d "$fixture/shallow.XXXXXX")
git clone --quiet --depth 2 --single-branch --branch fixture-pr-merge "file://$fixture" "$checkout"
[[ "$(cd "$checkout" && bash "$classifier" "$base" HEAD)" == docs ]]
printf 'PR scope classification fixtures passed.\n'

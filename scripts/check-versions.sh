#!/usr/bin/env bash
#
# check-versions.sh — 確認所有寫死版號的地方與 pom.xml 一致
#
# 為什麼需要這支腳本：版號散落在多個 manifest 裡，而它們沒有任何機制互相對照，
# 跳版時只能靠記得。這件事已經發生過兩次：
#
#   1. MCP 的 serverInfo.version 寫死 3.0.0，3.1.0 的 build 對 agent 回報 3.0.0。
#      修法是改由 ${project.version} 帶入（9a62bd6）。
#   2. 修完第一個之後，plugin 的三份 manifest 仍然停在 3.0.0——沒有人發現，因為
#      沒有東西會發現。而 plugin.json 的版號正是陌生人安裝時看到的那個數字。
#
# 所以真正要解的不是「把 3.0.0 改成 3.1.0」，而是「怎麼保證沒有第三次」。這支
# 腳本就是那道門檻：新增任何帶版號的檔案時把它加進 FILES，之後跳版忘記改就會
# 在 CI 上紅燈，而不是等到有人抱怨版本不對。
#
# 用法：
#   scripts/check-versions.sh
#
# 需要 ./mvnw（版號的單一事實來源是 pom.xml，不是這支腳本裡的常數）。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# 每一列是「檔案路徑」。這些檔案各自只應該有一個 "version" 鍵；若哪天不是了，
# 下面的數量檢查會擋下來，而不是默默比對到錯的那一個。
FILES="
plugin/.claude-plugin/plugin.json
.claude-plugin/marketplace.json
.codex-plugin/.codex-plugin/plugin.json
"

pom_version="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
if [ -z "$pom_version" ]; then
  echo "::error::讀不到 pom.xml 的 project.version" >&2
  exit 1
fi
echo "pom.xml 版本：${pom_version}"

failures=0
for file in $FILES; do
  if [ ! -f "$file" ]; then
    echo "::error::${file} 不存在。若檔案已搬移或刪除，請一併更新本腳本的 FILES。" >&2
    failures=$((failures + 1))
    continue
  fi

  count="$(grep -c '"version"[[:space:]]*:' "$file" || true)"
  if [ "$count" != "1" ]; then
    echo "::error::${file} 有 ${count} 個 \"version\" 鍵，預期剛好 1 個。" >&2
    echo "本腳本靠「檔案裡只有一個版號」這個前提取值，前提不成立就不能相信比對結果。" >&2
    failures=$((failures + 1))
    continue
  fi

  actual="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$file")"
  if [ "$actual" != "$pom_version" ]; then
    echo "::error::${file} 的版號是 ${actual}，pom.xml 是 ${pom_version}。" >&2
    failures=$((failures + 1))
  else
    echo "  [OK] ${file} = ${actual}"
  fi
done

if [ "$failures" -gt 0 ]; then
  echo "" >&2
  echo "有 ${failures} 個版號與 pom.xml 不一致。" >&2
  echo "跳版時請一併更新上列檔案；漏掉的話，使用者看到的版本號會是錯的，" >&2
  echo "而且不會有任何錯誤訊息告訴他。" >&2
  exit 1
fi

echo "所有版號與 pom.xml 一致。"

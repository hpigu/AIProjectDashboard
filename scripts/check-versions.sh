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
# 只需要 bash 與 grep／sed／awk：版號的單一事實來源是 pom.xml，不是這支腳本裡
# 的常數，也不需要為了讀一個字面值去啟動一次 Maven（見下面 pom_version 的註解）。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# 每一列是「檔案路徑」。這些檔案各自只應該有一個 "version" 鍵；若哪天不是了，
# 下面的數量檢查會擋下來，而不是默默比對到錯的那一個。
FILES="
plugin/.claude-plugin/plugin.json
.claude-plugin/marketplace.json
plugins/ai-project-board/.codex-plugin/plugin.json
"

# 不呼叫 `./mvnw help:evaluate`：那要開一個 JVM、組出 effective POM，還可能為了
# maven-help-plugin 連一次 Central，在 CI 上是十幾秒起跳——只為了讀一個字面值。
# 這裡直接讀 pom.xml，但必須跳過 <parent> 區塊，因為 spring-boot-starter-parent
# 的版號排在前面，天真地抓第一個 <version> 會拿到 Spring Boot 的版號。
# 「它是字面值」這個前提在下面立刻驗證：哪天版號改成 ${revision} 之類的屬性，
# 這裡會大聲失敗，而不是默默比對到一個沒被展開的字串。
pom_version="$(awk '
  /<parent>/ { in_parent = 1 }
  /<\/parent>/ { in_parent = 0; next }
  !in_parent && match($0, /<version>[^<]+<\/version>/) {
    print substr($0, RSTART + 9, RLENGTH - 19)
    exit
  }
' pom.xml)"
case "$pom_version" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *)
    echo "::error::從 pom.xml 讀到的 project.version 是「${pom_version}」，不像版號。" >&2
    echo "本腳本假設 <version> 是字面值；若已改成屬性或由 parent 繼承，請改用 mvn help:evaluate。" >&2
    exit 1
    ;;
esac
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

# README 的安裝指令裡寫著完整 jar 檔名（`java -jar target/...-3.1.0.jar`）。
# 那是陌生人照抄的第一行指令：版號沒跟上，他複製貼上後看到的是「檔案不存在」，
# 而 manifest 全對、CI 全綠。這裡把檔名裡的版號也一起比對。
#
# 只鎖定 `java -jar` 那一行：README 裡還會提到「舊 release tag 底下實際的
# asset 檔名」這類歷史事實敘述，那些檔名本來就該維持發布當時的舊版號，若和
# 整份文件裡所有同樣式的引用一起比對，會把正確的歷史敘述誤判成沒跟上版號。
readme_refs="$(grep -o 'java -jar target/ai-project-board-backend-[0-9][0-9A-Za-z.-]*\.jar' README.md \
  | grep -o 'ai-project-board-backend-[0-9][0-9A-Za-z.-]*\.jar' | sort -u || true)"
if [ -z "$readme_refs" ]; then
  echo "::error::README.md 裡找不到 java -jar 安裝指令引用的 ai-project-board-backend-<版號>.jar。" >&2
  echo "安裝指令若改寫成別的形式，請一併更新本腳本，否則這道檢查等於沒作用。" >&2
  failures=$((failures + 1))
fi
for ref in $readme_refs; do
  actual="${ref#ai-project-board-backend-}"
  actual="${actual%.jar}"
  if [ "$actual" != "$pom_version" ]; then
    echo "::error::README.md 的安裝指令寫的是 ${ref}，pom.xml 是 ${pom_version}。" >&2
    failures=$((failures + 1))
  else
    echo "  [OK] README.md 安裝指令 = ${actual}"
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

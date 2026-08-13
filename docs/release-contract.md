# Release 產品契約

本文件定義 stable GitHub Release 的 server 產物、checksum 與 thin plugin
版本契約。`v3.2.1` 已依這份契約發布。

[`release workflow`](../.github/workflows/release.yml) 會在四個原生平台 runner 各自
測試、封裝及自我驗證，再由唯一的 publish job 匯集四個產物，建立集中 checksum
清單並發布。publish job 只在四個 build jobs 都成功後執行，且拒絕覆寫既有（含
draft）release。macOS arm64 使用 GitHub `macos-15` runner，x64 使用
`macos-15-intel`；兩者均以 `uname -m` fail-closed 確認架構。preflight 會把已
驗證 tag 的 peeled commit、tag 與版本輸出給所有下游 job；所有 checkout 與 release
notes 都使用這組值，而不是 workflow dispatch 的預設 SHA。

## 1. 版本、tag 與來源追溯

令 `V` 為不帶 `v` 前綴的產品版本（例如 `3.2.1`）。`pom.xml` 的
`project.version` 是唯一的產品版本事實來源；目前它是字面值，且
`scripts/check-versions.sh` 以它驗證已存在的散布 metadata。

每個 stable release 都必須同時滿足下表。所有項目必須來自**同一個 tag commit**；
不能以另一個 commit 的 plugin 或產物補湊。

| 追溯點 | 必須值／規則 | 目前位置 |
|---|---|---|
| Server | `project.version = V` | `pom.xml` |
| Claude Code plugin | `version = V` | `plugin/.claude-plugin/plugin.json` |
| Claude marketplace entry | `version = V` | `.claude-plugin/marketplace.json` |
| Codex plugin | `version = V` | `plugins/ai-project-board/.codex-plugin/plugin.json` |
| Codex marketplace | 指向 `./plugins/ai-project-board`；該目錄內上述 manifest 的 `version` 即為 marketplace 所安裝 plugin 的版本 | `.agents/plugins/marketplace.json` |
| Release tag | 精確為 `vV` | Git tag 與 GitHub Release |
| Server release assets | 檔名中的版本精確為 `V` | 本文件第 2 節 |

`.agents/plugins/marketplace.json` 目前沒有且不應另加重複的 `version` 欄位；它是
路徑型 marketplace entry，版本由被選取 plugin manifest 提供。這仍可從 tag
`vV` 追溯到同一份 Codex plugin metadata，而不創造第二個版本來源。

建立 stable release 前，發布工作會在 tag checkout 上執行
`./scripts/check-versions.sh`，並拒絕 `v${pom.xml project.version}` 以外的 tag。
Release notes 列出 tag `vV` 與實際建置 commit；服務的
`/api/health` 可回報建置期嵌入的 version 與 commit，供已安裝實例核對。

## 2. Stable release assets

一個 `vV` stable GitHub Release 必須上傳且只上傳以下五個**產品 asset**。GitHub
自動顯示的 source archive 不屬於這份產品 asset 清單，也不屬於 checksum 範圍：

| 平台／用途 | Release asset 檔名 | 內容與執行入口 |
|---|---|---|
| Windows x64 server | `ai-project-board-backend-windows-x64-V.zip` | Windows x64 的可攜 ZIP；結構與入口見下方。 |
| Linux x64 server | `ai-project-board-backend-linux-x64-V.jar` | Spring Boot executable JAR；以系統的 x64 JDK 21 執行 `java -jar ai-project-board-backend-linux-x64-V.jar`。 |
| macOS arm64 server | `ai-project-board-backend-macos-arm64-V.jar` | Spring Boot executable JAR；以系統的 arm64 JDK 21 執行 `java -jar ai-project-board-backend-macos-arm64-V.jar`。 |
| macOS x64 server | `ai-project-board-backend-macos-x64-V.jar` | Spring Boot executable JAR；以系統的 x64 JDK 21 執行 `java -jar ai-project-board-backend-macos-x64-V.jar`。 |
| 完整性清單 | `ai-project-board-backend-V-SHA256SUMS.txt` | 僅涵蓋上列四個平台 asset，格式見第 4 節。 |

檔名中的 `ai-project-board-backend` 延續 Maven 的既有 `artifactId`；`V` 必須以實際
版本取代，不能包含 `v`。平台 asset 以檔名區分，即使三個 JAR 的 Java bytecode
在某次實作中相同，也不能以「任選一個 JAR」取代對應平台 asset。

### Windows ZIP 結構

ZIP 解壓後必須只有一個頂層目錄
`ai-project-board-backend-windows-x64-V/`，且下列路徑為契約的一部分：

```text
ai-project-board-backend-windows-x64-V/
├── app/
│   └── ai-project-board-backend-V.jar
├── bin/
│   └── board.ps1
└── runtime/
    └── bin/java.exe
```

`runtime/` 是隨 ZIP 散布的 Windows x64 JDK 21 jlink runtime image；不得以系統
`java`、`JAVA_HOME` 或網路下載作為替代前提。使用者／安裝器的唯一 server 入口是
`bin\board.ps1`，它必須使用同一個 ZIP 內的 `runtime\bin\java.exe` 啟動
`app\ai-project-board-backend-V.jar`。這個 ZIP 不是 plugin cache 的內容。

Linux 與兩種 macOS asset **不是 ZIP**，不帶 `runtime/`、launcher 或 JDK；安裝器只
可把選定的 JAR 放入 server 安裝位置，並要求系統提供相同 CPU 架構的 JDK 21。它們的
server 入口就是表中完整檔名的 `java -jar`，不是 plugin 目錄內的任何檔案。

## 3. Server 與 plugin 的相容性

Claude Code 與 Codex plugin 都是 thin client：它們只散布各自的 manifest、MCP URL
宣告、角色薄殼與 skill。它們**不得**含 server source、任何 server JAR、jlink
runtime、`bin/board`／`bin/board.ps1` launcher，或這些檔案的複本／解壓快取。Server
由上節 asset（或使用者明確選擇的原始碼建置）獨立安裝與啟動；plugin 只連向已在
loopback 運作的 MCP server。

| Server release version | Claude plugin manifest version | Codex plugin manifest version | Stable 支援狀態 |
|---|---|---|---|
| `V` | `V` | `V` | 支援；三者同屬 tag `vV`。 |
| `V` | 非 `V` | 任意 | 不支援；先以相同 stable tag 的 Claude plugin 對齊。 |
| `V` | 任意 | 非 `V` | 不支援；先以相同 stable tag 的 Codex plugin 對齊。 |
| 任一缺失、非 stable，或無法追溯同一 tag | 任意 | 任意 | 不支援；不要以猜測的跨版組合發佈或驗收。 |

使用者不必同時安裝兩種 plugin；表中的兩個 manifest 是同一 release source 的
metadata。實際使用 Claude（或 Codex）時，只要求該已安裝 plugin 與 server 同為 `V`，
另一種 plugin 仍必須在 `vV` tag 內標示 `V` 才算完整 stable release。

這是發佈與支援矩陣，不是目前 server 已實作的 runtime 版本封鎖。現有 MCP 端點不會
因 plugin 版本不同而自動拒絕連線；遇到不一致時，安裝器、更新器與文件應要求使用者
回到同一個 `vV` release，而不是聲稱仍受支援。

`stable` 是已建立 GitHub Release、tag 符合 `vX.Y.Z` 且通過本文件第 1 節門檻的
channel。它不使用 mutable `latest` asset 名稱，也不以 branch、未標 tag commit 或
GitHub 的「最新 release」排序結果作為版本選擇依據。安裝與更新都必須由使用者明確
選定／確認目標 stable `vV`；本契約**不包含背景自動更新**。Claude/Codex 的 plugin
更新仍沿用各自 marketplace 的明確更新／重新安裝流程，並從同一 tag 的 plugin source
取得內容，不下載或快取 server asset。

## 4. SHA-256 完整性契約

`ai-project-board-backend-V-SHA256SUMS.txt` 是可機器驗證的唯一 checksum 清單，規則
如下：

- 檔案必須是 UTF-8、無 BOM、以 LF (`\n`) 換行，且最後一行也必須有一個 LF。
- 必須剛好四行，依 asset **basename** 以 `LC_ALL=C` 的 bytewise 遞增順序排列：
  Linux JAR、macOS arm64 JAR、macOS x64 JAR、Windows ZIP。
- 每行精確格式為 64 個小寫十六進位 SHA-256 字元、兩個 ASCII spaces、再接 asset
  basename：`<64-lowercase-hex>  <basename>\n`。不得使用 `*` binary marker、tab、
  `./`、絕對路徑、URL、目錄、反斜線或其他欄位。
- 每個 basename 必須是第 2 節四個檔名之一，版本皆為同一個 `V`，不能列 checksum
  清單本身、GitHub 自動產生的 source archive、plugin cache 或任何額外 asset。

以 `V=3.2.1` 時，清單的形狀（雜湊值僅為格式佔位）如下：

```text
<64 lowercase hex>  ai-project-board-backend-linux-x64-3.2.1.jar
<64 lowercase hex>  ai-project-board-backend-macos-arm64-3.2.1.jar
<64 lowercase hex>  ai-project-board-backend-macos-x64-3.2.1.jar
<64 lowercase hex>  ai-project-board-backend-windows-x64-3.2.1.zip
```

安裝器／更新器必須先下載（或取得）目標 asset 與同版 `SHA256SUMS.txt`，以嚴格 UTF-8
解析上述格式，確認清單集合、排序與目標 basename 完全符合，再以 SHA-256 計算 asset
內容並作精確字串比對。任何缺行、重複行、額外行、版本不符、路徑字元、非小寫 hash、
編碼／換行不符或 hash 不符，都必須在安裝／替換前失敗且保留現有已安裝 server。
驗證成功後才可解壓 Windows ZIP 或替換 Linux/macOS JAR。這是下載完整性檢查，並不
等同簽章、來源身分驗證或背景更新授權。

## 5. Windows 發布驗證

Windows x64 runner 建置 `target/ai-project-board-backend-V.jar` 後執行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\package-windows-x64.ps1 `
  -ServerJar target\ai-project-board-backend-V.jar -JdkHome $env:JAVA_HOME `
  -Version V -OutputDirectory target\release
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\package-fixture.ps1 `
  -ServerJar target\ai-project-board-backend-V.jar -Version V
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\release-check.ps1 `
  -ReleaseZip target\release\ai-project-board-backend-windows-x64-V.zip -Smoke
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\update-fixture.ps1 `
  -ReleaseZip target\release\ai-project-board-backend-windows-x64-V.zip -Version V
```

打包器只接受 Windows x64 JDK 21。`release-check.ps1 -Smoke` 驗證
start/status/stop；`update-fixture.ps1` 驗證 checksum 拒絕、含空白與非
ASCII 路徑，以及 publish、activate、start、readiness 失敗後的回滾。
這些檢查必須在 artifact upload 前通過。

測試行程只能依該案例記錄的 PID 終止；不得用 process name、JAR 名稱或
command-line substring 清理，以免誤傷正式看板。

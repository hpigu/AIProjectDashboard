# Vendored 前端資源來源

本目錄下的檔案皆為離線打包的第三方資源（#109），取代原本對
unpkg / Google Fonts 的線上請求。所有檔案皆為靜態二進位/文字檔，
不引入 npm、Vite、SFC 或 TypeScript 建置步驟。

## Vue

| 檔案 | 版本 | 來源 | 授權 |
| --- | --- | --- | --- |
| `vue/vue.global.prod.js` | **3.5.40**（精確版本，非 latest） | https://unpkg.com/vue@3.5.40/dist/vue.global.prod.js | MIT，見 `licenses/vue-LICENSE.txt` |

下載指令：
```
curl -sL -o vue.global.prod.js https://unpkg.com/vue@3.5.40/dist/vue.global.prod.js
curl -sL -o vue-LICENSE.txt https://unpkg.com/vue@3.5.40/LICENSE
```

SHA-256（供日後比對是否遭竄改）：
```
9e0039a3f6ed0e85308e24d737447f1af6af83d229d69e1267a32b29bc2a1337  vue.global.prod.js
```

## 字型

僅打包實際會用到的 weight 與 subset：

- **Archivo** 700、800 —— `--font-display`，用於標題（`.app-title`、`.project-name`、
  `.board-title`、`.column-count`）
- **IBM Plex Sans** 400、500 —— `--font-body`，網頁預設本文字體
- **IBM Plex Mono** 400、500 —— `--font-data`，用於數據/狀態類文字（連線狀態、時間戳、
  task id、category 等 `.conn-status` / `.task-id` / `.project-meta` 等）

只保留 **latin** subset（Google Fonts 依 `unicode-range` 切成 cyrillic / greek /
vietnamese / latin-ext / latin 等多個檔案）。這三個字型家族本身都不含 CJK 字符，
介面中的中文字一律回退至系統字型（`sans-serif`），因此非 latin 的 subset
（西里爾字母、希臘字母、越南語變音等）對本專案沒有實際用途，不予打包。

版本來自 Google Fonts API 回傳的 CSS 註解：

| 家族 | 版本 | 檔案 |
| --- | --- | --- |
| Archivo | v25 | `fonts/archivo-700.woff2`, `fonts/archivo-800.woff2` |
| IBM Plex Sans | v23 | `fonts/ibm-plex-sans-400.woff2`, `fonts/ibm-plex-sans-500.woff2` |
| IBM Plex Mono | v20 | `fonts/ibm-plex-mono-400.woff2`, `fonts/ibm-plex-mono-500.woff2` |

授權：三個字型家族皆為 SIL Open Font License 1.1，授權原文取自
`google/fonts` 官方 repo，存放於：
- `licenses/archivo-OFL.txt`
- `licenses/ibm-plex-sans-OFL.txt`
- `licenses/ibm-plex-mono-OFL.txt`

取得方式：對 `https://fonts.googleapis.com/css2?family=...&display=swap`
帶桌機版 Chrome UA 發出請求，取得 CSS 後從 `/* latin */` 區塊取出對應
`https://fonts.gstatic.com/...woff2` 網址下載。例如：

```
curl -s -A "Mozilla/5.0 ... Chrome/120.0.0.0 Safari/537.36" \
  "https://fonts.googleapis.com/css2?family=Archivo:wght@700&display=swap"
```

授權原文來源：
```
https://raw.githubusercontent.com/google/fonts/main/ofl/archivo/OFL.txt
https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsans/OFL.txt
https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/OFL.txt
```

## 更新方式

之後要升級 Vue 版本或補充字型 weight／subset，重複上述下載步驟，
並同步更新本檔案與 `index.html` 內對應的 `<link>`／`<script>` 路徑、
`fonts.css` 的 `@font-face` 宣告。**務必固定精確版本**，不要使用
`latest`、`@3` 這類浮動版本號。

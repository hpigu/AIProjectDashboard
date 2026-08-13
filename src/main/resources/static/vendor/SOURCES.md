# Vendored frontend sources

These third-party assets are stored in the repository so the UI makes no runtime
requests to unpkg or Google Fonts. They do not introduce npm, Vite, SFC, or a
TypeScript build step.

## Vue

| File | Version | Source | License |
|---|---|---|---|
| `vue/vue.global.prod.js` | `3.5.40` | https://unpkg.com/vue@3.5.40/dist/vue.global.prod.js | MIT; see `licenses/vue-LICENSE.txt` |

```bash
curl -sL -o vue.global.prod.js https://unpkg.com/vue@3.5.40/dist/vue.global.prod.js
curl -sL -o vue-LICENSE.txt https://unpkg.com/vue@3.5.40/LICENSE
```

SHA-256:

```text
9e0039a3f6ed0e85308e24d737447f1af6af83d229d69e1267a32b29bc2a1337  vue.global.prod.js
```

## Fonts

Only weights used by the UI are included:

- Archivo 700 and 800 for display text;
- IBM Plex Sans 400 and 500 for body text;
- IBM Plex Mono 400 and 500 for IDs, categories, timestamps, and status data.

Only the Latin subsets are bundled. These font families do not contain CJK
glyphs, so Traditional Chinese text falls back to the system sans-serif font.

| Family | Google Fonts version | Files |
|---|---|---|
| Archivo | v25 | `fonts/archivo-700.woff2`, `fonts/archivo-800.woff2` |
| IBM Plex Sans | v23 | `fonts/ibm-plex-sans-400.woff2`, `fonts/ibm-plex-sans-500.woff2` |
| IBM Plex Mono | v20 | `fonts/ibm-plex-mono-400.woff2`, `fonts/ibm-plex-mono-500.woff2` |

All three families use the SIL Open Font License 1.1. License copies are stored
under `licenses/`:

- `archivo-OFL.txt`
- `ibm-plex-sans-OFL.txt`
- `ibm-plex-mono-OFL.txt`

Font files were selected from the `/* latin */` section returned by the Google
Fonts CSS API with a desktop Chrome user agent.

```bash
curl -s -A "Mozilla/5.0 ... Chrome/120.0.0.0 Safari/537.36" \
  "https://fonts.googleapis.com/css2?family=Archivo:wght@700&display=swap"
```

License sources:

```text
https://raw.githubusercontent.com/google/fonts/main/ofl/archivo/OFL.txt
https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsans/OFL.txt
https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/OFL.txt
```

## Updating assets

Pin exact versions; do not use floating selectors such as `latest` or `@3`.
After replacing an asset, update this file, the relevant paths in `index.html`,
and the `@font-face` declarations in `fonts.css`.

# Frontend browser regression tool

## Purpose

`check.mjs` drives a local Chrome or Chromium instance through the Chrome DevTools
Protocol. It uses only Node.js built-in modules: there is no `package.json`, npm
install, or frontend build step. Run it manually when a change needs real browser
rendering or interaction.

## Test boundary

The script requires a locally installed browser and a remote-debugging port, so it
is not part of `./mvnw test` or CI. Server-side checks remain in
`FrontendStaticAssetsTest`, `SecurityHeadersFilterTest`, and
`OperationalSafetyConfigurationTest`.

## When to run it

- major layout, breakpoint, or drawer focus changes in `app.js`;
- changes to CSP in `SecurityHeadersFilter`;
- SSE reconnection changes in `EventStreamController`, `SseEmitterRegistry`, or
  the frontend `EventSource` handling;
- a pre-release browser check.

For smaller changes, use `docs/frontend-regression-checklist.md` and select only
the relevant checks.

## Run

Requirements: Chrome or Chromium and Node.js 18 or newer.

```bash
# Use an isolated development port and database. Never use 8080 or data/board.
BOARD_PORT=8091 BOARD_DB_URL=jdbc:h2:file:./data/dev-qa-next4 \
  java -jar target/ai-project-board-backend-*.jar &

# Start Chrome with a CDP port.
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --headless=new --remote-debugging-port=9222 --no-first-run about:blank &

# Run the checks.
node scripts/frontend-regression/check.mjs --base-url http://127.0.0.1:8091
```

The script prints PASS/FAIL for each check and exits non-zero on failure.

## Coverage

| Check | Server-side JUnit | Manual browser script | Human checklist |
| --- | --- | --- | --- |
| CSP headers | `SecurityHeadersFilterTest` | - | - |
| Same-origin vendor assets | `OperationalSafetyConfigurationTest`, `FrontendStaticAssetsTest` | CSP violation listener | - |
| API and vendor availability | `FrontendStaticAssetsTest` | - | - |
| SSE content type and access | `SecurityHeadersFilterTest` | Reconnection state | Restart server manually |
| Responsive horizontal overflow | - | `checkNoHorizontalOverflow()` | Review extreme content |
| Browser console errors | - | `checkNoConsoleErrors()` | - |
| Escape closes drawer and restores focus | - | `checkEscapeReturnsFocus()` | Confirm focus order |
| Filter URL round trip | - | `checkFilterUrlRoundTrip()` | - |
| Initial API call counts | - | `checkApiCallCounts()` | - |
| Dependency graph quality | - | - | Visual review |
| i18n dictionary and placeholders | `I18nDictionaryTest` | - | Copy review |
| Language switching and persistence | - | `checkManualSwitchUpdatesHtmlLangAndPersists()` | - |
| Unsupported locale fallback | - | `checkUnsupportedStoredLocaleFallsBackToDefault()` | - |
| Browser-language detection | - | `checkBrowserLanguageDetectionWithoutStoredPreference()` | - |
| Raw translation keys | - | `checkNoRawKeyFallbackVisibleInRenderedDom()` | Visual scan |
| Interpolation output | - | `checkInterpolationRendersActualValues()` | Native notification review |

## Limits

- DAG layout quality and extreme-content rendering still require visual review.
- The script runs only when invoked manually.

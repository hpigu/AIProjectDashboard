#!/usr/bin/env node
// 前端手動回歸驗證工具（#140）。純 Node.js 內建模組，不使用任何 npm 套件，
// 不進 Maven 生命週期、不是 CI 必要條件。見同目錄 README.md。
//
// 用法：
//   node scripts/frontend-regression/check.mjs --base-url http://127.0.0.1:8091 [--cdp-port 9222]
//
// 前置需求：
//   1. 看板已用開發用埠號/資料庫啟動（見 README.md）
//   2. Chrome/Chromium 已以 --remote-debugging-port=<cdp-port> 啟動
//
// 設計原則：只用 Node 內建的 http/WebSocket client（Node 22 內建 WebSocket 全域
// 物件）驅動 Chrome DevTools Protocol，不引入 puppeteer/playwright 等 npm 套件。

import { setTimeout as sleep } from 'node:timers/promises';

const args = parseArgs(process.argv.slice(2));
const baseUrl = args['base-url'] || 'http://127.0.0.1:8091';
const cdpPort = args['cdp-port'] || '9222';

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const key = argv[i].slice(2);
      const value = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
      out[key] = value;
    }
  }
  return out;
}

async function newCdpSession(cdpHttpPort) {
  const res = await fetch(`http://127.0.0.1:${cdpHttpPort}/json/new?about:blank`, { method: 'PUT' });
  if (!res.ok) {
    throw new Error(`無法透過 CDP 開新分頁（${res.status}）。請確認 Chrome 已以 --remote-debugging-port=${cdpHttpPort} 啟動。`);
  }
  const target = await res.json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });

  let nextId = 1;
  const pending = new Map();
  const eventLog = [];

  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      if (msg.error) reject(new Error(JSON.stringify(msg.error)));
      else resolve(msg.result);
    } else if (msg.method) {
      eventLog.push(msg);
    }
  });

  function send(method, params = {}) {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
  }

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Log.enable');
  await send('Security.enable');

  return {
    targetId: target.id,
    send,
    eventLog,
    close: () => ws.close(),
    async navigate(url) {
      eventLog.length = 0;
      await send('Page.navigate', { url });
      await waitForEvent(eventLog, 'Page.loadEventFired', 15000);
    },
    async eval(expression) {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (result.exceptionDetails) {
        throw new Error(`前端腳本執行錯誤: ${JSON.stringify(result.exceptionDetails)}`);
      }
      return result.result.value;
    },
    async setViewport(width, height) {
      await send('Emulation.setDeviceMetricsOverride', {
        width, height, deviceScaleFactor: 1, mobile: width < 768,
      });
    },
  };
}

async function waitForEvent(eventLog, method, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (eventLog.some((e) => e.method === method)) return;
    await sleep(50);
  }
  throw new Error(`等待事件逾時: ${method}`);
}

const results = [];

async function check(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`PASS  ${name}`);
  } catch (err) {
    results.push({ name, ok: false, error: err.message });
    console.log(`FAIL  ${name}\n      ${err.message}`);
  }
}

async function checkNoHorizontalOverflow(session) {
  const sizes = [
    ['desktop 1440x900', 1440, 900],
    ['tablet 768x1024', 768, 1024],
    ['mobile 375x812', 375, 812],
  ];
  for (const [label, w, h] of sizes) {
    await session.setViewport(w, h);
    await session.navigate(`${baseUrl}/index.html`);
    const overflow = await session.eval(
      'document.documentElement.scrollWidth > document.documentElement.clientWidth'
    );
    if (overflow) {
      throw new Error(`${label} 發生水平溢出（scrollWidth > clientWidth）`);
    }
  }
}

async function checkNoConsoleErrors(session) {
  await session.setViewport(1440, 900);
  session.eventLog.length = 0;
  await session.navigate(`${baseUrl}/index.html`);
  await sleep(1000); // 讓非同步的初始 API 呼叫與渲染完成
  const errors = session.eventLog.filter(
    (e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error'
  );
  const runtimeExceptions = session.eventLog.filter((e) => e.method === 'Runtime.exceptionThrown');
  if (errors.length > 0 || runtimeExceptions.length > 0) {
    throw new Error(
      `偵測到 console error 或未捕捉例外: ${JSON.stringify([...errors, ...runtimeExceptions].slice(0, 3))}`
    );
  }
}

async function checkNoCspViolations(session) {
  await session.setViewport(1440, 900);
  session.eventLog.length = 0;
  await session.navigate(`${baseUrl}/index.html`);
  await sleep(1000);
  const violations = session.eventLog.filter(
    (e) => e.method === 'Log.entryAdded' && /Content Security Policy/i.test(e.params.entry.text || '')
  );
  if (violations.length > 0) {
    throw new Error(`偵測到 CSP violation: ${JSON.stringify(violations)}`);
  }
}

async function checkNoCrossOriginRequests(session) {
  await session.setViewport(1440, 900);
  session.eventLog.length = 0;
  await session.navigate(`${baseUrl}/index.html`);
  await sleep(1000);
  const requests = session.eventLog.filter((e) => e.method === 'Network.requestWillBeSent');
  const base = new URL(baseUrl);
  const crossOrigin = requests.filter((e) => {
    try {
      const u = new URL(e.params.request.url);
      return u.origin !== base.origin && u.protocol.startsWith('http');
    } catch {
      return false;
    }
  });
  if (crossOrigin.length > 0) {
    throw new Error(`偵測到跨 origin 請求: ${crossOrigin.map((e) => e.params.request.url).join(', ')}`);
  }
}

// ---- i18n（#145） ----
// 以下檢查需要真的執行 window.__i18n / localStorage / navigator，JUnit 端的
// I18nDictionaryTest 只能靜態掃描原始碼字串，無法驗證這些「執行期行為」。
// 涵蓋範圍見 README.md 對照表。

async function checkManualSwitchUpdatesHtmlLangAndPersists(session) {
  await session.setViewport(1440, 900);
  await session.eval(`window.localStorage.removeItem('board.locale')`);
  await session.navigate(`${baseUrl}/index.html`);

  const initialLocale = await session.eval('window.__i18n.state.locale');
  const other = initialLocale === 'en' ? 'zh-TW' : 'en';

  await session.eval(`window.__i18n.setLocale(${JSON.stringify(other)})`);
  const htmlLangAfterSwitch = await session.eval('document.documentElement.getAttribute("lang")');
  const expectedLang = other === 'en' ? 'en' : 'zh-Hant-TW';
  if (htmlLangAfterSwitch !== expectedLang) {
    throw new Error(`切換到 ${other} 後 html lang 應為 ${expectedLang}，實際為 ${htmlLangAfterSwitch}`);
  }

  const stored = await session.eval(`window.localStorage.getItem('board.locale')`);
  if (stored !== other) {
    throw new Error(`切換語言後 localStorage['board.locale'] 應為 ${other}，實際為 ${stored}`);
  }

  // reload 後應保留剛才手動切換的語言（不是重新偵測瀏覽器語言）。
  await session.navigate(`${baseUrl}/index.html`);
  const localeAfterReload = await session.eval('window.__i18n.state.locale');
  if (localeAfterReload !== other) {
    throw new Error(`reload 後語言應保留為 ${other}，實際為 ${localeAfterReload}`);
  }
  const htmlLangAfterReload = await session.eval('document.documentElement.getAttribute("lang")');
  if (htmlLangAfterReload !== expectedLang) {
    throw new Error(`reload 後 html lang 應維持 ${expectedLang}，實際為 ${htmlLangAfterReload}`);
  }
}

async function checkUnsupportedStoredLocaleFallsBackToDefault(session) {
  await session.setViewport(1440, 900);
  await session.navigate(`${baseUrl}/index.html`);
  // 塞入一個不支援的 locale（例如舊版本存過的值、或人為竄改），reload 後
  // detectLocale() 必須忽略它並 fallback 到 DEFAULT_LOCALE（'zh-TW'）。
  await session.eval(`window.localStorage.setItem('board.locale', 'fr-FR')`);
  await session.navigate(`${baseUrl}/index.html`);
  const locale = await session.eval('window.__i18n.state.locale');
  if (locale !== 'zh-TW') {
    throw new Error(`不支援的 stored locale 'fr-FR' 應 fallback 到 zh-TW，實際為 ${locale}`);
  }
  const htmlLang = await session.eval('document.documentElement.getAttribute("lang")');
  if (htmlLang !== 'zh-Hant-TW') {
    throw new Error(`fallback 後 html lang 應為 zh-Hant-TW，實際為 ${htmlLang}`);
  }
  await session.eval(`window.localStorage.removeItem('board.locale')`);
}

async function checkBrowserLanguageDetectionWithoutStoredPreference(session) {
  await session.setViewport(1440, 900);
  await session.eval(`window.localStorage.removeItem('board.locale')`);
  // Emulation.setUserAgentOverride 可覆寫 navigator.language(s)，不需要真的
  // 改系統/瀏覽器語言設定即可驗證偵測邏輯。
  await session.send('Emulation.setUserAgentOverride', {
    userAgent: await session.eval('navigator.userAgent'),
    acceptLanguage: 'en-US,en;q=0.9',
  });
  await session.navigate(`${baseUrl}/index.html`);
  const locale = await session.eval('window.__i18n.state.locale');
  if (locale !== 'en') {
    throw new Error(`Accept-Language 為 en-US 且未存過偏好時應偵測為 en，實際為 ${locale}`);
  }

  await session.send('Emulation.setUserAgentOverride', {
    userAgent: await session.eval('navigator.userAgent'),
    acceptLanguage: 'zh-CN,zh;q=0.9',
  });
  await session.eval(`window.localStorage.removeItem('board.locale')`);
  await session.navigate(`${baseUrl}/index.html`);
  const zhCnLocale = await session.eval('window.__i18n.state.locale');
  if (zhCnLocale !== 'zh-TW') {
    throw new Error(`zh-CN（非 zh-TW/zh-Hant 變體）應 fallback 到 zh-TW，實際為 ${zhCnLocale}`);
  }
  await session.eval(`window.localStorage.removeItem('board.locale')`);
}

async function checkNoRawKeyFallbackVisibleInRenderedDom(session) {
  // ⚠ 開頭的 raw key 標記若出現在實際渲染的畫面文字中，代表有 key 對不上
  // （字典缺 key，或呼叫端打錯 key）。JUnit 的靜態掃描已比對過所有「字面值」
  // 呼叫，這裡额外用真實渲染結果雙重確認執行期沒有被遺漏的動態組出 key
  // （例如 blockerReason 的 key 是後端傳來的自由值組出來的，見 app.js
  // formatBlockerReason()）。
  await session.setViewport(1440, 900);
  for (const locale of ['zh-TW', 'en']) {
    await session.eval(`window.localStorage.setItem('board.locale', ${JSON.stringify(locale)})`);
    await session.navigate(`${baseUrl}/index.html`);
    await sleep(500);
    const bodyText = await session.eval('document.body.innerText');
    if (/⚠[\w.-]/.test(bodyText)) {
      throw new Error(`locale=${locale} 的畫面渲染結果中偵測到 raw key fallback 標記（⚠key），代表有 key 缺漏: ${bodyText.match(/⚠[\w.-]+/g)}`);
    }
  }
  await session.eval(`window.localStorage.removeItem('board.locale')`);
}

async function checkInterpolationRendersActualValues(session) {
  // conn.connected / conn.reconnecting 不含插值，改驗證有插值語法的
  // relTime 系列在渲染後不殘留 {n} 字面樣板（透過 t() 直接呼叫驗證，
  // 不依賴特定資料狀態）。
  await session.setViewport(1440, 900);
  await session.navigate(`${baseUrl}/index.html`);
  const rendered = await session.eval(`window.__i18n.t('relTime.secondsAgo', { n: 42 })`);
  if (rendered !== '42 秒前' && rendered !== '42s ago') {
    throw new Error(`插值結果不符預期，實際為: ${rendered}`);
  }
  if (rendered.includes('{n}')) {
    throw new Error(`插值後不應殘留 {n} 樣板字面值，實際為: ${rendered}`);
  }
}

async function main() {
  console.log(`目標: ${baseUrl}（CDP port ${cdpPort}）\n`);
  const session = await newCdpSession(cdpPort);
  try {
    await check('RWD 三尺寸無水平溢出', () => checkNoHorizontalOverflow(session));
    await check('console 無錯誤/未捕捉例外', () => checkNoConsoleErrors(session));
    await check('無 CSP violation', () => checkNoCspViolations(session));
    await check('零跨 origin 請求', () => checkNoCrossOriginRequests(session));
    await check('i18n: 手動切換更新 html lang 並於 reload 後保留', () => checkManualSwitchUpdatesHtmlLangAndPersists(session));
    await check('i18n: 不支援的 stored locale fallback 到 zh-TW', () => checkUnsupportedStoredLocaleFallsBackToDefault(session));
    await check('i18n: 無 stored 偏好時依瀏覽器語言偵測（含 zh-CN fallback）', () => checkBrowserLanguageDetectionWithoutStoredPreference(session));
    await check('i18n: 兩語系渲染結果皆無 raw key fallback（⚠key）', () => checkNoRawKeyFallbackVisibleInRenderedDom(session));
    await check('i18n: 插值正確渲染、不殘留樣板字面值', () => checkInterpolationRendersActualValues(session));
  } finally {
    session.close();
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} 通過`);
  if (failed.length > 0) {
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error('腳本執行失敗:', err);
  process.exitCode = 1;
});

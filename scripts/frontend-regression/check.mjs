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

async function main() {
  console.log(`目標: ${baseUrl}（CDP port ${cdpPort}）\n`);
  const session = await newCdpSession(cdpPort);
  try {
    await check('RWD 三尺寸無水平溢出', () => checkNoHorizontalOverflow(session));
    await check('console 無錯誤/未捕捉例外', () => checkNoConsoleErrors(session));
    await check('無 CSP violation', () => checkNoCspViolations(session));
    await check('零跨 origin 請求', () => checkNoCrossOriginRequests(session));
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

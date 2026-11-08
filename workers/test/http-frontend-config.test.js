import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../src/main/resources/static/app.js', import.meta.url), 'utf8');

function frontend({ origin = 'http://127.0.0.1:8091', fetch: fetchResponse } = {}) {
  const copied = [];
  const context = vm.createContext({
    URL, URLSearchParams, AbortController, Headers, Response,
    fetch: fetchResponse,
    setTimeout: (...args) => { const timer = setTimeout(...args); timer.unref(); return timer; },
    clearTimeout,
    navigator: { clipboard: { writeText: async value => copied.push(value) } },
    window: {
      location: { origin, search: '', href: origin + '/' },
      __i18n: { t: (key, params) => key + (params ? ':' + params.status : ''), state: { locale: 'en' }, SUPPORTED_LOCALES: [] },
    },
    Vue: {
      ref: value => ({ value }), computed: value => value,
      createApp: () => ({ config: { globalProperties: {} }, mount() {} }),
    },
  });
  vm.runInContext(source, context);
  const evaluate = code => vm.runInContext(code, context);
  const component = evaluate('LevelOne');
  const instance = { copyState: 'idle', copyTimer: null };
  Object.defineProperty(instance, 'mcpEndpoint', { get: () => component.computed.mcpEndpoint() });
  return { evaluate, copied, instance, component };
}

test('Java local onboarding keeps original endpoint and immediate copy without extra config request', async () => {
  const requests = [];
  const app = frontend({ fetch: async url => { requests.push(url); return Response.json([]); } });
  assert.equal(app.instance.mcpEndpoint, 'http://127.0.0.1:8091/mcp');
  await app.component.methods.copyEndpoint.call(app.instance);
  assert.deepEqual(app.copied, ['http://127.0.0.1:8091/mcp']);
  await app.evaluate("fetchJson('/api/projects?status=ALL')");
  assert.deepEqual(requests, ['/api/projects?status=ALL']);
  assert.equal(app.instance.mcpEndpoint, 'http://127.0.0.1:8091/mcp');
});

test('Workers onboarding suppresses wrong URL during one shared config fetch and copies validated endpoint', async () => {
  const requests = [];
  let resolveConfig;
  const app = frontend({ origin: 'https://board.example.com', fetch: async (url, options) => {
    requests.push(url);
    if (url === '/api/runtime-config') {
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.redirect, 'error');
      assert.equal(options.cache, 'no-store');
      return new Promise(resolve => { resolveConfig = resolve; });
    }
    return Response.json([], { headers: { 'X-Board-Runtime': 'workers' } });
  } });
  await app.evaluate("fetchJson('/api/projects')");
  assert.equal(app.instance.mcpEndpoint, '');
  await app.component.methods.copyEndpoint.call(app.instance);
  assert.equal(app.copied.length, 0);
  await app.evaluate("fetchJson('/api/projects?status=ALL')");
  assert.equal(requests.filter(url => url === '/api/runtime-config').length, 1);
  resolveConfig(Response.json({ mcpEndpoint: 'https://mcp.example.com/mcp' }));
  await app.evaluate('runtimeMcpConfigRequest');
  assert.equal(app.instance.mcpEndpoint, 'https://mcp.example.com/mcp');
  await app.component.methods.copyEndpoint.call(app.instance);
  assert.deepEqual(app.copied, ['https://mcp.example.com/mcp']);
  assert.ok(app.component.template.includes(':disabled="!mcpEndpoint"'));
});

test('Workers config failure or invalid URL never falls back to web /mcp or cross-origin fetch', async () => {
  for (const value of [null, '/mcp', 'http://mcp.example.com/mcp',
    'https://board.example.com/mcp', 'https://user:private@mcp.example.com/mcp',
    'https://mcp.example.com/mcp?private', 'https://mcp.example.com/mcp#',
    'javascript:alert(1)', 'https://127.0.0.1/mcp', 'https://mcp.localhost/mcp']) {
    const requests = [];
    const app = frontend({ origin: 'https://board.example.com', fetch: async url => {
      requests.push(url);
      return url === '/api/runtime-config'
        ? value === null ? Response.json({ error: 'unauthorized' }, { status: 401 }) : Response.json({ mcpEndpoint: value })
        : Response.json([], { headers: { 'X-Board-Runtime': 'workers' } });
    } });
    await app.evaluate("fetchJson('/api/projects')");
    await app.evaluate('runtimeMcpConfigRequest');
    assert.equal(app.instance.mcpEndpoint, '');
    await app.component.methods.copyEndpoint.call(app.instance);
    assert.equal(app.copied.length, 0);
    assert.equal(requests.every(url => url.startsWith('/')), true);
    const error = app.component.computed.mcpEndpointError.call({ t: (key, params) => `${key}:${params.status}` });
    assert.match(error, /^error.apiError:(401|503)$/);
  }
});

test('runtime marker from other API paths cannot override the local endpoint', async () => {
  const requests = [];
  const app = frontend({ fetch: async url => { requests.push(url); return Response.json([], { headers: { 'X-Board-Runtime': 'workers' } }); } });
  await app.evaluate("fetchJson('/api/roles')");
  assert.equal(app.instance.mcpEndpoint, 'http://127.0.0.1:8091/mcp');
  assert.deepEqual(requests, ['/api/roles']);
});

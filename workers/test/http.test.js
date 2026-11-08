import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandler, eventStream } from '../src/index.js';

const trusted = async () => ({ email: 'owner@example.com' });
const mockService = {
  latestEventId: async () => 0,
  listProjects: async params => [{ id: 1, query: params.get('q') }],
  listRoles: async projectName => [{ name: 'qa', projectName }],
  getBoard: async id => ({ id }), getDependencies: async id => ({ projectId: id }),
  getTaskDetail: async (projectId, taskId) => ({ projectId, taskId }),
  getHistory: async () => [], execute: async () => 'done', eventsAfter: async () => [],
};

test('all web routes/assets/health authenticate before data access; mode boundaries are strict', async () => {
  let reads = 0;
  const handler = createHandler({ serviceFactory: () => { reads++; return mockService; } });
  for (const path of ['/', '/vendor/vue.global.prod.js', '/api/health', '/api/health/live', '/api/health/ready', '/api/projects', '/api/events', '/api/runtime-config']) {
    const response = await handler(new Request('https://board.example.com' + path), { BOARD_MODE: 'web' });
    assert.equal(response.status, 503);
    assert.equal(response.headers.get('X-Frame-Options'), 'DENY');
    assert.equal(response.headers.get('Cache-Control'), 'no-store');
  }
  assert.equal(reads, 0);
  const allowed = createHandler({ authenticate: trusted, serviceFactory: () => mockService });
  for (const method of ['POST', 'PUT', 'DELETE']) assert.equal((await allowed(new Request('https://board.example.com/api/projects', { method }), { BOARD_MODE: 'web' })).status, 403);
  assert.equal((await allowed(new Request('https://board.example.com/mcp'), { BOARD_MODE: 'web' })).status, 403);
  assert.equal((await allowed(new Request('https://mcp.example.com/api/projects'), { BOARD_MODE: 'mcp' })).status, 403);
  assert.equal((await allowed(new Request('https://mcp.example.com/mcp', { headers: { Origin: 'https://board.example.com' } }), { BOARD_MODE: 'mcp' })).status, 403);
  assert.equal((await allowed(new Request('https://mcp.example.com/mcp'), { BOARD_MODE: 'mcp' })).status, 405);
  assert.equal((await allowed(new Request('https://board.example.com/'), {})).status, 503);
});

test('authenticated web runtime config returns only the validated fixed MCP URL, independent of D1', async () => {
  const handler = createHandler({ authenticate: trusted, serviceFactory() { throw new Error('Config must not access D1'); } });
  const env = { BOARD_MODE: 'web', MCP_PUBLIC_URL: 'https://mcp.example.com/mcp' };
  const response = await handler(new Request('https://board.example.com/api/runtime-config?mcpEndpoint=https://attacker.example/mcp', {
    headers: { 'X-Forwarded-Host': 'attacker.example' },
  }), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { mcpEndpoint: env.MCP_PUBLIC_URL });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  const head = await handler(new Request('https://board.example.com/api/runtime-config', { method: 'HEAD' }), env);
  assert.equal(head.status, 200);
  assert.equal(await head.text(), '');
  assert.equal((await handler(new Request('https://board.example.com/api/runtime-config', { method: 'POST' }), env)).status, 403);
  assert.equal((await handler(new Request('https://mcp.example.com/api/runtime-config'), { ...env, BOARD_MODE: 'mcp' })).status, 403);
  const unauthenticated = createHandler();
  const rejected = await unauthenticated(new Request('https://board.example.com/api/runtime-config'), {
    ...env, CLOUDFLARE_ACCESS_TEAM: 'test-team', CLOUDFLARE_ACCESS_AUD: 'aud', OWNER_EMAIL: 'owner@example.com',
  });
  assert.equal(rejected.status, 401);
  assert.equal((await rejected.text()).includes(env.MCP_PUBLIC_URL), false);
});

test('runtime config fails closed for missing, unsafe, same-origin, or placeholder endpoints', async () => {
  const handler = createHandler({ authenticate: trusted });
  for (const value of [undefined, '', 'https://REPLACE_MCP_HOST/mcp', '/mcp',
    'http://mcp.example.com/mcp', 'https://board.example.com/mcp',
    'https://user:private@mcp.example.com/mcp', 'https://mcp.example.com:8443/mcp',
    'https://mcp.example.com/mcp?token=private', 'https://mcp.example.com/mcp#fragment',
    'https://mcp.example.com/mcp?', 'https://mcp.example.com/mcp#',
    'https://mcp.example.com/another-path', 'https://127.0.0.1/mcp',
    'https://mcp.localhost/mcp', 'https://mcp.example.com/\nmcp']) {
    const response = await handler(new Request('https://board.example.com/api/runtime-config'), { BOARD_MODE: 'web', MCP_PUBLIC_URL: value });
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'runtime_configuration_unavailable' });
  }
  const projects = createHandler({ authenticate: trusted, serviceFactory: () => mockService });
  const response = await projects(new Request('https://board.example.com/api/projects'), { BOARD_MODE: 'web' });
  assert.equal(response.headers.get('X-Board-Runtime'), 'workers');
});

test('web endpoints forward Java-compatible paths and HEAD strips bodies; assets retain security headers', async () => {
  const handler = createHandler({ authenticate: trusted, serviceFactory: () => mockService });
  const env = { BOARD_MODE: 'web', ASSETS: { fetch: async () => new Response('asset') } };
  const call = path => handler(new Request('https://board.example.com' + path), env);
  assert.equal((await (await call('/api/projects?q=search')).json())[0].query, 'search');
  assert.equal((await (await call('/api/projects/2/board')).json()).id, 2);
  assert.equal((await (await call('/api/projects/2/dependencies')).json()).projectId, 2);
  assert.equal((await (await call('/api/projects/2/tasks/3')).json()).taskId, 3);
  assert.deepEqual(await (await call('/api/projects/2/tasks/3/history')).json(), []);
  assert.equal((await (await call('/api/roles?projectName=example')).json())[0].projectName, 'example');
  assert.equal((await call('/api/unknown')).status, 404);
  const asset = await call('/vendor/example.js');
  assert.equal(await asset.text(), 'asset');
  assert.ok(asset.headers.get('Content-Security-Policy').includes("script-src 'self' 'unsafe-eval'"));
  const head = await handler(new Request('https://board.example.com/api/projects', { method: 'HEAD' }), env);
  assert.equal(await head.text(), '');
  assert.equal((await call('/api/health/ready')).status, 200);
});

test('SSE emits initial heartbeat and committed event payloads, then closes and stops polling', async () => {
  let calls = 0;
  const response = await eventStream(new Request('https://board.example.com/api/events'), {
    latestEventId: async () => 7,
    eventsAfter: async cursor => {
      calls++;
      return cursor === 7 ? [{ id: 8, type: 'tasks.created', payload: { projectId: 2, count: 1 } }] : [];
    },
  }, { pollMs: 5, heartbeatMs: 10, lifetimeMs: 35 });
  const body = await response.text();
  assert.ok(body.startsWith('event: heartbeat\ndata: {}\n\n'));
  assert.ok(body.includes('id: 8\nevent: tasks.created\ndata: {"projectId":2,"count":1}'));
  const finished = calls;
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(calls, finished);
});

test('SSE abort and reader cancellation clear polling timers', async () => {
  for (const abort of [false, true]) {
    let calls = 0;
    const controller = new AbortController();
    const response = await eventStream(new Request('https://board.example.com/api/events', { signal: controller.signal }), {
      latestEventId: async () => 0, eventsAfter: async () => { calls++; return []; },
    }, { pollMs: 5 });
    const reader = response.body.getReader();
    await reader.read();
    if (abort) controller.abort(); else await reader.cancel();
    await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(calls, 0);
  }
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { handleMcp } from '../src/mcp.js';
import { createHandler } from '../src/index.js';

function request(method, params = {}) {
  return new Request('https://mcp.example.test/mcp', { method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
}
const writes = {
  create_project: { name: 'new project' },
  create_tasks: { projectId: 1, tasks: [{ title: 'task', description: '', category: 'TEST', dependsOnIndexes: [], dependsOnTaskIds: [] }] },
  claim_next_task: { projectName: 'project', category: 'TEST', assignee: 'worker' },
  update_task_status: { taskId: 1, status: 'TODO' },
  reset_task_claim: { taskId: 1 },
  block_task: { taskId: 1, reasonType: 'OTHER', detail: 'blocked' },
  complete_task: { taskId: 1, summary: 'done', verificationResults: [{ name: 'check', result: 'PASSED', detail: 'ok' }] },
  update_task_details: { taskId: 1, expectedVersion: 0, title: 'new title' },
  set_task_dependencies: { taskId: 1, prerequisiteTaskIds: [], expectedVersion: 0 },
  archive_project: { projectName: 'project', reason: 'maintenance test' },
  restore_project: { projectName: 'project', reason: 'maintenance test' },
  upsert_role: { name: 'qa', instructions: 'test' },
};

test('maintenance pauses every existing write tool before any service call', async () => {
  let calls = 0;
  for (const [name, args] of Object.entries(writes)) {
    const response = await handleMcp(request('tools/call', { name, arguments: args }),
      { execute() { calls++; throw new Error('Must not reach service'); } }, { maintenance: true });
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.equal(body.result?.isError, true, name);
    assert.match(body.result.content[0].text, /writes are paused/);
  }
  assert.equal(calls, 0);
});

test('maintenance preserves discovery, ping and explicit read-only operations', async () => {
  const seen = [];
  const service = { execute: async name => { seen.push(name); return { ok: true }; } };
  for (const [name, args] of Object.entries({ list_tasks: { projectId: 1 }, list_roles: {},
    get_role: { name: 'qa' }, preview_archive_project: { projectName: 'project' } })) {
    const body = await (await handleMcp(request('tools/call', { name, arguments: args }), service, { maintenance: true })).json();
    assert.notEqual(body.result.isError, true);
  }
  assert.equal(seen.length, 4);
  const discovery = await (await handleMcp(request('tools/list'), service, { maintenance: true })).json();
  assert.equal(discovery.result.tools.length, Object.keys(writes).length + seen.length);
  assert.deepEqual((await (await handleMcp(request('ping'), service, { maintenance: true })).json()).result, {});
});

test('normal operation resumes without credential replacement', async () => {
  let calls = 0;
  const service = { execute: async () => { calls++; return { ok: true }; } };
  for (const options of [undefined, { maintenance: false }]) {
    const body = await (await handleMcp(request('tools/call', { name: 'create_project', arguments: { name: 'project' } }), service, options)).json();
    assert.notEqual(body.result.isError, true);
  }
  assert.equal(calls, 2);
});

test('HTTP maintenance switch fails closed on malformed values and remains behind authentication', async () => {
  let calls = 0;
  const service = { execute: async () => { calls++; return { ok: true }; } };
  const handler = createHandler({ authenticate: async () => ({ deviceId: 'test' }), serviceFactory: () => service });
  for (const value of ['true', '', 'FALSE', 'typo', false]) {
    const response = await handler(request('tools/call', { name: 'create_project', arguments: { name: 'project' } }),
      { BOARD_MODE: 'mcp', BOARD_MAINTENANCE: value });
    assert.equal((await response.json()).result.isError, true);
  }
  assert.equal(calls, 0);
  for (const value of [undefined, 'false']) {
    const response = await handler(request('tools/call', { name: 'create_project', arguments: { name: 'project' } }),
      { BOARD_MODE: 'mcp', BOARD_MAINTENANCE: value });
    assert.notEqual((await response.json()).result.isError, true);
  }
  assert.equal(calls, 2);
  const protectedHandler = createHandler({ serviceFactory: () => { throw new Error('Must authenticate first'); } });
  const denied = await protectedHandler(request('tools/list'), { BOARD_MODE: 'mcp', BOARD_MAINTENANCE: 'true' });
  assert.equal(denied.status, 503);
});

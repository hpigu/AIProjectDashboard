import test from 'node:test';
import assert from 'node:assert/strict';
import { handleMcp } from '../src/mcp.js';
import { tools } from '../src/tools.js';

const service = { execute: async (name, args) => ({ action: name, name: args.name }) };
function post(method, params = {}, headers = {}, id = 1) {
  const message = { jsonrpc: '2.0', method, params };
  if (id !== undefined) message.id = id;
  return new Request('https://mcp.example.com/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers }, body: JSON.stringify(message) });
}

test('stateless Streamable HTTP negotiates versions, lists all tools, returns envelopes', async () => {
  for (const version of ['2024-11-05', '2025-03-26', '2025-06-18', 'future-version']) {
    const response = await handleMcp(post('initialize', { protocolVersion: version, capabilities: {}, clientInfo: { name: 'test', version: '1' } }), service);
    const { result } = await response.json();
    assert.equal(result.protocolVersion, version === '2025-03-26' ? version : '2025-06-18');
    assert.equal(response.headers.has('Mcp-Session-Id'), false);
    assert.equal(result.capabilities.tools.listChanged, false);
  }
  const listed = await (await handleMcp(post('tools/list'), service)).json();
  assert.equal(listed.result.tools.length, 22);
  const complete = tools.find(tool => tool.name === 'complete_task');
  assert.ok(complete.inputSchema.properties.verificationResults.items.properties.detail);
  const result = await (await handleMcp(post('tools/call', { name: 'create_project', arguments: { name: 'smoke' } }, { 'MCP-Protocol-Version': '2025-06-18' }), service, { managementAuthorized: true })).json();
  assert.equal(result.result.structuredContent.action, 'create_project');
  assert.equal(JSON.parse(result.result.content[0].text).name, 'smoke');
  const notification = new Request('https://mcp.example.com/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' }, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
  assert.equal((await handleMcp(notification, service)).status, 202);
  for (const method of ['GET', 'DELETE']) assert.equal((await handleMcp(new Request('https://mcp.example.com/mcp', { method }), service)).status, 405);
});

test('protocol/content/size/schema errors are bounded and domain error text is private', async () => {
  assert.equal((await handleMcp(post('ping', {}, { 'MCP-Protocol-Version': 'unsupported' }), service)).status, 400);
  assert.equal((await handleMcp(post('ping', {}, { 'Content-Type': 'text/plain' }), service)).status, 415);
  assert.equal((await handleMcp(post('ping', {}, { Accept: 'application/json' }), service)).status, 406);
  const oversized = post('ping', { text: 'x'.repeat(65536) });
  assert.equal((await handleMcp(oversized, service)).status, 413);
  const badArgs = await (await handleMcp(post('tools/call', { name: 'create_project', arguments: { extra: true } }), service)).json();
  assert.equal(badArgs.error.code, -32602);
  const raw = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
  const failed = await (await handleMcp(post('tools/call', { name: 'get_role', arguments: { name: 'qa' } }), { execute() { throw new Error(raw); } })).json();
  assert.equal(failed.result.isError, true);
  assert.equal(JSON.stringify(failed).includes(raw), false);
});

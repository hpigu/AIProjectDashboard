import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('both local deployment configurations share D1 and enforce separate asset/auth boundaries', async () => {
  const read = async name => JSON.parse(await readFile(new URL('../' + name, import.meta.url), 'utf8'));
  const web = await read('wrangler.jsonc'), machine = await read('wrangler.mcp.jsonc');
  assert.equal(web.vars.BOARD_MODE, 'web');
  assert.equal(web.vars.MCP_PUBLIC_URL, 'https://REPLACE_MCP_HOST/mcp');
  assert.equal(machine.vars.MCP_PUBLIC_URL, undefined);
  assert.equal(machine.vars.BOARD_MODE, 'mcp');
  assert.equal(machine.vars.BOARD_MAINTENANCE, 'false');
  assert.notEqual(web.name, machine.name);
  assert.deepEqual(web.d1_databases, machine.d1_databases);
  assert.equal(web.assets.run_worker_first, true);
  assert.equal(web.assets.binding, 'ASSETS');
  assert.equal(machine.assets, undefined);
  for (const config of [web, machine]) {
    assert.equal(config.d1_databases[0].remote, false);
    assert.equal(config.preview_urls, false);
    assert.equal(config.observability.enabled, false);
    assert.equal(config.dev.ip, '127.0.0.1');
    assert.equal(config.compatibility_flags.includes('enable_request_signal'), true);
    assert.notEqual(config.dev.port, 8080);
    assert.equal(config.vars.MACHINE_TOKEN_HASHES, undefined);
    assert.equal(config.durable_objects, undefined);
    assert.equal(config.r2_buckets, undefined);
    assert.equal(config.limits, undefined);
  }
});

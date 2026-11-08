import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, realpath, stat, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { maintenance } from '../scripts/maintenance.mjs';

async function fixture(t, { changed = false, failed = false, deferred = true } = {}) {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'board-maintenance-')));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const repo = join(directory, 'repo'), root = join(repo, 'workers');
  await mkdir(root, { recursive: true });
  const common = { account_id: 'test-account', preview_urls: false, observability: { enabled: false },
    d1_databases: [{ binding: 'DB', database_id: 'test-db', database_name: 'test', remote: false }] };
  const web = { ...common, vars: { BOARD_MODE: 'web', CLOUDFLARE_ACCESS_TEAM: 'test-team',
    CLOUDFLARE_ACCESS_AUD: 'test-aud', OWNER_EMAIL: 'owner@example.test' },
    assets: { binding: 'ASSETS', run_worker_first: true } };
  const mcp = { ...common, vars: { BOARD_MODE: 'mcp', BOARD_MAINTENANCE: 'true' } };
  await writeFile(join(root, 'wrangler.production.web.jsonc'), JSON.stringify(web));
  await writeFile(join(root, 'wrangler.production.mcp.jsonc'), JSON.stringify(mcp));
  const calls = []; let snapshots = 0;
  const runner = async args => {
    calls.push(args);
    assert.equal(args.includes('--remote'), false);
    assert.equal(args.includes('--local'), true);
    if (args[1] === 'execute') {
      const revision = changed && ++snapshots > 1 ? 2 : 1;
      return JSON.stringify([{ success: true, results: [{ revision }] },
        { success: true, results: [{ name: 'task', sql: 'CREATE TABLE task(id INTEGER PRIMARY KEY)' }] },
        { success: true, results: [{ id: 1, name: '0001_init.sql', applied_at: '2026-01-01' }] }]);
    }
    assert.deepEqual(args.slice(0, 3), ['d1', 'export', 'DB']);
    if (failed) throw new Error('private fixture data must not surface');
    const output = args[args.indexOf('--output') + 1];
    await writeFile(output, args.includes('--no-data') ? 'CREATE TABLE task(id INTEGER PRIMARY KEY);\n'
      : (deferred ? 'PRAGMA defer_foreign_keys=TRUE;\n' : '') + 'INSERT INTO task VALUES(1);\n');
    return '';
  };
  return { directory, repo, root, runner, calls, out: join(directory, 'backup') };
}

test('split local backup is private, checksummed and accepted by unchanged-target preflight', async t => {
  const f = await fixture(t);
  const result = await maintenance(['backup', '--out', f.out], f);
  assert.equal(result.checks.backupValid, true);
  assert.equal((await stat(f.out)).mode & 0o777, 0o700);
  for (const name of ['schema.sql', 'data.sql', 'manifest.json']) assert.equal((await stat(join(f.out, name))).mode & 0o777, 0o600);
  const manifest = JSON.parse(await readFile(join(f.out, 'manifest.json')));
  assert.equal(manifest.valid, true);
  assert.equal(JSON.stringify(manifest).includes('owner@example.test'), false);
  assert.equal((await maintenance(['preflight-update', '--backup', f.out], f)).checks.backupChecksums, true);
  assert.ok(f.calls.every(args => !args.includes('--persist-to')));
  assert.ok(f.calls.filter(args => args[1] === 'export').every(args => args.includes('--no-data') || args.includes('--no-schema')));
  await writeFile(join(f.out, 'data.sql'), 'tampered', { mode: 0o600 });
  await assert.rejects(maintenance(['preflight-update', '--backup', f.out], f), /checksum/);
});

test('remote backup requires both explicit pause acknowledgement and maintenance config', async t => {
  const f = await fixture(t);
  await assert.rejects(maintenance(['backup', '--out', f.out, '--remote'], f), /acknowledgement/);
  const config = JSON.parse(await readFile(join(f.root, 'wrangler.production.mcp.jsonc')));
  config.vars.BOARD_MAINTENANCE = 'false';
  await writeFile(join(f.root, 'wrangler.production.mcp.jsonc'), JSON.stringify(config));
  await assert.rejects(maintenance(['backup', '--out', f.out, '--remote', '--ack-writes-paused'], f), /maintenance mode/);
  assert.equal(f.calls.length, 0);
});

test('changed database or incomplete export never creates a valid manifest', async t => {
  for (const options of [{ changed: true }, { failed: true }, { deferred: false }]) {
    const f = await fixture(t, options);
    await assert.rejects(maintenance(['backup', '--out', f.out], f));
    await assert.rejects(stat(join(f.out, 'manifest.json')), { code: 'ENOENT' });
    const names = f.calls.map(args => args[1]);
    assert.equal(names.includes('restore'), false);
  }
});

test('refuses repository output, symbolic links and overwrite without deleting prior files', async t => {
  const f = await fixture(t);
  await assert.rejects(maintenance(['backup', '--out', join(f.repo, 'backup')], f), /outside/);
  const target = join(f.directory, 'destination'); await mkdir(target);
  const link = join(f.directory, 'alias'); await symlink(target, link);
  await assert.rejects(maintenance(['backup', '--out', join(link, 'backup')], f), /Symbolic/);
  await mkdir(f.out); await writeFile(join(f.out, 'sentinel'), 'keep');
  await assert.rejects(maintenance(['backup', '--out', f.out], f), /overwrite/);
  assert.equal(await readFile(join(f.out, 'sentinel'), 'utf8'), 'keep');
  assert.equal(f.calls.length, 0);
});

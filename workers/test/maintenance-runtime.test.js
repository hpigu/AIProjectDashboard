import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, realpath, symlink, copyFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { maintenance, wranglerRunner } from '../scripts/maintenance.mjs';

test('official local Wrangler produces a private valid backup accepted by update preflight', { timeout: 90000 }, async t => {
  const parent = await realpath(await mkdtemp(join(tmpdir(), 'board-maintenance-runtime-')));
  t.after(() => rm(parent, { recursive: true, force: true }));
  const repo = join(parent, 'repo'), root = join(repo, 'workers');
  const actual = fileURLToPath(new URL('../', import.meta.url));
  await mkdir(join(root, 'migrations'), { recursive: true });
  await symlink(join(actual, 'node_modules'), join(root, 'node_modules'));
  await copyFile(join(actual, 'migrations/0001_init.sql'), join(root, 'migrations/0001_init.sql'));
  const common = { name: 'maintenance-local-test', compatibility_date: '2026-11-04',
    account_id: '00000000000000000000000000000001', preview_urls: false, observability: { enabled: false },
    d1_databases: [{ binding: 'DB', database_name: 'maintenance-local-test',
      database_id: '00000000-0000-4000-8000-000000000001', migrations_dir: 'migrations', remote: false }] };
  const web = { ...common, vars: { BOARD_MODE: 'web', CLOUDFLARE_ACCESS_TEAM: 'test-team',
    CLOUDFLARE_ACCESS_AUD: 'test-aud', OWNER_EMAIL: 'owner@example.test' },
    assets: { directory: resolve(actual, '../src/main/resources/static'), binding: 'ASSETS', run_worker_first: true } };
  const mcp = { ...common, vars: { BOARD_MODE: 'mcp', BOARD_MAINTENANCE: 'true' } };
  for (const [mode, config] of [['web', web], ['mcp', mcp]]) await writeFile(join(root, `wrangler.production.${mode}.jsonc`), JSON.stringify(config));
  await wranglerRunner(['d1', 'migrations', 'apply', 'DB', '--local', '--config', join(root, 'wrangler.production.web.jsonc'), '--env', ''], { root, remote: false });
  const out = join(parent, 'backup');
  assert.equal((await maintenance(['backup', '--out', out], { root, repo })).checks.backupValid, true);
  assert.equal((await maintenance(['preflight-update', '--backup', out], { root, repo })).checks.backupChecksums, true);
  assert.match(await readFile(join(out, 'data.sql'), 'utf8'), /PRAGMA defer_foreign_keys\s*=\s*TRUE/i);
  assert.match(await readFile(join(out, 'schema.sql'), 'utf8'), /CREATE TABLE task/);
});

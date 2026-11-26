import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const execute = promisify(execFile);
const root = fileURLToPath(new URL('../', import.meta.url));
const cli = fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js', import.meta.url));
const migrations = fileURLToPath(new URL('../migrations', import.meta.url));

test('official Wrangler local SQL export restores a separate D1 without touching the source',
  { timeout: 90_000 }, async t => {
    const directory = await mkdtemp(join(tmpdir(), 'ai-board-d1-backup-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const source = join(directory, 'source'), restored = join(directory, 'restored');
    const schemaBackup = join(directory, 'schema.sql'), dataBackup = join(directory, 'data.sql');
    const env = { ...process.env, CI: 'true', WRANGLER_SEND_METRICS: 'false',
      WRANGLER_LOG_PATH: join(directory, 'logs') };
    for (const name of Object.keys(env)) {
      if (name.startsWith('CLOUDFLARE_') || ['CF_API_KEY', 'CF_API_EMAIL', 'NODE_OPTIONS',
        'WRANGLER_API_BASE_URL'].includes(name)) delete env[name];
    }
    async function local(args, persist) {
      assert.equal(args.includes('--remote'), false);
      // Export has no --persist-to flag. Each isolated config therefore uses
      // its own default .wrangler/state, matching all other commands here.
      await mkdir(persist, { recursive: true });
      const config = join(persist, 'wrangler.json');
      await writeFile(config, JSON.stringify({ name: 'local-backup-test', send_metrics: false,
        compatibility_date: '2026-11-04', d1_databases: [{ binding: 'DB',
          database_name: 'local-backup-test', database_id: '00000000-0000-4000-8000-000000000002',
          migrations_dir: migrations, remote: false }] }));
      try {
        return (await execute(process.execPath, [cli, ...args, '--local',
          '--config', config, '--env', ''], { cwd: root, env, timeout: 25_000, maxBuffer: 2_000_000 })).stdout;
      } catch (error) {
        // This test database contains only public fixtures, never credentials.
        const detail = String(error.stderr || '').replace(/\u001b\[[0-9;]*m/g, '').slice(-1200);
        throw new Error('Wrangler local backup command failed: ' + args.slice(0, 3).join(' ') + '\n' + detail);
      }
    }
    await local(['d1', 'migrations', 'apply', 'DB'], source);
    // Isolated fixtures only. No machine or claim credentials enter command args.
    await local(['d1', 'execute', 'DB', '--command',
      "INSERT INTO project(name,normalized_name,description,created_at,updated_at) VALUES('backup fixture','backup fixture','isolated','2026-11-08T00:00:00Z','2026-11-08T00:00:00Z');" +
      "INSERT INTO task(project_id,title,status,category,sort_order,assignee,claimed_at,created_at,updated_at) VALUES(1,'restore fixture','IN_PROGRESS','BACKEND',0,'backup-fixture','2026-11-08T00:00:00Z','2026-11-08T00:00:00Z','2026-11-08T00:00:00Z');" +
      "INSERT INTO task_block_event(task_id,reason_type,detail,blocked_by,blocked_at) VALUES(1,'TECHNICAL','backup fixture','backup-fixture','2026-11-08T00:00:00Z');" +
      "UPDATE task SET status='BLOCKED',current_block_event_id=1 WHERE id=1;"], source);
    // Schema first is necessary for the circular task/block-event FK. A combined
    // dump interleaves CREATE/INSERT and can refer to a not-yet-created table.
    await local(['d1', 'export', 'DB', '--no-data', '--output', schemaBackup], source);
    await local(['d1', 'export', 'DB', '--no-schema', '--output', dataBackup], source);
    assert.match(await readFile(dataBackup, 'utf8'), /PRAGMA defer_foreign_keys=TRUE;/);
    await local(['d1', 'execute', 'DB', '--file', schemaBackup], restored);
    await local(['d1', 'execute', 'DB', '--file', dataBackup], restored);
    const query = "SELECT (SELECT count(*) FROM project) AS projects, (SELECT count(*) FROM task) AS tasks," +
      " (SELECT count(*) FROM role) AS roles, (SELECT count(*) FROM task t JOIN task_block_event b ON b.id=t.current_block_event_id WHERE t.title='restore fixture' AND t.status='BLOCKED') AS retained;";
    for (const path of [source, restored]) {
      const output = JSON.parse(await local(['d1', 'execute', 'DB', '--command', query, '--json'], path));
      assert.deepEqual(output[0].results, [{ projects: 1, tasks: 1, roles: 5, retained: 1 }]);
    }
  });

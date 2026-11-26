import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { unstable_splitSqlQuery as splitSqlQuery } from 'wrangler';

const { values } = parseArgs({ options: {
  baseline: { type: 'string' },
  samples: { type: 'string', default: '20' },
} });
const samples = Number(values.samples);
assert.ok(Number.isInteger(samples) && samples >= 5 && samples <= 50, '--samples 必須介於 5 與 50');
const warmups = 5;
const workersDir = fileURLToPath(new URL('../', import.meta.url));
const projectCount = 120;
const taskCount = 200;

function meteredDb(db, metrics) {
  function wrap(raw) {
    return {
      raw,
      bind: (...params) => wrap(raw.bind(...params)),
      async first() { return (await this.all()).results[0] ?? null; },
      async all() {
        const result = await raw.all();
        assert.ok(Number.isInteger(result.meta.rows_read), 'D1 未提供 rows_read');
        metrics.readStatements++;
        metrics.rowsRead += result.meta.rows_read;
        return result;
      },
    };
  }
  return {
    prepare: sql => wrap(db.prepare(sql)),
    batch: statements => db.batch(statements.map(statement => statement.raw)),
  };
}

async function seed(db, directory) {
  const migrations = pathToFileURL(directory + '/migrations/');
  for (const name of (await readdir(migrations)).filter(name => name.endsWith('.sql')).sort()) {
    const sql = await readFile(new URL(name, migrations), 'utf8');
    await db.batch(splitSqlQuery(sql).map(statement => db.prepare(statement)));
  }
  await db.batch([
    db.prepare(`WITH RECURSIVE ids(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM ids WHERE n<${projectCount})
      INSERT INTO project(id,name,normalized_name,status,created_at,updated_at)
      SELECT n,printf('Project %03d',n),printf('project %03d',n),
        CASE WHEN n%2=0 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
        '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z' FROM ids`),
    db.prepare(`WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<${taskCount})
      INSERT INTO task(id,project_id,title,status,category,sort_order,created_at,updated_at)
      SELECT (p.id-1)*${taskCount}+n,p.id,'Task '||n,
        CASE WHEN p.id<>1 AND n%7=0 THEN 'BLOCKED' ELSE 'TODO' END,
        CASE WHEN n IN (1,3) OR n>130 THEN 'INFRA' ELSE 'BACKEND' END,n,
        '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'
      FROM project p CROSS JOIN seq`),
    db.prepare(`INSERT INTO task_dependency(task_id,depends_on_task_id,created_at)
      SELECT id,1,'2026-01-01T00:00:00Z' FROM task WHERE project_id=1 AND (id=2 OR id>130)`),
  ]);
}

function measureSummary(rows) {
  const sorted = rows.map(row => row.ms).sort((a, b) => a - b);
  const percentile = value => Number(sorted[Math.ceil(sorted.length * value) - 1].toFixed(2));
  const range = key => [Math.min(...rows.map(row => row[key])), Math.max(...rows.map(row => row[key]))];
  return { p50Ms: percentile(0.5), p95Ms: percentile(0.95), rowsReadRange: range('rowsRead'),
    readStatementsRange: range('readStatements') };
}

async function benchmark(directory) {
  const { BoardService } = await import(pathToFileURL(resolve(directory, 'src/service.js')).href);
  const runtime = new Miniflare(convertV4MiniflareOptions({
    host: '127.0.0.1', port: 0, inspectorPort: 0, cf: false,
    modules: true, script: 'export default { fetch: () => new Response(null, { status: 404 }) }',
    compatibilityDate: '2026-11-04', d1Databases: { DB: 'board-benchmark' },
    outboundService: () => { throw new Error('Benchmark does not use outbound networking'); },
  }));
  try {
    const db = await runtime.getD1Database('DB');
    await seed(db, directory);
    const metrics = {};
    const service = new BoardService(meteredDb(db, metrics));
    const scenarios = [
      { name: 'list-prefix', run: () => service.listProjects(new URLSearchParams({ q: 'Project 071' })),
        verify: result => assert.deepEqual(result.map(row => [row.id, row.total]), [[71, taskCount]]) },
      { name: 'list-active', run: () => service.listProjects(),
        verify: result => assert.equal(result.length, projectCount / 2) },
      { name: 'list-all', run: () => service.listProjects(new URLSearchParams({ status: 'ALL' })),
        verify: result => assert.equal(result.length, projectCount) },
      { name: 'claim-first-eligible', run: () => service.execute('claim_next_task', {
        projectName: 'Project 001', category: 'BACKEND', assignee: 'isolated-benchmark',
      }), verify: (result, i) => {
        assert.equal(result.claimed, true);
        assert.equal(result.task.id, i + 4);
      } },
    ];
    const results = {};
    const outputs = {};
    for (const { name, run, verify } of scenarios) {
      const rows = [];
      for (let i = 0; i < warmups + samples; i++) {
        Object.assign(metrics, { readStatements: 0, rowsRead: 0 });
        const started = performance.now();
        const result = await run();
        const ms = performance.now() - started;
        verify(result, i);
        if (i >= warmups) rows.push({ ms, ...metrics });
        if (i === warmups && name.startsWith('list-')) outputs[name] = result;
      }
      results[name] = measureSummary(rows);
    }
    return { results, outputs };
  } finally {
    await runtime.dispose();
  }
}

const baseline = values.baseline ? await benchmark(resolve(values.baseline, 'workers')) : null;
const current = await benchmark(workersDir);
if (baseline) assert.deepEqual(current.outputs, baseline.outputs, '專案摘要與排列結果必須相同');
console.log(JSON.stringify({
  runtime: 'local Miniflare/D1', projects: projectCount, tasks: projectCount * taskCount,
  warmups, samples, ...(baseline ? { baseline: baseline.results } : {}), current: current.results,
}, null, 2));

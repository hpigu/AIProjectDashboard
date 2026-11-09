import { env as acceptanceEnv, attest, CODE_REF, signed, gitPolicy } from './acceptance-fixture.js';
// Real local workerd + D1 emulation, with outbound networking disabled.
// All credentials are generated in memory and never logged or saved.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomBytes, generateKeyPairSync, sign } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { unstable_splitSqlQuery as splitSqlQuery } from 'wrangler';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const projectRoot = new URL('../../', import.meta.url);
const workerRoot = new URL('../', import.meta.url);
const token = randomBytes(32).toString('base64url');
const tokenHash = createHash('sha256').update(token).digest('hex');
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const key = { ...publicKey.export({ format: 'jwk' }), kid: 'integration-key', alg: 'RS256', use: 'sig' };
const owner = 'owner@example.test';

function assertion(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', kid: key.kid })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ iss: 'https://test-team.cloudflareaccess.com',
    aud: ['integration-audience'], email: owner, sub: 'test-owner', iat: now, nbf: now - 1,
    exp: now + 300, ...overrides })).toString('base64url');
  const input = `${header}.${payload}`;
  return `${input}.${sign('RSA-SHA256', Buffer.from(input), privateKey).toString('base64url')}`;
}

function domain(result) {
  assert.notEqual(result.isError, true, 'MCP tool returned a domain error');
  if (result.structuredContent) return result.structuredContent;
  const text = result.content.filter(x => x.type === 'text').map(x => x.text).join('\n');
  try { return JSON.parse(text); } catch { return text; }
}

async function frame(reader, buffered) {
  const deadline = AbortSignal.timeout(15_000);
  while (!buffered.value.includes('\n\n')) {
    const chunk = await Promise.race([reader.read(), new Promise((_, reject) => {
      deadline.addEventListener('abort', () => reject(new Error('SSE event timed out')), { once: true });
    })]);
    assert.equal(chunk.done, false, 'SSE ended before expected event');
    buffered.value += new TextDecoder().decode(chunk.value);
  }
  const position = buffered.value.indexOf('\n\n');
  const result = buffered.value.slice(0, position);
  buffered.value = buffered.value.slice(position + 2);
  return result;
}

test('real Workers runtime: MCP SDK, D1 atomicity, authenticated UI/SSE, persistence',
  { timeout: 120_000 }, async t => {
    const directory = await mkdtemp(join(tmpdir(), 'ai-board-workers-integration-'));
    let mf;
    const clients = [];
    t.after(async () => {
      for (const client of clients) await client.close().catch(() => {});
      await mf?.dispose();
      await rm(directory, { recursive: true, force: true });
    });
    const bundle = await build({ entryPoints: [fileURLToPath(new URL('src/index.js', workerRoot))],
      bundle: true, write: false, format: 'esm', platform: 'browser', target: 'es2022' });
    const script = bundle.outputFiles[0].text;
    let certRequests = 0;

    async function start() {
      const outboundService = async request => {
        if (request.method === 'GET' && request.url === 'https://test-team.cloudflareaccess.com/cdn-cgi/access/certs') {
          certRequests++;
          return Response.json({ keys: [key] });
        }
        throw new Error('Unexpected outbound network request');
      };
      mf = new Miniflare(convertV4MiniflareOptions({ host: '127.0.0.1', port: 0, inspectorPort: 0, cf: false,
        resourcePersistencePath: directory, workers: [
          { name: 'web', modules: true, script, compatibilityDate: '2026-11-04', compatibilityFlags: ['enable_request_signal'], outboundService,
            d1Databases: { DB: 'integration-db' }, bindings: { ...acceptanceEnv, BOARD_MODE: 'web',
              CLOUDFLARE_ACCESS_TEAM: 'test-team', CLOUDFLARE_ACCESS_AUD: 'integration-audience',
              OWNER_EMAIL: owner }, serviceBindings: { ASSETS: async request => {
                const path = new URL(request.url).pathname;
                if (!['/', '/index.html', '/app.js', '/tokens.css'].includes(path)) return new Response('', { status: 404 });
                return new Response(await readFile(new URL('src/main/resources/static' +
                  (path === '/' ? '/index.html' : path), projectRoot)), {
                  headers: { 'content-type': path.endsWith('.js') ? 'application/javascript' : 'text/html' }
                });
              } } },
          { name: 'mcp', modules: true, script, compatibilityDate: '2026-11-04', compatibilityFlags: ['enable_request_signal'], outboundService,
            d1Databases: { DB: 'integration-db' }, bindings: { ...acceptanceEnv, BOARD_MODE: 'mcp',
              MANAGEMENT_DEVICE_IDS: JSON.stringify(['integration-device']), MACHINE_TOKEN_HASHES: JSON.stringify({ 'integration-device': tokenHash }) } }
        ] }));
      return { web: await mf.getWorker('web'), machine: await mf.getWorker('mcp'),
        db: await mf.getD1Database('DB', 'mcp') };
    }

    let { web, machine, db } = await start();
    const migrationNames = (await readdir(new URL('migrations/', workerRoot))).filter(n => n.endsWith('.sql')).sort();
    // D1.exec splits by newline; use Wrangler's SQL-aware splitter for multiline
    // schemas and role instructions containing quoted newlines/semicolons.
    for (const file of migrationNames) {
      const sql = await readFile(new URL('migrations/' + file, workerRoot), 'utf8');
      await db.batch(splitSqlQuery(sql).map(statement => db.prepare(statement)));
    }

    async function browser(path, jwt = assertion()) {
      return web.fetch('https://web.example.test' + path, { headers: { 'Cf-Access-Jwt-Assertion': jwt } });
    }
    async function connect() {
      const client = new Client({ name: 'workers-integration', version: '1' });
      const transport = new StreamableHTTPClientTransport(new URL('https://mcp.example.test/mcp'), {
        requestInit: { headers: { Authorization: 'Bearer ' + token } },
        fetch: (input, init) => machine.fetch(input, init)
      });
      await client.connect(transport);
      clients.push(client);
      return client;
    }
    const first = await connect();
    const second = await connect();

    await t.test('all existing tool names and public routes stay protected', async () => {
      const tools = (await first.listTools()).tools;
      const expected = [];
      for (const name of await readdir(new URL('src/main/java/dev/aiboard/mcp/', projectRoot))) {
        if (name.endsWith('.java')) {
          const source = await readFile(new URL('src/main/java/dev/aiboard/mcp/' + name, projectRoot), 'utf8');
          expected.push(...[...source.matchAll(/@Tool\(name\s*=\s*"([a-z_]+)"/g)].map(m => m[1]));
        }
      }
      assert.deepEqual(tools.map(x => x.name).sort(), expected.sort());
      for (const path of ['/', '/app.js', '/api/projects', '/api/events']) {
        assert.equal((await web.fetch('https://web.example.test' + path)).status, 401);
      }
      assert.equal((await browser('/')).status, 200);
      assert.equal(certRequests, 1, 'Certificate cache avoids per-asset upstream fetches');
      assert.equal((await browser('/app.js')).status, 200);
      assert.equal((await browser('/api/projects', assertion({ email: 'other@example.test' }))).status, 401);
      assert.equal((await browser('/api/projects', assertion({ aud: ['wrong-app'] }))).status, 401);
      assert.equal((await browser('/api/projects', assertion({ exp: 1 }))).status, 401);
      assert.notEqual((await browser('/mcp')).status, 200);
      assert.notEqual((await machine.fetch('https://mcp.example.test/api/projects', {
        headers: { Authorization: 'Bearer ' + token } })).status, 200);
      assert.equal((await machine.fetch('https://mcp.example.test/mcp', { method: 'POST',
        headers: { Authorization: 'Bearer ' + token, Origin: 'https://web.example.test' } })).status, 403);
    });

    await t.test('project Git workflow planning, signed confirmation and reuse work through real MCP, D1 and read-only browser API', async () => {
      const call = async (name, args) => domain(await first.callTool({ name, arguments: args }));
      const project = (await call('create_project', { name:'Workflow planning integration' })).project;
      let workflow = await call('get_project_workflow', { projectName:project.name });
      assert.equal(workflow.status,'UNCONFIGURED');
      workflow = await call('prepare_project_workflow',{ projectName:project.name,expectedRevision:0,gitPolicy:{baseBranch:'fixture/base'},sources:[{field:'baseBranch',kind:'PROJECT_RULE',ref:'AGENTS.md',version:'fixture-v1'}],questions:[{field:'integrationTarget',question:'整合目標？'}] });
      assert.equal(workflow.draft.readyForConfirmation,false);
      const invalid = await first.callTool({name:'confirm_project_workflow',arguments:signed('fixture-owner',{kind:'CONFIRM_PROJECT_WORKFLOW',projectId:project.id,draftId:workflow.draft.version,expectedRevision:workflow.revision,previewDigest:workflow.draft.previewDigest,approved:true,approvalRef:'explicit-owner-confirmation'})});
      assert.equal(invalid.isError,true);
      workflow = await call('prepare_project_workflow',{projectName:project.name,expectedRevision:workflow.revision,gitPolicy,questions:[]});
      const ownerApproval = signed('fixture-owner',{...workflow.approvalPayload,approvalRef:'fixture://human-confirmation'});
      workflow = await call('confirm_project_workflow',ownerApproval);
      assert.equal(workflow.status,'CONFIRMED');assert.equal(workflow.active.version,3);
      const response = await browser(`/api/projects/${project.id}/workflow`);
      assert.equal(response.status,200);
      assert.deepEqual((await response.json()).active.gitPolicy,gitPolicy);
      assert.equal((await call('get_project_workflow',{projectName:project.name})).active.version,3);
      assert.equal((await first.callTool({name:'confirm_project_workflow',arguments:ownerApproval})).isError,true);
      const webWrite=await web.fetch(`https://web.example.test/api/projects/${project.id}/workflow`,{method:'POST',headers:{'Cf-Access-Jwt-Assertion':assertion()},body:'{}'});
      assert.equal(webWrite.status,403);
    });

    let projectId;
    let claimedTask;
    let claimToken;
    await t.test('D1 transactions, CAS and dependencies work through real MCP SDK', async () => {
      domain(await first.callTool({ name: 'create_project', arguments: { name: 'Workers integration', description: 'isolated' } }));
      projectId = (await db.prepare('SELECT id FROM project WHERE normalized_name = ?').bind('workers integration').first()).id;
      domain(await first.callTool({ name: 'create_tasks', arguments: { projectId, tasks: [
        { title: 'prerequisite', description: 'first', category: 'BACKEND', dependsOnIndexes: [], dependsOnTaskIds: [] },
        { title: 'dependent', description: 'wait', category: 'BACKEND', dependsOnIndexes: [1], dependsOnTaskIds: [] }
      ] } }));
      const results = await Promise.all([first, second].map((client, i) => client.callTool({
        name: 'claim_next_task', arguments: { projectName: 'Workers integration', category: 'BACKEND', assignee: 'worker-' + i }
      })));
      const values = results.filter(x => !x.isError).map(domain);
      const findToken = value => value && typeof value === 'object' && (value.claimToken || Object.values(value).map(findToken).find(Boolean));
      const tokens = values.map(findToken).filter(Boolean);
      assert.equal(tokens.length, 1, 'Exactly one client may receive ownership');
      claimToken = tokens[0];
      const rows = (await db.prepare('SELECT * FROM task WHERE project_id=? ORDER BY sort_order').bind(projectId).all()).results;
      assert.deepEqual(rows.map(x => x.status), ['IN_PROGRESS', 'TODO']);
      assert.equal(rows[1].claim_token_hash, null);
      claimedTask = rows[0].id;
      assert.equal(rows[0].claim_token_hash, createHash('sha256').update(claimToken).digest('hex'));
      const before = await db.prepare('SELECT COUNT(*) AS n FROM task_log').first();
      const rejected = await first.callTool({ name: 'update_task_status', arguments: {
        taskId: claimedTask, status: 'TODO', note: 'invalid ownership', claimToken: randomBytes(32).toString('base64url')
      } });
      assert.equal(rejected.isError, true);
      assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM task_log').first()).n, before.n);
      assert.equal((await db.prepare('SELECT status FROM task WHERE id=?').bind(claimedTask).first()).status, 'IN_PROGRESS');
      const evidenceRequired = await first.callTool({ name: 'update_task_status', arguments: {
        taskId: claimedTask, status: 'DONE', note: 'must use evidence', claimToken
      } });
      assert.equal(evidenceRequired.isError, true);
      const serializedEvents = JSON.stringify((await db.prepare('SELECT * FROM board_event').all()).results);
      assert.equal(serializedEvents.includes(claimToken), false);
      const serializedLogs = JSON.stringify((await db.prepare('SELECT * FROM task_log').all()).results);
      assert.equal(serializedLogs.includes(claimToken), false);
    });

    await t.test('frontend claims and resumes have no role quota or capacity payload', async () => {
      const call = async (client, name, args) => domain(await client.callTool({ name, arguments: args }));
      const p = (await call(first, 'create_project', { name: 'Unrestricted frontend' })).project.id;
      await call(first, 'create_tasks', { projectId: p, tasks: [1, 2].map(i => ({ title: `frontend ${i}`, description: '', category: 'FRONTEND', dependsOnIndexes: [], dependsOnTaskIds: [] })) });
      const claimArgs = { projectName: 'Unrestricted frontend', category: 'FRONTEND', assignee: 'worker' };
      const claims = await Promise.all([first, second].map(c => call(c, 'claim_next_task', claimArgs)));
      assert.equal(claims.every(c => c.claimed), true);
      assert.equal(new Set(claims.map(c => c.task.id)).size, 2);
      assert.equal(Object.hasOwn(claims[0], 'capacity'), false);
      const listed = await call(first, 'list_tasks', { projectId: p });
      assert.equal(Object.hasOwn(listed, 'capacities'), false);
      for (const claim of claims) {
        await call(first, 'block_task', { taskId: claim.task.id, claimToken: claim.claimToken, reasonType: 'TECHNICAL', detail: 'wait' });
        await call(first, 'update_task_status', { taskId: claim.task.id, status: 'IN_PROGRESS', claimToken: claim.claimToken });
      }
      assert.equal((await db.prepare("SELECT count(*) AS n FROM task WHERE project_id=? AND status='IN_PROGRESS'").bind(p).first()).n, 2);
    });

    await t.test('six backend tasks admit distinct workers with bounded contention retries', async () => {
      const call = async (client, name, args) => domain(await client.callTool({ name, arguments: args }));
      const p = (await call(first, 'create_project', { name: 'Backend six racers' })).project.id;
      await call(first, 'create_tasks', { projectId: p, tasks: Array.from({ length: 6 }, (_, i) =>
        ({ title: `backend ${i}`, description: '', category: 'BACKEND', dependsOnIndexes: [], dependsOnTaskIds: [] })) });
      const racers = [first, second, await connect(), await connect(), await connect(), await connect()];
      let release;
      const start = new Promise(resolve => { release = resolve; });
      const pending = racers.map(async (client, i) => {
        await start;
        return call(client, 'claim_next_task', { projectName: 'Backend six racers', category: 'BACKEND', assignee: `worker-${i}` });
      });
      release();
      const claims = await Promise.all(pending);
      for (let i = 0; i < claims.length; i++) {
        if (!claims[i].claimed) {
          assert.equal(claims[i].outcome, 'CONTENDED');
          claims[i] = await call(racers[i], 'claim_next_task', { projectName: 'Backend six racers', category: 'BACKEND', assignee: `worker-${i}` });
        }
      }
      const winners = claims.filter(c => c.claimed);
      assert.equal(winners.length, 6);
      assert.equal(new Set(winners.map(c => c.task.id)).size, 6);
      assert.equal(new Set(winners.map(c => c.task.assignee)).size, 6);
      assert.equal((await db.prepare("SELECT count(*) AS n FROM task WHERE project_id=? AND status='IN_PROGRESS'").bind(p).first()).n, 6);
      assert.equal((await call(first, 'claim_next_task', { projectName: 'Backend six racers', category: 'BACKEND', assignee: 'replacement' })).outcome, 'NO_TASK');
      const [a, b] = winners;
      for (const [name, args] of [
        ['update_task_status', { taskId: b.task.id, status: 'TODO', claimToken: a.claimToken }],
        ['update_task_status', { taskId: b.task.id, status: 'TODO' }],
        ['block_task', { taskId: b.task.id, claimToken: a.claimToken, reasonType: 'TECHNICAL', detail: 'unauthorized' }],
        ['complete_task', { taskId: b.task.id, claimToken: a.claimToken, summary: 'unauthorized', verificationResults: [{ name: 'test', result: 'PASSED', detail: '' }] }]
      ]) assert.equal((await first.callTool({ name, arguments: args })).isError, true);
      assert.equal((await db.prepare('SELECT status FROM task WHERE id=?').bind(b.task.id).first()).status, 'IN_PROGRESS');
    });

    await t.test('two blocked workers resume without role quota', async () => {
      const call = async (client, name, args) => domain(await client.callTool({ name, arguments: args }));
      const p = (await call(first, 'create_project', { name: 'Resume competition' })).project.id;
      await call(first, 'create_tasks', { projectId: p, tasks: [1, 2].map(i =>
        ({ title: `front ${i}`, description: '', category: 'FRONTEND', dependsOnIndexes: [], dependsOnTaskIds: [] })) });
      const claims = [];
      for (let i = 0; i < 2; i++) {
        const claim = await call(first, 'claim_next_task', { projectName: 'Resume competition', category: 'FRONTEND', assignee: `front-${i}` });
        claims.push(claim);
        await call(first, 'block_task', { taskId: claim.task.id, claimToken: claim.claimToken, reasonType: 'TECHNICAL', detail: 'waiting' });
      }
      let release;
      const start = new Promise(resolve => { release = resolve; });
      const pending = claims.map(async (claim, i) => {
        await start;
        return [first, second][i].callTool({ name: 'update_task_status', arguments: { taskId: claim.task.id, status: 'IN_PROGRESS', claimToken: claim.claimToken } });
      });
      release();
      const results = await Promise.all(pending);
      for (let i = 0; i < results.length; i++) {
        if (results[i].isError) {
          results[i] = await first.callTool({ name: 'update_task_status', arguments: { taskId: claims[i].task.id, status: 'IN_PROGRESS', claimToken: claims[i].claimToken } });
        }
      }
      assert.equal(results.every(result => !result.isError), true);
      assert.equal((await db.prepare("SELECT count(*) AS n FROM task WHERE project_id=? AND status='IN_PROGRESS'").bind(p).first()).n, 2);
    });

    await t.test('three backend workers racing one task produce exactly one owner', async () => {
      const call = async (client, name, args) => domain(await client.callTool({ name, arguments: args }));
      const p = (await call(first, 'create_project', { name: 'Backend single racer' })).project.id;
      await call(first, 'create_tasks', { projectId: p, tasks: [{ title: 'only', description: '', category: 'BACKEND', dependsOnIndexes: [], dependsOnTaskIds: [] }] });
      const racers = [first, second, await connect()];
      let release;
      const start = new Promise(resolve => { release = resolve; });
      const pending = racers.map(async (client, i) => {
        await start;
        return call(client, 'claim_next_task', { projectName: 'Backend single racer', category: 'BACKEND', assignee: `single-${i}` });
      });
      release();
      const claims = await Promise.all(pending);
      assert.equal(claims.filter(c => c.claimed).length, 1);
      assert.equal((await db.prepare("SELECT count(*) AS n FROM task WHERE project_id=? AND status='IN_PROGRESS'").bind(p).first()).n, 1);
    });

    await t.test('authenticated browser DTOs and cross-worker committed SSE events', async () => {
      const boardResponse = await browser(`/api/projects/${projectId}/board`);
      assert.equal(boardResponse.status, 200);
      const board = await boardResponse.json();
      assert.equal(board.id, projectId);
      assert.deepEqual(Object.keys(board.columns), ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE']);
      const detail = await (await browser(`/api/projects/${projectId}/tasks/${claimedTask}`)).json();
      assert.equal(detail.id, claimedTask);
      assert.equal(Array.isArray(detail.history), true);
      const graph = await (await browser(`/api/projects/${projectId}/dependencies`)).json();
      assert.equal(graph.nodes.length, 2);
      assert.equal(graph.edges.length, 1);
      const response = await browser('/api/events');
      assert.equal(response.status, 200);
      const reader = response.body.getReader();
      const buffered = { value: '' };
      try {
        assert.match(await frame(reader, buffered), /event: heartbeat/);
        await attest(async (name,args) => domain(await first.callTool({name,arguments:args})), (await domain(await first.callTool({name:'list_tasks',arguments:{projectId}}))).tasks.find(t => t.id === claimedTask));
        domain(await first.callTool({ name: 'complete_task', arguments: { taskId: claimedTask,
          summary: 'integration passed', changedFiles: 'workers/', commitRef: CODE_REF, claimToken,
          verificationResults: [{ name: 'runtime integration', result: 'PASSED', detail: 'local workerd' }] } }));
        let event;
        for (let i = 0; i < 8; i++) {
          event = await frame(reader, buffered);
          if (event.includes('event: task.status_changed')) break;
        }
        assert.match(event, /event: task.status_changed/);
        assert.equal(event.includes(claimToken), false);
      } finally { await reader.cancel(); }
      assert.equal((await db.prepare('SELECT status FROM task WHERE id=?').bind(claimedTask).first()).status, 'DONE');
      const acceptance = await (await browser(`/api/projects/${projectId}/tasks/${claimedTask}/acceptance`)).json();
      assert.equal(acceptance.canClose, true);
      assert.equal(acceptance.events.find(e => e.kind === 'RUN')._verifiedActor, 'fixture-runner');
      assert.equal((await browser(`/api/projects/${projectId + 999}/tasks/${claimedTask}/acceptance`)).status, 404);
    });

    await t.test('the real D1 engine rolls back partial mutations and supports 50-task batches', async () => {
      const previous = await db.prepare('SELECT description FROM project WHERE id=?').bind(projectId).first();
      const before = await db.prepare('SELECT count(*) AS n FROM board_event').first();
      await assert.rejects(() => db.batch([
        db.prepare("UPDATE project SET description='must roll back' WHERE id=?").bind(projectId),
        db.prepare("INSERT INTO board_event(type,payload,created_at) VALUES('must.rollback','{}','2026-11-08')"),
        db.prepare('INSERT INTO mutation_guard(ok) VALUES(0)')
      ]));
      assert.deepEqual(await db.prepare('SELECT description FROM project WHERE id=?').bind(projectId).first(), previous);
      assert.equal((await db.prepare('SELECT count(*) AS n FROM board_event').first()).n, before.n);
      const created = domain(await first.callTool({ name: 'create_project', arguments: { name: 'Batch fifty' } }));
      domain(await first.callTool({ name: 'create_tasks', arguments: { projectId: created.project.id,
        tasks: Array.from({ length: 50 }, (_, i) => ({ title: 'batch-' + i, description: '', category: 'OTHER',
          dependsOnIndexes: [], dependsOnTaskIds: [] })) } }));
      assert.equal((await db.prepare('SELECT count(*) AS n FROM task WHERE project_id=?').bind(created.project.id).first()).n, 50);
    });

    await t.test('runtime restart retains tasks, history, evidence and dependencies', async () => {
      const snapshot = await db.prepare('SELECT id,status,assignee,claim_token_hash FROM task ORDER BY id').all();
      for (const client of clients) await client.close();
      clients.length = 0;
      await mf.dispose();
      ({ web, machine, db } = await start());
      const restored = await db.prepare('SELECT id,status,assignee,claim_token_hash FROM task ORDER BY id').all();
      assert.deepEqual(restored.results, snapshot.results);
      assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM task_dependency').first()).n, 1);
      assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM task_completion_evidence WHERE task_id=?').bind(claimedTask).first()).n, 1);
      const client = await connect();
      domain(await client.callTool({ name: 'claim_next_task', arguments: {
        projectName: 'Workers integration', category: 'BACKEND', assignee: 'after-restart'
      } }));
      assert.equal((await db.prepare("SELECT status FROM task WHERE title='dependent'").first()).status, 'IN_PROGRESS');
    });
  });

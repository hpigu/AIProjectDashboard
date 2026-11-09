#!/usr/bin/env node
// Fixed isolated Java + browser regression. Never invokes project shell commands.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { approveWorkflow, createMcpClient } from '../../bin/approve-project-workflow.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
assert.equal(args.length, 2, 'Usage: BOARD_WORKFLOW_FIXTURE_CONFIRM=isolated node scripts/project-workflow-check/browser.mjs --base-url http://127.0.0.1:8093');
assert.equal(args[0], '--base-url');
const base = new URL(args[1]);
assert.equal(process.env.BOARD_WORKFLOW_FIXTURE_CONFIRM, 'isolated', 'Explicit isolated-fixture confirmation required');
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname));
assert.equal(base.protocol, 'http:');
assert.equal(base.port, '8093', 'Only dedicated isolated fixture port 8093 allowed; never 8080');
assert.ok(!base.username && !base.password && !base.search && !base.hash && base.pathname === '/');
const baseUrl = base.origin;
const health = await fetch(baseUrl + '/api/health', { signal: AbortSignal.timeout(10000), redirect: 'error' });
assert.equal(health.status, 200, 'Isolated app health check');
const healthInfo = await health.json();
assert.ok(JSON.stringify(healthInfo).includes('get_project_workflow'), 'Fixture must expose workflow tools');
// Public fixed test credentials only. A production configuration must never use these.
const managementToken = 'workflow-management-fixture-token-at-least-32';
const owner = { mcpUrl: baseUrl + '/mcp', actor: 'workflow-owner', secret: 'workflow-owner-fixture-credential-at-least-32' };
const output = mkdtempSync(join(tmpdir(), 'board-workflow-browser-'));
const hash = value => createHash('sha256').update(value).digest('hex');
const results = [];
const record = (name, actual) => { results.push({ name, actual, at: new Date().toISOString() }); console.log(name + ': ' + actual); };
let chrome, ws, send, evaluate;
const network = [], browserErrors = [];
async function readJson(path) {
  const res = await fetch(baseUrl + path, { signal: AbortSignal.timeout(10000), redirect: 'error' });
  assert.equal(res.status, 200); return res.json();
}
function rawClient(token) {
  let session, seq = 0;
  async function request(method, params, notification = false) {
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18' };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (session) headers['Mcp-Session-Id'] = session;
    const id = ++seq;
    const response = await fetch(baseUrl + '/mcp', { method: 'POST', redirect: 'error', headers, signal: AbortSignal.timeout(15000), body: JSON.stringify({ jsonrpc: '2.0', ...(notification ? {} : { id }), method, params }) });
    session = response.headers.get('Mcp-Session-Id') || session;
    const text = await response.text();
    if (notification) { assert.ok(response.ok); return; }
    let parsed;
    if (response.headers.get('content-type')?.includes('text/event-stream')) {
      for (const part of text.split(/\r?\n\r?\n/)) {
        const data = part.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (data) { const item = JSON.parse(data); if (item.id === id) parsed = item; }
      }
    } else { try { parsed = JSON.parse(text); } catch {} }
    return { status: response.status, message: parsed };
  }
  return {
    async initialize() { assert.equal((await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'isolated-workflow-browser', version: '1.0.0' } })).status, 200); await request('notifications/initialized', {}, true); },
    async call(name, args) { return request('tools/call', { name, arguments: args }); },
  };
}
function toolResult(reply) {
  assert.equal(reply.status, 200); assert.ok(reply.message?.result && !reply.message.result.isError, JSON.stringify(reply.message));
  const result = reply.message.result;
  if (result.structuredContent) return result.structuredContent;
  const text = result.content.map(c => c.text || '').join('\n');
  try { return JSON.parse(text); } catch { return text; }
}
async function until(expression) {
  for (let i = 0; i < 150; i++) { if (await evaluate(expression)) return; await sleep(100); }
  throw new Error('Browser assertion timeout: ' + expression);
}
async function trueInputClick(selector) {
  const point = await evaluate(`(()=>{const el=document.querySelector(${JSON.stringify(selector)}); if(!el) throw Error('Missing click target'); el.scrollIntoView({block:'center'}); const r=el.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point });
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point });
}
async function screenshot(name, width, height) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 700 });
  await evaluate("document.querySelector('.workflow-panel').scrollIntoView({block:'start'})");
  await sleep(150);
  const image = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  writeFileSync(join(output, name), Buffer.from(image.data, 'base64'));
}
try {
  const admin = rawClient(managementToken); await admin.initialize();
  const projectName = '隔離 Git 流程瀏覽器 ' + Date.now();
  toolResult(await admin.call('create_project', { name: projectName, description: 'Fixed isolated workflow fixture; no customer data' }));
  const projects = await readJson('/api/projects');
  const projectList = Array.isArray(projects) ? projects : projects.projects;
  assert.ok(Array.isArray(projectList), 'Project list shape');
  const project = projectList.find(p => p.name === projectName); assert.ok(project);
  const projectId = project.id;
  const summary = () => readJson(`/api/projects/${projectId}/workflow`);
  assert.equal((await summary()).status, 'UNCONFIGURED');
  const board = await readJson(`/api/projects/${projectId}/board`);
  assert.ok(board.columns && typeof board.columns === 'object', 'Board has task columns');
  assert.equal(Object.values(board.columns).flat().length, 0, 'Fixture has no tasks');
  const servedApp = await (await fetch(baseUrl + '/app.js')).text();
  assert.equal(hash(servedApp), hash(readFileSync(join(root, 'src/main/resources/static/app.js'))), 'Jar must serve current front-end code');
  const executable = process.env.CHROME_BIN || '/Users/daniel/Library/Caches/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-mac-arm64/chrome-headless-shell';
  assert.ok(existsSync(executable), 'Set CHROME_BIN to an installed Chrome/Chromium executable');
  chrome = spawn(executable, ['--headless=new', `--user-data-dir=${join(output, 'chrome')}`, '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--disable-component-update', '--no-proxy-server', 'about:blank'], { env: { PATH: process.env.PATH, HOME: output, TMPDIR: tmpdir() }, stdio: 'ignore' });
  const portFile = join(output, 'chrome/DevToolsActivePort');
  for (let i = 0; i < 150 && !existsSync(portFile); i++) await sleep(100);
  assert.ok(existsSync(portFile), 'Browser started');
  const port = readFileSync(portFile, 'utf8').split('\n')[0];
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json();
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let sequence = 0; const pending = new Map();
  ws.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const item = pending.get(message.id); pending.delete(message.id);
      message.error ? item.reject(new Error(JSON.stringify(message.error))) : item.resolve(message.result);
    } else if (message.method === 'Network.requestWillBeSent') {
      const request = message.params.request;
      network.push({ method: request.method, url: request.url });
    } else if (message.method === 'Runtime.exceptionThrown') browserErrors.push(message.params.exceptionDetails.text);
  };
  send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, 15000);
    pending.set(id, { resolve: result => { clearTimeout(timer); resolve(result); }, reject: error => { clearTimeout(timer); reject(error); } });
    ws.send(JSON.stringify({ id, method, params }));
  });
  evaluate = async expression => {
    const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails));
    return response.result.value;
  };
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');
  await send('Browser.grantPermissions', { origin: baseUrl, permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
  await send('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('board.locale','zh-TW');" });
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: baseUrl });
  await until("!!document.querySelector('.project-row')");
  const rowIndex = await evaluate(`Array.from(document.querySelectorAll('.project-row')).findIndex(el=>el.textContent.includes(${JSON.stringify(projectName)}))`);
  assert.ok(rowIndex >= 0); await trueInputClick(`.project-row:nth-child(${rowIndex + 1})`);
  await until("document.querySelector('.workflow-panel')?.textContent.includes('未設定')");
  assert.ok(await evaluate("document.querySelector('.empty-state')?.textContent.includes('沒有任務') || !document.querySelector('.task-card')"));
  await trueInputClick('.workflow-prompt button');
  await until("document.querySelector('.workflow-panel')?.textContent.includes('已複製')");
  const clipboard = await evaluate('navigator.clipboard.readText()');
  assert.ok(clipboard.includes(projectName) && !clipboard.includes('"gitPolicy"'));
  record('Empty project / natural-language entry', 'Read-only panel exists before tasks; copy button writes a project-specific planning prompt');
  await screenshot('unconfigured-desktop.png', 1280, 1000);
  const prepare = async (gitPolicy, sources, questions) => {
    const current = await summary();
    return toolResult(await admin.call('prepare_project_workflow', { projectName, expectedRevision: current.revision, gitPolicy, sources, questions }));
  };
  let current = await prepare({ baseBranch: 'fixture-start' }, [{ field: 'baseBranch', kind: 'PROJECT_RULE', ref: 'fixture/AGENTS.md', version: 'fixture-v1', detail: 'Fixed fixture source' }], [{ field: 'doneWhen', question: '驗收完成或整合完成才解鎖？' }]);
  assert.equal(current.draft.missingFields.length, 6);
  await until("document.querySelector('.workflow-panel')?.textContent.includes('缺少欄位') && document.querySelector('.workflow-panel')?.textContent.includes('驗收完成或整合完成才解鎖')");
  record('Partial planning / SSE update', 'Only base branch supplied; six missing fields and business question shown without reloading');
  await screenshot('draft-mobile.png', 390, 844);
  const rest = { integrationTarget: 'fixture-review', pullRequestPolicy: 'Human-authorized PR only', requiredChecks: 'Unit + API + browser', reviewPolicy: 'Independent QA and reviewer', mergeAuthority: 'Human owner only', doneWhen: 'INTEGRATED' };
  const source = Object.keys(rest).map(field => ({ field, kind: 'USER_DECISION', ref: 'isolated-browser-fixture', version: 'fixture-v1', detail: 'Simulated human decision for fixture only' }));
  current = await prepare(rest, [...current.draft.sources, ...source], []);
  assert.equal(current.draft.gitPolicy.baseBranch, 'fixture-start'); assert.equal(current.draft.readyForConfirmation, true);
  await until("document.querySelector('.workflow-panel')?.textContent.includes('等待人類核准')");
  record('Complete draft / preserve existing fields', 'Remaining six fields supplied; original base branch retained and question cleared');
  async function fixtureApproval() {
    const state = await summary();
    const helperOutput = [];
    const result = await approveWorkflow({ projectName, credentials: owner, client: createMcpClient(owner), io: {
      isTTY: true, write: line => helperOutput.push(line),
      question: async () => `確認 ${projectName} #${state.draft.version}`,
    } });
    assert.equal(result.approved, true);
    // Explicitly a fixed test simulation, never a real human approval.
    record('Owner helper fixture approval', 'Actual HTTP MCP signing round-trip; io.isTTY and human reply simulated only for isolated fixture');
    writeFileSync(join(output, `owner-helper-version-${result.version}.log`), helperOutput.join('\n'));
    return summary();
  }
  current = await fixtureApproval(); assert.equal(current.active.confirmedBy, owner.actor);
  await until("document.querySelector('.workflow-panel')?.textContent.includes('已核准') && document.querySelector('.workflow-panel')?.textContent.includes('workflow-owner') && !document.querySelector('.workflow-panel')?.textContent.includes('待確認草稿')");
  await send('Page.reload');
  await until("document.querySelector('.workflow-panel')?.textContent.includes('workflow-owner')");
  const displayed = await evaluate("document.querySelector('.workflow-panel').textContent");
  for (const value of Object.values(current.active.gitPolicy)) assert.ok(displayed.includes(value), 'Approved field displayed: ' + value);
  assert.equal((await summary()).revision, current.revision, 'Reading settings reuses active version without re-entering fields');
  record('Persistence / reuse', 'All seven approved fields and owner reference survive browser reload; subsequent reads retain revision');
  await screenshot('confirmed-desktop.png', 1280, 1000);
  await screenshot('confirmed-mobile.png', 390, 844);
  current = await prepare({ integrationTarget: 'fixture-release-candidate' }, [{ field: 'integrationTarget', kind: 'SUGGESTION', ref: 'isolated-browser-fixture', version: 'fixture-v2', detail: 'Fixture change awaiting approval' }], []);
  assert.equal(current.active.gitPolicy.integrationTarget, 'fixture-review');
  assert.equal(current.draft.gitPolicy.integrationTarget, 'fixture-release-candidate');
  await until("document.querySelector('.workflow-panel')?.textContent.includes('fixture-review') && document.querySelector('.workflow-panel')?.textContent.includes('fixture-release-candidate')");
  record('Draft change isolation', 'New integration target shown as draft while previous active remains effective');
  current = await fixtureApproval(); assert.equal(current.active.gitPolicy.integrationTarget, 'fixture-release-candidate');
  await until("document.querySelector('.workflow-panel')?.textContent.includes('fixture-release-candidate') && !document.querySelector('.workflow-panel')?.textContent.includes('待確認草稿')");
  const post = await fetch(`${baseUrl}/api/projects/${projectId}/workflow`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(post.status, 405);
  const unauthenticated = rawClient(); await unauthenticated.initialize();
  const denied = await unauthenticated.call('prepare_project_workflow', { projectName, expectedRevision: current.revision, gitPolicy: { baseBranch: 'forbidden' }, sources: [], questions: [] });
  assert.equal(denied.status, 403); assert.equal((await summary()).revision, current.revision);
  record('Authorization / read-only boundary', 'Browser endpoint POST returns 405; prepare without management token returns 403 with unchanged revision');
  await send('Network.setBlockedURLs', { urls: [`*://*/api/projects/${projectId}/workflow`] });
  await trueInputClick('.workflow-heading button');
  await until("document.querySelector('.workflow-panel')?.textContent.includes('設定讀取失敗')");
  await send('Network.setBlockedURLs', { urls: [] });
  await trueInputClick('.workflow-panel [role="alert"] button');
  await until("!document.querySelector('.workflow-panel [role=\"alert\"]') && document.querySelector('.workflow-panel')?.textContent.includes('fixture-release-candidate')");
  record('Read failure / retry', 'Blocked workflow request shows retry; real input retry restores approved configuration');
  assert.equal(browserErrors.length, 0, 'No JavaScript runtime exceptions');
  assert.ok(network.filter(r => r.url.startsWith(baseUrl)).every(r => r.method === 'GET'), 'Browser makes GET requests only');
  const sources = ['scripts/project-workflow-check/browser.mjs', 'bin/approve-project-workflow.mjs', 'src/main/resources/static/app.js', 'src/main/resources/static/i18n.js', 'src/main/resources/static/layout.css'];
  const contentHashes = Object.fromEntries(sources.map(path => [path, hash(readFileSync(join(root, path)))]));
  const artifacts = Object.fromEntries(['unconfigured-desktop.png', 'draft-mobile.png', 'confirmed-desktop.png', 'confirmed-mobile.png'].map(path => [path, hash(readFileSync(join(output, path)))]));
  const report = { status: 'PASSED', environment: 'ISOLATED_JAVA_HTTP_MCP_REAL_CHROME', baseUrl, projectId, projectName, healthInfo, contentHashes, servedAppSha256: hash(servedApp), artifacts, results, browserRequests: network, browserErrors, limitation: 'Fixture owner response is simulated through fixed helper IO. No real human approval, company Git workflow, company system, or production service has been verified.' };
  writeFileSync(join(output, 'result.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', output, checks: results.length }));
} catch (error) {
  writeFileSync(join(output, 'failure.json'), JSON.stringify({ status: 'FAILED', message: error.message, results, browserErrors }, null, 2));
  console.error('Artifacts retained: ' + output);
  throw error;
} finally {
  ws?.close();
  if (chrome?.pid && chrome.exitCode === null) chrome.kill('SIGTERM'); // Only this browser's exact child PID.
}

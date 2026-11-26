import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, chmod, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { approveWorkflow, validateSummary, validateUrl, loadCredentials, createMcpClient } from '../../bin/approve-project-workflow.mjs';
const secret = 'owner-only-test-secret-32-characters-long';
const policy = { baseBranch: 'feature-base', integrationTarget: 'release-candidate', pullRequestPolicy: 'MR required', requiredChecks: 'unit + browser', reviewPolicy: 'independent review', mergeAuthority: 'owner', doneWhen: 'INTEGRATED' };
function summary() {
  return { projectId: 9, projectName: '隔離專案', revision: 3, active: null,
    draft: { version: 3, baseVersion: null, gitPolicy: policy, sources: [{ ref: 'repo:policy.md', version: 'abc' }], questions: [], missingFields: [], changes: ['baseBranch'], readyForConfirmation: true, previewDigest: 'a'.repeat(64) },
    approvalPayload: { kind: 'CONFIRM_PROJECT_WORKFLOW', projectId: 9, draftId: 3, expectedRevision: 3, previewDigest: 'a'.repeat(64), approved: true } };
}
function fixture({ answer = '確認 隔離專案 #3', mutate, isTTY = true } = {}) {
  let reads = 0;
  const calls = [], output = [];
  const client = { initialize: async () => {}, call: async (name, args) => {
    calls.push({ name, args });
    if (name === 'confirm_project_workflow') return {};
    reads++;
    if (reads === 3) return { projectId: 9, active: { version: 4, previewDigest: 'a'.repeat(64), confirmedBy: 'human-owner' } };
    const data = summary();
    if (reads === 2 && mutate) mutate(data);
    return data;
  } };
  return { calls, output, input: { projectName: '隔離專案', credentials: { actor: 'human-owner', secret }, client,
    io: { isTTY, write: x => output.push(x), question: async () => answer }, now: () => '2026-11-09T00:00:00Z' } };
}
test('明確人類確認後簽署相同草稿，驗證生效版本；不印出簽章與金鑰', async () => {
  const f = fixture();
  assert.deepEqual(await approveWorkflow(f.input), { approved: true, version: 4 });
  const confirmation = f.calls.find(c => c.name === 'confirm_project_workflow').args;
  assert.equal(confirmation.signature, createHmac('sha256', secret).update(confirmation.payload).digest('hex'));
  assert.match(JSON.parse(confirmation.payload).approvalRef, /^owner-cli:2026-11-09/);
  assert.equal(f.output.join('').includes(secret), false);
  assert.equal(f.output.join('').includes(confirmation.signature), false);
  for (const field of ['起始分支', '整合目標', 'PR／MR', '必要檢查', '審查規則', '合併權限', 'DONE 時機', '依據', '差異']) assert.match(f.output.join(''), new RegExp(field));
});
test('取消或無 TTY 不寫入', async () => {
  const cancel = fixture({ answer: 'yes' });
  assert.deepEqual(await approveWorkflow(cancel.input), { approved: false });
  assert.equal(cancel.calls.some(c => c.name === 'confirm_project_workflow'), false);
  const pipe = fixture({ isTTY: false });
  await assert.rejects(approveWorkflow(pipe.input), /互動終端機/);
  assert.equal(pipe.calls.length, 0);
});
test('人確認期間版本、指紋或欄位變動不簽署', async () => {
  for (const mutate of [s => { s.revision++; s.approvalPayload.expectedRevision++; }, s => { s.draft.previewDigest = 'b'.repeat(64); s.approvalPayload.previewDigest = s.draft.previewDigest; }, s => { s.draft.changes = ['new change']; }]) {
    const f = fixture({ mutate });
    await assert.rejects(approveWorkflow(f.input), /已變更/);
    assert.equal(f.calls.some(c => c.name === 'confirm_project_workflow'), false);
  }
});
test('拒絕含隱藏核准欄位、錯專案、錯 draft、缺欄與錯指紋的摘要', () => {
  for (const mutate of [s => { s.approvalPayload.extra = 'hidden'; }, s => { s.projectName = '另一專案'; }, s => { s.approvalPayload.draftId = 100; }, s => { s.draft.gitPolicy = {}; }, s => { s.draft.previewDigest = 'bad'; }]) {
    const s = summary(); mutate(s); assert.throws(() => validateSummary(s, '隔離專案'));
  }
});
test('不完整草稿只顯示，不允許核准', async () => {
  const f = fixture(); f.input.client.call = async () => { const s = summary(); s.draft.readyForConfirmation = false; return s; };
  await assert.rejects(approveWorkflow(f.input), /缺欄或待決/);
});
test('拒絕不安全 URL、userinfo、query、redirect 路由', () => {
  for (const url of ['http://example.com/mcp', 'https://example.com/mcp?secret=x', 'https://user:pw@example.com/mcp', 'https://example.com/other', 'file:///mcp', 'https://example.com/mcp#x']) assert.throws(() => validateUrl(url));
  assert.equal(validateUrl('http://127.0.0.1:18991/mcp'), 'http://127.0.0.1:18991/mcp');
  assert.equal(validateUrl('https://board.example/mcp'), 'https://board.example/mcp');
});
test('owner 憑證必須是自己擁有的 0600 非 symlink 檔案', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'board-owner-helper-'));
  const path = join(dir, 'owner.json'), link = join(dir, 'owner-link.json');
  await writeFile(path, JSON.stringify({ actor: 'human-owner', secret, mcpUrl: 'http://127.0.0.1:18991/mcp' }), { mode: 0o600 });
  assert.equal((await loadCredentials(path)).actor, 'human-owner');
  await symlink(path, link); await assert.rejects(loadCredentials(link));
  await chmod(path, 0o644); await assert.rejects(loadCredentials(path), /0600/);
});
test('真 HTTP MCP 往返，server 檢查簽章、session 與 Bearer', async t => {
  let confirmed = false;
  const server = createServer(async (req, res) => {
    let body = ''; for await (const part of req) body += part;
    const rpc = JSON.parse(body);
    assert.equal(req.headers.authorization, 'Bearer fixture-device');
    assert.equal(req.headers.accept, 'application/json, text/event-stream');
    if (rpc.method !== 'initialize') assert.equal(req.headers['mcp-session-id'], 'isolated-session');
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Mcp-Session-Id', 'isolated-session');
    if (rpc.method === 'notifications/initialized') { res.writeHead(202); res.end(); return; }
    let result;
    if (rpc.method === 'initialize') result = { protocolVersion: '2025-06-18' };
    else {
      const { name, arguments: args } = rpc.params;
      if (name === 'confirm_project_workflow') {
        assert.equal(args.actor, 'human-owner');
        assert.equal(args.signature, createHmac('sha256', secret).update(args.payload).digest('hex'));
        assert.equal(JSON.parse(args.payload).previewDigest, 'a'.repeat(64));
        confirmed = true;
        result = { content: [{ type: 'text', text: '{}' }] };
      } else result = { content: [{ type: 'text', text: JSON.stringify(confirmed ? { projectId: 9, active: { version: 4, previewDigest: 'a'.repeat(64), confirmedBy: 'human-owner' } } : summary()) }] };
    }
    res.end(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const credentials = { actor: 'human-owner', secret, machineToken: 'fixture-device', mcpUrl: `http://127.0.0.1:${server.address().port}/mcp` };
  const f = fixture(); f.input.credentials = credentials; f.input.client = createMcpClient(credentials);
  assert.equal((await approveWorkflow(f.input)).approved, true);
  assert.equal(confirmed, true);
});
test('MCP 拒絕操作與重新導向時不洩漏 server 回覆', async () => {
  const config = { mcpUrl: 'http://127.0.0.1:18991/mcp' };
  for (const response of [new Response('sensitive server trace', { status: 403 }), new Response(JSON.stringify({ id: 1, error: { message: secret } }), { status: 200 })]) {
    const client = createMcpClient(config, async () => response);
    await assert.rejects(client.initialize(), error => !error.message.includes(secret) && !error.message.includes('sensitive server trace'));
  }
  const client = createMcpClient(config, async (_url, options) => { assert.equal(options.redirect, 'error'); throw new Error(secret); });
  await assert.rejects(client.initialize(), error => !error.message.includes(secret));
});
for (const backend of ['Java JSON text', 'Workers structuredContent']) {
  test(`${backend}：未完整草稿 approvalPayload null 顯示缺項後拒絕，不詢問、不寫入`, async () => {
    const pending = summary();
    pending.draft.gitPolicy = { baseBranch: 'feature-base' };
    pending.draft.missingFields = ['integrationTarget', 'requiredChecks'];
    pending.draft.questions = [{ field: 'integrationTarget', question: '整合到哪個分支？' }];
    pending.draft.readyForConfirmation = false;
    pending.approvalPayload = null;
    const names = [], output = [];
    const client = createMcpClient({ mcpUrl: 'http://127.0.0.1:18991/mcp' }, async (_url, options) => {
      const rpc = JSON.parse(options.body);
      if (rpc.method === 'notifications/initialized') return new Response(null, { status: 202 });
      let result;
      if (rpc.method === 'initialize') result = { protocolVersion: '2025-06-18' };
      else {
        names.push(rpc.params.name);
        result = backend.startsWith('Java') ? { content: [{ type: 'text', text: JSON.stringify(pending) }] }
          : { structuredContent: pending, content: [{ type: 'text', text: JSON.stringify(pending) }] };
      }
      return new Response(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }), { headers: { 'Content-Type': 'application/json' } });
    });
    await assert.rejects(approveWorkflow({ projectName: '隔離專案', credentials: { actor: 'human-owner', secret }, client,
      io: { isTTY: true, write: x => output.push(x), question: () => assert.fail('缺項草稿不可要求人類核准') } }), /缺欄或待決問題/);
    assert.deepEqual(names, ['get_project_workflow']);
    assert.match(output.join('\n'), /integrationTarget/);
    assert.match(output.join('\n'), /整合到哪個分支/);
    assert.match(output.join('\n'), /起始分支：feature-base/);
    assert.match(output.join('\n'), /DONE 時機：尚未提供/);
  });
}
test('SUGGESTION 提案來源不會阻擋 owner 採用完整草稿', async () => {
  const f = fixture();
  const oldCall = f.input.client.call;
  f.input.client.call = async (name, args) => {
    const s = await oldCall(name, args);
    if (s.draft) s.draft.sources.push({ field: 'baseBranch', kind: 'SUGGESTION', detail: '代理提案，等待 owner 採用' });
    return s;
  };
  assert.equal((await approveWorkflow(f.input)).approved, true);
  assert.match(f.output.join('\n'), /owner 核准完整草稿即表示採用/);
});

test('repo 外 parent symlink 指向 repo 內的 0600 憑證仍拒絕', async t => {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const inside = await mkdtemp(join(root, '.owner-path-fixture-'));
  const outside = await mkdtemp(join(tmpdir(), 'board-owner-parent-'));
  // Only this test's newly created fixture directories are cleaned up.
  t.after(async () => { await rm(outside, { recursive: true, force: true }); await rm(inside, { recursive: true, force: true }); });
  const credential = join(inside, 'owner.json');
  await writeFile(credential, JSON.stringify({ actor: 'human-owner', secret, mcpUrl: 'http://127.0.0.1:18991/mcp' }), { mode: 0o600 });
  const parentLink = join(outside, 'indirect-owner');
  await symlink(inside, parentLink, 'dir');
  await assert.rejects(loadCredentials(join(parentLink, 'owner.json')), error => /實際位置必須放在 repo 外/.test(error.message) && !error.message.includes(secret));
});

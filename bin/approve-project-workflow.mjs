#!/usr/bin/env node
import { createHmac, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { open, realpath } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline/promises';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MAX_REPLY_BYTES = 1024 * 1024;
const FIELDS = {
  baseBranch: '起始分支', integrationTarget: '整合目標', pullRequestPolicy: 'PR／MR 規則',
  requiredChecks: '必要檢查', reviewPolicy: '審查規則', mergeAuthority: '合併權限', doneWhen: 'DONE 時機',
};
function requireValue(condition, message) { if (!condition) throw new Error(message); }
function object(value) { return value && typeof value === 'object' && !Array.isArray(value); }
function text(value) { return typeof value === 'string' && value.trim() && value.length <= 16000; }
function version(value) { return (Number.isSafeInteger(value) && value > 0) || (text(value) && value.length <= 200); }
function safe(value) { return String(value).replace(/[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]/g, ' '); }
export function validateUrl(input) {
  let url;
  try { url = new URL(input); } catch { throw new Error('mcpUrl 必須是有效 URL'); }
  requireValue(!url.username && !url.password && !url.search && !url.hash && url.pathname === '/mcp', 'mcpUrl 不得含帳密、查詢或片段，路徑須為 /mcp');
  const local = ['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname);
  requireValue(url.protocol === 'https:' || (url.protocol === 'http:' && local), '只允許 HTTPS 或 loopback HTTP');
  return url.href;
}
export async function loadCredentials(path) {
  requireValue(isAbsolute(path), '憑證檔必須使用絕對路徑');
  const rel = relative(ROOT, resolve(path));
  requireValue(rel.startsWith('..' + '/') || isAbsolute(rel), 'owner 憑證檔必須放在 repo 外');
  let file;
  try {
    const [actualRoot, actualPath, actualParent] = await Promise.all([realpath(ROOT), realpath(path), realpath(dirname(path))]);
    const actualRelative = relative(actualRoot, actualPath);
    requireValue(actualRelative.startsWith('..' + '/') || isAbsolute(actualRelative), 'owner 憑證檔實際位置必須放在 repo 外');
    requireValue(join(actualParent, basename(path)) === actualPath, '憑證檔不得使用 symlink');
    // Open the resolved parent path, so a swapped caller-supplied parent link is never followed.
    file = await open(actualPath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const stat = await file.stat();
    requireValue(stat.isFile() && (stat.mode & 0o777) === 0o600 && stat.size <= 32768, '憑證必須是 mode 0600 一般檔案（最多 32 KiB）');
    requireValue(typeof process.getuid !== 'function' || stat.uid === process.getuid(), '憑證擁有者必須是目前操作者');
    const config = JSON.parse(await file.readFile('utf8'));
    requireValue(object(config) && text(config.actor) && text(config.secret) && config.secret.length >= 32, '憑證須含 actor 與至少 32 字元 secret');
    requireValue(config.machineToken === undefined || text(config.machineToken), 'machineToken 必須是非空字串');
    return { mcpUrl: validateUrl(config.mcpUrl), actor: config.actor, secret: config.secret, machineToken: config.machineToken };
  } catch (error) {
    if (error instanceof SyntaxError || error.code) throw new Error('無法安全讀取憑證檔，請確認路徑、格式及權限');
    throw error;
  } finally { await file?.close(); }
}
export function validateSummary(summary, projectName) {
  requireValue(object(summary) && Number.isSafeInteger(summary.projectId) && summary.projectId > 0 && summary.projectName === projectName
    && Number.isSafeInteger(summary.revision) && summary.revision >= 0, '看板回覆的專案或版本無效');
  const draft = summary.draft;
  requireValue(object(draft) && version(draft.version) && object(draft.gitPolicy)
    && Array.isArray(draft.sources) && Array.isArray(draft.questions) && Array.isArray(draft.missingFields)
    && Array.isArray(draft.changes) && typeof draft.readyForConfirmation === 'boolean'
    && Object.hasOwn(draft, 'baseVersion') && (draft.baseVersion === null || (Number.isSafeInteger(draft.baseVersion) && draft.baseVersion >= 0)), '草稿摘要不完整，請重新整理規劃');
  requireValue(typeof draft.previewDigest === 'string' && /^[a-f0-9]{64}$/.test(draft.previewDigest), '草稿摘要指紋無效');
  const payload = summary.approvalPayload;
  if (!draft.readyForConfirmation && payload === null) return summary;
  requireValue(object(payload) && payload.kind === 'CONFIRM_PROJECT_WORKFLOW' && payload.projectId === summary.projectId
    && payload.draftId === draft.version && payload.expectedRevision === summary.revision
    && payload.previewDigest === draft.previewDigest && payload.approved === true
    && Object.keys(payload).sort().join(',') === 'approved,draftId,expectedRevision,kind,previewDigest,projectId', '核准內容與顯示的草稿不一致');
  if (draft.readyForConfirmation) {
    requireValue(draft.missingFields.length === 0 && draft.questions.length === 0 && Object.keys(FIELDS).every(key => text(draft.gitPolicy[key]))
      && ['ACCEPTED', 'INTEGRATED'].includes(draft.gitPolicy.doneWhen), '草稿缺少必要設定，不能核准');
  }
  return summary;
}
export function preview(summary) {
  const d = summary.draft;
  return [
    `專案：${summary.projectName}；目前版本：${summary.active?.version ?? '尚未設定'}；草稿：${d.version}；基底：${d.baseVersion ?? '無'}；修訂：${summary.revision}`,
    ...Object.entries(FIELDS).map(([key, label]) => `${label}：${d.gitPolicy[key] ?? '尚未提供'}`),
    `依據：${JSON.stringify(d.sources)}`,
    '來源標記 SUGGESTION 表示原始提案；owner 核准完整草稿即表示採用，標記保留作為來源紀錄。', `待決問題：${JSON.stringify(d.questions)}`,
    `缺少欄位：${JSON.stringify(d.missingFields)}`, `差異：${JSON.stringify(d.changes)}`,
    '核准只保存並套用設定；不執行 Git、PR／MR、部署或發布。未確認前既有設定保持有效。',
  ].map(safe).join('\n');
}
export function createMcpClient(config, fetchImpl = fetch) {
  let session;
  let id = 0;
  async function request(method, params, notification = false) {
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18' };
    if (session) headers['Mcp-Session-Id'] = session;
    if (config.machineToken) headers.Authorization = `Bearer ${config.machineToken}`;
    let response;
    try {
      response = await fetchImpl(config.mcpUrl, { method: 'POST', redirect: 'error', headers,
        signal: AbortSignal.timeout(15000), body: JSON.stringify({ jsonrpc: '2.0', ...(notification ? {} : { id: ++id }), method, params }) });
      requireValue(response.ok, 'MCP 請求失敗，請確認連線與權限');
      session = response.headers.get('Mcp-Session-Id') || session;
      if (notification) return;
      const reader = response.body?.getReader();
      requireValue(reader, 'MCP 回覆沒有內容');
      const chunks = [];
      let bytes = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        if (bytes > MAX_REPLY_BYTES) { await reader.cancel(); throw new Error('MCP 回覆超過上限'); }
        chunks.push(value);
      }
      const responseText = Buffer.concat(chunks).toString('utf8');
      let message;
      if (response.headers.get('content-type')?.includes('text/event-stream')) {
        for (const event of responseText.split(/\r?\n\r?\n/)) {
          const data = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
          if (data) { const parsed = JSON.parse(data); if (parsed.id === id) message = parsed; }
        }
      } else message = JSON.parse(responseText);
      requireValue(message?.id === id && !message.error && message.result && !message.result.isError, 'MCP 拒絕操作或回覆無效；未確認保存結果');
      return message.result;
    } catch { throw new Error('MCP 連線、權限或回覆檢查失敗；請重新讀取設定確認狀態'); }
  }
  return {
    async initialize() {
      const result = await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'owner-workflow-approval', version: '1.0.0' } });
      requireValue(result.protocolVersion === '2025-06-18', '看板不支援核准工具使用的 MCP 版本');
      await request('notifications/initialized', {}, true);
    },
    async call(name, args) {
      const result = await request('tools/call', { name, arguments: args });
      // Java tools return JSON text; Workers may return structuredContent.
      if (object(result.structuredContent)) return result.structuredContent;
      requireValue(Array.isArray(result.content) && result.content.length === 1 && result.content[0].type === 'text', 'MCP 工具回覆格式無效');
      try { return JSON.parse(result.content[0].text); } catch { throw new Error('MCP 工具回覆不是 JSON'); }
    },
  };
}
export async function approveWorkflow({ projectName, credentials, client, io, now = () => new Date().toISOString() }) {
  requireValue(text(projectName) && !/[\x00-\x1f\x7f-\x9f]/.test(projectName), '專案名稱無效');
  requireValue(io.isTTY === true, '核准需要操作者的互動終端機，不接受管線或自動確認');
  await client.initialize();
  const initial = validateSummary(await client.call('get_project_workflow', { projectName }), projectName);
  io.write(preview(initial));
  requireValue(initial.draft.readyForConfirmation, '草稿尚有缺欄或待決問題，請回規劃對話補齊');
  const expected = `確認 ${projectName} #${initial.draft.version}`;
  const answer = await io.question(`請完整輸入「${safe(expected)}」核准，其他輸入均取消：`);
  if (answer !== expected) { io.write('已取消，未送出核准。'); return { approved: false }; }
  const fresh = validateSummary(await client.call('get_project_workflow', { projectName }), projectName);
  requireValue(fresh.draft.readyForConfirmation && fresh.revision === initial.revision && fresh.draft.version === initial.draft.version
    && fresh.draft.previewDigest === initial.draft.previewDigest && JSON.stringify(fresh.approvalPayload) === JSON.stringify(initial.approvalPayload)
    && JSON.stringify(fresh.draft) === JSON.stringify(initial.draft), '確認期間草稿已變更；請重新執行並閱讀新草稿');
  const payload = JSON.stringify({ ...initial.approvalPayload, approvalRef: `owner-cli:${now()}:${randomUUID()}` });
  const signature = createHmac('sha256', credentials.secret).update(payload).digest('hex');
  await client.call('confirm_project_workflow', { actor: credentials.actor, payload, signature });
  const result = await client.call('get_project_workflow', { projectName });
  requireValue(result?.projectId === initial.projectId && result?.active?.version === initial.revision + 1 && result?.active?.previewDigest === initial.draft.previewDigest && result?.active?.confirmedBy === credentials.actor, '核准後未確認到生效版本，請重新讀取設定；不要直接重送');
  io.write(`已核准 ${safe(projectName)} 的版本 ${safe(result.active.version)}，後續任務可沿用。`);
  return { approved: true, version: result.active.version };
}
async function main(args) {
  requireValue(args.length === 4 && args[0] === '--project' && args[2] === '--credentials', '用法：node bin/approve-project-workflow.mjs --project 專案名稱 --credentials /絕對路徑/owner.json');
  requireValue(process.stdin.isTTY === true && process.stdout.isTTY === true, '核准需要操作者的互動終端機');
  const credentials = await loadCredentials(args[3]);
  const terminal = createInterface({ input: process.stdin, output: process.stdout });
  try {
    await approveWorkflow({ projectName: args[1], credentials, client: createMcpClient(credentials), io: {
      isTTY: true, write: value => process.stdout.write(value + '\n'), question: value => terminal.question(value),
    } });
  } finally { terminal.close(); }
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main(process.argv.slice(2)).catch(error => { process.stderr.write(safe(error.message) + '\n'); process.exitCode = 1; });
}

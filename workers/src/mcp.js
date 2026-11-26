import { tools, validArguments } from './tools.js';

export const PROTOCOLS = ['2025-03-26', '2025-06-18'];
const json = (body, status = 200) => Response.json(body, { status });
const failure = (id, code, message, status = 200) => json({ jsonrpc: '2.0', id, error: { code, message } }, status);
// New tools stay paused unless explicitly classified as read-only.
const managementTools = new Set(['prepare_project_workflow','create_project','create_tasks','reset_task_claim','update_task_details','set_task_dependencies','preview_archive_project','archive_project','restore_project','upsert_role']);
const readOnlyTools = new Set(['get_project_workflow','get_acceptance', 'list_tasks', 'list_roles', 'get_role', 'preview_archive_project']);

async function boundedBody(request) {
  const limit = 65536;
  const length = request.headers.get('Content-Length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > limit)) throw new RangeError();
  const reader = request.body?.getReader();
  if (!reader) return '';
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) { await reader.cancel(); throw new RangeError(); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

export async function handleMcp(request, service, { maintenance = false, managementAuthorized = false, managementActor = 'unverified-caller' } = {}) {
  if (request.method !== 'POST') return new Response(null, { status: 405, headers: { Allow: 'POST' } });
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') || '')) return json({ error: 'unsupported_media_type' }, 415);
  const accept = request.headers.get('Accept') || '';
  if (!accept.includes('application/json') || !accept.includes('text/event-stream')) return json({ error: 'not_acceptable' }, 406);
  const version = request.headers.get('MCP-Protocol-Version');
  if (version && !PROTOCOLS.includes(version)) return json({ error: 'unsupported_protocol_version' }, 400);
  let message;
  try { message = JSON.parse(await boundedBody(request)); }
  catch (error) { return failure(null, -32700, 'Invalid JSON request', error instanceof RangeError ? 413 : 400); }
  if (!message || Array.isArray(message) || message.jsonrpc !== '2.0'
      || typeof message.method !== 'string'
      || (Object.hasOwn(message, 'id') && typeof message.id !== 'string' && !Number.isSafeInteger(message.id))) return failure(null, -32600, 'Invalid request', 400);
  const id = message.id;
  if (!Object.hasOwn(message, 'id')) {
    return message.method.startsWith('notifications/') ? new Response(null, { status: 202 }) : failure(null, -32600, 'Invalid notification', 400);
  }
  let result;
  switch (message.method) {
    case 'initialize': {
      const params = message.params;
      if (!params || typeof params.protocolVersion !== 'string' || !params.clientInfo || typeof params.capabilities !== 'object') return failure(id, -32602, 'Invalid initialization parameters');
      result = { protocolVersion: PROTOCOLS.includes(params.protocolVersion) ? params.protocolVersion : '2025-06-18',
        capabilities: { tools: { listChanged: false } }, serverInfo: { name: 'project-board-workers', version: '1.0.0' } };
      break;
    }
    case 'ping': result = {}; break;
    case 'tools/list': result = { tools }; break;
    case 'tools/call': {
      const tool = tools.find(item => item.name === message.params?.name);
      if (!tool) return failure(id, -32602, 'Unknown tool');
      const args = message.params.arguments ?? {};
      if (!validArguments(args, tool.inputSchema)) return failure(id, -32602, 'Invalid tool arguments');
      if (maintenance && !readOnlyTools.has(tool.name)) {
        result = { isError: true, content: [{ type: 'text', text: 'Maintenance mode: writes are paused. Retry after the owner finishes maintenance.' }] };
        break;
      }
      if (managementTools.has(tool.name) && service.auditManagement) {
        try { await service.auditManagement(managementAuthorized === true ? managementActor : 'unverified-caller', tool.name, managementAuthorized === true); }
        catch { return failure(id, -32603, 'Authorization audit unavailable'); }
      }
      if (managementTools.has(tool.name) && managementAuthorized !== true) {
        result = { isError: true, content: [{ type: 'text', text: 'Management operation requires an explicitly authorized device identity.' }] };
        break;
      }
      try {
        const value = await service.execute(tool.name, args, { managementActor: managementAuthorized === true ? managementActor : undefined });
        result = { content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value ?? null) }] };
        if (value && typeof value === 'object' && !Array.isArray(value) && version === '2025-06-18') result.structuredContent = value;
      } catch (error) {
        // Domain errors must never reflect arbitrary arguments, claim tokens, or SQL.
        result = { isError: true, content: [{ type: 'text', text: 'Operation rejected; check task state, version, dependencies, and claim credentials.' }] };
      }
      break;
    }
    default: return failure(id, -32601, 'Method not found');
  }
  return json({ jsonrpc: '2.0', id, result });
}

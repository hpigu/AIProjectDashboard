import { authenticate as defaultAuthenticate, AuthError } from './auth.js';
import { handleMcp } from './mcp.js';
import { BoardService } from './service.js';
import { workerCapacity } from './capacity.js';

const securityHeaders = {
  'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'",
  'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer', 'Permissions-Policy': 'geolocation=(), camera=(), microphone=()',
  'Cache-Control': 'no-store',
};
const problem = (status, error) => Response.json({ error }, { status });

function runtimeConfig(env, webOrigin) {
  try {
    const value = env.MCP_PUBLIC_URL;
    if (typeof value !== 'string' || /[\s?#]/.test(value)) throw new Error();
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port
        || url.pathname !== '/mcp' || url.origin === webOrigin
        || !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/.test(url.hostname)
        || /^[0-9.]+$/.test(url.hostname) || url.hostname.endsWith('.localhost')) throw new Error();
    return Response.json({ mcpEndpoint: url.href });
  } catch {
    // Only validated, public connection metadata leaves the configured web Worker.
    return problem(503, 'runtime_configuration_unavailable');
  }
}

export async function eventStream(request, service, { pollMs = 5000, heartbeatMs = 15000, lifetimeMs = 50000 } = {}) {
  let cursor = await service.latestEventId();
  let timer, controller, closed = false, polling = false;
  const started = Date.now();
  let heartbeat = started;
  const encoder = new TextEncoder();
  function close() {
    if (closed) return;
    closed = true;
    clearInterval(timer);
    request.signal.removeEventListener('abort', close);
    try { controller?.close(); } catch { /* Reader already cancelled. */ }
  }
  const body = new ReadableStream({
    start(value) {
      controller = value;
      const send = (type, payload, id) => {
        if (!closed) controller.enqueue(encoder.encode(`${id === undefined ? '' : `id: ${id}\n`}event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`));
      };
      send('heartbeat', {});
      request.signal.addEventListener('abort', close, { once: true });
      if (request.signal.aborted) { close(); return; }
      timer = setInterval(async () => {
        if (polling || closed) return;
        if (Date.now() - started >= lifetimeMs) { close(); return; }
        // Bound buffered events when a browser stops reading. Reconnect refetches state.
        if (controller.desiredSize <= 0) { close(); return; }
        polling = true;
        try {
          for (const event of await service.eventsAfter(cursor)) {
            if (closed) return;
            send(event.type, event.payload, event.id);
            cursor = event.id;
          }
          if (Date.now() - heartbeat >= heartbeatMs) { send('heartbeat', {}); heartbeat = Date.now(); }
        } catch { close(); }
        finally { polling = false; }
      }, pollMs);
    },
    cancel: close,
  }, { highWaterMark: 128 });
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' } });
}

export function createHandler({ serviceFactory = env => new BoardService(env.DB, workerCapacity(env)), authenticate = defaultAuthenticate, streamOptions } = {}) {
  return async function fetch(request, env, context) {
    let response;
    try {
      const mode = env.BOARD_MODE;
      if (!['web', 'mcp'].includes(mode)) throw new AuthError(503);
      await authenticate(request, env, mode);
      const url = new URL(request.url);
      // Browser credentials have no write route, and machine credentials no read-only UI.
      if (mode === 'mcp') {
        if (request.headers.has('Origin') || url.pathname !== '/mcp') response = problem(403, 'forbidden_route');
        else response = await handleMcp(request, serviceFactory(env), {
          maintenance: env.BOARD_MAINTENANCE !== undefined && env.BOARD_MAINTENANCE !== 'false',
        });
      } else if (!['GET', 'HEAD'].includes(request.method) || url.pathname === '/mcp' || url.pathname.startsWith('/mcp/')) {
        response = problem(403, 'forbidden_route');
      } else {
        const path = url.pathname;
        const service = path === '/api/runtime-config' ? null : serviceFactory(env);
        if (path === '/api/runtime-config') response = runtimeConfig(env, url.origin);
        else if (path === '/api/events') response = request.method === 'HEAD' ? new Response(null) : await eventStream(request, service, streamOptions);
        else if (path === '/api/projects') response = Response.json(await service.listProjects(url.searchParams), { headers: { 'X-Board-Runtime': 'workers' } });
        else if (path === '/api/roles') response = Response.json(await service.listRoles(url.searchParams.get('projectName')));
        else if (['/api/health', '/api/health/live', '/api/health/ready'].includes(path)) {
          await service.latestEventId();
          response = Response.json({ status: 'UP' });
        } else {
          const match = /^\/api\/projects\/([1-9]\d*)\/(board|dependencies|tasks\/([1-9]\d*)(\/history)?)$/.exec(path);
          if (match) {
            const projectId = Number(match[1]), taskId = Number(match[3]);
            if (!Number.isSafeInteger(projectId) || (match[3] && !Number.isSafeInteger(taskId))) response = problem(400, 'invalid_id');
            else if (match[2] === 'board') response = Response.json(await service.getBoard(projectId));
            else if (match[2] === 'dependencies') response = Response.json(await service.getDependencies(projectId));
            else if (match[4]) response = Response.json(await service.getHistory(projectId, taskId));
            else response = Response.json(await service.getTaskDetail(projectId, taskId));
          } else if (path.startsWith('/api/')) response = problem(404, 'not_found');
          else response = env.ASSETS ? await env.ASSETS.fetch(request) : problem(404, 'not_found');
        }
      }
    } catch (error) {
      if (error instanceof AuthError) response = problem(error.status, error.status === 503 ? 'authentication_unavailable' : 'unauthorized');
      else if (error.name === 'BoardError' && [400, 404, 409].includes(error.status)) response = problem(error.status, 'operation_rejected');
      else response = problem(500, 'request_failed');
    }
    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(securityHeaders)) headers.set(key, value);
    if (response.status === 401) headers.set('WWW-Authenticate', 'Bearer');
    return new Response(request.method === 'HEAD' ? null : response.body, { status: response.status, headers });
  };
}

export default { fetch: createHandler() };

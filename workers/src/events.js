export async function eventStream(request, service, { pollMs = 5000, heartbeatMs = 15000, lifetimeMs = 50000 } = {}) {
  let cursor = await service.latestEventId();
  let timer, expiry, controller, closed = false, polling = false;
  let heartbeat = Date.now();
  const encoder = new TextEncoder();
  function close() {
    if (closed) return;
    closed = true;
    clearInterval(timer);
    clearTimeout(expiry);
    request.signal.removeEventListener('abort', close);
    try { controller?.close(); } catch { /* Reader already cancelled. */ }
  }
  const body = new ReadableStream({
    start(value) {
      controller = value;
      const send = (type, payload, id) => {
        if (closed) return false;
        if (controller.desiredSize <= 0) { close(); return false; }
        controller.enqueue(encoder.encode(`${id === undefined ? '' : `id: ${id}\n`}event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`));
        return true;
      };
      send('heartbeat', {});
      request.signal.addEventListener('abort', close, { once: true });
      if (request.signal.aborted) { close(); return; }
      expiry = setTimeout(close, lifetimeMs);
      timer = setInterval(async () => {
        if (polling || closed) return;
        // Bound buffered events when a browser stops reading. Reconnect refetches state.
        if (controller.desiredSize <= 0) { close(); return; }
        polling = true;
        try {
          for (const event of await service.eventsAfter(cursor)) {
            if (!send(event.type, event.payload, event.id)) return;
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

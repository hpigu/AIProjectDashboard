window.startBoardLiveUpdates = function ({ onState, onEvent, onRefresh }) {
  const eventTypes = [
    'project.created', 'project.status_changed', 'project.workflow_changed',
    'tasks.created', 'task.updated', 'task.status_changed', 'task.acceptance_changed',
  ];
  const changes = new Map();
  let source, refreshTimer, recoveryTimer, watchdog;
  let state = 'reconnecting', refreshAll = false, connectionFailed = false;

  function setState(next) {
    if (next === state) return;
    state = next;
    onState(state);
  }

  function queueRefresh(event) {
    if (event) {
      const { projectId, taskId } = event.payload;
      const key = event.type === 'task.status_changed'
        ? [projectId, event.type, taskId].join(':')
        : [projectId, event.type].join(':');
      changes.delete(key);
      changes.set(key, event);
    } else {
      refreshAll = true;
    }
    if (refreshTimer) return;
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      const batch = refreshAll ? null : [...changes.values()];
      refreshAll = false;
      changes.clear();
      onRefresh(batch);
    }, 100);
  }

  function recover() {
    if (state === 'connected' || document.hidden) return;
    setState('polling');
    queueRefresh();
    if (!source || source.readyState === EventSource.CLOSED) connect();
  }

  function recoverIfNeeded() {
    if (connectionFailed || state === 'polling') recover();
  }

  function scheduleRecovery() {
    if (recoveryTimer) return;
    recoveryTimer = setTimeout(() => {
      recoveryTimer = null;
      recover();
      if (state !== 'connected') scheduleRecovery();
    }, 15_000);
  }

  function watchHeartbeat() {
    clearTimeout(watchdog);
    watchdog = setTimeout(() => {
      source?.close();
      source = null;
      connectionFailed = true;
      setState('reconnecting');
      recover();
      scheduleRecovery();
    }, 45_000);
  }

  function connect() {
    source?.close();
    if (typeof EventSource !== 'function') return;
    const stream = source = new EventSource('/api/events');
    watchHeartbeat();

    function connected() {
      if (source !== stream) return;
      if (state !== 'connected') queueRefresh();
      connectionFailed = false;
      setState('connected');
      clearTimeout(recoveryTimer);
      recoveryTimer = null;
      watchHeartbeat();
    }

    stream.addEventListener('open', connected);
    stream.addEventListener('heartbeat', connected);
    stream.addEventListener('error', () => {
      if (source !== stream) return;
      connectionFailed = true;
      if (state === 'connected') setState('reconnecting');
      scheduleRecovery();
    });
    for (const type of eventTypes) {
      stream.addEventListener(type, event => {
        if (source !== stream) return;
        let payload;
        try { payload = JSON.parse(event.data); } catch { return; }
        if (!Number.isSafeInteger(payload?.projectId)) return;
        if (state === 'connected') watchHeartbeat();
        onEvent(type, payload);
        queueRefresh({ type, payload });
      });
    }
  }

  connect();
  scheduleRecovery();
  window.addEventListener('online', recoverIfNeeded);
  document.addEventListener('visibilitychange', recoverIfNeeded);
  return () => {
    source?.close();
    source = null;
    clearTimeout(refreshTimer);
    clearTimeout(recoveryTimer);
    clearTimeout(watchdog);
    window.removeEventListener('online', recoverIfNeeded);
    document.removeEventListener('visibilitychange', recoverIfNeeded);
  };
};

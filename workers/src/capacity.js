// Shared limits apply independently per project and category. OTHER belongs to the leader.
const defaults = Object.freeze({ BACKEND: 3, FRONTEND: 1, TEST: 1, INFRA: 1, DOC: 1 });

export function workerCapacity(env) {
  return Object.fromEntries(Object.entries(defaults).map(([category, fallback]) => {
    const name = `BOARD_CAPACITY_${category}`;
    const raw = env[name] === undefined ? String(fallback) : env[name];
    if (typeof raw !== 'string' || !/^[1-9][0-9]*$/.test(raw)
        || /[^0-9]/.test(raw) || Number(raw) < 1 || Number(raw) > 2147483647) {
      throw new Error(`${name} must be a positive integer <= 2147483647`);
    }
    return [category, Number(raw)];
  }));
}

// Informational only: count IN_PROGRESS for this scope in the database. Guard admission atomically.
export function capacitySnapshot(limits, projectId, category, inProgress) {
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || !Object.hasOwn(defaults, category)
      || !Number.isSafeInteger(inProgress) || inProgress < 0) {
    throw new Error('invalid capacity snapshot scope or count');
  }
  const limit = limits[category];
  if (!Number.isInteger(limit) || limit < 1 || limit > 2147483647) throw new Error('invalid capacity limit');
  return { projectId, category, limit, inProgress, available: Math.max(0, limit - inProgress), full: inProgress >= limit };
}

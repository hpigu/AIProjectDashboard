import test from 'node:test';
import assert from 'node:assert/strict';
import { workerCapacity, capacitySnapshot } from '../src/capacity.js';

test('defaults, per-project snapshots and reduced capacity', () => {
  const limits = workerCapacity({});
  assert.deepEqual(limits, { BACKEND: 3, FRONTEND: 1, TEST: 1, INFRA: 1, DOC: 1 });
  assert.equal(capacitySnapshot(limits, 1, 'BACKEND', 2).available, 1);
  assert.equal(capacitySnapshot(limits, 2, 'BACKEND', 0).available, 3);
  const reduced = capacitySnapshot(workerCapacity({ BOARD_CAPACITY_BACKEND: '1' }), 1, 'BACKEND', 2);
  assert.deepEqual(reduced, { projectId: 1, category: 'BACKEND', limit: 1, inProgress: 2, available: 0, full: true });
  assert.throws(() => capacitySnapshot(limits, 1, 'OTHER', 0));
});

test('invalid configuration fails with the same variable name', () => {
  for (const value of ['', '0', '01', '-1', '1.0', ' 1', '2147483648', 'abc', '1\n', '1\r', '1\r\n', null])
    assert.throws(() => workerCapacity({ BOARD_CAPACITY_BACKEND: value }), /BOARD_CAPACITY_BACKEND must be a positive integer/);
});

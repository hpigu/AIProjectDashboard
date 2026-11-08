'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

// Executable reference model of the documented leader decisions; no client or board is invoked.
class Leader {
  constructor(tasks, limit = 3, slots = 4) {
    this.tasks = tasks.map(t => ({ status: 'TODO', deps: [], resources: [], ...t }));
    this.limit = limit; this.slots = slots; this.reserved = new Set();
    this.live = new Map(); this.pending = null; this.reconcile = false;
  }
  occupied() { return new Set([...this.reserved, ...this.tasks.filter(t => t.status === 'IN_PROGRESS').map(t => t.id)]).size; }
  reserve() {
    if (this.pending || this.reconcile || this.occupied() >= this.limit || this.live.size >= this.slots - 1) return null;
    const active = this.tasks.filter(t => t.status === 'IN_PROGRESS' || this.reserved.has(t.id));
    const candidate = this.tasks.find(t => t.status === 'TODO' && !this.reserved.has(t.id)
      && t.deps.every(id => this.tasks.find(x => x.id === id)?.status === 'DONE')
      && !active.some(a => a.resources.some(r => t.resources.includes(r))));
    if (!candidate) return null;
    this.reserved.add(candidate.id); this.pending = candidate.id;
    this.live.set(candidate.id, `backend-t${candidate.id}`);
    return candidate.id;
  }
  // Simulates the worker's claim response, including a possible externally changed candidate.
  claim(actual) {
    assert.notEqual(this.pending, null);
    const expected = this.pending; this.pending = null;
    this.tasks.find(t => t.id === actual).status = 'IN_PROGRESS';
    if (actual !== expected) {
      this.reserved.delete(expected); this.live.delete(expected);
      this.live.set(actual, `backend-t${expected}`); this.reconcile = true;
      return { edit: false, expected, actual };
    }
    this.reserved.delete(expected);
    return { edit: true, expected, actual };
  }
  end(id) { this.live.delete(id); }
  resume(id) {
    const t = this.tasks.find(x => x.id === id);
    if (t.status !== 'BLOCKED' || this.occupied() >= this.limit) return false;
    t.status = 'IN_PROGRESS'; return true;
  }
}
const tasks = n => Array.from({ length: n }, (_, i) => ({ id: i + 1 }));

test('three distinct instances, serial handshake, reservation and both capacity limits', () => {
  const l = new Leader(tasks(4));
  assert.equal(l.reserve(), 1); assert.equal(l.occupied(), 1);
  assert.equal(l.reserve(), null); // handshake is outstanding
  assert.deepEqual(l.claim(1), { edit: true, expected: 1, actual: 1 });
  assert.equal(l.occupied(), 1); // conversion is not double-counted
  assert.equal(l.reserve(), 2); l.claim(2);
  assert.equal(l.reserve(), 3); l.claim(3);
  assert.equal(new Set(l.live.values()).size, 3);
  assert.equal(l.reserve(), null);
  const platform = new Leader(tasks(4), 5, 4);
  for (let i = 1; i <= 3; i++) { assert.equal(platform.reserve(), i); platform.claim(i); }
  assert.equal(platform.reserve(), null);
});

test('dependency, resource conflict and shortage do not start idle workers', () => {
  const l = new Leader([{id: 1, resources:['same-file']}, {id: 2, deps:[1]},
    {id: 3, resources:['same-file']}, {id: 4, resources:['other']}]);
  assert.equal(l.reserve(), 1); l.claim(1);
  assert.equal(l.reserve(), 4); l.claim(4);
  assert.equal(l.reserve(), null);
  l.tasks[0].status = 'DONE'; l.end(1);
  assert.equal(l.reserve(), 2); l.claim(2);
  assert.equal(l.reserve(), 3); l.claim(3);
  assert.equal(l.reserve(), null);
});

test('unexpected claim halts edits and dispatch until inventory is reconciled', () => {
  const l = new Leader(tasks(3)); assert.equal(l.reserve(), 1);
  assert.deepEqual(l.claim(2), {edit:false, expected:1, actual:2});
  assert.equal(l.reserve(), null); assert.equal(l.occupied(), 1);
  l.reconcile = false; // leader inspected actual claim and assigned the correct worktree
  assert.equal(l.reserve(), 1);
});

test('capacity one, disconnected and pending integration, blocked resume, limit reduction', () => {
  const l = new Leader(tasks(3), 1);
  assert.equal(l.reserve(), 1); l.claim(1); l.end(1); // no live session: claim remains
  assert.equal(l.reserve(), null); assert.equal(l.tasks[0].status, 'IN_PROGRESS');
  l.tasks[0].status = 'BLOCKED'; assert.equal(l.occupied(), 0);
  assert.equal(l.reserve(), 2); l.claim(2); assert.equal(l.resume(1), false);
  l.end(2); assert.equal(l.reserve(), null); // pending integration still occupies capacity
  l.tasks[1].status = 'DONE'; assert.equal(l.resume(1), true);
  const over = new Leader(tasks(4));
  for (let i=1; i<=3; i++) { over.reserve(); over.claim(i); }
  over.limit = 1;
  assert.equal(over.occupied(), 3); assert.equal(over.reserve(), null);
  assert.equal(over.tasks.filter(t => t.status === 'IN_PROGRESS').length, 3);
});

test('real isolated Git worktrees retain dirty work and gate completion on merge', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'board-worker-regression-'));
  const repo = path.join(root, 'repo'), a = path.join(root, 'worker-a'), b = path.join(root, 'worker-b');
  fs.mkdirSync(repo);
  const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding:'utf8', stdio:['ignore','pipe','pipe'] }).trim();
  try {
    git(repo, 'init', '-b', 'dev'); git(repo, 'config', 'user.name', 'Fixture'); git(repo, 'config', 'user.email', 'fixture@example.invalid');
    fs.writeFileSync(path.join(repo, 'shared.txt'), 'base\n'); git(repo, 'add', 'shared.txt'); git(repo, 'commit', '-m', 'base');
    git(repo, 'worktree', 'add', '-b', 'task/1-backend', a);
    git(repo, 'worktree', 'add', '-b', 'task/2-backend', b);
    fs.writeFileSync(path.join(a, 'shared.txt'), 'worker A dirty\n');
    fs.writeFileSync(path.join(b, 'shared.txt'), 'worker B dirty\n');
    assert.equal(fs.readFileSync(path.join(a, 'shared.txt'), 'utf8'), 'worker A dirty\n');
    assert.equal(fs.readFileSync(path.join(b, 'shared.txt'), 'utf8'), 'worker B dirty\n');
    assert.equal(fs.readFileSync(path.join(repo, 'shared.txt'), 'utf8'), 'base\n');
    assert.match(git(a, 'status', '--porcelain'), /shared.txt/); assert.match(git(b, 'status', '--porcelain'), /shared.txt/);
    let status = 'IN_PROGRESS', gate = false, merged = false;
    const complete = () => { if (!gate || !merged) throw new Error('gate or merge missing'); status = 'DONE'; };
    assert.throws(complete); assert.equal(status, 'IN_PROGRESS');
    git(a, 'add', 'shared.txt'); git(a, 'commit', '-m', 'task 1');
    assert.equal(git(a, 'status', '--porcelain'), ''); gate = true;
    assert.throws(complete); assert.equal(status, 'IN_PROGRESS');
    git(repo, 'merge', '--no-ff', 'task/1-backend', '-m', 'integrate task 1'); merged = true;
    complete(); assert.equal(status, 'DONE');
    // The other worker's uncommitted result survives both integration and simulated failure.
    assert.equal(fs.readFileSync(path.join(b, 'shared.txt'), 'utf8'), 'worker B dirty\n');
    assert.match(git(b, 'status', '--porcelain'), /shared.txt/);
  } finally {
    // Preserve all artifacts, including failed runs, for inspection; never touch an existing path.
    console.log(`Preserved isolated fixture: ${root}`);
  }
});

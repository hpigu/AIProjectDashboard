import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { openSync, closeSync, fstatSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { createCredentialStore, main } from '../scripts/device-credential.mjs';

const token = Buffer.alloc(32, 19).toString('base64url');
function fakeStore(results) {
  const calls = [];
  const store = createCredentialStore({ platform: 'darwin', home: '/Users/Test User',
    random: size => { assert.equal(size, 32); return Buffer.alloc(32, 19); },
    runner: async (...args) => { calls.push(args); return results.shift(); } });
  return { store, calls };
}

test('initialization stores token only in security stdin and never allows overwrite', async () => {
  const { store, calls } = fakeStore([{ code: 44, stdout: '' }, { code: 0, stdout: '' }]);
  await store.initialize('ai-board.production', 'mac-codex');
  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1][0], ['-i', '-q']);
  assert.equal(JSON.stringify(calls[1][0]).includes(token), false);
  assert.match(calls[1][1], /-T "" -w [A-Za-z0-9_-]{43} "\/Users\/Test User\/Library\/Keychains\/login.keychain-db"\n$/);
  assert.equal(calls[1][1].includes('-U'), false);
  assert.equal(calls[1][1].includes('-A'), false);
  assert.equal(calls[1][1].includes('quit'), false);
});

test('existing credential, denied access and duplicate race fail without replacement', async () => {
  for (const status of [0, 36, 1, null]) {
    const { store, calls } = fakeStore([{ code: status, stdout: '' }]);
    await assert.rejects(store.initialize('ai-board.production', 'device'));
    assert.equal(calls.length, 1);
  }
  const { store } = fakeStore([{ code: 44, stdout: '' }, { code: 45, stdout: token }]);
  await assert.rejects(store.initialize('ai-board.production', 'device'), error => !error.message.includes(token));
});

test('hash output contains only device/hash and run passes raw token only in child env', async () => {
  const { store, calls } = fakeStore([{ code: 0, stdout: `${token}\n` }, { code: 0, stdout: token }]);
  let output = '';
  let status = '';
  await main(['hash-map', 'ai-board.production', 'device', 'mac-codex'], {
    store, stdout: { write: text => { output += text; } }, stderr: { write: text => { status += text; } }
  });
  assert.deepEqual(JSON.parse(output), { 'mac-codex': createHash('sha256').update(token).digest('hex') });
  assert.equal(output.includes(token), false);
  assert.equal(status, '');
  const code = await main(['run', 'ai-board.production', 'device', '--', 'codex', '--version'], {
    store, launch: async (command, args, env) => {
      assert.equal(command, 'codex');
      assert.deepEqual(args, ['--version']);
      assert.equal(env.BOARD_MACHINE_TOKEN, token);
      assert.equal(JSON.stringify(args).includes(token), false);
      return 7;
    }
  });
  assert.equal(code, 7);
  assert.equal(calls.every(([args]) => args.includes('-w')), true);
});

test('rejects injection, malformed credentials, non-macOS and ambiguous paths', async () => {
  const { store, calls } = fakeStore([]);
  for (const value of ['bad\ncommand', 'bad"value', 'bad value', '-U', '', undefined]) {
    await assert.rejects(store.initialize(value, 'device'));
  }
  assert.equal(calls.length, 0);
  assert.throws(() => createCredentialStore({ platform: 'linux' }), /no plaintext fallback/);
  assert.throws(() => createCredentialStore({ platform: 'darwin', home: '/Users/bad"name' }), /Unsupported/);
  for (const stdout of ['secret', `${token}\nextra`, `${token}\n\n`]) {
    const fake = fakeStore([{ code: 0, stdout }]);
    await assert.rejects(fake.store.hashMap('service', 'account', 'device'), /not a valid/);
  }
});

// Exercise the same stdout descriptor that a client gives its helper process.
const helper = new URL('../scripts/device-credential.mjs', import.meta.url).href;
function captureHeaders(stdio, result) {
  const script = `import { main, createCredentialStore } from ${JSON.stringify(helper)};
    const store = createCredentialStore({ platform: 'darwin', home: '/Users/Test',
      runner: async () => {
        if (!${Boolean(result)}) throw Error('Unexpected Keychain read');
        return ${JSON.stringify(result ?? null)};
      } });
    try { await main(['headers', 'service', 'account'], { store }); }
    catch (error) { console.error(error.message); process.exitCode = 1; }`;
  return spawnSync(process.execPath, ['--input-type=module', '-e', script], { stdio, encoding: 'utf8' });
}

test('headers emits a single header to the client capture pipe and fails closed on Keychain errors', () => {
  const stdio = ['ignore', 'pipe', 'pipe'];
  const ok = captureHeaders(stdio, { code: 0, stdout: token });
  assert.equal(ok.status, 0, ok.stderr);
  assert.deepEqual(JSON.parse(ok.stdout), { Authorization: `Bearer ${token}` });
  assert.equal(ok.stderr, '');
  for (const result of [{ code: 36, stdout: token }, { code: 0, stdout: 'malformed' }]) {
    const denied = captureHeaders(stdio, result);
    assert.equal(denied.status, 1);
    assert.equal(denied.stdout, '');
    assert.equal(denied.stderr.includes(token), false);
  }
});

test('headers rejects redirected file output before accessing Keychain', () => {
  const directory = mkdtempSync(join(tmpdir(), 'board-header-output-'));
  const fd = openSync(join(directory, 'capture'), 'wx', 0o600);
  try {
    const denied = captureHeaders(['ignore', fd, 'pipe']);
    assert.equal(denied.status, 1);
    assert.match(denied.stderr, /private pipe/);
    assert.equal(fstatSync(fd).size, 0);
  } finally {
    closeSync(fd);
    rmSync(directory, { recursive: true });
  }
});

test('headers refuses terminal output before reading Keychain', async () => {
  const { store, calls } = fakeStore([]);
  await assert.rejects(main(['headers', 'service', 'account'], {
    store, stdout: { isTTY: true, write: () => assert.fail('Unexpected secret output') }
  }), /private pipe/);
  assert.equal(calls.length, 0);
});

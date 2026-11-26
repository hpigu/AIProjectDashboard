import test from 'node:test';
import assert from 'node:assert/strict';
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

test('headers emits exactly one JSON object to private pipe with no status output', async () => {
  const { store, calls } = fakeStore([{ code: 0, stdout: `${token}\n` }]);
  let output = '';
  let status = '';
  const code = await main(['headers', 'ai-board.production', 'mac-codex'], {
    store,
    stdout: { isTTY: false, write: text => { output += text; } },
    stderr: { write: text => { status += text; } }
  });
  assert.equal(code, 0);
  assert.equal(output, `${JSON.stringify({ Authorization: `Bearer ${token}` })}\n`);
  assert.equal(status, '');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0].includes('-w'), true);
  assert.equal(JSON.stringify(calls[0][0]).includes(token), false);
});

test('headers refuses terminal output before reading Keychain and fails closed on denial', async () => {
  const { store, calls } = fakeStore([]);
  await assert.rejects(main(['headers', 'service', 'account'], {
    store, stdout: { isTTY: true, write: () => assert.fail('Unexpected secret output') }
  }), /private pipe/);
  assert.equal(calls.length, 0);
  for (const result of [{ code: 36, stdout: token }, { code: 0, stdout: 'malformed' }]) {
    const fake = fakeStore([result]);
    let output = '';
    await assert.rejects(main(['headers', 'service', 'account'], {
      store: fake.store, stdout: { isTTY: false, write: text => { output += text; } }
    }), error => !error.message.includes(token));
    assert.equal(output, '');
  }
});

// macOS-only device credentials. Secrets are confined to Keychain, pipes, and memory.
// Apple security.c: non-TTY -i reads stdin without echo; never enable -v or a PTY.
import { spawn } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const security = '/usr/bin/security';
const labelPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;

function validateLabel(value) {
  if (!labelPattern.test(value ?? '')) throw new Error('Service, account and device must use 1–100 ASCII letters, digits, dot, underscore or hyphen.');
}

// Capture subprocess output privately: security errors must never reach a log.
export async function securityRunner(args, input = '') {
  return new Promise((resolveResult, reject) => {
    const child = spawn(security, args, { stdio: ['pipe', 'pipe', 'pipe'], shell: false });
    let output = '';
    let size = 0;
    child.stdout.on('data', chunk => {
      size += chunk.length;
      if (size > 65536) child.kill('SIGTERM');
      else output += chunk.toString('utf8');
    });
    child.stderr.on('data', () => {});
    child.stdin.on('error', () => {});
    child.once('error', () => reject(new Error('Unable to run macOS Keychain tool.')));
    child.once('close', code => resolveResult({ code: size > 65536 ? -1 : code, stdout: output }));
    child.stdin.end(input);
  });
}

export function createCredentialStore({ runner = securityRunner, random = randomBytes,
  platform = process.platform, home = homedir() } = {}) {
  if (platform !== 'darwin') throw new Error('Only macOS login Keychain is supported; no plaintext fallback.');
  const keychain = `${home}/Library/Keychains/login.keychain-db`;
  // security interactive tokenizer supports quoted paths; reject ambiguous syntax.
  if (/["'\\\r\n\0]/.test(keychain)) throw new Error('Unsupported login Keychain path.');
  const args = (service, account, password = false) => {
    validateLabel(service);
    validateLabel(account);
    return ['find-generic-password', '-s', service, '-a', account,
      ...(password ? ['-w'] : []), keychain];
  };

  async function read(service, account) {
    const result = await runner(args(service, account, true));
    if (result.code !== 0) throw new Error('Keychain credential unavailable or access denied.');
    const value = result.stdout.replace(/\r?\n$/, '');
    if (!tokenPattern.test(value)) throw new Error('Keychain credential is not a valid device token.');
    return value;
  }

  return {
    async initialize(service, account) {
      const existing = await runner(args(service, account));
      if (existing.code === 0) throw new Error('Credential already exists; refusing to overwrite.');
      // errSecItemNotFound = -25300, shell exit status 44. Other failures must stop.
      if (existing.code !== 44) throw new Error('Cannot confirm credential absence; Keychain access failed.');
      const token = random(32).toString('base64url');
      if (!tokenPattern.test(token)) throw new Error('Device token generation failed.');
      // Password is in stdin, never argv. No -U, -A, -v, shell, terminal or file.
      // -T "" removes the creator's automatic trusted-app access.
      const command = `add-generic-password -s ${service} -a ${account} -T "" -w ${token} "${keychain}"\n`;
      const stored = await runner(['-i', '-q'], command);
      if (stored.code !== 0) throw new Error('Keychain initialization failed; credential was not replaced.');
    },
    async hashMap(service, account, device) {
      validateLabel(device);
      const token = await read(service, account);
      return JSON.stringify({ [device]: createHash('sha256').update(token).digest('hex') });
    },
    async headers(service, account) {
      const token = await read(service, account);
      return JSON.stringify({ Authorization: `Bearer ${token}` });
    },
    async childEnvironment(service, account, base = process.env) {
      const token = await read(service, account);
      return { ...base, BOARD_MACHINE_TOKEN: token };
    }
  };
}

async function runChild(command, args, env) {
  return new Promise((resolveCode, reject) => {
    const child = spawn(command, args, { env, shell: false, stdio: 'inherit' });
    child.once('error', () => reject(new Error('Unable to start credential consumer.')));
    child.once('exit', (code, signal) => resolveCode(code ?? (signal ? 1 : 0)));
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => child.kill(signal));
  });
}

export async function main(argv, { store = createCredentialStore(), stdout = process.stdout,
  stderr = process.stderr, launch = runChild } = {}) {
  const [operation, service, account, ...rest] = argv;
  if (operation === 'init' && rest.length === 0) {
    await store.initialize(service, account);
    stderr.write('Device credential initialized in login Keychain.\n');
    return 0;
  }
  if (operation === 'hash-map' && rest.length === 1) {
    stdout.write(`${await store.hashMap(service, account, rest[0])}\n`);
    return 0;
  }
  if (operation === 'headers' && rest.length === 0) {
    // Credentials may only flow to a capturing transport helper, never a terminal.
    if (stdout.isTTY) throw new Error('Headers must be consumed by a private pipe, not displayed in a terminal.');
    stdout.write(`${await store.headers(service, account)}\n`);
    return 0;
  }
  if (operation === 'run' && rest[0] === '--' && rest.length >= 2) {
    return launch(rest[1], rest.slice(2), await store.childEnvironment(service, account));
  }
  throw new Error('Usage: device-credential.mjs init SERVICE ACCOUNT | hash-map SERVICE ACCOUNT DEVICE | headers SERVICE ACCOUNT | run SERVICE ACCOUNT -- TRUSTED_COMMAND [ARGS...]');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    // Only our fixed messages are surfaced; subprocess output is always private.
    console.error(error.message);
    process.exitCode = 1;
  });
}

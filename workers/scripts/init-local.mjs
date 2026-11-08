// Explicit fresh-only local initialization. Existing paths are never opened or changed.
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, rename } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const cli = resolve(root, 'node_modules/wrangler/bin/wrangler.js');
const config = resolve(root, 'wrangler.jsonc');
const seed = resolve(root, 'scripts/fresh-role-seed.sql');
const destination = process.argv[2];
if (process.argv.length !== 3 || !destination || !isAbsolute(destination)) {
  console.error('Usage: node scripts/init-local.mjs <new-absolute-directory>');
  process.exit(2);
}
// Atomic reservation rejects all existing paths, including empty or custom databases.
await mkdir(destination, { mode: 0o700 });
// No service receives this unpredictable path until migrations and seed have completed.
const staging = await mkdtemp(resolve(destination, '.building-'));
const env = { ...process.env, CI: 'true', WRANGLER_SEND_METRICS: 'false', WRANGLER_LOG_PATH: resolve(destination, 'logs') };
for (const key of Object.keys(env)) {
  if (key.startsWith('CLOUDFLARE_') || ['CF_API_KEY', 'CF_API_EMAIL', 'NODE_OPTIONS', 'WRANGLER_API_BASE_URL'].includes(key)) delete env[key];
}
async function run(args) {
  const code = await new Promise((done, reject) => {
    const child = spawn(process.execPath, [cli, ...args, '--local', '--persist-to', staging, '--config', config, '--env', ''], { cwd: root, env, stdio: 'inherit' });
    child.once('error', reject);
    child.once('exit', (value, signal) => done(value ?? (signal ? 1 : 0)));
  });
  if (code !== 0) throw new Error('Local initialization failed; destination was not published.');
}
await run(['d1', 'migrations', 'apply', 'DB']);
await run(['d1', 'execute', 'DB', '--file', seed]);
const state = resolve(destination, 'state');
// Only this process has created the new parent; never replace an existing state path.
await mkdir(state, { mode: 0o700 });
// Rename into the exclusively reserved empty directory as a child, not over any path.
await rename(staging, resolve(state, 'd1-state'));
console.log(`Fresh local D1 state: ${resolve(state, 'd1-state')}`);

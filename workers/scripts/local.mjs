// Local-only convenience commands. Deliberately no deploy / remote operation.
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const cli = resolve(root, 'node_modules/wrangler/bin/wrangler.js');
const state = resolve(root, '.wrangler/state');
const webConfig = ['--config', resolve(root, 'wrangler.jsonc')];
const mcpConfig = ['--config', resolve(root, 'wrangler.mcp.jsonc')];
const tasks = {
  web: [['dev', '--local', '--ip', '127.0.0.1', '--port', '18787', '--persist-to', state, '--env', '', ...webConfig]],
  mcp: [['dev', '--local', '--ip', '127.0.0.1', '--port', '18788', '--persist-to', state, '--env', '', ...mcpConfig]],
  migrate: [['d1', 'migrations', 'apply', 'DB', '--local', '--persist-to', state, '--env', '', ...webConfig]],
  build: [
    ['deploy', '--dry-run', '--outdir', resolve(root, '.wrangler/build/web'), '--env', '', ...webConfig],
    ['deploy', '--dry-run', '--outdir', resolve(root, '.wrangler/build/mcp'), '--env', '', ...mcpConfig]
  ]
};
const task = process.argv[2];
if (!Object.hasOwn(tasks, task) || process.argv.length !== 3) {
  console.error('Usage: node scripts/local.mjs web|mcp|migrate|build');
  process.exit(2);
}
// Ignore ambient credentials; remote resources must never be used by this runner.
const env = {
  ...process.env,
  WRANGLER_SEND_METRICS: 'false',
  WRANGLER_LOG_PATH: resolve(root, '.wrangler/logs'),
  CI: 'true'
};
for (const key of Object.keys(env)) {
  if (key.startsWith('CLOUDFLARE_') || key === 'CF_API_KEY' || key === 'CF_API_EMAIL') delete env[key];
}
let active;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => active?.kill(signal));
}
for (const args of tasks[task]) {
  const code = await new Promise((resolveExit, reject) => {
    active = spawn(process.execPath, [cli, ...args], { cwd: root, env, stdio: 'inherit' });
    active.once('error', reject);
    active.once('exit', (exitCode, signal) => resolveExit(exitCode ?? (signal ? 1 : 0)));
  });
  if (code !== 0) process.exit(code);
}

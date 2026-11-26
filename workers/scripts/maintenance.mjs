// Explicit maintenance only: no deployment, migration, restore or deletion.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, writeFile, mkdir, lstat, realpath, rename } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const workersRoot = fileURLToPath(new URL('../', import.meta.url));
const execute = promisify(execFile);
const failure = message => { throw new Error(message); };
const digest = value => createHash('sha256').update(value).digest('hex');
const metadataSql = 'SELECT revision FROM board_revision WHERE id=1; ' +
  "SELECT type,name,tbl_name,sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name; " +
  'SELECT id,name,applied_at FROM d1_migrations ORDER BY id;';

// Our two production configs are JSON-compatible JSONC. Reject ambiguous input.
async function configFile(path) {
  try { return JSON.parse(await readFile(path, 'utf8')); }
  catch { failure('Production configuration could not be read.'); }
}

async function checkConfigs(root) {
  const webPath = join(root, 'wrangler.production.web.jsonc');
  const mcpPath = join(root, 'wrangler.production.mcp.jsonc');
  for (const path of [webPath, mcpPath]) {
    if (!(await lstat(path)).isFile() || (await lstat(path)).isSymbolicLink()) failure('Production configuration must be a regular file.');
  }
  const web = await configFile(webPath), mcp = await configFile(mcpPath);
  const a = web.d1_databases?.[0], b = mcp.d1_databases?.[0];
  if (!web.account_id || web.account_id !== mcp.account_id ||
      web.d1_databases?.length !== 1 || mcp.d1_databases?.length !== 1 ||
      a?.binding !== 'DB' || b?.binding !== 'DB' || !a.database_id ||
      a.database_id !== b.database_id || a.database_name !== b.database_name ||
      !a.database_name || a.remote !== false || b.remote !== false) failure('Production database bindings do not match.');
  if (web.vars?.BOARD_MODE !== 'web' || mcp.vars?.BOARD_MODE !== 'mcp' ||
      web.assets?.run_worker_first !== true || web.assets?.binding !== 'ASSETS' || mcp.assets ||
      web.observability?.enabled !== false || mcp.observability?.enabled !== false ||
      web.preview_urls !== false || mcp.preview_urls !== false ||
      !web.vars?.CLOUDFLARE_ACCESS_TEAM || !web.vars?.CLOUDFLARE_ACCESS_AUD || !web.vars?.OWNER_EMAIL) {
    failure('Production security configuration check failed.');
  }
  return { path: webPath, target: digest(JSON.stringify([web.account_id, a.database_id])),
    writesPaused: mcp.vars?.BOARD_MAINTENANCE === 'true',
    configHash: digest(JSON.stringify([web, mcp])) };
}

async function externalPath(path, repo) {
  if (!path || !isAbsolute(path)) failure('An absolute external output path is required.');
  const normalized = resolve(path);
  const repoReal = await realpath(repo);
  // Check every existing component, including parent directories, before creating files.
  let part = normalized;
  while (true) {
    try { if ((await lstat(part)).isSymbolicLink()) failure('Symbolic links are not accepted.'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (dirname(part) === part) break;
    part = dirname(part);
  }
  const rel = relative(repoReal, normalized);
  if (!rel || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel))) failure('Backup files must remain outside the repository.');
  return normalized;
}

async function privateEntry(path, directory = false) {
  const stat = await lstat(path);
  if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile()) ||
      (stat.mode & 0o777) !== (directory ? 0o700 : 0o600) || stat.uid !== process.getuid()) {
    failure('Backup ownership or permissions are unsafe.');
  }
}

// Free D1 databases can still produce large SQL exports. Hash with bounded memory
// instead of loading a complete project-data dump into the Node heap.
async function fileDigest(path) {
  const hash = createHash('sha256');
  let bytes = 0, prefix = Buffer.alloc(0);
  try {
    for await (const chunk of createReadStream(path)) {
      hash.update(chunk);
      bytes += chunk.length;
      if (prefix.length < 4096) prefix = Buffer.concat([prefix, chunk.subarray(0, 4096 - prefix.length)]);
    }
    return { sha256: hash.digest('hex'), bytes, prefix: prefix.toString('utf8') };
  } catch { failure('Backup file could not be read.'); }
}

export async function wranglerRunner(args, { root, remote }) {
  const env = { ...process.env, WRANGLER_SEND_METRICS: 'false', WRANGLER_WRITE_LOGS: 'false', CI: 'true' };
  for (const key of Object.keys(env)) {
    if (key === 'NODE_OPTIONS' || key === 'CLOUDFLARE_ENV' || key === 'CLOUDFLARE_ACCOUNT_ID' ||
        (key.startsWith('WRANGLER_') && !['WRANGLER_SEND_METRICS', 'WRANGLER_WRITE_LOGS'].includes(key)) ||
        (!remote && (key.startsWith('CLOUDFLARE_') || key.startsWith('CF_')))) delete env[key];
  }
  try {
    const result = await execute(process.execPath, [join(root, 'node_modules/wrangler/bin/wrangler.js'), ...args],
      { cwd: root, env, timeout: 180000, maxBuffer: 16 * 1024 * 1024 });
    return result.stdout;
  } catch { failure('Wrangler maintenance command failed; no backup was marked valid.'); }
}

function parseArgs(argv) {
  const [operation, ...rest] = argv;
  if (!['backup', 'preflight-update'].includes(operation)) failure('Choose backup or preflight-update.');
  const options = { operation, remote: false, paused: false };
  const seen = new Set();
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    if (seen.has(flag)) failure('Duplicate maintenance option.');
    seen.add(flag);
    if (flag === '--remote') options.remote = true;
    else if (flag === '--ack-writes-paused') options.paused = true;
    else if (['--out', '--backup'].includes(flag) && rest[i + 1] && !rest[i + 1].startsWith('--')) options[flag.slice(2)] = rest[++i];
    else failure('Unsupported maintenance option.');
  }
  if (options.remote && !options.paused) failure('Remote maintenance requires acknowledgement that application writes and all DDL are paused.');
  if ((operation === 'backup' && (!options.out || options.backup)) ||
      (operation === 'preflight-update' && (!options.backup || options.out))) failure('Specify --out for backup or --backup for preflight-update.');
  return options;
}

export async function maintenance(argv, { root = workersRoot, repo = resolve(root, '..'), runner = wranglerRunner } = {}) {
  const options = parseArgs(argv);
  const config = await checkConfigs(root);
  if (options.remote && !config.writesPaused) failure('Enable and deploy MCP maintenance mode before remote maintenance.');
  const mode = options.remote ? 'remote' : 'local';
  const base = [`--${mode}`, '--config', config.path, '--env', ''];
  const call = async args => {
    try { return await runner([...args, ...base], { root, remote: options.remote }); }
    catch { failure('Maintenance subprocess failed; no backup was marked valid.'); }
  };
  const snapshot = async () => {
    try {
      const data = JSON.parse(await call(['d1', 'execute', 'DB', '--command', metadataSql, '--json']));
      if (data.length !== 3 || data.some(row => row.success === false || !Array.isArray(row.results)) ||
          data[0].results.length !== 1 || !Number.isSafeInteger(data[0].results[0].revision) ||
          data[0].results[0].revision < 0 || !data[1].results.length) failure('Invalid metadata.');
      return { revision: data[0].results[0].revision, schemaHash: digest(JSON.stringify(data[1].results)),
        migrationHash: digest(JSON.stringify(data[2].results)) };
    } catch { failure('Database consistency metadata is unavailable.'); }
  };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const verifyConfig = async () => {
    if ((await checkConfigs(root)).configHash !== config.configHash) failure('Production configuration changed during maintenance.');
  };
  const oldMask = process.umask(0o077);
  try {
    if (options.operation === 'backup') {
      const out = await externalPath(options.out, repo);
      try { await mkdir(out, { mode: 0o700 }); }
      catch { failure('Output directory must be new; refusing to overwrite existing files.'); }
      await privateEntry(out, true);
      const before = await snapshot();
      const files = {};
      for (const [name, flag] of [['schema.sql', '--no-data'], ['data.sql', '--no-schema']]) {
        const partial = join(out, `${name}.partial`);
        await writeFile(partial, '', { flag: 'wx', mode: 0o600 });
        await call(['d1', 'export', 'DB', flag, '--output', partial]);
        await privateEntry(partial);
        const sql = await fileDigest(partial);
        if (!sql.bytes || (name === 'data.sql' && !/PRAGMA\s+defer_foreign_keys\s*=\s*(TRUE|ON|1)\s*;/i.test(sql.prefix))) {
          failure('Export is incomplete or lacks deferred foreign-key protection.');
        }
        files[name] = { sha256: sql.sha256, bytes: sql.bytes };
      }
      const after = await snapshot();
      if (!same(before, after)) failure('Database changed during export; partial files are not a valid backup.');
      await verifyConfig();
      const manifest = { format: 1, valid: true, mode, target: config.target, configHash: config.configHash,
        createdAt: new Date().toISOString(), writesPausedAcknowledged: options.paused, snapshot: before, files };
      for (const name of Object.keys(files)) await rename(join(out, `${name}.partial`), join(out, name));
      await writeFile(join(out, 'manifest.json.partial'), `${JSON.stringify(manifest, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
      await rename(join(out, 'manifest.json.partial'), join(out, 'manifest.json'));
      return { checks: { configuration: true, exports: true, unchangedRevisionSchemaMigrations: true, backupValid: true } };
    }
    const backup = await externalPath(options.backup, repo);
    await privateEntry(backup, true);
    await privateEntry(join(backup, 'manifest.json'));
    let manifest;
    try { manifest = JSON.parse(await readFile(join(backup, 'manifest.json'), 'utf8')); }
    catch { failure('Valid backup manifest is required.'); }
    if (manifest.format !== 1 || manifest.valid !== true || manifest.mode !== mode ||
        manifest.target !== config.target || manifest.configHash !== config.configHash ||
        !same(Object.keys(manifest.files ?? {}).sort(), ['data.sql', 'schema.sql'])) failure('Backup does not match this target and configuration.');
    const before = await snapshot();
    for (const name of ['schema.sql', 'data.sql']) {
      await privateEntry(join(backup, name));
      const content = await fileDigest(join(backup, name));
      if (content.sha256 !== manifest.files[name].sha256 || content.bytes !== manifest.files[name].bytes) failure('Backup checksum verification failed.');
    }
    const after = await snapshot();
    if (!same(before, after) || !same(before, manifest.snapshot)) failure('Backup is stale or database changed during preflight.');
    await verifyConfig();
    return { checks: { configuration: true, backupChecksums: true, currentRevisionSchemaMigrations: true },
      note: 'No deployment, migration, restore or deletion was performed. This check is not a write lock.' };
  } finally { process.umask(oldMask); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  maintenance(process.argv.slice(2)).then(result => console.log(JSON.stringify(result))).catch(() => {
    console.error('Maintenance failed; no deployment, migration, restore or deletion was performed.');
    process.exitCode = 1;
  });
}

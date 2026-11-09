import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdtemp, writeFile, readFile, mkdir, readdir, chmod, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { encryptBackup, restoreDrill } from './maintenance.mjs';

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), 'ai-board-backup-fixture-'));
  const source = join(directory, 'board-startup-fixture.mv.db');
  const key = join(directory, 'key');
  const output = join(directory, 'destination');
  await mkdir(output);
  await writeFile(source, 'H:2 isolated fixture, not a usable database', { mode: 0o600 });
  await writeFile(key, randomBytes(32), { mode: 0o600 });
  return { directory, source, key, output };
}

test('authenticated roundtrip preserves source and reports its narrow verification scope', async () => {
  const f = await fixture(), before = await readFile(f.source);
  const result = await encryptBackup(f.source, f.output, f.key);
  assert.equal(result.offsiteVerified, false);
  const report = await restoreDrill(result.output, f.key);
  assert.deepEqual(await readFile(report.restored), before);
  assert.deepEqual(await readFile(f.source), before);
  assert.equal(report.databaseOpened, false);
  assert.equal(report.businessAcceptance, 'NOT_EXECUTED');
  assert.match(report.restored, /ai-board-restore-drill-/);
  assert.equal((await readdir(f.output)).length, 1);
});

test('missing destination and live database filename fail closed', async () => {
  const f = await fixture();
  await assert.rejects(encryptBackup(f.source, undefined, f.key), /目的/);
  const live = join(f.directory, 'board.mv.db');
  await writeFile(live, 'H:2 live name fixture');
  await assert.rejects(encryptBackup(live, f.output, f.key), /完成快照/);
  assert.deepEqual(await readdir(f.output), []);
});

test('tampered metadata/ciphertext and wrong key cannot become accepted restored files', async () => {
  const f = await fixture();
  const result = await encryptBackup(f.source, f.output, f.key);
  const envelope = JSON.parse(await readFile(result.output, 'utf8'));
  envelope.metadata.sourceName = 'forged';
  const tampered = join(f.directory, 'tampered.abbackup');
  await writeFile(tampered, JSON.stringify(envelope));
  await assert.rejects(restoreDrill(tampered, f.key));
  const original = JSON.parse(await readFile(result.output, 'utf8'));
  const bytes = Buffer.from(original.ciphertext, 'base64'); bytes[0] ^= 1;
  original.ciphertext = bytes.toString('base64');
  await writeFile(tampered, JSON.stringify(original));
  await assert.rejects(restoreDrill(tampered, f.key));
  const otherKey = join(f.directory, 'other-key');
  await writeFile(otherKey, randomBytes(32), { mode: 0o600 });
  await assert.rejects(restoreDrill(result.output, otherKey));
});

test('invalid snapshot and permissive key files are refused before writing', async () => {
  const f = await fixture();
  await writeFile(f.source, 'not an H2 file');
  await assert.rejects(encryptBackup(f.source, f.output, f.key), /檔頭/);
  if (process.platform !== 'win32') {
    await chmod(f.key, 0o644);
    await assert.rejects(encryptBackup(f.source, f.output, f.key), /金鑰檔/);
    const keyLink = join(f.directory, 'key-link');
    await symlink(f.key, keyLink);
    await assert.rejects(encryptBackup(f.source, f.output, keyLink));
  }
  assert.deepEqual(await readdir(f.output), []);
});

test('zip snapshot extraction uses a fixed isolated output path and rejects traversal', async () => {
  const f = await fixture();
  const zip = join(f.directory, 'board-scheduled-fixture.zip');
  for (const entry of ['snapshot.mv.db', '../escape.mv.db']) {
    execFileSync('python3', ['-c', 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],"w"); z.writestr(sys.argv[2],b"H:2 zip fixture"); z.close()', zip, entry]);
    const encrypted = await encryptBackup(zip, f.output, f.key);
    if (entry.startsWith('..')) {
      await assert.rejects(restoreDrill(encrypted.output, f.key));
    } else {
      const report = await restoreDrill(encrypted.output, f.key);
      assert.equal((await readFile(report.restored)).toString(), 'H:2 zip fixture');
    }
  }
});

test('CLI failure is nonzero and never prints source contents or key material', async () => {
  const f = await fixture();
  try {
    execFileSync(process.execPath, ['scripts/backup-check/maintenance.mjs', 'encrypt', f.source, '/nonexistent-board-destination', f.key], { encoding: 'utf8', stdio: 'pipe' });
    assert.fail('expected failure');
  } catch (error) {
    assert.equal(error.status, 1);
    assert.match(error.stderr, /未完成/);
    assert.doesNotMatch(error.stderr, /isolated fixture|ENCRYPTED_COPY_WRITTEN/);
  }
});

// Reproducible fixture only: all database files, key and output are new temporary paths.
// The H2 driver is an operator-selected trusted dependency, never an agent evidence artifact.
import { mkdtemp, mkdir, writeFile, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { encryptBackup, restoreDrill } from './maintenance.mjs';

try {
  if (process.argv.length !== 3) throw new Error('用法：node scripts/backup-check/real-h2-drill.mjs <可信任的H2-driver.jar>');
  const jar = await realpath(process.argv[2]);
  const directory = await mkdtemp(join(tmpdir(), 'ai-board-real-h2-drill-'));
  const key = join(directory, 'key');
  await writeFile(key, randomBytes(32), { mode: 0o600 });
  const destination = join(directory, 'encrypted');
  await mkdir(destination);
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const source = join(directory, 'source');
  const zip = join(directory, 'board-scheduled-isolated.zip');
  function sql(db, query) {
    const output = execFileSync(java, ['-cp', jar, 'org.h2.tools.Shell', '-url', 'jdbc:h2:file:' + db,
      '-user', 'sa', '-password', '', '-sql', query], { encoding: 'utf8', timeout: 30_000, maxBuffer: 8192 });
    // H2 Shell can print SQL errors yet exit zero; do not mistake process success for query success.
    if (/Error:|Exception/.test(output)) throw new Error('H2 query failed');
    return output;
  }
  sql(source, "CREATE TABLE drill_fixture(id BIGINT PRIMARY KEY, value_text VARCHAR(100)); " +
    "INSERT INTO drill_fixture VALUES(1, 'isolated-data-survives'); BACKUP TO '" + zip.replaceAll("'", "''") + "';");
  const encrypted = await encryptBackup(zip, destination, key);
  const report = await restoreDrill(encrypted.output, key);
  const restoredRow = sql(report.restored.slice(0, -6), 'SELECT id,value_text FROM drill_fixture;');
  const sourceRow = sql(source, 'SELECT id,value_text FROM drill_fixture;');
  if (!restoredRow.includes('isolated-data-survives') || !sourceRow.includes('isolated-data-survives')) throw new Error('row validation failed');
  const result = { result: 'REAL_H2_ROW_REOPEN_VERIFIED', directory, encrypted: encrypted.output,
    restored: report.restored, snapshotSha256: report.snapshotSha256, restoredSha256: report.restoredSha256,
    sourceRow, restoredRow, offsiteVerified: false, businessAcceptance: 'NOT_EXECUTED' };
  await writeFile(join(directory, 'actual-h2-result.json'), JSON.stringify(result, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(result, null, 2));
} catch {
  console.error('[real-h2-drill][失敗] 隔離 H2 演練未通過；未操作正式看板，未確認離機備份。');
  process.exitCode = 1;
}

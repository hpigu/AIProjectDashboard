// Explicit maintenance CLI. Never starts a board, reads its settings, or runs a supplied command.
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { constants } from 'node:fs';
import { open, writeFile, realpath, mkdtemp } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execute = promisify(execFile);
const FORMAT = 'ai-board-backup-aes256gcm-v1';
const MAX = 128 * 1024 * 1024;
const digest = data => createHash('sha256').update(data).digest('hex');
const fail = message => { throw new Error(message); };

// O_NOFOLLOW and the same open descriptor close the symlink check/read race on POSIX.
async function boundedFile(path, limit = MAX, secret = false) {
  const handle = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > limit) fail('來源必須是大小限制內的一般檔案');
    if (secret && process.platform !== 'win32' &&
        ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid())) {
      fail('金鑰檔必須由目前使用者持有，且群組與其他使用者不得存取（chmod 600）');
    }
    return await handle.readFile();
  } finally { await handle.close(); }
}
async function keyFrom(path) {
  const key = await boundedFile(path, 32, true);
  if (key.length !== 32) fail('金鑰必須是 32 bytes 隨機資料，不能使用密碼文字');
  return key;
}
function assertType(bytes, type) {
  if (type === 'mv.db' && bytes.subarray(0, 3).toString() === 'H:2') return;
  if (type === 'zip' && bytes.subarray(0, 4).equals(Buffer.from([80, 75, 3, 4]))) return;
  fail('備份格式或檔頭不符；這僅是檔案完整性檢查，不保證 H2 能開啟');
}

export async function encryptBackup(source, destination, keyFile) {
  if (!source || !destination || !keyFile) fail('必須明確指定備份來源、目的目錄與金鑰檔；沒有預設離機目的地');
  const input = await realpath(source);
  // Only known completed snapshot filenames are accepted; never accept a live board.mv.db.
  const match = /^board-(startup-.+\.mv\.db|(shutdown|scheduled)-.+\.zip)$/.exec(basename(input));
  if (!match) fail('只能使用 board-startup-*.mv.db 或 board-{shutdown,scheduled}-*.zip 完成快照');
  const type = input.endsWith('.zip') ? 'zip' : 'mv.db';
  const key = await keyFrom(keyFile);
  const data = await boundedFile(input);
  assertType(data, type);
  const metadata = { format: FORMAT, type, sourceName: basename(input), createdAt: new Date().toISOString(),
    plaintextSha256: digest(data), bytes: data.length };
  const aad = JSON.stringify(metadata);
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(aad));
  const ciphertext = Buffer.concat([cipher.update(data), cipher.final()]);
  const envelope = JSON.stringify({ metadata, iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') });
  // Existing, explicitly chosen directory only; no remote commands or destination discovery.
  const directory = await realpath(destination);
  const output = join(directory, `${basename(input)}.${randomBytes(8).toString('hex')}.abbackup`);
  await writeFile(output, envelope, { flag: 'wx', mode: 0o600 });
  return { status: 'ENCRYPTED_COPY_WRITTEN', output, ciphertextSha256: digest(envelope),
    offsiteVerified: false, note: '僅確認指定路徑寫入成功；是否離機、遠端持久性與可用性尚未驗證' };
}

export async function restoreDrill(archive, keyFile) {
  if (!archive || !keyFile) fail('必須指定加密備份與金鑰檔');
  const key = await keyFrom(keyFile);
  const envelope = JSON.parse((await boundedFile(archive, MAX * 2)).toString());
  const { metadata, iv, tag, ciphertext } = envelope;
  if (metadata?.format !== FORMAT || !['zip', 'mv.db'].includes(metadata.type)) fail('不支援的備份版本');
  const nonce = Buffer.from(iv, 'base64'), authTag = Buffer.from(tag, 'base64');
  if (nonce.length !== 12 || authTag.length !== 16) fail('加密參數長度不符');
  const decipher = createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAAD(Buffer.from(JSON.stringify(metadata)));
  decipher.setAuthTag(authTag);
  // Nothing is written before authenticated decryption succeeds.
  const plain = Buffer.concat([decipher.update(Buffer.from(ciphertext, 'base64')), decipher.final()]);
  if (plain.length > MAX || metadata.bytes !== plain.length || metadata.plaintextSha256 !== digest(plain)) fail('備份雜湊或大小不符');
  assertType(plain, metadata.type);
  const directory = await mkdtemp(join(tmpdir(), 'ai-board-restore-drill-'));
  const restored = join(directory, 'restored.mv.db');
  if (metadata.type === 'mv.db') {
    await writeFile(restored, plain, { flag: 'wx', mode: 0o600 });
  } else {
    const zip = join(directory, 'snapshot.zip');
    await writeFile(zip, plain, { flag: 'wx', mode: 0o600 });
    // Fixed, reviewed extractor: no shell, archive paths are never used as output paths.
    // Reject zip bombs, multiple databases, links and traversal before reading an entry.
    await execute('python3', ['-c', `
import zipfile, stat, sys, os
with zipfile.ZipFile(sys.argv[1]) as z:
    entries = z.infolist()
    if len(entries) != 1: raise ValueError('expected exactly one H2 database')
    item = entries[0]
    if '/' in item.filename or '\\\\' in item.filename or not item.filename.endswith('.mv.db'): raise ValueError('unsafe archive path')
    if stat.S_ISLNK(item.external_attr >> 16) or item.file_size > ${MAX}: raise ValueError('unsafe entry')
    data = z.read(item)
    if data[:3] != b'H:2': raise ValueError('invalid H2 header')
    fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as f: f.write(data)
`, zip, restored], { timeout: 30_000, maxBuffer: 4096 });
  }
  const actual = await boundedFile(restored);
  assertType(actual, 'mv.db');
  const report = { status: 'FILE_RESTORE_VERIFIED', scope: 'AUTHENTICATED_FILE_RESTORE_ONLY',
    sourceSnapshot: metadata.sourceName, snapshotSha256: metadata.plaintextSha256,
    restoredSha256: digest(actual), restored, checkedAt: new Date().toISOString(),
    databaseOpened: false, businessAcceptance: 'NOT_EXECUTED', offsiteVerified: false,
    note: '未啟動應用、未連線任何埠號、未驗證 H2 可開啟或業務資料正確性；檔案保留供後續隔離演練' };
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { flag: 'wx', mode: 0o600 });
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, ...args] = process.argv.slice(2);
  try {
    const result = command === 'encrypt' && args.length === 3 ? await encryptBackup(...args)
      : command === 'drill' && args.length === 2 ? await restoreDrill(...args)
      : fail('用法：node scripts/backup-check/maintenance.mjs encrypt <完成快照> <明確目的目錄> <32-byte金鑰檔> | drill <加密檔> <金鑰檔>');
    console.log(JSON.stringify(result, null, 2));
  } catch {
    // Never print a key, source contents, or child-process arguments/error buffers.
    console.error('[backup-maintenance][失敗] 備份或演練未完成；請檢查參數、格式、金鑰、權限與可用空間。既有資料未覆寫，未確認離機備份。');
    process.exitCode = 1;
  }
}

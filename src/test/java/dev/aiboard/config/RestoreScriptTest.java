package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 實際執行 bin/restore-db.sh，驗證兩種備份格式都還原得回來、且高風險路徑都有
 * 攔截。沿用 {@link StartupBackupScriptTest} 的作法（用 ProcessBuilder 跑真正的
 * 腳本），因為這類腳本的價值完全在於「在真實 shell 下的行為」，用 mock 驗不出來。
 *
 * <p>H2 的 .mv.db 在這裡以「H:2」開頭的假檔案代表：腳本只做檔案層級的搬移與
 * 檔頭驗證，不開啟資料庫，因此不需要真的 H2 檔案就能完整覆蓋腳本邏輯。
 */
class RestoreScriptTest {

    @TempDir
    Path tempDir;

    private final Path script = Path.of("bin/restore-db.sh").toAbsolutePath().normalize();

    @Test
    void restoresStartupBackupAndPreservesExistingDatabase() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path database = dataDir.resolve("board.mv.db");
        Files.writeString(database, "H:2-current-database");
        Path backup = backups.resolve("board-startup-20240101T000000Z-UTC.mv.db");
        Files.writeString(backup, "H:2-backup-content");

        ProcessResult result = run(backups, dataDir, List.of(backup.toString(), "--yes"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.readString(database)).isEqualTo("H:2-backup-content");

        List<Path> preserved = listMatching(dataDir, "board.mv.db.pre-restore-");
        assertThat(preserved).as("既有資料庫必須改名保留，不得直接覆蓋").hasSize(1);
        assertThat(Files.readString(preserved.get(0))).isEqualTo("H:2-current-database");
        assertThat(result.output()).contains("還原完成");
    }

    @Test
    void restoredDatabaseFileIsRestrictedToOwnerOnly() throws Exception {
        // #142：還原流程重寫的 .mv.db 不得沿用預設 umask（macOS/Linux 常見
        // 022，即其他使用者可讀）；還原的正是使用者的完整看板資料。
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "此檔案系統不支援 POSIX 權限，略過");

        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path database = dataDir.resolve("board.mv.db");
        Files.writeString(database, "H:2-current-database");
        Files.setPosixFilePermissions(database,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        Path backup = backups.resolve("board-startup-20240101T000000Z-UTC.mv.db");
        Files.writeString(backup, "H:2-backup-content");

        ProcessResult result = run(backups, dataDir, List.of(backup.toString(), "--yes"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.getPosixFilePermissions(database))
                .as(result.output())
                .isEqualTo(LocalFilePermissionHardener.FILE_PERMISSIONS);
    }

    @Test
    void restoresShutdownZipBackupByExtractingInnerDatabase() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path backup = backups.resolve("board-shutdown-20240102T000000Z-UTC.zip");
        writeZipContaining(backup, "board.mv.db", "H:2-from-shutdown-zip");

        ProcessResult result = run(backups, dataDir, List.of(backup.toString(), "--yes"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.readString(dataDir.resolve("board.mv.db"))).isEqualTo("H:2-from-shutdown-zip");
    }

    @Test
    void latestPicksNewestBackupAcrossBothFormats() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        Path older = backups.resolve("board-startup-20240101T000000Z-UTC.mv.db");
        Files.writeString(older, "H:2-older");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 86_400_000L));

        Path newer = backups.resolve("board-shutdown-20240103T000000Z-UTC.zip");
        writeZipContaining(newer, "board.mv.db", "H:2-newer");

        ProcessResult result = run(backups, dataDir, List.of("latest", "--yes"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.readString(dataDir.resolve("board.mv.db"))).isEqualTo("H:2-newer");
    }

    @Test
    void listShowsBothBackupKindsWithoutTouchingDatabase() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path database = dataDir.resolve("board.mv.db");
        Files.writeString(database, "H:2-untouched");
        Files.writeString(backups.resolve("board-startup-20240101T000000Z-UTC.mv.db"), "H:2-a");
        writeZipContaining(backups.resolve("board-shutdown-20240102T000000Z-UTC.zip"), "board.mv.db", "H:2-b");

        ProcessResult result = run(backups, dataDir, List.of("--list"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("board-startup-20240101T000000Z-UTC.mv.db")
                .contains("board-shutdown-20240102T000000Z-UTC.zip");
        assertThat(Files.readString(database)).isEqualTo("H:2-untouched");
    }

    @Test
    void corruptBackupIsRejectedAndDatabaseLeftIntact() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path database = dataDir.resolve("board.mv.db");
        Files.writeString(database, "H:2-current-database");
        Path backup = backups.resolve("board-startup-20240101T000000Z-UTC.mv.db");
        Files.writeString(backup, "this-is-not-an-h2-file");

        ProcessResult result = run(backups, dataDir, List.of(backup.toString(), "--yes"));

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(Files.readString(database))
                .as("驗證失敗時不得動到現有資料庫")
                .isEqualTo("H:2-current-database");
        assertThat(listMatching(dataDir, "board.mv.db.pre-restore-")).isEmpty();
        assertThat(listMatching(dataDir, "board.mv.db.restore.tmp")).isEmpty();
    }

    @Test
    void nonInteractiveRunWithoutYesIsRefused() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path database = dataDir.resolve("board.mv.db");
        Files.writeString(database, "H:2-current-database");
        Path backup = backups.resolve("board-startup-20240101T000000Z-UTC.mv.db");
        Files.writeString(backup, "H:2-backup-content");

        ProcessResult result = run(backups, dataDir, List.of(backup.toString()));

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("--yes");
        assertThat(Files.readString(database)).isEqualTo("H:2-current-database");
    }

    @Test
    void missingBackupFileFailsBeforeTouchingAnything() throws Exception {
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(dataDir.resolve("board.mv.db"), "H:2-current-database");

        ProcessResult result = run(backups, dataDir,
                List.of(backups.resolve("does-not-exist.mv.db").toString(), "--yes"));

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(Files.readString(dataDir.resolve("board.mv.db"))).isEqualTo("H:2-current-database");
    }

    // -----------------------------------------------------------------------

    private ProcessResult run(Path backupDir, Path dataDir, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(script.getParent().getParent().toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("BOARD_BACKUP_DIR", backupDir.toString());
        builder.environment().put("BOARD_DB_URL",
                "jdbc:h2:file:" + dataDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE");
        // 這些測試不得碰到正式看板：埠號指向一個不會有人聽的高位埠，
        // 家目錄也指到 tempDir，避免讀到開發機上真正的 PID 檔。
        builder.environment().put("BOARD_PORT", "18099");
        builder.environment().put("BOARD_HOME_DIR", tempDir.resolve("home").toString());

        Process process = builder.start();
        // 非互動確認路徑需要 stdin 立即關閉，否則腳本會等在 read
        process.getOutputStream().close();
        String output = readAll(process.getInputStream());
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("restore-db.sh 逾時未結束：" + output);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private static String readAll(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static void writeZipContaining(Path zipFile, String entryName, String content) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static List<Path> listMatching(Path dir, String prefix) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

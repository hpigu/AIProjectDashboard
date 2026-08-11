package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StartupBackupScriptTest {

    @TempDir
    Path tempDir;

    private final Path script = Path.of("bin/backup-db.sh").toAbsolutePath().normalize();

    // 這裡跑的是真正的 POSIX shell 腳本，只在 Linux/macOS 上有意義。Windows 的
    // 對應實作是 bin/backup-db.ps1，由 scripts/windows-check/check.ps1 涵蓋。
    // 少了這道防護，Windows 上的 `bash` 會被解析成 WSL；沒有安裝任何
    // distribution 時它印出安裝指引並回傳 1，測試就會拿這段訊息去比對而失敗。
    @BeforeEach
    void requirePosixShellHost() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "bin/backup-db.sh 僅適用於 Linux/macOS；Windows 由 backup-db.ps1 與 windows-check 涵蓋");
    }

    @Test
    void validDatabaseCreatesVerifiedBackupWithoutTmpResidue() throws Exception {
        Path database = tempDir.resolve("board.mv.db");
        Path backups = tempDir.resolve("backups");
        Files.createDirectories(backups);
        Files.write(database, "H:2-test-database".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path staleTmp = backups.resolve("board-startup-20240101T000000Z-UTC.mv.db.tmp");
        Files.writeString(staleTmp, "interrupted-copy");

        ProcessResult result = run(database, backups, null, null);

        assertThat(result.exitCode()).as(result.output()).isZero();
        try (Stream<Path> files = Files.list(backups)) {
            List<Path> created = files.toList();
            List<Path> completed = created.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".mv.db"))
                    .toList();
            assertThat(completed).hasSize(1);
            assertThat(completed.get(0).getFileName().toString())
                    .matches("board-startup-\\d{8}T\\d{6}Z-.+\\.mv\\.db");
            assertThat(Files.readAllBytes(completed.get(0))).isEqualTo(Files.readAllBytes(database));
            assertThat(created).noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }

    @Test
    void backupDirectoryAndFileAreRestrictedToOwnerOnly() throws Exception {
        // #142：新建的備份目錄與備份檔都不得沿用預設 umask。
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "此檔案系統不支援 POSIX 權限，略過");

        Path database = tempDir.resolve("board.mv.db");
        Path backups = tempDir.resolve("fresh-backups");
        Files.write(database, "H:2-test-database".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ProcessResult result = run(database, backups, null, null);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.getPosixFilePermissions(backups))
                .as(result.output())
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);

        try (Stream<Path> files = Files.list(backups)) {
            Path created = files.filter(p -> p.getFileName().toString().endsWith(".mv.db"))
                    .findFirst().orElseThrow();
            assertThat(Files.getPosixFilePermissions(created))
                    .as(result.output())
                    .isEqualTo(LocalFilePermissionHardener.FILE_PERMISSIONS);
        }
    }

    @Test
    void invalidDatabaseFailsClosedAndRemovesTmp() throws Exception {
        Path database = tempDir.resolve("corrupt.mv.db");
        Path backups = tempDir.resolve("failed-backups");
        Files.writeString(database, "not-an-h2-store");

        ProcessResult result = run(database, backups, null, null);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        if (Files.isDirectory(backups)) {
            try (Stream<Path> files = Files.list(backups)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void retentionDeletesExpiredBackupsButPreservesNewestSeven() throws Exception {
        Path database = tempDir.resolve("retention.mv.db");
        Path backups = tempDir.resolve("retention-backups");
        Files.createDirectories(backups);
        Files.write(database, "H:2-current".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileTime old = FileTime.from(Instant.now().minusSeconds(2L * 86400));
        for (int i = 0; i < 10; i++) {
            Path backup = backups.resolve("board-startup-20240101T0000"
                    + String.format("%02d", i) + "Z-UTC.mv.db");
            Files.write(backup, ("H:2-old-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.setLastModifiedTime(backup, old);
        }

        ProcessResult result = run(database, backups, "0", "7");

        assertThat(result.exitCode()).as(result.output()).isZero();
        try (Stream<Path> files = Files.list(backups)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".mv.db")))
                    .as("所有備份都過期時也必須保留最新七份")
                    .hasSize(7);
        }
    }

    /**
     * 保留策略必須留下「最新的」七份，不是任意七份。
     *
     * <p>原本的測試只斷言 hasSize(7)，因此驗不出實際踩到的 bug：取 mtime 用的是
     * {@code stat -f '%m' || stat -c '%Y'}，而 GNU coreutils 的 {@code -f} 是
     * 「顯示檔案系統狀態」、會以 exit 0 印出多行檔案系統資訊，於是 {@code ||}
     * 右邊永遠不執行，排序鍵變成垃圾字串。份數碰巧仍是 7，但保留下來的是哪七份
     * 完全隨機——在 Linux 上可能把最新的備份刪掉。這個測試釘住排序語意。
     */
    @Test
    void retentionKeepsTheNewestBackupsSpecifically() throws Exception {
        Path database = tempDir.resolve("ordering.mv.db");
        Path backups = tempDir.resolve("ordering-backups");
        Files.createDirectories(backups);
        Files.write(database, "H:2-current".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 十份都過期，但 mtime 明確遞增：index 越大越新。
        for (int i = 0; i < 10; i++) {
            Path backup = backups.resolve("board-startup-20240101T0000"
                    + String.format("%02d", i) + "Z-UTC.mv.db");
            Files.write(backup, ("H:2-old-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.setLastModifiedTime(backup, FileTime.from(Instant.now().minusSeconds((30L - i) * 86400)));
        }

        ProcessResult result = run(database, backups, "0", "7");

        assertThat(result.exitCode()).as(result.output()).isZero();
        try (Stream<Path> files = Files.list(backups)) {
            List<String> survivors = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".mv.db"))
                    .sorted()
                    .toList();

            assertThat(survivors).as(result.output()).hasSize(7);
            // 最舊的三份（index 0、1、2）必須被刪掉，最新的四份必須留著。
            assertThat(survivors)
                    .as("最舊的備份才該被刪除：%s", result.output())
                    .noneMatch(name -> name.contains("T000000Z"))
                    .noneMatch(name -> name.contains("T000001Z"))
                    .noneMatch(name -> name.contains("T000002Z"))
                    .anyMatch(name -> name.contains("T000009Z"))
                    .anyMatch(name -> name.contains("T000008Z"))
                    .anyMatch(name -> name.contains("T000007Z"))
                    .anyMatch(name -> name.contains("T000006Z"));
        }
    }

    private ProcessResult run(Path database, Path backups, String days, String minimum) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString(),
                database.toString(), backups.toString());
        builder.redirectErrorStream(true);
        if (days != null) {
            builder.environment().put("BACKUP_RETENTION_DAYS", days);
        }
        if (minimum != null) {
            builder.environment().put("BACKUP_RETENTION_MIN_COUNT", minimum);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

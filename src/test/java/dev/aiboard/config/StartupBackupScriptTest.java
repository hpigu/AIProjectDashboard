package dev.aiboard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StartupBackupScriptTest {

    @TempDir
    Path tempDir;

    private final Path script = Path.of("bin/backup-db.sh").toAbsolutePath().normalize();

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

package dev.aiboard.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 關閉前備份與排程備份共用同一套快照邏輯，差別只在 phase 名稱。這裡測的重點是
 * <b>phase 之間互不干擾</b>——那是這個設計成立與否的關鍵，也是唯一會安靜出錯的地方。
 *
 * <p>若兩種備份共用同一個保留桶，長時間運行下每 6 小時一次的排程備份很快就會把
 * 「保留最新 N 份」的額度吃光，把關閉前那份一致性快照擠掉；而那份往往正是最想留的。
 * 這種錯誤不會有任何錯誤訊息，只會在某天需要還原時才發現快照不見了。
 */
class H2SnapshotServiceTest {

    @TempDir
    Path tempDir;

    private Path backupDir;
    private DataSource dataSource;
    private String dbUrl;

    @BeforeEach
    void setUp() throws Exception {
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);

        dbUrl = "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE";
        DriverManagerDataSource ds = new DriverManagerDataSource(dbUrl, "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        this.dataSource = ds;

        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sample (id INT PRIMARY KEY, note VARCHAR(64))");
            statement.execute("INSERT INTO sample VALUES (1, 'before-snapshot')");
        }
    }

    private H2SnapshotService newService(int retentionDays, int retentionMinCount) {
        return new H2SnapshotService(dataSource, dbUrl, backupDir.toString(), retentionDays, retentionMinCount);
    }

    @Test
    void createsRestorableScheduledSnapshotUnderItsOwnPhaseName() throws Exception {
        Optional<Path> created = newService(30, 7).createSnapshot(ScheduledBackupService.PHASE, "[test]");

        assertThat(created).isPresent();
        Path snapshot = created.get();
        assertThat(snapshot.getFileName().toString()).matches("^board-scheduled-\\d{8}T\\d{6}Z-.+\\.zip$");
        assertThat(Files.size(snapshot)).isGreaterThan(0);

        // 不只確認檔案存在：zip 裡必須真的有 H2 的資料庫檔，否則這份備份還原不回來。
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
            assertThat(zip.stream().map(entry -> entry.getName()))
                    .as("備份 zip 內應含 .mv.db")
                    .anyMatch(name -> name.endsWith(".mv.db"));
        }

        // 成功的備份不得留下可能被誤認為成品的 tmp。
        assertThat(listNames(".tmp")).isEmpty();
    }

    @Test
    void retentionIsScopedPerPhaseSoScheduledBackupsNeverEvictShutdownOnes() throws Exception {
        FileTime expired = FileTime.from(Instant.now().minusSeconds(31L * 86400));
        for (int i = 0; i < 10; i++) {
            writeExpired("board-shutdown-20240101T0000" + String.format("%02d", i) + "Z-UTC.zip", expired);
            writeExpired("board-scheduled-20240101T0000" + String.format("%02d", i) + "Z-UTC.zip", expired);
        }

        newService(30, 7).createSnapshot(ScheduledBackupService.PHASE, "[test]");

        // 排程備份套用自己的保留策略：10 份過期的加上剛產生的 1 份，收斂到 7 份。
        assertThat(listNames("board-scheduled-"))
                .as("scheduled 應套用自己的保留策略")
                .hasSize(7);

        // 決定性斷言：關閉前備份一份都不能少。
        assertThat(listNames("board-shutdown-"))
                .as("scheduled 的保留策略不得動到 shutdown 的備份")
                .hasSize(10);
    }

    @Test
    void shutdownRetentionLikewiseLeavesScheduledBackupsAlone() throws Exception {
        FileTime expired = FileTime.from(Instant.now().minusSeconds(31L * 86400));
        for (int i = 0; i < 10; i++) {
            writeExpired("board-shutdown-20240101T0000" + String.format("%02d", i) + "Z-UTC.zip", expired);
            writeExpired("board-scheduled-20240101T0000" + String.format("%02d", i) + "Z-UTC.zip", expired);
        }

        newService(30, 7).createSnapshot(ShutdownBackupService.PHASE, "[test]");

        assertThat(listNames("board-shutdown-")).hasSize(7);
        assertThat(listNames("board-scheduled-"))
                .as("shutdown 的保留策略同樣不得動到 scheduled 的備份")
                .hasSize(10);
    }

    @Test
    void staleTmpCleanupOnlyTouchesItsOwnPhase() throws Exception {
        Files.writeString(backupDir.resolve("board-scheduled-20240101T000000Z-UTC.zip.tmp"), "partial");
        Files.writeString(backupDir.resolve("board-shutdown-20240101T000000Z-UTC.zip.tmp"), "partial");

        newService(30, 7).createSnapshot(ScheduledBackupService.PHASE, "[test]");

        assertThat(listNames("board-scheduled-20240101T000000Z-UTC.zip.tmp"))
                .as("自己 phase 的殘留 tmp 應被清掉")
                .isEmpty();
        assertThat(listNames("board-shutdown-20240101T000000Z-UTC.zip.tmp"))
                .as("別的 phase 的殘留 tmp 不該被碰")
                .hasSize(1);
    }

    @Test
    void nonFileBasedDatabaseIsSkippedRatherThanTreatedAsFailure() {
        H2SnapshotService memoryBacked = new H2SnapshotService(
                dataSource, "jdbc:h2:mem:whatever", backupDir.toString(), 30, 7);

        assertThat(memoryBacked.isFileBasedH2()).isFalse();
        assertThat(memoryBacked.createSnapshot(ScheduledBackupService.PHASE, "[test]")).isEmpty();
    }

    private void writeExpired(String name, FileTime when) throws Exception {
        Path path = backupDir.resolve(name);
        Files.writeString(path, "old");
        Files.setLastModifiedTime(path, when);
    }

    private List<String> listNames(String fragment) throws Exception {
        try (Stream<Path> stream = Files.list(backupDir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(name -> name.contains(fragment))
                    .toList();
        }
    }
}

package dev.aiboard.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import dev.aiboard.DashboardApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 #106 的關閉前一致性備份：實際啟動完整 Spring context（含真正的
 * H2 檔案資料庫與 Flyway migration），呼叫 context.close() 觸發
 * ContextClosedEvent，確認：
 *   1. 正常關閉會產生 shutdown 備份檔（驗收條件 1）。
 *   2. 備份可被還原／開啟，且 project/task/task_dependency/role 等表都在
 *      （驗收條件 2 —— 不只檢查檔案存在，而是真的還原後查表）。
 *   3. 備份目錄不可寫時，備份會失敗但 context.close() 仍正常結束
 *      （驗收條件 3）。
 */
class ShutdownBackupServiceTest {

    private static final Pattern SHUTDOWN_BACKUP_NAME =
            Pattern.compile("^board-shutdown-\\d{8}T\\d{6}Z-.+\\.zip$");

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext context;

    @AfterEach
    void closeContextIfStillOpen() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    void gracefulShutdownProducesReadableBackupContainingAllTables() throws Exception {
        Path dbDir = tempDir.resolve("db");
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(dbDir);
        String dbUrl = "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE";

        context = buildContext(dbUrl, backupDir);
        context.start();

        // 觸發正常關閉：對應「正常 JVM shutdown / SIGTERM / Ctrl+C」的共同
        // 路徑（三者都會走到 context.close()，見 ShutdownBackupService 註解）。
        context.close();

        assertThat(context.isActive()).isFalse();

        List<Path> backups = listShutdownBackups(backupDir);
        assertThat(backups)
                .as("關閉前應產生至少一份 board-shutdown-*.zip 備份")
                .hasSize(1);

        Path backupZip = backups.get(0);
        assertThat(Files.size(backupZip)).isGreaterThan(0);

        // 驗證備份確實是有效的 zip（H2 BACKUP TO 的輸出格式）。
        try (ZipFile zipFile = new ZipFile(backupZip.toFile())) {
            assertThat(zipFile.entries().hasMoreElements())
                    .as("備份 zip 內應至少有一個檔案項目（.mv.db）")
                    .isTrue();
        }

        // 真正還原：解壓縮後用獨立的 H2 連線開啟，確認所有既有表都能查到。
        Path restoreDir = tempDir.resolve("restore");
        Files.createDirectories(restoreDir);
        unzip(backupZip, restoreDir);

        Path restoredMvFile = findMvDbFile(restoreDir);
        assertThat(restoredMvFile).as("解壓後應可找到 .mv.db 檔").isNotNull();

        String restoredDbBaseName = restoredMvFile.getFileName().toString().replace(".mv.db", "");
        String restoredUrl = "jdbc:h2:file:" + restoreDir.resolve(restoredDbBaseName) + ";DB_CLOSE_ON_EXIT=TRUE";

        Set<String> expectedTables = Set.of("PROJECT", "TASK", "TASK_LOG", "TASK_DEPENDENCY", "ROLE");
        try (Connection restored = DriverManager.getConnection(restoredUrl, "sa", "")) {
            Set<String> actualTables = new HashSet<>();
            try (Statement statement = restored.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
                while (rs.next()) {
                    actualTables.add(rs.getString(1));
                }
            }
            assertThat(actualTables).containsAll(expectedTables);

            // project 表在 migration 後應可查詢（即使是空表，SELECT 不噴錯即代表結構完整）。
            try (Statement statement = restored.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM PROJECT")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    void backupFailureDoesNotBlockShutdown() throws Exception {
        Path dbDir = tempDir.resolve("db2");
        Path backupDir = tempDir.resolve("unwritable-backups");
        try {
            Files.createDirectories(dbDir);
            // 備份目錄的「父目錄」設為唯讀，讓 Files.createDirectories(backupDir)
            // 必然失敗（模擬驗收條件 3：備份目錄不可寫）。
            Path unwritableParent = tempDir.resolve("unwritable-parent");
            Files.createDirectories(unwritableParent);
            assertThat(unwritableParent.toFile().setWritable(false)).isTrue();
            backupDir = unwritableParent.resolve("backups");

            String dbUrl = "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE";
            context = buildContext(dbUrl, backupDir);
            context.start();

            // 關鍵斷言：即使備份必然失敗，close() 仍必須在合理時間內正常完成，
            // 不得拋出例外、不得掛住。
            context.close();

            assertThat(context.isActive()).isFalse();
        } finally {
            tempDir.resolve("unwritable-parent").toFile().setWritable(true);
        }
    }

    @Test
    void shutdownBackupRemovesTmpAndKeepsAtLeastSevenOldBackups() throws Exception {
        Path dbDir = tempDir.resolve("retention-db");
        Path backupDir = tempDir.resolve("retention-backups");
        Files.createDirectories(dbDir);
        Files.createDirectories(backupDir);

        FileTime old = FileTime.from(java.time.Instant.now().minusSeconds(31L * 86400));
        for (int i = 0; i < 10; i++) {
            Path backup = backupDir.resolve("board-shutdown-20240101T0000"
                    + String.format("%02d", i) + "Z-UTC.zip");
            Files.writeString(backup, "old-" + i);
            Files.setLastModifiedTime(backup, old);
        }
        Files.writeString(backupDir.resolve("board-shutdown-20231231T235959Z-UTC.zip.tmp"), "partial");

        String dbUrl = "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE";
        context = buildContext(dbUrl, backupDir, 30, 7);
        context.close();

        assertThat(listShutdownBackups(backupDir))
                .as("超過 30 天的 shutdown 備份清理後仍至少保留最新七份")
                .hasSize(7);
        try (Stream<Path> stream = Files.list(backupDir)) {
            assertThat(stream.filter(path -> path.getFileName().toString().endsWith(".tmp")))
                    .as("成功備份不得留下可被誤認為成品的 tmp")
                    .isEmpty();
        }
    }

    private ConfigurableApplicationContext buildContext(String dbUrl, Path backupDir) {
        return buildContext(dbUrl, backupDir, 30, 7);
    }

    private ConfigurableApplicationContext buildContext(String dbUrl, Path backupDir,
                                                         int retentionDays, int retentionMinCount) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(DashboardApplication.class)
                .web(org.springframework.boot.WebApplicationType.SERVLET);
        ConfigurableApplicationContext ctx = builder.application().run(
                buildArgs(dbUrl, backupDir, retentionDays, retentionMinCount));
        return ctx;
    }

    private String[] buildArgs(String dbUrl, Path backupDir) {
        return buildArgs(dbUrl, backupDir, 30, 7);
    }

    private String[] buildArgs(String dbUrl, Path backupDir,
                               int retentionDays, int retentionMinCount) {
        List<String> args = new ArrayList<>();
        args.add("--spring.datasource.url=" + dbUrl);
        args.add("--spring.datasource.username=sa");
        args.add("--spring.datasource.password=");
        args.add("--board.backup.dir=" + backupDir);
        args.add("--board.backup.retention-days=" + retentionDays);
        args.add("--board.backup.retention-min-count=" + retentionMinCount);
        args.add("--server.port=0");
        args.add("--logging.file.name=" + tempDir.resolve("test.log"));
        return args.toArray(new String[0]);
    }

    private List<Path> listShutdownBackups(Path backupDir) throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupDir)) {
            return stream.filter(p -> SHUTDOWN_BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                    .toList();
        }
    }

    private void unzip(Path zipPath, Path targetDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry 逃逸目標目錄: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                    continue;
                }
                Files.createDirectories(outPath.getParent());
                try (var in = zipFile.getInputStream(entry)) {
                    Files.copy(in, outPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path findMvDbFile(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".mv.db"))
                    .findFirst()
                    .orElse(null);
        }
    }
}

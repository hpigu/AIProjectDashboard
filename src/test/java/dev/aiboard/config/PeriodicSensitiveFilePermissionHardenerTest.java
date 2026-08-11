package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #159：#158 週期敏感檔案權限收斂的排程入口回歸測試。
 */
@ExtendWith(OutputCaptureExtension.class)
class PeriodicSensitiveFilePermissionHardenerTest {

    private static final Set<PosixFilePermission> OVERLY_PERMISSIVE_FILE =
            PosixFilePermissions.fromString("rw-r--r--");

    @TempDir
    Path tempDir;

    @BeforeEach
    void assumePosix() {
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "排程的 0600 回歸情境需要 POSIX 測試檔案系統");
    }

    @Test
    void firstCycleTightensExistingDatabaseAndNewActiveLogTo0600() throws IOException {
        Path databaseBase = tempDir.resolve("data/board");
        Path databaseFile = databaseBase.resolveSibling("board.mv.db");
        Files.createDirectories(databaseBase.getParent());
        Files.writeString(databaseFile, "existing database content");
        makeWorldReadable(databaseFile);

        Path activeLog = tempDir.resolve("logs/board.log");
        PeriodicSensitiveFilePermissionHardener hardener = hardener(databaseBase, activeLog);
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "new active log content");
        makeWorldReadable(activeLog);

        hardener.hardenSensitiveFiles();

        assertOwnerOnly(databaseFile, activeLog);
        assertThat(Files.readString(databaseFile)).isEqualTo("existing database content");
        assertThat(Files.readString(activeLog)).isEqualTo("new active log content");
    }

    @Test
    void nextCycleTightensRolledAndRecreatedLogsButIgnoresUnrelatedFiles() throws IOException {
        Path databaseBase = tempDir.resolve("data/board");
        Path activeLog = tempDir.resolve("logs/board.log");
        Files.createDirectories(databaseBase.getParent());
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "first inode");
        Path firstRolled = activeLog.resolveSibling("board.log.2026-08-11.0.gz");
        Files.writeString(firstRolled, "first rolled inode");
        makeWorldReadable(activeLog);
        makeWorldReadable(firstRolled);
        PeriodicSensitiveFilePermissionHardener hardener = hardener(databaseBase, activeLog);

        hardener.hardenSensitiveFiles();
        assertOwnerOnly(activeLog, firstRolled);

        Files.delete(activeLog);
        Files.writeString(activeLog, "recreated inode");
        Path secondRolled = activeLog.resolveSibling("board.log.2026-08-11.1.gz.tmp");
        Path unrelated = activeLog.resolveSibling("board.log.backup");
        Files.writeString(secondRolled, "second rolled inode");
        Files.writeString(unrelated, "not a logback rolling name");
        makeWorldReadable(activeLog);
        makeWorldReadable(secondRolled);
        makeWorldReadable(unrelated);

        hardener.hardenSensitiveFiles();

        assertOwnerOnly(activeLog, firstRolled, secondRolled);
        assertThat(Files.getPosixFilePermissions(unrelated)).isEqualTo(OVERLY_PERMISSIVE_FILE);
        assertThat(Files.readString(activeLog)).isEqualTo("recreated inode");
        assertThat(Files.readString(secondRolled)).isEqualTo("second rolled inode");
    }

    @Test
    void cycleSkipsSymlinkAndNonRegularCandidatesWithoutTouchingTargets() throws IOException {
        Path databaseBase = tempDir.resolve("data/board");
        Path databaseCandidate = databaseBase.resolveSibling("board.mv.db");
        Path activeLog = tempDir.resolve("logs/board.log");
        Path outsideTarget = tempDir.resolve("outside/real-database.mv.db");
        Files.createDirectories(databaseBase.getParent());
        Files.createDirectories(activeLog.getParent());
        Files.createDirectories(outsideTarget.getParent());
        Files.writeString(outsideTarget, "outside data");
        makeWorldReadable(outsideTarget);
        try {
            Files.createSymbolicLink(databaseCandidate, outsideTarget);
        } catch (UnsupportedOperationException | IOException ex) {
            Assumptions.abort("此測試檔案系統不支援建立 symbolic link：" + ex.getClass().getSimpleName());
        }
        Files.createDirectory(activeLog);
        Files.setPosixFilePermissions(activeLog, PosixFilePermissions.fromString("rwxr-xr-x"));

        hardener(databaseBase, activeLog).hardenSensitiveFiles();

        assertThat(Files.getPosixFilePermissions(outsideTarget)).isEqualTo(OVERLY_PERMISSIVE_FILE);
        assertThat(Files.readString(outsideTarget)).isEqualTo("outside data");
        assertThat(databaseCandidate).isSymbolicLink();
        assertThat(activeLog).isDirectory();
        assertThat(Files.getPosixFilePermissions(activeLog))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void unsupportedPosixIsWarnedOnceAndLeavesServiceAndDataUsable(CapturedOutput output)
            throws Exception {
        Path archive = tempDir.resolve("unsupported.zip");
        try (FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            Path databaseDir = Files.createDirectories(zip.getPath("/data"));
            Path logDir = Files.createDirectories(zip.getPath("/logs"));
            Path databaseBase = databaseDir.resolve("board");
            Path databaseFile = databaseDir.resolve("board.mv.db");
            Path activeLog = logDir.resolve("board.log");
            Files.writeString(databaseFile, "database survives unsupported permissions");
            Files.writeString(activeLog, "log survives unsupported permissions");
            PeriodicSensitiveFilePermissionHardener hardener = hardener(
                    tempDir.resolve("unused-db"), tempDir.resolve("unused.log"));
            replacePath(hardener, "databaseBase", databaseBase);
            replacePath(hardener, "activeLog", activeLog);

            hardener.hardenSensitiveFiles();
            hardener.hardenSensitiveFiles();

            assertThat(Files.readString(databaseFile))
                    .isEqualTo("database survives unsupported permissions");
            assertThat(Files.readString(activeLog))
                    .isEqualTo("log survives unsupported permissions");
        }

        assertThat(countOccurrences(output.getOut(), "檔案系統不支援 POSIX 權限"))
                .as("同一個 hardener instance 只警告一次")
                .isOne();
        assertThat(output.getOut()).contains("服務會繼續執行且不會改動資料");
    }

    @Test
    void inaccessibleCandidateIsSafelyWarnedWithoutDeletingOrRewritingData(CapturedOutput output)
            throws IOException {
        Path databaseDir = Files.createDirectory(tempDir.resolve("password=do-not-log-token=secret"));
        Path databaseBase = databaseDir.resolve("board");
        Path databaseFile = databaseDir.resolve("board.mv.db");
        Files.writeString(databaseFile, "critical database bytes");
        makeWorldReadable(databaseFile);
        Path activeLog = tempDir.resolve("logs/board.log");
        PeriodicSensitiveFilePermissionHardener hardener = hardener(databaseBase, activeLog);

        Files.setPosixFilePermissions(databaseDir, PosixFilePermissions.fromString("r--------"));
        try {
            hardener.hardenSensitiveFiles();
        } finally {
            Files.setPosixFilePermissions(databaseDir, PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(databaseFile).exists();
        assertThat(Files.readString(databaseFile)).isEqualTo("critical database bytes");
        assertThat(output.getOut())
                .contains("本輪已安全略過")
                .contains("服務會繼續執行且不會刪除、搬移或改寫資料");
        assertSanitized(output.getOut(), hardenerJdbcUrl(databaseBase), databaseDir);
    }

    @Test
    void invalidConfigurationWarningAndPublicEntryPointExposeNoSensitiveValues(CapturedOutput output)
            throws NoSuchMethodException {
        String secretUrl = "jdbc:h2:file:/Users/private-user/secret/password=hunter2/token=abc\0";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", secretUrl)
                .withProperty("logging.file.name", tempDir.resolve("logs/board.log").toString());

        PeriodicSensitiveFilePermissionHardener hardener =
                new PeriodicSensitiveFilePermissionHardener(environment);
        hardener.hardenSensitiveFiles();

        assertThat(PeriodicSensitiveFilePermissionHardener.class
                .getMethod("hardenSensitiveFiles").getReturnType()).isEqualTo(void.class);
        assertThat(output.getOut()).contains("未輸出路徑或連線字串");
        assertSanitized(output.getOut(), secretUrl, tempDir);
    }

    private PeriodicSensitiveFilePermissionHardener hardener(Path databaseBase, Path activeLog) {
        return new PeriodicSensitiveFilePermissionHardener(new MockEnvironment()
                .withProperty("spring.datasource.url", hardenerJdbcUrl(databaseBase))
                .withProperty("logging.file.name", activeLog.toString()));
    }

    private static String hardenerJdbcUrl(Path databaseBase) {
        return "jdbc:h2:file:" + databaseBase + ";PASSWORD=never-log-this;token=never-log-this";
    }

    private static void makeWorldReadable(Path file) throws IOException {
        Files.setPosixFilePermissions(file, OVERLY_PERMISSIVE_FILE);
    }

    private static void assertOwnerOnly(Path... files) throws IOException {
        for (Path file : files) {
            assertThat(Files.getPosixFilePermissions(file))
                    .as("%s 應收斂為 0600", file.getFileName())
                    .isEqualTo(LocalFilePermissionHardener.FILE_PERMISSIONS);
        }
    }

    private static void replacePath(PeriodicSensitiveFilePermissionHardener hardener,
                                    String fieldName, Path value) throws ReflectiveOperationException {
        Field field = PeriodicSensitiveFilePermissionHardener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(hardener, value);
    }

    private static long countOccurrences(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).count();
    }

    private static void assertSanitized(String output, String fullJdbcUrl, Path sensitivePath) {
        assertThat(output)
                .doesNotContain(fullJdbcUrl)
                .doesNotContain("PASSWORD=", "password=", "token=")
                .doesNotContain(System.getProperty("user.home"))
                .doesNotContain(sensitivePath.toAbsolutePath().normalize().toString())
                .doesNotContain("/Users/", "/home/", "C:\\Users\\");
    }
}

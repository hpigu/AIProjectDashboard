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
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #150 的 POSIX update/rollback 資料面整合驗證。
 *
 * <p>既有 shell fixture 用文字檔模擬 H2；這裡改用真正的 file-backed H2，並讓
 * 「新版本 start」故意破壞 live DB，再從 production updater 的 readiness 故障
 * 路徑回滾。完成後同時開啟 live DB 與保留的 snapshot，證明舊版資料與備份都
 * 實際可用。另一條 publish fault 覆蓋更新中斷發生在 activation 前的情況。
 */
class BoardUpdateRollbackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void readinessFailureRestoresOldRuntimeAndRealH2DataWhileKeepingUsableSnapshot() throws Exception {
        UpdateFixture fixture = createFixture("readiness");

        ProcessResult result = fixture.runUpdate("readiness", true);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("rolled back to v3.1.1");
        assertRollbackState(fixture, true);
    }

    @Test
    void interruptedPublishKeepsOldRuntimeAndRealH2DataWhileKeepingUsableSnapshot() throws Exception {
        UpdateFixture fixture = createFixture("publish interruption");

        ProcessResult result = fixture.runUpdate("publish", true);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("rolled back to v3.1.1");
        assertRollbackState(fixture, true);
    }

    @Test
    void checksumFailureOccursBeforeStopBackupOrDatabaseMutation() throws Exception {
        UpdateFixture fixture = createFixture("bad checksum");

        ProcessResult result = fixture.runUpdate("", false);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("SHA-256 verification failed");
        assertThat(Files.readSymbolicLink(fixture.root().resolve("current")).toString())
                .isEqualTo("releases/3.1.1");
        assertDatabaseMarker(fixture.databaseBase(), "舊版資料仍可讀");
        assertThat(Files.exists(fixture.root().resolve("running"))).isTrue();
        try (Stream<Path> entries = Files.list(fixture.root().resolve("backups"))) {
            assertThat(entries).as("checksum failure 在 stop/backup 前即應中止").isEmpty();
        }
    }

    private UpdateFixture createFixture(String scenario) throws Exception {
        String platform = currentPlatform();
        Assumptions.assumeTrue(platform != null, "board-update.sh 僅支援 Linux/macOS release host");

        Path root = tempDir.resolve("安裝 root with spaces").resolve(scenario);
        Path bin = Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("releases/3.1.1/app"));
        Files.createDirectories(root.resolve("backups"));
        Path data = Files.createDirectories(root.resolve("data"));
        Files.createSymbolicLink(root.resolve("current"), Path.of("releases/3.1.1"));

        Path databaseBase = data.resolve("board");
        String databaseUrl = "jdbc:h2:file:" + databaseBase + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE preserved_data (id INT PRIMARY KEY, note VARCHAR(100))");
            connection.createStatement().execute(
                    "INSERT INTO preserved_data VALUES (1, '舊版資料仍可讀')");
            connection.createStatement().execute("SHUTDOWN");
        }

        Path board = bin.resolve("board");
        Files.writeString(board, """
                #!/usr/bin/env bash
                set -eu
                case "${1:-}" in
                  status) [ -f "${BOARD_INSTALLED_HOME}/running" ] && exit 0; exit 3 ;;
                  start)
                    : > "${BOARD_INSTALLED_HOME}/running"
                    case "$(readlink "${BOARD_INSTALLED_HOME}/current")" in
                      *3.1.2) printf 'new runtime damaged the database\n' > "${TEST_DB_FILE}" ;;
                    esac
                    ;;
                  stop) rm -f "${BOARD_INSTALLED_HOME}/running" ;;
                  *) exit 1 ;;
                esac
                """, StandardCharsets.UTF_8);
        assertThat(board.toFile().setExecutable(true, true)).isTrue();
        Files.createFile(root.resolve("running"));

        Path fakeBin = Files.createDirectories(tempDir.resolve("fake-bin").resolve(scenario));
        Path fakeJar = fakeBin.resolve("jar");
        Files.writeString(fakeJar, """
                #!/usr/bin/env bash
                set -eu
                case "${1:-}" in
                  tf) exit 0 ;;
                  xf)
                    mkdir -p META-INF
                    printf 'Main-Class: org.springframework.boot.loader.launch.JarLauncher\n' > META-INF/MANIFEST.MF
                    printf 'build.version=3.1.2\n' > META-INF/build-info.properties
                    ;;
                  *) exit 1 ;;
                esac
                """, StandardCharsets.UTF_8);
        assertThat(fakeJar.toFile().setExecutable(true, true)).isTrue();

        Path assets = Files.createDirectories(tempDir.resolve("assets").resolve(scenario));
        Path target = assets.resolve("ai-project-board-backend-" + platform + "-3.1.2.jar");
        Files.writeString(target, "fixture target for " + scenario + "\n", StandardCharsets.US_ASCII);
        Path checksums = assets.resolve("ai-project-board-backend-3.1.2-SHA256SUMS.txt");
        String validHash = sha256(target);
        String zeros = "0".repeat(64);
        StringBuilder rows = new StringBuilder();
        for (String name : new String[]{
                "ai-project-board-backend-linux-x64-3.1.2.jar",
                "ai-project-board-backend-macos-arm64-3.1.2.jar",
                "ai-project-board-backend-macos-x64-3.1.2.jar",
                "ai-project-board-backend-windows-x64-3.1.2.zip"}) {
            rows.append(name.equals(target.getFileName().toString()) ? validHash : zeros)
                    .append("  ").append(name).append('\n');
        }
        Files.writeString(checksums, rows, StandardCharsets.US_ASCII);

        return new UpdateFixture(root, databaseBase, fakeBin, target, checksums);
    }

    private void assertRollbackState(UpdateFixture fixture, boolean expectSnapshot) throws Exception {
        assertThat(Files.readSymbolicLink(fixture.root().resolve("current")).toString())
                .isEqualTo("releases/3.1.1");
        assertThat(Files.exists(fixture.root().resolve("running")))
                .as("原本 running 的舊版服務狀態應恢復")
                .isTrue();
        assertDatabaseMarker(fixture.databaseBase(), "舊版資料仍可讀");

        if (expectSnapshot) {
            Path snapshot;
            try (Stream<Path> entries = Files.list(fixture.root().resolve("backups"))) {
                snapshot = entries
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("update-3.1.1-to-3.1.2-"))
                        .findFirst()
                        .orElseThrow();
            }
            assertThat(snapshot.resolve("manifest.sha256")).isRegularFile();
            assertThat(snapshot.resolve("board.mv.db")).isRegularFile();
            assertDatabaseMarker(snapshot.resolve("board"), "舊版資料仍可讀");
        }
    }

    private static void assertDatabaseMarker(Path databaseBase, String expected) throws Exception {
        String url = "jdbc:h2:file:" + databaseBase + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var row = connection.createStatement().executeQuery(
                     "SELECT note FROM preserved_data WHERE id = 1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(expected);
            connection.createStatement().execute("SHUTDOWN");
        }
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return "macos-arm64";
        }
        if (os.contains("mac") && (arch.equals("x86_64") || arch.equals("amd64"))) {
            return "macos-x64";
        }
        if (os.contains("linux") && (arch.equals("x86_64") || arch.equals("amd64"))) {
            return "linux-x64";
        }
        return null;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private record UpdateFixture(Path root, Path databaseBase, Path fakeBin, Path target, Path checksums) {
        ProcessResult runUpdate(String failAt, boolean validChecksum) throws Exception {
            if (!validChecksum) {
                String content = Files.readString(checksums, StandardCharsets.US_ASCII);
                String correct = sha256(target);
                String wrong = (correct.startsWith("0") ? "1" : "0") + correct.substring(1);
                Files.writeString(checksums, content.replace(correct, wrong), StandardCharsets.US_ASCII);
            }

            ProcessBuilder builder = new ProcessBuilder(
                    "bash", Path.of("bin/board-update.sh").toAbsolutePath().normalize().toString(),
                    "--version", "3.1.2", "--jar", target.toString(),
                    "--checksums", checksums.toString());
            builder.directory(Path.of("").toAbsolutePath().toFile());
            builder.redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("PATH", fakeBin + System.getProperty("path.separator") + env.getOrDefault("PATH", ""));
            env.put("TMPDIR", root.resolve("tmp").toString());
            Files.createDirectories(root.resolve("tmp"));
            env.put("BOARD_INSTALLED_HOME", root.toString());
            env.put("BOARD_DB_URL", "jdbc:h2:file:" + databaseBase + ";DB_CLOSE_ON_EXIT=FALSE");
            env.put("BOARD_PORT", "8083");
            env.put("TEST_DB_FILE", databaseBase + ".mv.db");
            if (!failAt.isBlank()) {
                env.put("BOARD_UPDATE_FAIL_AT", failAt);
            }

            Process process = builder.start();
            process.getOutputStream().close();
            String output = readAll(process.getInputStream());
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("board-update.sh 逾時：" + output);
            }
            return new ProcessResult(process.exitValue(), output);
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 bin/board 的生命週期指令。
 *
 * <p>這裡不啟動真的看板（那需要 JDK 偵測與 59MB jar，不適合放進單元測試迴圈），
 * 而是覆蓋兩件在真實使用中最容易出事、也最容易寫錯的行為：
 * <ol>
 *   <li>「看板沒在跑」時 stop/status 必須安全且訊息明確，殘留 PID 檔要清掉；</li>
 *   <li>PID 檔內容不可信任時（指向別人的行程、指向已回收的 PID），
 *       絕對不能對那個行程送訊號。</li>
 * </ol>
 * 完整的 start → stop → restore 演練屬於手動發版檢查項目，見
 * docs/operations.md。
 */
class BoardLifecycleScriptTest {

    @TempDir
    Path tempDir;

    private final Path script = Path.of("bin/board").toAbsolutePath().normalize();

    // 見 StartupBackupScriptTest.requirePosixShellHost：Windows 上的 `bash` 會
    // 落到未安裝 distribution 的 WSL，回傳的是安裝指引而不是腳本輸出。這裡另外
    // 還用到 `sleep` 這類 POSIX 工具。Windows 的生命週期入口是 bin/board.ps1，
    // 由 scripts/windows-check/check.ps1 涵蓋。
    @BeforeEach
    void requirePosixShellHost() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "bin/board 僅適用於 Linux/macOS；Windows 由 board.ps1 與 windows-check 涵蓋");
    }

    @Test
    void statusReportsNotRunningWithDedicatedExitCode() throws Exception {
        ProcessResult result = run(List.of("status"));

        // 3 = 未執行，與「指令失敗」的 1 區分開，方便寫進監控或其他腳本。
        assertThat(result.exitCode()).as(result.output()).isEqualTo(3);
        assertThat(result.output()).contains("行程：未執行");
    }

    @Test
    void stopOnIdleBoardSucceedsAndClearsStalePidFile() throws Exception {
        Path pidFile = tempDir.resolve("board.pid");
        // 指向一個幾乎不可能存在的 PID：模擬上次沒清乾淨的殘留 PID 檔。
        Files.writeString(pidFile, "4194303\n");

        ProcessResult result = run(List.of("stop"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("看板未在執行");
        assertThat(Files.exists(pidFile)).as("殘留 PID 檔應被清除").isFalse();
    }

    @Test
    void stopRefusesToSignalProcessThatIsNotTheBoard() throws Exception {
        // 起一個無關的長時間行程，把它的 PID 寫進 PID 檔——PID 會被作業系統回收，
        // 這是「腳本殺錯行程」的典型成因。stop 必須先確認行程是看板才動手。
        Process bystander = new ProcessBuilder("sleep", "30").start();
        try {
            Files.writeString(tempDir.resolve("board.pid"), bystander.pid() + "\n");

            ProcessResult result = run(List.of("stop"));

            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("看板未在執行");
            assertThat(bystander.isAlive())
                    .as("不是看板的行程絕對不能被送訊號")
                    .isTrue();
        } finally {
            bystander.destroyForcibly();
            bystander.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void helpListsEveryLifecycleCommand() throws Exception {
        ProcessResult result = run(List.of("--help"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("start")
                .contains("stop")
                .contains("restart")
                .contains("status")
                .contains("logs");
    }

    @Test
    void unknownCommandFailsInsteadOfDoingSomethingUnexpected() throws Exception {
        ProcessResult result = run(List.of("destroy-everything"));

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("不認識的指令");
    }

    @Test
    void statusMasksEmbeddedCredentialsInDatabaseUrl() throws Exception {
        // #142：不在 console 印出敏感連線資訊。BOARD_DB_URL 是使用者可控的環境
        // 變數，H2 允許把帳密當成分號分隔參數內嵌在 URL 裡；status 印出的資料庫
        // 那一行絕不能讓密碼明文出現在終端機捲動記錄裡。
        ProcessResult result = run(List.of("status"), Map.of(
                "BOARD_DB_URL", "jdbc:h2:file:" + tempDir.resolve("data/board")
                        + ";USER=sa;PASSWORD=super-secret;DB_CLOSE_ON_EXIT=FALSE"));

        assertThat(result.output())
                .as(result.output())
                .contains("PASSWORD=***")
                .doesNotContain("super-secret");
    }

    @Test
    void freshDatabaseAndLogDirectoriesAreCreatedOwnerOnly() throws Exception {
        // status 本身不啟動 JVM，但仍會走到 board-env.sh 載入的路徑解析；這裡改
        // 直接呼叫 board_secure_dir／board_secure_file 驗證 #142 的核心語意：
        // 新建路徑收斂為僅目前使用者可存取，且不因為既有資料而被破壞。
        Path freshDir = tempDir.resolve("fresh-secure-dir");
        Path freshFile = freshDir.resolve("secret.txt");

        Path binDir = script.getParent();
        String bashScript = "set -u\n"
                + "REPO_ROOT='" + binDir.getParent() + "'\n"
                + "source '" + binDir.resolve("board-env.sh") + "'\n"
                + "err() { printf '[err] %s\\n' \"$*\" >&2; }\n"
                + "board_secure_dir '" + freshDir + "' || exit 1\n"
                + "echo 'created' > '" + freshFile + "'\n"
                + "board_secure_file '" + freshFile + "' 1 || exit 1\n"
                + "stat -f '%Lp' '" + freshDir + "' 2>/dev/null || stat -c '%a' '" + freshDir + "'\n"
                + "stat -f '%Lp' '" + freshFile + "' 2>/dev/null || stat -c '%a' '" + freshFile + "'\n";

        ProcessBuilder builder = new ProcessBuilder("bash", "-c", bashScript);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getOutputStream().close();
        String output = readAll(process.getInputStream());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("腳本逾時：" + output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).as(output).contains("700");
        assertThat(output).as(output).contains("600");
    }

    @Test
    void stopNeverEscalatesToSigkillWithoutExplicitForce() throws Exception {
        String source = Files.readString(script);

        // SIGKILL 會跳過 ShutdownBackupService，等於在停止的當下丟掉一致性備份。
        // 因此腳本裡唯一的 kill -KILL 必須被 --force 分支包住。
        int killIndex = source.indexOf("kill -KILL");
        assertThat(killIndex).as("腳本應保留 --force 的強制終止路徑").isPositive();
        assertThat(source.indexOf("kill -KILL", killIndex + 1))
                .as("只應存在一處 SIGKILL")
                .isEqualTo(-1);

        String beforeKill = source.substring(0, killIndex);
        assertThat(beforeKill).contains("if [ \"$force\" -eq 1 ]; then");
        assertThat(source).contains("SIGKILL 不會執行關閉前備份");
    }

    // -----------------------------------------------------------------------

    private ProcessResult run(List<String> args) throws Exception {
        return run(args, Map.of());
    }

    private ProcessResult run(List<String> args, Map<String, String> extraEnv) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(script.getParent().getParent().toFile());
        builder.redirectErrorStream(true);
        // 全部指向 tempDir 並使用不會有人聽的高位埠，確保測試絕對碰不到
        // 開發機上正在運作的正式看板（AGENTS.md 的埠號/資料庫隔離要求）。
        builder.environment().put("BOARD_HOME_DIR", tempDir.toString());
        builder.environment().put("BOARD_PID_FILE", tempDir.resolve("board.pid").toString());
        builder.environment().put("BOARD_PORT", "18098");
        builder.environment().put("BOARD_LOG_FILE", tempDir.resolve("board.log").toString());
        builder.environment().put("BOARD_DB_URL",
                "jdbc:h2:file:" + tempDir.resolve("data/board") + ";DB_CLOSE_ON_EXIT=FALSE");
        builder.environment().putAll(extraEnv);

        Process process = builder.start();
        process.getOutputStream().close();
        String output = readAll(process.getInputStream());
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("bin/board 逾時未結束：" + output);
        }
        return new ProcessResult(process.exitValue(), output);
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

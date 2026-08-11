package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #142：{@link SensitiveDirectoryEnvironmentPostProcessor} 必須在資料庫／日誌
 * 目錄尚未被任何 Spring bean（DataSource、Logback）建立前，先把它們收斂為僅
 * 目前使用者可存取。這裡不啟動完整 Spring context（那樣就測不出「先收斂、後
 * 建檔」的時序保證是否成立，因為 context 啟動完成時目錄早已存在），只單獨呼叫
 * {@code postProcessEnvironment}，驗證它會依環境變數解析出正確路徑並建立/收斂。
 */
class SensitiveDirectoryEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private final SensitiveDirectoryEnvironmentPostProcessor processor =
            new SensitiveDirectoryEnvironmentPostProcessor();

    @BeforeEach
    void assumePosix() {
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "此檔案系統不支援 POSIX 權限，略過");
    }

    @Test
    void securesDatabaseAndLogDirectoriesResolvedFromEnvironmentVariables() throws IOException {
        Path dbDir = tempDir.resolve("db");
        Path logDir = tempDir.resolve("logs");
        StandardEnvironment environment = environmentWith(Map.of(
                "BOARD_DB_URL", "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE",
                "BOARD_LOG_FILE", logDir.resolve("board.log").toString()));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(dbDir).isDirectory();
        assertThat(Files.getPosixFilePermissions(dbDir))
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
        assertThat(logDir).isDirectory();
        assertThat(Files.getPosixFilePermissions(logDir))
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
    }

    @Test
    void nonFileBasedDatabaseUrlIsSkippedWithoutError() {
        StandardEnvironment environment = environmentWith(Map.of(
                "BOARD_DB_URL", "jdbc:h2:mem:testdb",
                "BOARD_LOG_FILE", tempDir.resolve("logs/board.log").toString()));

        // 不應拋出：mem: 模式沒有本機目錄需要收斂。
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(tempDir.resolve("logs")).isDirectory();
    }

    @Test
    void abortsStartupWhenDatabaseDirectoryCannotBeSecured() throws IOException {
        // 讓「父目錄」本身不可寫，使 mkdir/chmod 在其下建立子目錄時失敗，
        // 演練 EnvironmentPostProcessor 階段的 fail-closed 路徑：例外必須
        // 原樣往外傳，讓 SpringApplication.run 中止，不能被吞掉。
        Path readOnlyParent = tempDir.resolve("readonly-parent");
        Files.createDirectory(readOnlyParent);
        Files.setPosixFilePermissions(readOnlyParent, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));

        try {
            Path dbDir = readOnlyParent.resolve("db");
            StandardEnvironment environment = environmentWith(Map.of(
                    "BOARD_DB_URL", "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE",
                    "BOARD_LOG_FILE", tempDir.resolve("logs/board.log").toString()));

            assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                    .isInstanceOf(SecurityHardeningException.class);
        } finally {
            // 讓 @TempDir 清理得掉這個目錄。
            Files.setPosixFilePermissions(readOnlyParent, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private StandardEnvironment environmentWith(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        return environment;
    }
}

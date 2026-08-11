package dev.aiboard.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.aiboard.DashboardApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #142 端到端驗證：從全新環境（資料庫目錄尚不存在）啟動完整 Spring context，
 * 確認 {@link SensitiveDirectoryEnvironmentPostProcessor} 真的搶在 DataSource
 * 建立資料庫檔案之前先把目錄收斂，而不是理論上「順序應該對」但實際上 H2 已經
 * 用預設 umask 把 {@code .mv.db} 生出來了。
 */
class DatabaseFilePermissionIntegrationTest {

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
    void freshFileBasedDatabaseDirectoryAndFileAreOwnerOnly() throws Exception {
        Path dbDir = tempDir.resolve("brand-new-db-dir");
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "此檔案系統不支援 POSIX 權限，略過");
        assertThat(dbDir).doesNotExist();

        String dbUrl = "jdbc:h2:file:" + dbDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE";

        SpringApplicationBuilder builder = new SpringApplicationBuilder(DashboardApplication.class)
                .web(WebApplicationType.SERVLET);

        List<String> args = new ArrayList<>();
        args.add("--spring.datasource.url=" + dbUrl);
        args.add("--spring.datasource.username=sa");
        args.add("--spring.datasource.password=");
        args.add("--board.backup.schedule.enabled=false");
        args.add("--board.backup.dir=" + tempDir.resolve("backups"));
        args.add("--server.port=0");
        args.add("--logging.file.name=" + tempDir.resolve("logs/test.log"));

        context = builder.application().run(args.toArray(new String[0]));

        assertThat(dbDir).isDirectory();
        assertThat(Files.getPosixFilePermissions(dbDir))
                .as("EnvironmentPostProcessor 必須在 DataSource 建立資料庫檔案前就先收斂目錄")
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);

        List<Path> mvDbFiles;
        try (Stream<Path> stream = Files.list(dbDir)) {
            mvDbFiles = stream.filter(p -> p.getFileName().toString().endsWith(".mv.db")).toList();
        }
        assertThat(mvDbFiles).as("H2 應已在收斂後的目錄下建立資料庫檔").hasSize(1);
    }
}

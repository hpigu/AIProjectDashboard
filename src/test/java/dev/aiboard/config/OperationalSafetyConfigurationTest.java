package dev.aiboard.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalSafetyConfigurationTest {

    @Test
    void hostBindingAndLogPolicyRemainEnvironmentConfigurableWithSafeDefaults() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"));

        assertThat(application).contains("address: ${BOARD_HOST:127.0.0.1}");
        assertThat(application).contains("name: ${BOARD_LOG_FILE:./logs/board.log}");
        assertThat(logback).contains("${BOARD_LOG_MAX_FILE_SIZE:-10MB}");
        assertThat(logback).contains("${BOARD_LOG_MAX_HISTORY:-30}");
        assertThat(logback).contains("${BOARD_LOG_TOTAL_SIZE_CAP:-250MB}");
        assertThat(logback).contains("SizeAndTimeBasedRollingPolicy");
        assertThat(logback).contains(".gz</fileNamePattern>");
        assertThat(logback).contains("<logger name=\"com.zaxxer.hikari\" level=\"WARN\"/>");
        assertThat(logback).contains("<logger name=\"org.flywaydb\" level=\"WARN\"/>");
    }

    @Test
    void startupBackupRunsBeforeJarAndAbortsOnFailure() throws Exception {
        String script = Files.readString(Path.of("bin/board"));
        int backup = script.indexOf("backup_database \"${DB_FILE_PATH}.mv.db\"");
        int launch = script.indexOf("\"$JAVA_BIN\" -jar \"$JAR_PATH\"");

        assertThat(backup).isGreaterThan(0).isLessThan(launch);
        assertThat(script.substring(backup - 20, launch))
                .contains("if ! backup_database")
                .contains("exit 1");
    }

    /**
     * 目錄遍歷取代逐檔列舉（#156）：掃 static 根目錄下所有 *.js／*.css 與
     * index.html，而非硬編碼檔名清單。新增任何 static 檔案（例如 #143 的
     * i18n.js）都會自動被這個離線掃描涵蓋，不需要有人記得手動補列表。
     */
    @Test
    void uiIsSelfContainedAndCspCannotFetchExternalDependencies() throws Exception {
        Path staticRoot = Path.of("src/main/resources/static");
        Pattern remoteUrl = Pattern.compile("(?i)https?://");

        List<Path> topLevelAssets;
        try (Stream<Path> entries = Files.list(staticRoot)) {
            topLevelAssets = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".js") || name.endsWith(".css") || name.equals("index.html");
                    })
                    .toList();
        }
        assertThat(topLevelAssets)
                .as("預期至少涵蓋 index.html、app.js、i18n.js 與既有 css")
                .hasSizeGreaterThanOrEqualTo(5);

        for (Path path : topLevelAssets) {
            assertThat(Files.readString(path))
                    .as("離線 UI 資源不得引用外部 URL: %s", path)
                    .doesNotMatch("(?s).*" + remoteUrl.pattern() + ".*");
        }

        String index = Files.readString(staticRoot.resolve("index.html"));
        assertThat(index).contains("/vendor/vue/vue.global.prod.js");
        assertThat(index).doesNotContain("unpkg.com", "cdn.jsdelivr.net", "fonts.googleapis.com");
        assertThat(Files.isRegularFile(staticRoot.resolve("vendor/vue/vue.global.prod.js"))).isTrue();
        assertThat(Files.isRegularFile(staticRoot.resolve("vendor/fonts/archivo-700.woff2"))).isTrue();
    }
}

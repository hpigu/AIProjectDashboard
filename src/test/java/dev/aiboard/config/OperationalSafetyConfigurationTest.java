package dev.aiboard.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
        String script = Files.readString(Path.of("bin/start-board.sh"));
        int backup = script.indexOf("backup_database \"${DB_FILE_PATH}.mv.db\"");
        int launch = script.indexOf("\"$JAVA_BIN\" -jar \"$JAR_PATH\"");

        assertThat(backup).isGreaterThan(0).isLessThan(launch);
        assertThat(script.substring(backup - 20, launch))
                .contains("if ! backup_database")
                .contains("exit 1");
    }

    @Test
    void uiIsSelfContainedAndCspCannotFetchExternalDependencies() throws Exception {
        Path staticRoot = Path.of("src/main/resources/static");
        Pattern remoteUrl = Pattern.compile("(?i)https?://");

        for (String resource : new String[]{"index.html", "app.js", "fonts.css", "tokens.css", "layout.css"}) {
            Path path = staticRoot.resolve(resource);
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

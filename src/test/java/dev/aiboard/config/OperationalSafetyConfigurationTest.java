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
        // 從 nohup 錨定：$JAVA_BIN 在前面的 JDK 偵測就出現過（log 裡的
        // -version），只認 "$JAVA_BIN" 會匹配到那裡而不是真正的啟動點。nohup
        // 是背景啟動唯一的入口，用它當起點才對得上這個測試要守的順序。
        // 中間以非貪婪的 [\s\S]*? 允許任意 JVM 參數與反斜線續行：這裡要守的是
        // 「備份發生在啟動之前」，不是啟動指令的確切拼法。綁死整串字面值時，
        // 任何新增旗標都會讓比對落空，而失敗訊息看不出真正原因。
        int launch = indexOfPattern(script, "nohup \"\\$JAVA_BIN\"[\\s\\S]*?-jar \"\\$JAR_PATH\"");

        assertThat(backup).isGreaterThan(0).isLessThan(launch);
        assertThat(script.substring(backup - 20, launch))
                .contains("if ! backup_database")
                .contains("exit 1");
    }

    @Test
    void javaLaunchUsesAnAvailableUtf8LocaleInsteadOfUnsupportedJnuOverride() throws Exception {
        String script = Files.readString(Path.of("bin/board"));

        assertThat(script)
                .contains("find_utf8_locale()")
                .contains("LC_ALL=\"$java_locale\" LANG=\"$java_locale\"")
                .contains("nohup \"$JAVA_BIN\" -Dfile.encoding=UTF-8")
                .doesNotContain("-Dsun.jnu.encoding");
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

    /** 回傳第一個符合 regex 的位置，找不到時回傳 -1，語意與 String.indexOf 一致。 */
    private static int indexOfPattern(String haystack, String regex) {
        var matcher = Pattern.compile(regex).matcher(haystack);
        return matcher.find() ? matcher.start() : -1;
    }
}

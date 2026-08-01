package dev.aiboard.health;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提供「這個看板是不是最新的、連到哪個庫、有哪些 tool」所需的唯讀健康資訊。
 * 版本與 tool 清單都從實際狀態取得（jar manifest、實際註冊的 ToolCallbackProvider），
 * 不寫死清單，避免因為行程啟動時間早於某次 commit 而誤判。
 */
@Service
public class HealthService {

    private static final Pattern H2_FILE_PATTERN = Pattern.compile("jdbc:h2:file:([^;]+)");

    private final ToolCallbackProvider toolCallbackProvider;
    private final String datasourceUrl;
    private final Instant startedAt = Instant.now();

    public HealthService(ToolCallbackProvider toolCallbackProvider,
                          @Value("${spring.datasource.url}") String datasourceUrl) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.datasourceUrl = datasourceUrl;
    }

    public HealthInfo getHealth() {
        return new HealthInfo(resolveVersion(), resolveDatabasePath(), resolveToolNames(), startedAt);
    }

    private String resolveVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version != null ? version : "unknown";
    }

    private String resolveDatabasePath() {
        Matcher matcher = H2_FILE_PATTERN.matcher(datasourceUrl);
        if (!matcher.find()) {
            return "unknown";
        }
        Path path = Paths.get(matcher.group(1)).toAbsolutePath().normalize();
        return path.toString();
    }

    private List<String> resolveToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();
    }

    public record HealthInfo(String version, String databasePath, List<String> tools, Instant startedAt) {
    }
}

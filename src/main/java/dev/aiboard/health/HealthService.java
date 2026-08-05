package dev.aiboard.health;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 提供「這個看板是不是最新的、有哪些 tool」所需的唯讀健康資訊，供
 * {@code bin/start-board.sh} 等一般啟動檢查與外部監控使用。
 *
 * <p><b>刻意不包含敏感資訊</b>：絕對 DB 路徑、完整 JDBC URL 或任何 secret
 * 都不在這裡回傳（過去 {@code /api/health} 會吐出絕對路徑，等同對外洩漏
 * 檔案系統結構，已收斂）。需要這些細節的維運／debug 情境改用
 * {@code /api/diagnostics}，該端點的可見範圍由呼叫方自行控管。
 *
 * <p>版本、commit 與 tool 清單都從實際狀態取得（{@link BuildInfoProvider}、
 * 實際註冊的 ToolCallbackProvider），不寫死清單，避免因為行程啟動時間早於
 * 某次 commit 而誤判。
 */
@Service
public class HealthService {

    private final ToolCallbackProvider toolCallbackProvider;
    private final BuildInfoProvider buildInfo;
    private final Instant startedAt = Instant.now();

    public HealthService(ToolCallbackProvider toolCallbackProvider, BuildInfoProvider buildInfo) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.buildInfo = buildInfo;
    }

    public HealthInfo getHealth() {
        return new HealthInfo(buildInfo.version(), buildInfo.commit(), resolveToolNames(), startedAt);
    }

    private List<String> resolveToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();
    }

    public record HealthInfo(String version, String commit, List<String> tools, Instant startedAt) {
    }
}

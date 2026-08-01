package dev.aiboard.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 就緒探測（readiness）：確認「這個行程雖然活著，但是否真的可以開始接
 * 流量」。三個面向缺一不可：
 * <ul>
 *   <li>DB 連線可用（可以真的取得一條連線，不是只看 DataSource bean 存在）</li>
 *   <li>Flyway migration 沒有 pending（schema 與程式碼版本一致）</li>
 *   <li>MCP tool 至少註冊了一個（ToolCallbackProvider 不是空的）</li>
 * </ul>
 * 任何一項失敗都要能讓呼叫方明確辨識是哪一項、為什麼，因此每個檢查獨立
 * 回報成功/失敗與原因，而不是彙整成單一布林值。
 */
@Service
public class ReadinessService {

    private final DataSource dataSource;
    private final Flyway flyway;
    private final ToolCallbackProvider toolCallbackProvider;

    public ReadinessService(DataSource dataSource, Flyway flyway,
                             ToolCallbackProvider toolCallbackProvider) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public ReadinessInfo getReadiness() {
        List<CheckResult> checks = new ArrayList<>();
        checks.add(checkDatabase());
        checks.add(checkMigrations());
        checks.add(checkMcpTools());

        boolean allPassed = checks.stream().allMatch(CheckResult::pass);
        return new ReadinessInfo(allPassed ? "UP" : "DOWN", checks);
    }

    private CheckResult checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return valid
                    ? CheckResult.ok("database")
                    : CheckResult.fail("database", "連線取得但驗證未通過");
        } catch (SQLException ex) {
            return CheckResult.fail("database", "無法取得資料庫連線：" + ex.getMessage());
        }
    }

    private CheckResult checkMigrations() {
        try {
            MigrationInfo[] pending = flyway.info().pending();
            if (pending.length == 0) {
                return CheckResult.ok("flyway");
            }
            return CheckResult.fail("flyway", "尚有 " + pending.length + " 個未套用的 migration");
        } catch (Exception ex) {
            return CheckResult.fail("flyway", "無法讀取 migration 狀態：" + ex.getMessage());
        }
    }

    private CheckResult checkMcpTools() {
        try {
            int toolCount = toolCallbackProvider.getToolCallbacks().length;
            if (toolCount > 0) {
                return CheckResult.ok("mcpTools");
            }
            return CheckResult.fail("mcpTools", "沒有任何已註冊的 MCP tool");
        } catch (Exception ex) {
            return CheckResult.fail("mcpTools", "無法讀取 MCP tool 清單：" + ex.getMessage());
        }
    }

    public record CheckResult(String name, boolean pass, String detail) {
        static CheckResult ok(String name) {
            return new CheckResult(name, true, null);
        }

        static CheckResult fail(String name, String detail) {
            return new CheckResult(name, false, detail);
        }
    }

    public record ReadinessInfo(String status, List<CheckResult> checks) {
    }
}

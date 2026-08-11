package dev.aiboard.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回歸測試：{@link ReadinessService} 的三個檢查分支（database／flyway／mcpTools）
 * 在底層例外訊息夾帶密碼、JDBC 連線字串或使用者家目錄路徑時，都不得把這些
 * 敏感樣式透過公開的 {@code /api/health/ready} 洩漏出去（#134、#141、#153）。
 *
 * <p>斷言刻意不比對固定文案，而是逐一確認 detail 不含代表性的敏感樣式關鍵字
 * （password/pwd/token 的 key、jdbc: 連線字串、*nix／Windows 家目錄路徑），
 * 這樣未來調整 detail 文案不會讓測試失去防護意義。</p>
 */
class ReadinessServiceTest {

    /** 模擬 JDBC driver／Flyway 常見會夾帶的敏感字串組合。 */
    private static final String SENSITIVE_MESSAGE =
            "Connection failed: jdbc:h2:file:/Users/daniel/secret-project/data/board;PASSWORD=super-secret&token=abc123";

    /**
     * 對外斷言用的敏感樣式清單，逐一確認 detail 都不包含，避免只比對單一
     * 固定字串而遺漏其他洩漏管道。
     */
    private static void assertNoSensitiveLeak(String detail) {
        assertThat(detail).isNotNull();
        String lower = detail.toLowerCase(Locale.ROOT);
        assertThat(lower).as("不得含 password 相關 key").doesNotContain("password");
        assertThat(lower).as("不得含 pwd 相關 key").doesNotContain("pwd=");
        assertThat(lower).as("不得含 token 相關 key").doesNotContain("token=");
        assertThat(lower).as("不得含完整 JDBC 連線字串").doesNotContain("jdbc:");
        assertThat(detail).as("不得含 *nix 使用者家目錄路徑").doesNotContain("/Users/", "/home/");
        assertThat(lower).as("不得含 Windows 使用者家目錄路徑").doesNotContain("c:\\users\\");
        assertThat(detail).as("不得含測試用的明文密碼值").doesNotContain("super-secret", "abc123");
    }

    private static DataSource healthyDataSource() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        return dataSource;
    }

    private static Flyway healthyFlyway() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new org.flywaydb.core.api.MigrationInfo[0]);
        return flyway;
    }

    private static ToolCallbackProvider healthyTools() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[]{mock(ToolCallback.class)});
        return tools;
    }

    @Test
    void databaseFailureMakesReadinessDownWithoutAffectingLiveness() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("password=super-secret; /private/db"));
        Flyway flyway = healthyFlyway();
        ToolCallbackProvider tools = healthyTools();

        ReadinessService.ReadinessInfo readiness =
                new ReadinessService(dataSource, flyway, tools).getReadiness();

        assertThat(readiness.status()).isEqualTo("DOWN");
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.name()).isEqualTo("database");
            assertThat(check.pass()).isFalse();
            assertThat(check.detail())
                    .as("公開 readiness 不得轉送 JDBC driver 的 secret 或主機路徑")
                    .doesNotContain("super-secret", "/private/db");
        });
        assertThat(new LivenessService().getLiveness().status()).isEqualTo("UP");
    }

    @Test
    void databaseUnavailableReturnsDownWithOnlySafeSummary() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException(SENSITIVE_MESSAGE));
        Flyway flyway = healthyFlyway();
        ToolCallbackProvider tools = healthyTools();

        ReadinessService.ReadinessInfo readiness =
                new ReadinessService(dataSource, flyway, tools).getReadiness();

        assertThat(readiness.status()).isEqualTo("DOWN");
        ReadinessService.CheckResult databaseCheck = readiness.checks().stream()
                .filter(c -> c.name().equals("database"))
                .findFirst()
                .orElseThrow();
        assertThat(databaseCheck.pass()).isFalse();
        assertNoSensitiveLeak(databaseCheck.detail());
        // 安全摘要仍要能讓呼叫方辨識問題所在，只是不含底層例外原文。
        assertThat(databaseCheck.detail()).isNotBlank();
    }

    @Test
    void migrationFailureDoesNotLeakConnectionStringOrCredentials() throws Exception {
        DataSource dataSource = healthyDataSource();
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new RuntimeException(SENSITIVE_MESSAGE));
        ToolCallbackProvider tools = healthyTools();

        ReadinessService.ReadinessInfo readiness =
                new ReadinessService(dataSource, flyway, tools).getReadiness();

        assertThat(readiness.status()).isEqualTo("DOWN");
        ReadinessService.CheckResult flywayCheck = readiness.checks().stream()
                .filter(c -> c.name().equals("flyway"))
                .findFirst()
                .orElseThrow();
        assertThat(flywayCheck.pass()).isFalse();
        assertNoSensitiveLeak(flywayCheck.detail());
    }

    @Test
    void mcpToolLookupFailureDoesNotLeakConnectionStringOrCredentials() throws Exception {
        DataSource dataSource = healthyDataSource();
        Flyway flyway = healthyFlyway();
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenThrow(new RuntimeException(SENSITIVE_MESSAGE));

        ReadinessService.ReadinessInfo readiness =
                new ReadinessService(dataSource, flyway, tools).getReadiness();

        assertThat(readiness.status()).isEqualTo("DOWN");
        ReadinessService.CheckResult mcpCheck = readiness.checks().stream()
                .filter(c -> c.name().equals("mcpTools"))
                .findFirst()
                .orElseThrow();
        assertThat(mcpCheck.pass()).isFalse();
        assertNoSensitiveLeak(mcpCheck.detail());
    }

    @Test
    void allChecksPassingReportsUpStatus() throws Exception {
        ReadinessService.ReadinessInfo readiness =
                new ReadinessService(healthyDataSource(), healthyFlyway(), healthyTools()).getReadiness();

        assertThat(readiness.status()).isEqualTo("UP");
        assertThat(readiness.checks()).extracting(ReadinessService.CheckResult::pass)
                .containsOnly(true);
        assertThat(readiness.checks()).extracting(ReadinessService.CheckResult::name)
                .containsExactlyInAnyOrder("database", "flyway", "mcpTools");
    }

    @Test
    void multipleFailingChecksEachStaySafeIndependently() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException(SENSITIVE_MESSAGE));
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new RuntimeException(SENSITIVE_MESSAGE));
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenThrow(new RuntimeException(SENSITIVE_MESSAGE));

        ReadinessService.ReadinessInfo readiness =
                new ReadinessService(dataSource, flyway, tools).getReadiness();

        assertThat(readiness.status()).isEqualTo("DOWN");
        assertThat(readiness.checks()).hasSize(3);
        List<ReadinessService.CheckResult> failing = readiness.checks();
        assertThat(failing).allSatisfy(check -> {
            assertThat(check.pass()).isFalse();
            assertNoSensitiveLeak(check.detail());
        });
    }
}

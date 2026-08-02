package dev.aiboard.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadinessServiceTest {

    @Test
    void databaseFailureMakesReadinessDownWithoutAffectingLiveness() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("password=super-secret; /private/db"));
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new org.flywaydb.core.api.MigrationInfo[0]);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[]{mock(ToolCallback.class)});

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
}

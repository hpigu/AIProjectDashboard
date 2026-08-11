package dev.aiboard.web;

import dev.aiboard.health.DiagnosticsService;
import dev.aiboard.health.HealthService;
import dev.aiboard.health.LivenessService;
import dev.aiboard.health.ReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    private HealthService healthService;
    private LivenessService livenessService;
    private ReadinessService readinessService;
    private DiagnosticsService diagnosticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        healthService = mock(HealthService.class);
        livenessService = mock(LivenessService.class);
        readinessService = mock(ReadinessService.class);
        diagnosticsService = mock(DiagnosticsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(
                healthService, livenessService, readinessService, diagnosticsService)).build();
    }

    @Test
    void publicHealthIsMinimalAndDoesNotLeakDatabaseOrSecrets() throws Exception {
        when(healthService.getHealth()).thenReturn(new HealthService.HealthInfo(
                "3.1.0", "f23cd31", List.of("list_tasks"), Instant.parse("2026-08-02T00:00:00Z")));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3.1.0"))
                .andExpect(jsonPath("$.commit").value("f23cd31"))
                .andExpect(jsonPath("$.tools[0]").value("list_tasks"))
                .andExpect(content().string(not(containsString("jdbc:"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("/Users/"))));
    }

    @Test
    void livenessStaysUpWhileReadinessReturns503ForDatabaseFailure() throws Exception {
        when(livenessService.getLiveness()).thenReturn(new LivenessService.LivenessInfo("UP"));
        when(readinessService.getReadiness()).thenReturn(new ReadinessService.ReadinessInfo(
                "DOWN", List.of(new ReadinessService.CheckResult(
                "database", false, "無法取得資料庫連線"))));

        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.checks[0].name").value("database"))
                .andExpect(jsonPath("$.checks[0].pass").value(false));
    }

    /**
     * 回歸測試（#134、#141、#153）：即使 {@link ReadinessService} 內部因為
     * DB／Flyway／MCP 例外夾帶密碼、JDBC 連線字串或家目錄路徑，經 HTTP 層
     * 傳回的公開 {@code /api/health/ready} 回應仍只能有 503 與安全摘要，
     * 不含任何底層例外原文。
     */
    @Test
    void readyEndpointReturns503WithOnlySafeSummaryWhenDatabaseUnavailable() throws Exception {
        String unsafeDetailIfBugRegresses =
                "jdbc:h2:file:/Users/daniel/secret-project/data/board;PASSWORD=super-secret&token=abc123";
        when(livenessService.getLiveness()).thenReturn(new LivenessService.LivenessInfo("UP"));
        when(readinessService.getReadiness()).thenReturn(new ReadinessService.ReadinessInfo(
                "DOWN", List.of(
                        new ReadinessService.CheckResult("database", false, "無法取得資料庫連線，詳情請查閱伺服器日誌"),
                        new ReadinessService.CheckResult("flyway", true, null),
                        new ReadinessService.CheckResult("mcpTools", true, null))));

        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.checks[0].name").value("database"))
                .andExpect(jsonPath("$.checks[0].pass").value(false))
                .andExpect(content().string(not(containsString("jdbc:"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("PASSWORD"))))
                .andExpect(content().string(not(containsString("token="))))
                .andExpect(content().string(not(containsString("/Users/"))))
                .andExpect(content().string(not(containsString("super-secret"))))
                .andExpect(content().string(not(containsString(unsafeDetailIfBugRegresses))));
    }
}

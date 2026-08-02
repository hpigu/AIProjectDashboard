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
                "3.0.0", List.of("list_tasks"), Instant.parse("2026-08-02T00:00:00Z")));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3.0.0"))
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
}

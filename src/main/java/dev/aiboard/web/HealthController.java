package dev.aiboard.web;

import dev.aiboard.health.DiagnosticsService;
import dev.aiboard.health.HealthService;
import dev.aiboard.health.LivenessService;
import dev.aiboard.health.ReadinessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 唯讀健康／診斷端點，依用途拆成三層：
 * <ul>
 *   <li>{@code /api/health/live}——存活探測，只回答「行程還活著」。</li>
 *   <li>{@code /api/health/ready}——就緒探測，驗證 DB、Flyway、MCP tool，
 *       失敗時回 503 並列出各項檢查結果，讓呼叫方能辨識是哪一項壞了。</li>
 *   <li>{@code /api/diagnostics}——維運／debug 用的深度資訊，可能包含
 *       路徑等只有信任使用者才該看到的細節。</li>
 * </ul>
 * {@code /api/health} 保留給 {@code bin/start-board.sh} 既有的啟動檢查
 * （該腳本只確認回應中含有 {@code "version"} 欄位），內容已收斂為不含
 * 絕對 DB 路徑、JDBC URL 或 secret 的最小版本資訊。
 */
@RestController
public class HealthController {

    private final HealthService healthService;
    private final LivenessService livenessService;
    private final ReadinessService readinessService;
    private final DiagnosticsService diagnosticsService;

    public HealthController(HealthService healthService, LivenessService livenessService,
                             ReadinessService readinessService, DiagnosticsService diagnosticsService) {
        this.healthService = healthService;
        this.livenessService = livenessService;
        this.readinessService = readinessService;
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping("/api/health")
    public HealthView health() {
        HealthService.HealthInfo info = healthService.getHealth();
        return new HealthView(info.version(), info.tools(), info.startedAt());
    }

    @GetMapping("/api/health/live")
    public LivenessView live() {
        LivenessService.LivenessInfo info = livenessService.getLiveness();
        return new LivenessView(info.status());
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<ReadinessView> ready() {
        ReadinessService.ReadinessInfo info = readinessService.getReadiness();
        ReadinessView view = new ReadinessView(info.status(), info.checks());
        HttpStatus status = "UP".equals(info.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(view);
    }

    @GetMapping("/api/diagnostics")
    public DiagnosticsService.DiagnosticsInfo diagnostics() {
        return diagnosticsService.getDiagnostics();
    }

    public record HealthView(String version, List<String> tools, Instant startedAt) {
    }

    public record LivenessView(String status) {
    }

    public record ReadinessView(String status, List<ReadinessService.CheckResult> checks) {
    }
}

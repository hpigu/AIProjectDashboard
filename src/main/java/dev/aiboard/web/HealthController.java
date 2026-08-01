package dev.aiboard.web;

import dev.aiboard.health.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 唯讀健康檢查端點：讓啟動腳本與使用者能一眼確認「這個看板是不是最新的、
 * 連到哪個庫、有哪些 tool」，不需要先呼叫 MCP 工具才發現行程沒跟上最新 commit。
 */
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/api/health")
    public HealthView health() {
        HealthService.HealthInfo info = healthService.getHealth();
        return new HealthView(info.version(), info.databasePath(), info.tools(), info.startedAt());
    }

    public record HealthView(String version, String databasePath, List<String> tools, Instant startedAt) {
    }
}

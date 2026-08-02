package dev.aiboard.health;

import org.springframework.stereotype.Service;

/**
 * 存活探測（liveness）：只回答「這個 JVM 行程還活著、能回應 HTTP」，
 * 刻意不觸碰資料庫或任何外部相依。若這裡也去檢查 DB，會讓「資料庫短暫
 * 不可用」被誤判為「行程需要被重啟」，導致不必要的重啟風暴。
 *
 * <p>資料庫、Flyway、MCP tool 是否就緒屬於 readiness 的責任
 * （見 {@link ReadinessService}），不在這裡檢查。
 */
@Service
public class LivenessService {

    public LivenessInfo getLiveness() {
        return new LivenessInfo("UP");
    }

    public record LivenessInfo(String status) {
    }
}

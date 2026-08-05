package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 正常關閉前的 H2 一致性備份。快照本身由 {@link H2SnapshotService} 產生
 * （與排程備份共用同一條 tmp → 驗證 → 原子改名 → 保留的路徑），本類別只負責
 * 「什麼時候觸發」以及「失敗不能擋住關閉」。
 *
 * <p>觸發時機：Spring {@link ContextClosedEvent}，涵蓋以下情境：
 * <ul>
 *   <li>SIGTERM（Spring Boot 註冊的 shutdown hook 會觸發 context close）</li>
 *   <li>終端機 Ctrl+C（等同對前景行程送 SIGINT，同樣觸發 JVM shutdown hook）；
 *       Windows 沒有 SIGTERM，{@code bin\board.ps1 stop} 送的正是
 *       {@code CTRL_C_EVENT}，走的就是這條路</li>
 *   <li>正常 JVM shutdown（{@code SpringApplication.run} 結束、
 *       {@code context.close()} 被明確呼叫等）</li>
 * </ul>
 *
 * <p><b>無法涵蓋、也不可能涵蓋</b>：{@code kill -9}（SIGKILL 不給行程任何機會
 * 執行 shutdown hook）、JVM crash、斷電或硬體層級的異常終止。這些情況只能仰賴
 * 啟動前備份（{@code bin/backup-db.sh}）與{@link ScheduledBackupService 排程備份}，
 * 本服務對此無能為力，這是作業系統與 JVM 的基本限制，不是實作疏漏。
 *
 * <p>不需要額外指定監聽順序（{@code @Order}）：{@link ContextClosedEvent} 的觸發
 * 時機早於 bean 銷毀（DataSource／連線池的 {@code @PreDestroy} 尚未執行），
 * 事件觸發當下 DataSource 仍可正常取得連線。
 */
@Component
public class ShutdownBackupService {

    private static final Logger log = LoggerFactory.getLogger(ShutdownBackupService.class);

    /** phase 名稱同時決定檔名與保留分桶，見 {@link H2SnapshotService}。 */
    static final String PHASE = "shutdown";

    private static final String LOG_TAG = "[shutdown-backup]";

    private final H2SnapshotService snapshotService;

    public ShutdownBackupService(H2SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /**
     * 只處理根 context 的關閉事件，避免測試中巢狀 context（例如
     * {@code @SpringBootTest} 搭配 web 環境時可能產生的 child context）
     * 造成備份被觸發多次。
     */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        try {
            Optional<Path> backup = snapshotService.createSnapshot(PHASE, LOG_TAG);
            backup.ifPresent(path -> log.info("{} 已建立關閉前一致性備份：{}", LOG_TAG, path));
        } catch (Exception ex) {
            // 任何未預期例外都必須被吞掉：關閉流程不能因為備份失敗而卡死或中止，
            // 這是驗收條件之一。ERROR 等級確保會進日誌、容易被察覺。
            log.error("{} 關閉前備份發生未預期例外，已略過（不影響關閉流程）", LOG_TAG, ex);
        }
    }
}

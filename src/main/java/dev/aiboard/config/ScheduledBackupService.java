package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 定期的 H2 一致性快照。
 *
 * <p>在此之前，備份只在<b>啟動</b>（{@code bin/backup-db.sh} 的冷備份）與
 * <b>正常關閉</b>（{@link ShutdownBackupService}）兩個時間點發生。這對這個產品的
 * 主要使用情境剛好是最壞的組合：看板的賣點是「讓 agent 跑好幾天、人不用盯著」，
 * 而那正好意味著行程長時間不重啟——連續跑兩週，就是兩週沒有任何新快照，期間
 * 所有由 MCP 寫入的專案與任務都只存在於單一個 {@code .mv.db} 檔案裡。
 * 一旦遇到 {@code kill -9}、JVM crash、斷電或檔案損毀，能還原到的最近狀態是
 * 兩週前開機的那一刻。
 *
 * <p>排程備份用 {@code scheduled} 這個 phase，與 {@code shutdown} 分開。
 * 保留策略是<b>依 phase 分桶</b>的（見 {@link H2SnapshotService}），若共用同一個
 * phase，長時間運行下頻繁產生的排程備份會把關閉前那份一致性快照擠出保留額度，
 * 而那份通常是最值得留的一份。
 *
 * <p>用 {@code fixedDelay} 而非 {@code fixedRate}：間隔從「上一次執行結束」起算。
 * 大型資料庫的 {@code BACKUP TO} 可能耗時，{@code fixedRate} 在執行時間超過間隔時
 * 會讓任務連續排隊堆積。備份的準時性沒有任何價值，不重疊才有。
 *
 * <p>{@code initialDelay} 刻意等於間隔，不在啟動後立刻跑一次：啟動前備份才剛由
 * {@code bin/backup-db.sh} 產生，馬上再做一份只是浪費。
 *
 * <p>停用方式：{@code board.backup.schedule.enabled=false}（或
 * {@code BOARD_BACKUP_SCHEDULE_ENABLED=false}）。停用時整個 bean 不會建立。
 */
@Component
@ConditionalOnProperty(name = "board.backup.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledBackupService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledBackupService.class);

    /** phase 名稱同時決定檔名與保留分桶，見 {@link H2SnapshotService}。 */
    static final String PHASE = "scheduled";

    private static final String LOG_TAG = "[scheduled-backup]";

    private final H2SnapshotService snapshotService;

    public ScheduledBackupService(H2SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Scheduled(
            fixedDelayString = "${board.backup.schedule.interval:6h}",
            initialDelayString = "${board.backup.schedule.interval:6h}")
    public void takeScheduledSnapshot() {
        try {
            Optional<Path> backup = snapshotService.createSnapshot(PHASE, LOG_TAG);
            backup.ifPresent(path -> log.info("{} 已建立定期一致性備份：{}", LOG_TAG, path));
        } catch (Exception ex) {
            // 必須吞掉：從 @Scheduled 方法拋出的例外會讓這個任務不再被排程，
            // 也就是「一次失敗 = 之後永遠不再備份」，而且沒有任何明顯徵兆。
            log.error("{} 定期備份發生未預期例外，本次略過（不影響後續排程）", LOG_TAG, ex);
        }
    }
}

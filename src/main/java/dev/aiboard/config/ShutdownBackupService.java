package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 正常關閉前的 H2 一致性備份。
 *
 * <p>與 #105 的啟動前冷備份（bin/backup-db.sh，檔案複製 + magic bytes 驗證）互補，
 * 但手法不同：啟動前資料庫尚未被任何行程開啟，直接複製檔案即可；關閉前資料庫
 * 連線仍然存在（甚至可能有進行中的交易），檔案複製會有讀到「寫一半」內容的
 * 風險。H2 提供 {@code BACKUP TO '<file>'} SQL 陳述式，由資料庫引擎自己在
 * 連線仍開啟的情況下產生一致性快照（zip 內含當下已提交狀態的 .mv.db），
 * 語意上等同於「熱備份」，比檔案層級複製更適合這裡的情境，因此本服務改用
 * 這個 SQL 陳述式而非檔案複製。
 *
 * <p>觸發時機：Spring {@link ContextClosedEvent}，涵蓋以下情境：
 * <ul>
 *   <li>SIGTERM（Spring Boot 註冊的 shutdown hook 會觸發 context close）</li>
 *   <li>終端機 Ctrl+C（等同對前景行程送 SIGINT，同樣觸發 JVM shutdown hook）</li>
 *   <li>正常 JVM shutdown（{@code SpringApplication.run} 結束、context.close()
 *       被明確呼叫等）</li>
 * </ul>
 * <b>無法涵蓋、也不可能涵蓋</b>：{@code kill -9}（SIGKILL 不給行程任何機會執行
 * shutdown hook）、行程崩潰（JVM crash，例如 native code 段錯誤）、斷電或硬體
 * 層級的異常終止。這些情況只能仰賴 #105 的啟動前備份與正常的定期備份，本服務
 * 對此無能為力，這是作業系統與 JVM 的基本限制，不是實作疏漏。
 *
 * <p>失敗處理：備份失敗（例如備份目錄不可寫、磁碟已滿、SQL 執行例外）一律
 * 記錄為 ERROR 等級的高優先日誌，但<b>絕不拋出例外、絕不阻塞關閉流程</b>——
 * 資料庫關閉與 JVM 結束必須照常進行，備份只是「盡力而為」的保護措施，不是
 * 關閉流程的必要條件。
 *
 * <p>不需要額外指定監聽順序（{@code @Order}）：{@link ContextClosedEvent}
 * 的觸發時機早於 bean 銷毀（DataSource／連線池的 {@code @PreDestroy} 尚未
 * 執行），事件觸發當下 {@link DataSource} 仍可正常取得連線。
 */
@Component
public class ShutdownBackupService {

    private static final Logger log = LoggerFactory.getLogger(ShutdownBackupService.class);

    private static final Pattern H2_FILE_PATTERN = Pattern.compile("^jdbc:h2:file:([^;]+)");
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));
    private static final Pattern SHUTDOWN_BACKUP_NAME =
            Pattern.compile("^board-shutdown-\\d{8}T\\d{6}Z-.+\\.zip$");
    private static final Pattern SHUTDOWN_BACKUP_TMP_NAME =
            Pattern.compile("^board-shutdown-\\d{8}T\\d{6}Z-.+\\.zip\\.tmp$");

    private final DataSource dataSource;
    private final String datasourceUrl;
    private final Path backupDir;
    private final int retentionDays;
    private final int retentionMinCount;

    public ShutdownBackupService(DataSource dataSource,
                                  @Value("${spring.datasource.url}") String datasourceUrl,
                                  @Value("${board.backup.dir:${user.home}/.ai-project-board/backups}") String backupDir,
                                  @Value("${board.backup.retention-days:30}") int retentionDays,
                                  @Value("${board.backup.retention-min-count:7}") int retentionMinCount) {
        this.dataSource = dataSource;
        this.datasourceUrl = datasourceUrl;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
        this.retentionMinCount = retentionMinCount;
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
            performShutdownBackup();
        } catch (Exception ex) {
            // 任何未預期例外都必須被吞掉：關閉流程不能因為備份失敗而卡死或
            // 中止，這是驗收條件之一。ERROR 等級確保會進日誌、容易被察覺。
            log.error("[shutdown-backup] 關閉前備份發生未預期例外，已略過（不影響關閉流程）", ex);
        }
    }

    private void performShutdownBackup() {
        if (!isFileBasedH2(datasourceUrl)) {
            log.info("[shutdown-backup] 資料庫非 H2 檔案模式（{}），略過關閉前備份。", datasourceUrl);
            return;
        }

        if (!ensureBackupDir()) {
            return;
        }

        String fileName = buildBackupFileName();
        Path target = backupDir.resolve(fileName);
        Path tmpTarget = backupDir.resolve(fileName + ".tmp");

        // 清理先前中斷（例如 kill -9、崩潰）留下的殘留 tmp，不限於本次同名
        // 檔案：只要符合 shutdown tmp 的命名慣例、且不是本次即將寫入的
        // tmpTarget，都視為過期殘留一併清掉，避免永久累積。任何清理失敗
        // 都不影響後續備份流程。
        cleanupStaleTmpFiles(tmpTarget);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // H2 的 BACKUP TO 是 SQL 層級陳述式，在連線仍開啟、可能有其他
            // 交易進行中的情況下，由引擎自行產生一致性快照（zip 內含當下
            // 已提交資料的 .mv.db），不需要應用層自己處理鎖定或交易隔離，
            // 也不會因為「檔案複製途中被其他連線寫入」而產生半成品。這正是
            // 選用它而非檔案複製的原因——關閉當下連線與資料源尚未真正釋放，
            // 檔案複製無法保證讀到一致的位元組序列。
            String escapedPath = tmpTarget.toString().replace("'", "''");
            statement.execute("BACKUP TO '" + escapedPath + "'");
        } catch (SQLException ex) {
            log.error("[shutdown-backup] 執行 H2 BACKUP TO 失敗，關閉前備份未完成：{}", ex.getMessage(), ex);
            deleteQuietly(tmpTarget);
            return;
        }

        if (!Files.isRegularFile(tmpTarget) || isEmptyOrUnreadable(tmpTarget)) {
            log.error("[shutdown-backup] BACKUP TO 執行完成但備份檔缺失或為空：{}", tmpTarget);
            deleteQuietly(tmpTarget);
            return;
        }

        try {
            Files.move(tmpTarget, target);
        } catch (IOException ex) {
            log.error("[shutdown-backup] 備份改名（原子提交）失敗：{} -> {}", tmpTarget, target, ex);
            deleteQuietly(tmpTarget);
            return;
        }

        log.info("[shutdown-backup] 已建立關閉前一致性備份：{}", target);

        applyRetention();
    }

    private boolean isFileBasedH2(String url) {
        return url != null && H2_FILE_PATTERN.matcher(url).find();
    }

    private boolean ensureBackupDir() {
        try {
            Files.createDirectories(backupDir);
            return true;
        } catch (IOException ex) {
            // 驗收條件要求「失敗時 JVM 仍能結束」：目錄不可寫（權限、唯讀檔案
            // 系統等）是最常見的失敗來源，記錄後直接放棄本次備份，不重試、
            // 不拋出。
            log.error("[shutdown-backup] 無法建立備份目錄，關閉前備份已放棄：{}", backupDir, ex);
            return false;
        }
    }

    /**
     * 檔名慣例沿用 #105（board-&lt;階段&gt;-&lt;UTC 時間戳&gt;-&lt;時區縮寫&gt;.&lt;副檔名&gt;），
     * 以 {@code shutdown} 區分階段、以 {@code .zip} 區分格式（H2 BACKUP TO 固定產出 zip，
     * 而非 startup 備份的 .mv.db 原始檔複製）。
     */
    private String buildBackupFileName() {
        Instant now = Instant.now();
        String tsUtc = TS_FORMATTER.format(now);
        String tzAbbr = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("zzz"));
        if (tzAbbr == null || tzAbbr.isBlank()) {
            tzAbbr = "local";
        }
        return "board-shutdown-" + tsUtc + "-" + tzAbbr + ".zip";
    }

    /**
     * 清理 {@code board-shutdown-*.zip.tmp} 殘留檔案。
     *
     * <p>正常流程中 tmp 檔案會在 {@code BACKUP TO} 完成後被原子改名為正式
     * 備份（{@link #performShutdownBackup()} 內的 {@code Files.move}），只有
     * 在上一次關閉流程被異常中斷（例如 {@code kill -9}、JVM crash）、來不及
     * 改名或清掉自己建立的 tmp 時，才會留下這類殘留檔案。這裡在每次關閉前
     * 備份流程開始時掃描一次，安全地清掉「符合命名慣例」且「不是本次正在
     * 使用中」的 tmp 檔案。
     *
     * <p>刻意只比對嚴格的命名 pattern（{@link #SHUTDOWN_BACKUP_TMP_NAME}），
     * 不會動到正式備份（{@code .zip}，無 {@code .tmp} 後綴）或目錄中其他
     * 非本服務產生的檔案，避免誤刪。任何 I/O 例外都只記錄、不拋出，符合
     * 「備份失敗不阻塞 shutdown」的要求。
     */
    private void cleanupStaleTmpFiles(Path currentTmpTarget) {
        if (!Files.isDirectory(backupDir)) {
            return;
        }

        List<Path> staleTmpFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> SHUTDOWN_BACKUP_TMP_NAME.matcher(p.getFileName().toString()).matches())
                    .filter(p -> !p.equals(currentTmpTarget))
                    .forEach(staleTmpFiles::add);
        } catch (IOException ex) {
            log.warn("[shutdown-backup] 掃描殘留 tmp 檔案時列出備份目錄失敗，略過清理：{}", backupDir, ex);
            return;
        }

        for (Path stale : staleTmpFiles) {
            try {
                Files.delete(stale);
                log.info("[shutdown-backup] 已清除前次中斷遺留的殘留 tmp 檔案：{}", stale);
            } catch (IOException ex) {
                log.warn("[shutdown-backup] 清除殘留 tmp 檔案失敗，略過：{}", stale, ex);
            }
        }
    }

    private boolean isEmptyOrUnreadable(Path path) {
        try {
            return Files.size(path) <= 0;
        } catch (IOException ex) {
            return true;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理暫存檔失敗不是致命問題，不再往外拋。
        }
    }

    /**
     * 保留策略與 #105 完全一致：30 天內一律保留；超過 30 天的備份，只要
     * 刪除後剩餘份數仍 &gt;= 7 就可以刪，一旦剩 7 份就停手。只作用於
     * {@code board-shutdown-*.zip}，不會誤刪 bin/backup-db.sh 管理的
     * {@code board-startup-*.mv.db}。
     */
    private void applyRetention() {
        if (!Files.isDirectory(backupDir)) {
            return;
        }

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> SHUTDOWN_BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                    .forEach(candidates::add);
        } catch (IOException ex) {
            log.error("[shutdown-backup] 套用保留策略時列出備份目錄失敗，略過清理：{}", backupDir, ex);
            return;
        }

        if (candidates.isEmpty()) {
            return;
        }

        candidates.sort(Comparator.comparing(this::lastModifiedSafe).reversed());

        Instant cutoff = Instant.now().minusSeconds((long) retentionDays * 86400);
        int remaining = 0;
        for (Path path : candidates) {
            Instant mtime = lastModifiedSafe(path);
            if (!mtime.isBefore(cutoff)) {
                remaining++;
                continue;
            }
            if (remaining >= retentionMinCount) {
                try {
                    Files.delete(path);
                    log.info("[shutdown-backup] 刪除超過保留期限的舊備份：{}", path);
                } catch (IOException ex) {
                    log.warn("[shutdown-backup] 刪除舊備份失敗，略過：{}", path, ex);
                    remaining++;
                }
                continue;
            }
            remaining++;
        }
    }

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }
}

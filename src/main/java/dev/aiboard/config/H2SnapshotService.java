package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 以 H2 的 {@code BACKUP TO} 產生一致性快照，供關閉前備份與排程備份共用。
 *
 * <p>選用 SQL 陳述式而非檔案複製的理由：資料庫連線仍然存在（甚至可能有進行中的
 * 交易）時，檔案層級複製有讀到「寫一半」內容的風險；{@code BACKUP TO} 由引擎
 * 自己產生當下已提交狀態的快照（zip 內含 .mv.db），語意上等同熱備份。
 * 啟動前備份不走這條路——那時資料庫還沒被任何行程開啟，直接複製檔案即可，
 * 由 {@code bin/backup-db.sh} 負責。
 *
 * <p><b>phase 是保留策略的分桶依據，不只是檔名的一部分。</b>命名慣例
 * {@code board-<phase>-<UTC 時間戳>-<時區縮寫>.zip} 沿用 #105；而
 * {@link #applyRetention(String)} 只會掃描「同一個 phase」的檔案，因此
 * {@code shutdown} 與 {@code scheduled} 各自擁有獨立的「保留 N 份」額度。
 * 這是刻意的：若共用一個桶，長時間運行時頻繁產生的排程備份會把關閉前那份
 * 一致性快照擠掉，而那份往往是最值得留的。
 *
 * <p>失敗處理一律「記錄但不拋出」。呼叫端有兩種情境，兩種都不能被備份拖垮：
 * 關閉流程不能因為備份失敗而卡住或中止；排程執行緒拋出例外會讓後續排程停擺。
 */
@Component
public class H2SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(H2SnapshotService.class);

    private static final Pattern H2_FILE_PATTERN = Pattern.compile("^jdbc:h2:file:([^;]+)");
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

    private final DataSource dataSource;
    private final String datasourceUrl;
    private final Path backupDir;
    private final int retentionDays;
    private final int retentionMinCount;

    public H2SnapshotService(DataSource dataSource,
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

    public Path backupDir() {
        return backupDir;
    }

    public boolean isFileBasedH2() {
        return datasourceUrl != null && H2_FILE_PATTERN.matcher(datasourceUrl).find();
    }

    /**
     * 建立一份快照，成功時回傳正式備份的路徑，任何失敗都回傳
     * {@link Optional#empty()}（並已記錄日誌，呼叫端不需要也不應該重試）。
     *
     * @param phase 檔名與保留分桶用的階段名稱，例如 {@code shutdown}、{@code scheduled}
     * @param logTag 日誌前綴，讓兩種呼叫端在同一份日誌裡分得出來
     */
    public Optional<Path> createSnapshot(String phase, String logTag) {
        if (!isFileBasedH2()) {
            log.info("{} 資料庫非 H2 檔案模式（{}），略過備份。", logTag, datasourceUrl);
            return Optional.empty();
        }

        if (!ensureBackupDir(logTag)) {
            return Optional.empty();
        }

        String fileName = buildBackupFileName(phase);
        Path target = backupDir.resolve(fileName);
        Path tmpTarget = backupDir.resolve(fileName + ".tmp");

        // 清理先前中斷（例如 kill -9、崩潰）留下的殘留 tmp，不限於本次同名檔案：
        // 只要符合這個 phase 的 tmp 命名慣例、且不是本次正在使用的 tmpTarget，
        // 都視為過期殘留一併清掉，避免永久累積。清理失敗不影響後續備份流程。
        cleanupStaleTmpFiles(phase, tmpTarget, logTag);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String escapedPath = tmpTarget.toString().replace("'", "''");
            statement.execute("BACKUP TO '" + escapedPath + "'");
        } catch (SQLException ex) {
            log.error("{} 執行 H2 BACKUP TO 失敗，備份未完成：{}", logTag, ex.getMessage(), ex);
            deleteQuietly(tmpTarget);
            return Optional.empty();
        }

        if (!Files.isRegularFile(tmpTarget) || isEmptyOrUnreadable(tmpTarget)) {
            log.error("{} BACKUP TO 執行完成但備份檔缺失或為空：{}", logTag, tmpTarget);
            deleteQuietly(tmpTarget);
            return Optional.empty();
        }

        try {
            Files.move(tmpTarget, target);
        } catch (IOException ex) {
            log.error("{} 備份改名（原子提交）失敗：{} -> {}", logTag, tmpTarget, target, ex);
            deleteQuietly(tmpTarget);
            return Optional.empty();
        }

        applyRetention(phase, logTag);
        return Optional.of(target);
    }

    /**
     * 保留策略與 #105 完全一致：{@code retentionDays} 天內一律保留；超過期限的
     * 備份，只要刪除後剩餘份數仍 &gt;= {@code retentionMinCount} 就可以刪，
     * 一旦剩下 {@code retentionMinCount} 份就停手。
     *
     * <p>只作用於「同一個 phase」的 {@code .zip}，因此不會誤刪其他階段的備份，
     * 也不會動到 {@code bin/backup-db.sh} 管理的 {@code board-startup-*.mv.db}。
     */
    void applyRetention(String phase) {
        applyRetention(phase, "[backup]");
    }

    private void applyRetention(String phase, String logTag) {
        if (!Files.isDirectory(backupDir)) {
            return;
        }

        Pattern namePattern = backupNamePattern(phase);
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> namePattern.matcher(p.getFileName().toString()).matches())
                    .forEach(candidates::add);
        } catch (IOException ex) {
            log.error("{} 套用保留策略時列出備份目錄失敗，略過清理：{}", logTag, backupDir, ex);
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
                    log.info("{} 刪除超過保留期限的舊備份：{}", logTag, path);
                } catch (IOException ex) {
                    log.warn("{} 刪除舊備份失敗，略過：{}", logTag, path, ex);
                    remaining++;
                }
                continue;
            }
            remaining++;
        }
    }

    private static Pattern backupNamePattern(String phase) {
        return Pattern.compile("^board-" + Pattern.quote(phase) + "-\\d{8}T\\d{6}Z-.+\\.zip$");
    }

    private static Pattern backupTmpNamePattern(String phase) {
        return Pattern.compile("^board-" + Pattern.quote(phase) + "-\\d{8}T\\d{6}Z-.+\\.zip\\.tmp$");
    }

    private boolean ensureBackupDir(String logTag) {
        try {
            Files.createDirectories(backupDir);
            return true;
        } catch (IOException ex) {
            log.error("{} 無法建立備份目錄，備份已放棄：{}", logTag, backupDir, ex);
            return false;
        }
    }

    /**
     * 檔名慣例沿用 #105：{@code board-<phase>-<UTC 時間戳>-<時區縮寫>.zip}。
     * 副檔名固定 {@code .zip}（H2 BACKUP TO 的產出格式），與啟動前備份直接複製
     * 產生的 {@code .mv.db} 區分開來。
     */
    private String buildBackupFileName(String phase) {
        Instant now = Instant.now();
        String tsUtc = TS_FORMATTER.format(now);
        String tzAbbr = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("zzz"));
        if (tzAbbr == null || tzAbbr.isBlank()) {
            tzAbbr = "local";
        }
        return "board-" + phase + "-" + tsUtc + "-" + tzAbbr + ".zip";
    }

    /**
     * 正常流程中 tmp 檔案會在 {@code BACKUP TO} 完成後被原子改名為正式備份，
     * 只有在上一次流程被異常中斷（{@code kill -9}、JVM crash）來不及改名或清理時
     * 才會留下殘留。刻意只比對嚴格的命名 pattern，不會動到正式備份或目錄中
     * 其他非本服務產生的檔案。
     */
    private void cleanupStaleTmpFiles(String phase, Path currentTmpTarget, String logTag) {
        if (!Files.isDirectory(backupDir)) {
            return;
        }

        Pattern tmpPattern = backupTmpNamePattern(phase);
        List<Path> staleTmpFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> tmpPattern.matcher(p.getFileName().toString()).matches())
                    .filter(p -> !p.equals(currentTmpTarget))
                    .forEach(staleTmpFiles::add);
        } catch (IOException ex) {
            log.warn("{} 掃描殘留 tmp 檔案時列出備份目錄失敗，略過清理：{}", logTag, backupDir, ex);
            return;
        }

        for (Path stale : staleTmpFiles) {
            try {
                Files.delete(stale);
                log.info("{} 已清除前次中斷遺留的殘留 tmp 檔案：{}", logTag, stale);
            } catch (IOException ex) {
                log.warn("{} 清除殘留 tmp 檔案失敗，略過：{}", logTag, stale, ex);
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

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }
}

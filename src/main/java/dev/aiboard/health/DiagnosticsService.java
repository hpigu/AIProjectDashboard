package dev.aiboard.health;

import dev.aiboard.project.ProjectRepository;
import dev.aiboard.task.TaskRepository;
import dev.aiboard.web.SseEmitterRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 給維運／debug 用的深度資訊，內容可能包含系統路徑等只有信任使用者才該
 * 看到的細節（不像 {@code /api/health}、{@code /api/health/ready} 對外
 * 保持最小化）。呼叫方需自行決定這個端點的可見範圍。
 *
 * <p>聚合（tool 清單、專案／任務統計、備份、磁碟）會觸發多次查詢與檔案
 * I/O，不適合每次請求都重算，因此加上短暫 cache：同一份結果在
 * {@link #CACHE_TTL_MS} 內重複使用，避免高頻探測造成不必要的負載，同時
 * 仍能反映近期變化。cache 本身不做 N+1：專案/任務統計固定各一次查詢
 * （沿用 {@link TaskRepository#countAllGroupedByProjectAndStatus()} 既有的
 * 全庫單次聚合），不會對每個專案各查一次。
 */
@Service
public class DiagnosticsService {

    private static final long CACHE_TTL_MS = 5_000L;
    private static final Pattern H2_FILE_PATTERN = Pattern.compile("^jdbc:h2:file:([^;]+)");
    private static final Pattern BACKUP_FILE_PATTERN =
            Pattern.compile("^board-(startup|shutdown)-(\\d{8}T\\d{6}Z)-.+\\.(mv\\.db|zip)$");

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final Flyway flyway;
    private final ToolCallbackProvider toolCallbackProvider;
    private final String datasourceUrl;
    private final Path backupDir;
    private final Instant startedAt = Instant.now();

    private final ReentrantLock cacheLock = new ReentrantLock();
    private volatile DiagnosticsInfo cached;
    private volatile long cachedAtMillis = -1;

    public DiagnosticsService(ProjectRepository projectRepository,
                               TaskRepository taskRepository,
                               SseEmitterRegistry sseEmitterRegistry,
                               Flyway flyway,
                               ToolCallbackProvider toolCallbackProvider,
                               @Value("${spring.datasource.url}") String datasourceUrl,
                               @Value("${board.backup.dir:${user.home}/.ai-project-board/backups}") String backupDir) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.flyway = flyway;
        this.toolCallbackProvider = toolCallbackProvider;
        this.datasourceUrl = datasourceUrl;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
    }

    public DiagnosticsInfo getDiagnostics() {
        long now = System.currentTimeMillis();
        DiagnosticsInfo snapshot = cached;
        if (snapshot != null && (now - cachedAtMillis) < CACHE_TTL_MS) {
            return snapshot;
        }

        cacheLock.lock();
        try {
            // 拿到鎖之後重新檢查一次：可能已有另一個執行緒剛算完新的快取。
            now = System.currentTimeMillis();
            snapshot = cached;
            if (snapshot != null && (now - cachedAtMillis) < CACHE_TTL_MS) {
                return snapshot;
            }
            DiagnosticsInfo fresh = compute();
            cached = fresh;
            cachedAtMillis = System.currentTimeMillis();
            return fresh;
        } finally {
            cacheLock.unlock();
        }
    }

    private DiagnosticsInfo compute() {
        return new DiagnosticsInfo(
                resolveVersion(),
                startedAt,
                resolveDatabaseType(),
                resolveMigrationInfo(),
                resolveToolNames(),
                sseEmitterRegistry.connectionCount(),
                resolveProjectTaskAggregate(),
                resolveLatestBackup(),
                resolveDiskStatus()
        );
    }

    private String resolveVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version != null ? version : "unknown";
    }

    private String resolveDatabaseType() {
        if (H2_FILE_PATTERN.matcher(datasourceUrl).find()) {
            return "h2-file";
        }
        if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:h2:mem:")) {
            return "h2-mem";
        }
        return "unknown";
    }

    private MigrationSummary resolveMigrationInfo() {
        try {
            MigrationInfo current = flyway.info().current();
            int pendingCount = flyway.info().pending().length;
            int appliedCount = flyway.info().applied().length;
            String currentVersion = current != null && current.getVersion() != null
                    ? current.getVersion().getVersion() : null;
            return new MigrationSummary(currentVersion, appliedCount, pendingCount);
        } catch (Exception ex) {
            return new MigrationSummary(null, -1, -1);
        }
    }

    private List<String> resolveToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();
    }

    /**
     * 專案／任務統計固定兩次查詢（專案總數一次、狀態分組聚合一次），不論
     * 專案有幾個都不會增加查詢次數，避免 N+1。
     */
    private ProjectTaskAggregate resolveProjectTaskAggregate() {
        long projectCount = projectRepository.count();

        Map<String, Long> statusTotals = new HashMap<>();
        long taskCount = 0;
        for (TaskRepository.StatusCountProjection row : taskRepository.countAllGroupedByProjectAndStatus()) {
            statusTotals.merge(row.getStatus(), row.getCnt(), Long::sum);
            taskCount += row.getCnt();
        }
        return new ProjectTaskAggregate(projectCount, taskCount, statusTotals);
    }

    private BackupStatus resolveLatestBackup() {
        if (!Files.isDirectory(backupDir)) {
            return new BackupStatus(false, null, null, null);
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> BACKUP_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .forEach(candidates::add);
        } catch (IOException ex) {
            return new BackupStatus(false, null, null, null);
        }
        if (candidates.isEmpty()) {
            return new BackupStatus(true, null, null, null);
        }
        Path latest = candidates.stream()
                .max(Comparator.comparing(this::lastModifiedSafe))
                .orElse(null);
        if (latest == null) {
            return new BackupStatus(true, null, null, null);
        }
        Matcher matcher = BACKUP_FILE_PATTERN.matcher(latest.getFileName().toString());
        String phase = matcher.matches() ? matcher.group(1) : "unknown";
        Instant mtime = lastModifiedSafe(latest);
        long sizeBytes = sizeSafe(latest);
        return new BackupStatus(true, phase, mtime, sizeBytes);
    }

    private DiskStatus resolveDiskStatus() {
        try {
            Path probe = Files.isDirectory(backupDir) ? backupDir : backupDir.getRoot();
            if (probe == null) {
                probe = Path.of(".").toAbsolutePath();
            }
            FileStore store = Files.getFileStore(probe);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            return new DiskStatus(total, usable, true);
        } catch (IOException ex) {
            return new DiskStatus(-1, -1, false);
        }
    }

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }

    private long sizeSafe(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -1;
        }
    }

    public record MigrationSummary(String currentVersion, int appliedCount, int pendingCount) {
    }

    public record ProjectTaskAggregate(long projectCount, long taskCount, Map<String, Long> taskStatusTotals) {
    }

    public record BackupStatus(boolean backupDirAvailable, String latestPhase,
                                Instant latestAt, Long latestSizeBytes) {
    }

    public record DiskStatus(long totalBytes, long usableBytes, boolean available) {
    }

    public record DiagnosticsInfo(String version, Instant startedAt, String databaseType,
                                   MigrationSummary migration, List<String> tools,
                                   int sseConnectionCount, ProjectTaskAggregate projectTaskAggregate,
                                   BackupStatus latestBackup, DiskStatus disk) {
    }
}

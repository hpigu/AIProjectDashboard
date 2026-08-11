package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 週期收斂 H2 執行期檔案與 Logback active／rolled logs 的 POSIX 權限。
 *
 * <p>啟動前的 {@link SensitiveDirectoryEnvironmentPostProcessor} 能先保護目錄，
 * 但 H2 的 {@code .mv.db} 與 Logback 的 active log 都在之後才建立，rolling／compact
 * 又會產生新 inode。這裡每十分鐘只查看兩個已知父目錄的直接子項，不遞迴全樹，
 * 因此可涵蓋既有、新建、重建及輪替檔案，又不會造成高頻 I/O。
 *
 * <p>這是既有資料的縱深補修：不支援 POSIX 或 chmod 失敗時絕不阻擋啟動、刪除、
 * 搬移或改寫資料。警告只輸出候選類型與數量，不輸出絕對路徑、JDBC URL 或底層
 * 例外訊息，避免安全修補本身洩漏敏感設定。
 */
@Component
public final class PeriodicSensitiveFilePermissionHardener {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSensitiveFilePermissionHardener.class);

    private static final Pattern H2_FILE_PATTERN = Pattern.compile("^jdbc:h2:file:([^;]+)");
    private static final Pattern ROLLED_LOG_SUFFIX =
            Pattern.compile("\\.\\d{4}-\\d{2}-\\d{2}\\.\\d+(?:\\.gz)?(?:\\.tmp)?");

    private static final String DEFAULT_DATABASE_URL =
            "jdbc:h2:file:./data/board;DB_CLOSE_ON_EXIT=FALSE";
    private static final String DEFAULT_LOG_FILE = "./logs/board.log";

    private final Path databaseBase;
    private final Path activeLog;
    private final AtomicBoolean unsupportedWarningLogged = new AtomicBoolean();
    private final AtomicBoolean invalidConfigurationWarningLogged = new AtomicBoolean();

    public PeriodicSensitiveFilePermissionHardener(Environment environment) {
        this.databaseBase = resolveDatabaseBase(firstNonBlank(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("BOARD_DB_URL"),
                DEFAULT_DATABASE_URL));
        this.activeLog = resolvePath(firstNonBlank(
                environment.getProperty("logging.file.name"),
                environment.getProperty("BOARD_LOG_FILE"),
                DEFAULT_LOG_FILE));
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT15S")
    public void hardenSensitiveFiles() {
        Set<Path> candidates = new LinkedHashSet<>();
        int discoveryFailures = collectDatabaseFiles(candidates) + collectLogFiles(candidates);

        int unsupported = 0;
        int failed = discoveryFailures;
        for (Path candidate : candidates) {
            LocalFilePermissionHardener.PosixFileHardeningResult result =
                    SensitiveDirectories.secureExistingPosixFile(candidate);
            if (result == LocalFilePermissionHardener.PosixFileHardeningResult.UNSUPPORTED) {
                unsupported++;
            } else if (result == LocalFilePermissionHardener.PosixFileHardeningResult.FAILED) {
                failed++;
            }
        }

        if (unsupported > 0 && unsupportedWarningLogged.compareAndSet(false, true)) {
            log.warn("[file-permission-cycle] 檔案系統不支援 POSIX 權限，已略過敏感檔案的 0600 收斂；"
                    + "服務會繼續執行且不會改動資料");
        }
        if (failed > 0) {
            log.warn("[file-permission-cycle] 有 {} 個敏感檔案或候選目錄無法完成 0600 收斂；"
                    + "本輪已安全略過，服務會繼續執行且不會刪除、搬移或改寫資料", failed);
        }
    }

    private int collectDatabaseFiles(Set<Path> candidates) {
        if (databaseBase == null || databaseBase.getParent() == null) {
            return 0;
        }
        String baseName = databaseBase.getFileName().toString();
        return collectDirectChildren(databaseBase.getParent(), candidates,
                name -> isH2RuntimeFile(name, baseName));
    }

    private int collectLogFiles(Set<Path> candidates) {
        if (activeLog == null || activeLog.getParent() == null) {
            return 0;
        }
        String activeName = activeLog.getFileName().toString();
        return collectDirectChildren(activeLog.getParent(), candidates,
                name -> name.equals(activeName)
                        || (name.startsWith(activeName)
                        && ROLLED_LOG_SUFFIX.matcher(name.substring(activeName.length())).matches()));
    }

    private int collectDirectChildren(Path directory, Set<Path> candidates, NameMatcher matcher) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                Path fileName = child.getFileName();
                if (fileName != null && matcher.matches(fileName.toString())) {
                    candidates.add(child);
                }
            }
            return 0;
        } catch (IOException | RuntimeException ex) {
            return 1;
        }
    }

    private static boolean isH2RuntimeFile(String name, String baseName) {
        return name.equals(baseName + ".mv.db")
                || name.equals(baseName + ".trace.db")
                || name.equals(baseName + ".lock.db")
                || name.equals(baseName + ".temp.db")
                || name.equals(baseName + ".mv.db.newFile")
                || name.equals(baseName + ".mv.db.tempFile");
    }

    private Path resolveDatabaseBase(String datasourceUrl) {
        if (datasourceUrl == null) {
            return null;
        }
        Matcher matcher = H2_FILE_PATTERN.matcher(datasourceUrl);
        return matcher.find() ? resolvePath(matcher.group(1)) : null;
    }

    private Path resolvePath(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            if (invalidConfigurationWarningLogged.compareAndSet(false, true)) {
                log.warn("[file-permission-cycle] 敏感檔案設定含無效本機路徑，已略過週期權限收斂；"
                        + "服務會繼續執行（未輸出路徑或連線字串）");
            }
            return null;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface NameMatcher {
        boolean matches(String name);
    }
}

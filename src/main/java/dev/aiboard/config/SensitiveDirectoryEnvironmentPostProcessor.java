package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 Spring context 建立、DataSource／Flyway／Logback 初始化之前，先把資料庫
 * 目錄與日誌目錄收斂為僅目前使用者可存取。
 *
 * <p><b>為什麼必須是 {@link EnvironmentPostProcessor} 而不是一般
 * {@code @Component}／{@code ApplicationRunner}</b>：H2 的 DataSource 在
 * context refresh 過程中就會嘗試開啟／建立資料庫檔案，Logback 也在
 * {@code logback-spring.xml} 生效時就開始寫檔——這些都早於任何一般 bean 的
 * 建構子執行。若等到 {@code @PostConstruct}／{@code ApplicationRunner} 才收斂
 * 權限，資料庫檔案早已用預設 umask（macOS/Linux 常見 022，即其他使用者可讀）
 * 建立完成，收斂權限的動作永遠慢一步。{@link EnvironmentPostProcessor} 是
 * {@code SpringApplication} 生命週期中在任何 bean 定義載入前就會執行的擴充點，
 * 是唯一能保證「先收斂、後建檔」順序的位置。
 *
 * <p>只處理資料庫與日誌目錄；備份目錄由 {@code H2SnapshotService} 在建立時
 * 收斂（那裡才知道最終解析後的路徑，且時機本來就晚於資料庫/日誌），PID 檔與
 * 啟動前冷備份則由 {@code bin/board}／{@code bin/board.ps1} 等腳本在行程啟動前
 * 以同樣的「新建 fail closed、既有安全修正」語意處理（Java 行程尚未存在，
 * 這段邏輯管不到）。
 *
 * <p>只解析目前的環境變數與 {@code application.yml} 預設值，不引入額外設定；
 * 資料庫非本機檔案模式（{@code jdbc:h2:mem:}、{@code jdbc:h2:tcp:} 等）時，
 * 沒有本機目錄可收斂，直接略過，不視為失敗。
 *
 * <p>這個啟動前擴充點只收斂目錄本身；Logback 產生／輪替的個別日誌檔案由
 * {@link PeriodicSensitiveFilePermissionHardener} 在行程啟動後以低頻、非遞迴方式
 * 持續補修：
 * POSIX 權限模型下，沒有目錄的「執行」位元就無法解析出目錄內任何檔案的路徑，
 * 因此目錄收斂為僅目前使用者可存取（{@code 0700}）之後，其他使用者即使知道
 * 確切檔名也無法讀取目錄內任何檔案，不論該檔案自身的權限位元為何；週期補修
 * 仍把檔案本身收斂為 {@code 0600}，補足檔案被搬出該目錄時的縱深防禦。
 *
 * <p>資料庫檔案（H2 的 {@code .mv.db}）則不受這條「僅靠目錄保護即足夠」推論
 * 涵蓋：多個看板實例、備份／還原流程或未來的匯出功能都可能在同一個目錄下
 * 建立不同檔案，逐一確認每個敏感檔案本身的權限（見
 * {@code H2SnapshotService}、{@code bin/board}）仍是必要的縱深防禦，不因為
 * 目錄已收斂就省略。
 *
 * <p>{@code META-INF/spring.factories} 註冊本類別；此擴充點不透過
 * {@code @SpringBootApplication} 掃描生效，遺漏該檔會讓整個機制悄悄失效，
 * 修改此類別時務必留意。
 */
public class SensitiveDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDirectoryEnvironmentPostProcessor.class);

    private static final Pattern H2_FILE_PATTERN = Pattern.compile("^jdbc:h2:file:([^;]+)");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            secureDatabaseDirectory(environment);
        } catch (SecurityHardeningException ex) {
            // EnvironmentPostProcessor 階段拋出的例外會讓 SpringApplication.run
            // 直接中止並印出堆疊，本身就是這裡要的 fail-closed 行為；只是補一行
            // ERROR 確保訊息在早期啟動輸出中夠醒目（此時 logback 可能還沒接手）。
            log.error("[startup] 資料庫目錄權限收斂失敗，啟動中止：{}", ex.getMessage());
            throw ex;
        }

        try {
            secureLogDirectory(environment);
        } catch (SecurityHardeningException ex) {
            log.error("[startup] 日誌目錄權限收斂失敗，啟動中止：{}", ex.getMessage());
            throw ex;
        }
    }

    private void secureDatabaseDirectory(ConfigurableEnvironment environment) {
        // 優先看「實際會生效的資料庫連線設定」而非直接讀 BOARD_DB_URL 環境變數：
        // spring.datasource.url 才是 Spring 最終會拿去建立 DataSource 的值，
        // 測試（以 --spring.datasource.url 程式參數覆寫）與正式啟動（以
        // BOARD_DB_URL 環境變數覆寫 application.yml 的 ${BOARD_DB_URL:...}
        // 佔位符）都會反映在這個 key 上；反過來優先讀 BOARD_DB_URL 環境變數，
        // 會在測試程序本身的環境變數剛好也設了 BOARD_DB_URL（例如本 repo
        // AGENTS.md 要求的 `BOARD_DB_URL=... ./mvnw test` 隔離慣例）時，
        // 收斂到錯誤的目錄——這裡曾經真的因此漏測，見
        // DatabaseFilePermissionIntegrationTest 的踩坑記錄。
        String dbUrl = firstNonBlank(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("BOARD_DB_URL"),
                "jdbc:h2:file:./data/board;DB_CLOSE_ON_EXIT=FALSE");

        Matcher matcher = H2_FILE_PATTERN.matcher(dbUrl);
        if (!matcher.find()) {
            // 非檔案模式（mem:/tcp:）沒有本機目錄需要收斂，不是失敗。
            return;
        }

        String dbFilePath = matcher.group(1);
        Path dbDir = Path.of(dbFilePath).toAbsolutePath().normalize().getParent();
        if (dbDir == null) {
            return;
        }

        SensitiveDirectories.ensureSecureDirectory(dbDir);
    }

    private void secureLogDirectory(ConfigurableEnvironment environment) {
        // 同一條「優先讀實際生效設定」的理由，見 secureDatabaseDirectory 開頭註解。
        String logFile = firstNonBlank(
                environment.getProperty("logging.file.name"),
                environment.getProperty("BOARD_LOG_FILE"),
                "./logs/board.log");

        Path logDir = Path.of(logFile).toAbsolutePath().normalize().getParent();
        if (logDir == null) {
            return;
        }

        SensitiveDirectories.ensureSecureDirectory(logDir);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}

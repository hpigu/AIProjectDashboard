package dev.aiboard.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 就緒探測（readiness）：確認「這個行程雖然活著，但是否真的可以開始接
 * 流量」。三個面向缺一不可：
 * <ul>
 *   <li>DB 連線可用（可以真的取得一條連線，不是只看 DataSource bean 存在）</li>
 *   <li>Flyway migration 沒有 pending（schema 與程式碼版本一致）</li>
 *   <li>MCP tool 至少註冊了一個（ToolCallbackProvider 不是空的）</li>
 * </ul>
 * 任何一項失敗都要能讓呼叫方明確辨識是哪一項、為什麼，因此每個檢查獨立
 * 回報成功/失敗與原因，而不是彙整成單一布林值。
 *
 * <p>三個檢查的失敗分支都必須遵守同一條界線：{@link CheckResult#detail()}
 * 會被 {@code /api/health/ready} 原樣回給任何呼叫方（無認證的公開端點），
 * 因此絕不可放入底層例外的 {@code getMessage()}——JDBC driver、Flyway 的
 * 例外訊息經常夾帶完整連線字串、密碼或伺服器檔案路徑。完整例外只送進
 * {@link #log}，且送進去之前先用 {@link #safeDiagnostic(Exception)} 遮罩掉
 * 常見的敏感樣式（password=、jdbc: 開頭的連線字串、使用者家目錄路徑），
 * 確保一般日誌檔一樣不會外洩這些內容；需要完整原始訊息時才由維運人員在
 * 有權限的環境另外重現。</p>
 */
@Service
public class ReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    /** {@code password=...}／{@code pwd=...}／{@code token=...} 這類 key=value 形式的憑證。 */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("(?i)(password|pwd|token)=[^;&\\s]*");
    /** JDBC 連線字串，例如 {@code jdbc:h2:file:./data/board;...}。 */
    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("jdbc:[^\\s;]+(;[^\\s]*)?");
    /** *nix 使用者家目錄路徑，例如 {@code /Users/xxx} 或 {@code /home/xxx}。 */
    private static final Pattern UNIX_HOME_PATTERN =
            Pattern.compile("/(?:Users|home)/[^\\s:;,'\"]*");
    /** Windows 使用者家目錄路徑，例如 {@code C:\Users\xxx}。 */
    private static final Pattern WINDOWS_HOME_PATTERN =
            Pattern.compile("[A-Za-z]:\\\\Users\\\\[^\\s:;,'\"]*");

    /**
     * 把例外訊息裡常見的敏感樣式遮罩掉，只保留例外類別名稱與遮罩後訊息，
     * 供 {@link #log} 記錄用。不做完整脫敏保證，但涵蓋本服務會遇到的
     * JDBC／Flyway 例外中最常見的洩漏來源。
     */
    private static String safeDiagnostic(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        String masked = PASSWORD_PATTERN.matcher(message).replaceAll("$1=***");
        masked = JDBC_URL_PATTERN.matcher(masked).replaceAll("jdbc:***");
        masked = UNIX_HOME_PATTERN.matcher(masked).replaceAll("/***");
        masked = WINDOWS_HOME_PATTERN.matcher(masked).replaceAll("***");
        return ex.getClass().getSimpleName() + ": " + masked;
    }

    private final DataSource dataSource;
    private final Flyway flyway;
    private final ToolCallbackProvider toolCallbackProvider;

    public ReadinessService(DataSource dataSource, Flyway flyway,
                             ToolCallbackProvider toolCallbackProvider) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public ReadinessInfo getReadiness() {
        List<CheckResult> checks = new ArrayList<>();
        checks.add(checkDatabase());
        checks.add(checkMigrations());
        checks.add(checkMcpTools());

        boolean allPassed = checks.stream().allMatch(CheckResult::pass);
        return new ReadinessInfo(allPassed ? "UP" : "DOWN", checks);
    }

    private CheckResult checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return valid
                    ? CheckResult.ok("database")
                    : CheckResult.fail("database", "連線取得但驗證未通過");
        } catch (SQLException ex) {
            log.warn("readiness 檢查失敗：無法取得資料庫連線 [{}]", safeDiagnostic(ex));
            return CheckResult.fail("database", "無法取得資料庫連線，詳情請查閱伺服器日誌");
        }
    }

    private CheckResult checkMigrations() {
        try {
            MigrationInfo[] pending = flyway.info().pending();
            if (pending.length == 0) {
                return CheckResult.ok("flyway");
            }
            return CheckResult.fail("flyway", "尚有 " + pending.length + " 個未套用的 migration");
        } catch (Exception ex) {
            log.warn("readiness 檢查失敗：無法讀取 migration 狀態 [{}]", safeDiagnostic(ex));
            return CheckResult.fail("flyway", "無法讀取 migration 狀態，詳情請查閱伺服器日誌");
        }
    }

    private CheckResult checkMcpTools() {
        try {
            int toolCount = toolCallbackProvider.getToolCallbacks().length;
            if (toolCount > 0) {
                return CheckResult.ok("mcpTools");
            }
            return CheckResult.fail("mcpTools", "沒有任何已註冊的 MCP tool");
        } catch (Exception ex) {
            log.warn("readiness 檢查失敗：無法讀取 MCP tool 清單 [{}]", safeDiagnostic(ex));
            return CheckResult.fail("mcpTools", "無法讀取 MCP tool 清單，詳情請查閱伺服器日誌");
        }
    }

    public record CheckResult(String name, boolean pass, String detail) {
        static CheckResult ok(String name) {
            return new CheckResult(name, true, null);
        }

        static CheckResult fail(String name, String detail) {
            return new CheckResult(name, false, detail);
        }
    }

    public record ReadinessInfo(String status, List<CheckResult> checks) {
    }
}

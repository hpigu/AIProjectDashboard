package dev.aiboard.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 啟動時把實際解析到的 H2 資料庫絕對路徑記進日誌。
 *
 * <p>背景：application.yml 的預設 BOARD_DB_URL 是相對路徑
 * {@code jdbc:h2:file:./data/board}。若 jar 從 target/ 目錄啟動，
 * 這個相對路徑會解析到 target/data/board——一個全新的空資料庫，
 * 而使用者以為連到的是專案根目錄的 data/board.mv.db。
 * 相對路徑本身在「從專案根目錄啟動」這個既有慣例下沒有問題，
 * 但啟動時若不留下任何線索，出事時完全無從排查。
 *
 * <p>這裡只做兩件事，不改變任何既有行為：
 * <ol>
 *   <li>永遠記錄實際解析到的資料庫檔案絕對路徑（含 .mv.db 是否已存在）。</li>
 *   <li>若解析到的路徑不存在資料檔（也就是即將建立全新空庫），
 *       但目前工作目錄的父層存在同名資料檔，則印出明顯警告，
 *       提醒可能是從錯誤的工作目錄啟動。</li>
 * </ol>
 *
 * <p>實作為 {@link EnvironmentPostProcessor} 而非 {@code ApplicationRunner}：
 * 檢查必須發生在 DataSource／H2 實際建立資料檔之前，否則「檔案是否存在」
 * 這個判斷條件會被 H2 自己的啟動流程搶先滿足，導致偵測永遠失效。
 * 需在 {@code META-INF/spring.factories} 註冊才會生效。
 *
 * <p>{@link Ordered} 優先度刻意設在
 * {@link ConfigDataEnvironmentPostProcessor} 之後：
 * application.yml（含 {@code spring.datasource.url} 的預設值與
 * {@code ${BOARD_DB_URL}} 佔位符）要先被載入並解析完成，
 * 這裡才讀得到真正生效的資料庫連線字串。
 *
 * <p>這個階段跑在 Spring Boot 的 logging system 初始化之前，
 * 直接用 SLF4J logger 記錄會被靜默吞掉。因此改用建構子注入的
 * {@link DeferredLogFactory}：訊息會先暫存，等 logging system
 * 就緒後由 Spring Boot 自動「回放」到真正的 log 輸出（含檔案）。
 */
public class DatabaseLocationCheck implements EnvironmentPostProcessor, Ordered {

    // 匹配 jdbc:h2:file:<path>，path 後面可能接 ;PARAM=... 或結束
    private static final Pattern H2_FILE_URL = Pattern.compile("jdbc:h2:file:([^;]+)");

    private final Log log;

    public DatabaseLocationCheck(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DatabaseLocationCheck.class);
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 100;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String datasourceUrl = environment.getProperty("spring.datasource.url");
        if (datasourceUrl == null) {
            return;
        }

        Matcher matcher = H2_FILE_URL.matcher(datasourceUrl);
        if (!matcher.find()) {
            log.info("資料庫連線字串不是 H2 檔案型資料庫，略過路徑檢查：" + datasourceUrl);
            return;
        }

        String rawPath = matcher.group(1);
        Path dbPath = Path.of(rawPath).toAbsolutePath().normalize();
        Path mvDbFile = Path.of(dbPath + ".mv.db");
        Path cwd = Path.of("").toAbsolutePath();
        boolean exists = mvDbFile.toFile().exists();

        log.info("[DB] 目前工作目錄：" + cwd);
        log.info("[DB] 實際使用的資料庫絕對路徑：" + dbPath
                + "（資料檔：" + mvDbFile + "，存在：" + exists + "）");

        if (!exists) {
            warnIfDataExistsElsewhere(mvDbFile, cwd);
        }
    }

    /**
     * 目標資料庫檔不存在（即將建立全新空庫）。
     * 檢查工作目錄的上層目錄是否存在同名資料檔，
     * 這是「從 target/ 或子目錄啟動、預設相對路徑連到空庫」最典型的事故模式。
     */
    private void warnIfDataExistsElsewhere(Path mvDbFile, Path cwd) {
        String fileName = mvDbFile.getFileName().toString();

        Path dir = cwd.getParent();
        int depth = 0;
        while (dir != null && depth < 5) {
            File candidate = dir.resolve("data").resolve(fileName).toFile();
            if (candidate.exists()) {
                log.warn("========================================================================");
                log.warn("[DB] 警告：即將在 " + mvDbFile + " 建立全新的空資料庫，");
                log.warn("[DB] 但在上層目錄發現既有資料檔：" + candidate.getAbsolutePath());
                log.warn("[DB] 這通常代表啟動時的工作目錄不對（例如從 target/ 而非專案根目錄啟動），");
                log.warn("[DB] 導致 BOARD_DB_URL 的相對路徑解析到錯誤位置。");
                log.warn("[DB] 請確認 BOARD_DB_URL 或啟動工作目錄是否正確，否則既有資料將視為消失。");
                log.warn("========================================================================");
                return;
            }
            dir = dir.getParent();
            depth++;
        }
    }
}

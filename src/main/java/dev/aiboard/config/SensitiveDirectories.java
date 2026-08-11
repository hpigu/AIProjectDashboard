package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 建立並收斂看板本機敏感目錄（資料庫、備份、日誌、PID）存取權限的共用入口。
 *
 * <p>刻意集中在這裡而不是各自在 {@code H2SnapshotService}／啟動流程各寫一份：
 * 「新建目錄要收斂到僅目前使用者可存取」與「既有目錄要安全地補修」這兩條規則
 * 必須對所有敏感目錄一致套用，分散實作容易漏掉其中一處（例如只保護了資料庫、
 * 忘記備份目錄）。
 */
final class SensitiveDirectories {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDirectories.class);

    private SensitiveDirectories() {
    }

    /**
     * 確保目錄存在且僅目前使用者可存取。
     *
     * <p>若目錄（或其任何父目錄）本次呼叫才被建立，沿路每一層新建的目錄都會套用
     * 嚴格權限並在失敗時 fail closed（{@link SecurityHardeningException}）；
     * 若目錄原本就存在，只嘗試收斂它自身的權限，失敗時記錄 WARN 並放行，不動
     * 任何既有資料，也不遞迴處理其父目錄——父目錄可能是使用者自行管理的一般
     * 目錄（例如整個家目錄），不應該被本服務動權限。
     *
     * @param dir 要確保存在且受保護的目錄
     */
    static void ensureSecureDirectory(Path dir) {
        Path absolute = dir.toAbsolutePath().normalize();

        if (Files.isDirectory(absolute)) {
            LocalFilePermissionHardener.securePath(absolute, false);
            return;
        }

        Deque<Path> createdChain = newlyCreatedAncestorChain(absolute);

        try {
            Files.createDirectories(absolute);
        } catch (IOException ex) {
            throw new SecurityHardeningException(
                    "無法建立敏感目錄，已中止啟動：" + absolute, ex);
        }

        // 由最外層新建的目錄開始，逐層往內收斂權限；任何一層失敗都視為新建路徑
        // 的失敗（fail closed），因為整條鏈到此為止都還沒有使用者資料。
        for (Path createdDir : createdChain) {
            LocalFilePermissionHardener.securePath(createdDir, true);
        }
        LocalFilePermissionHardener.securePath(absolute, true);
    }

    /**
     * 對單一敏感檔案套用「僅目前使用者可讀寫」，區分新建／既有以決定失敗時的處理方式
     * （見 {@link LocalFilePermissionHardener}）。呼叫前必須確保檔案已存在。
     */
    static void secureExistingFile(Path file, boolean newlyCreated) {
        LocalFilePermissionHardener.securePath(file.toAbsolutePath().normalize(), newlyCreated);
    }

    /**
     * 低頻收斂一個執行期間才出現或被重建的敏感檔案。結果由呼叫端彙總記錄，
     * 這一層刻意不輸出絕對路徑。
     */
    static LocalFilePermissionHardener.PosixFileHardeningResult secureExistingPosixFile(Path file) {
        return LocalFilePermissionHardener.secureExistingPosixFile(file.toAbsolutePath().normalize());
    }

    /**
     * 回傳「目前不存在、待會會被 {@code Files.createDirectories} 一併建立」的父目錄
     * 鏈，由最外層（離根最近）到最內層排序，不含 {@code target} 本身。
     */
    private static Deque<Path> newlyCreatedAncestorChain(Path target) {
        Deque<Path> missingAncestors = new ArrayDeque<>();
        Path cursor = target.getParent();
        while (cursor != null && !Files.exists(cursor)) {
            missingAncestors.push(cursor);
            cursor = cursor.getParent();
        }
        return missingAncestors;
    }
}

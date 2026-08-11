package dev.aiboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 統一收斂看板在本機建立／持有的敏感路徑（資料庫、備份、日誌、PID、設定檔）的
 * 存取權限，取代目前各自為政、預設繼承 umask（macOS/Linux 常見 {@code 022}，
 * 即目錄 {@code 755}、檔案 {@code 644}）的狀態——這代表同一台機器上的其他使用者
 * 預設就能讀到任務內容、H2 連線字串與備份快照。
 *
 * <p><b>目標語意</b>：POSIX 平台收斂為「僅目前使用者可讀寫」（目錄
 * {@code rwx------}／{@code 700}，檔案 {@code rw-------}／{@code 600}）；
 * Windows 平台改用 {@link AclFileAttributeView} 套用僅限目前使用者的 ACL，
 * 兩者都刻意不藉由群組或其他人授予任何權限。
 *
 * <p><b>新建 vs 既有路徑的處理不對稱，是刻意設計，不是疏漏</b>：
 * <ul>
 *   <li><b>新建路徑</b>（{@link #securePath(Path, boolean)} 呼叫前才建立）尚無
 *       使用者資料可言，可以安全地在「權限套用失敗」時 fail closed——直接拋出
 *       {@link SecurityHardeningException} 中止啟動，因為此時放行只會讓一個
 *       從一開始就權限過寬的新目錄留在磁碟上。</li>
 *   <li><b>既有路徑</b>（呼叫前已存在，通常是既有使用者从舊版升級而來）已經
 *       裝著使用者資料。修正過寬權限的操作本身（{@code chmod}／套用 ACL）
 *       失敗時，不能因此中止啟動或動任何資料——那會把「不小心把權限設太寬」
 *       的既有小問題，升級成「使用者今天完全用不了看板」的大問題。因此既有
 *       路徑收斂失敗只記錄 WARN 並放行，讓服務持續可用。</li>
 * </ul>
 *
 * <p><b>不支援權限語意的檔案系統一律降級，不算失敗</b>：例如掛載在 FAT32／
 * exFAT／某些網路檔案系統上，{@link PosixFileAttributeView} 或
 * {@link AclFileAttributeView} 可能整個不存在（{@code getFileAttributeView}
 * 回傳 {@code null}），這種情況不是「操作失敗」而是「這個檔案系統本來就沒有
 * 這個安全機制」，記錄一次 INFO 說明後放行，不當成新建路徑的 fail-closed 條件，
 * 否則會讓看板在這類環境下完全無法啟動。
 */
final class LocalFilePermissionHardener {

    private static final Logger log = LoggerFactory.getLogger(LocalFilePermissionHardener.class);

    /** 目錄：僅目前使用者可讀寫執行。 */
    static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /** 一般檔案：僅目前使用者可讀寫，不給執行位元。 */
    static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private LocalFilePermissionHardener() {
    }

    /**
     * 對單一路徑套用「僅目前使用者」的存取權限。
     *
     * @param path       要收斂的路徑，可以是目錄或一般檔案
     * @param newlyCreated 這次呼叫之前該路徑是否才剛被建立（決定失敗時的處理方式，
     *                     見類別註解）
     * @throws SecurityHardeningException 僅在 {@code newlyCreated=true} 且檔案系統
     *         支援權限語意、但實際套用失敗時拋出；其餘情況一律吞下並記錄
     */
    static void securePath(Path path, boolean newlyCreated) {
        boolean isDirectory = Files.isDirectory(path);
        try {
            PosixFileAttributeView posixView =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posixView != null) {
                applyPosix(path, posixView, isDirectory, newlyCreated);
                return;
            }

            AclFileAttributeView aclView =
                    Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (aclView != null) {
                applyWindowsAcl(path, aclView, newlyCreated);
                return;
            }

            // 兩種權限模型都不存在：檔案系統不支援（FAT32/exFAT/部分網路檔案系統）。
            // 這是降級情境，不是失敗，見類別註解。
            log.info("[file-permission] 檔案系統不支援 POSIX 權限或 ACL，略過權限收斂（僅本機存取風險仍在，"
                    + "建議改用支援權限控制的磁碟區）：{}", path);
        } catch (SecurityHardeningException ex) {
            throw ex;
        } catch (Exception ex) {
            handleFailure(path, newlyCreated, ex);
        }
    }

    private static void applyPosix(Path path, PosixFileAttributeView view, boolean isDirectory,
                                    boolean newlyCreated) {
        Set<PosixFilePermission> target = isDirectory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
        try {
            Set<PosixFilePermission> current = view.readAttributes().permissions();
            if (current.equals(target)) {
                return;
            }
            view.setPermissions(target);
            log.info("[file-permission] 已收斂權限為僅目前使用者可讀寫{}：{}",
                    isDirectory ? "執行" : "", path);
        } catch (IOException ex) {
            handleFailure(path, newlyCreated, ex);
        }
    }

    private static void applyWindowsAcl(Path path, AclFileAttributeView view, boolean newlyCreated) {
        try {
            FileOwnerAttributeView ownerView =
                    Files.getFileAttributeView(path, FileOwnerAttributeView.class);
            UserPrincipal owner = ownerView != null ? ownerView.getOwner() : view.getOwner();
            if (owner == null) {
                throw new IOException("無法取得檔案擁有者，無法建立僅限目前使用者的 ACL");
            }

            AclEntry allowOwner = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();

            List<AclEntry> entries = new ArrayList<>();
            entries.add(allowOwner);
            view.setAcl(entries);
            log.info("[file-permission] 已套用僅限目前使用者（{}）的 Windows ACL：{}", owner.getName(), path);
        } catch (IOException ex) {
            handleFailure(path, newlyCreated, ex);
        }
    }

    private static void handleFailure(Path path, boolean newlyCreated, Exception cause) {
        if (newlyCreated) {
            throw new SecurityHardeningException(
                    "無法為新建路徑套用僅限目前使用者的存取權限，為避免留下權限過寬的敏感資料路徑，"
                            + "已中止啟動。請確認目前使用者對下列路徑的父目錄具備變更權限的許可，"
                            + "或手動移除後重試：" + path, cause);
        }
        log.warn("[file-permission] 既有路徑收斂權限失敗，已略過（不影響服務啟動、不會刪除或搬動任何資料）。"
                + "若要手動修正，請確認目前使用者對此路徑具備變更權限的許可：{}", path, cause);
    }
}

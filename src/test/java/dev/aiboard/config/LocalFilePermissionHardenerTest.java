package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #142：POSIX 平台上新建／既有敏感路徑的權限收斂語意。
 *
 * <p>只在支援 {@link PosixFileAttributeView} 的檔案系統上執行（CI 的 Linux／
 * macOS runner 皆符合）；不支援時整批以 {@link Assumptions#assumeTrue} 略過，
 * 而不是誤判為失敗——這正是 {@link LocalFilePermissionHardener} 對不支援檔案
 * 系統要做的「降級不是失敗」語意，測試環境本身也要遵守同一條規則。
 */
class LocalFilePermissionHardenerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void assumePosix() {
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "此檔案系統不支援 POSIX 權限，略過（見 LocalFilePermissionHardenerTest 類別註解）");
    }

    @Test
    void newlyCreatedDirectoryIsRestrictedToOwnerOnly() throws IOException {
        Path dir = tempDir.resolve("newdir");
        Files.createDirectory(dir);

        LocalFilePermissionHardener.securePath(dir, true);

        assertThat(posixPermissions(dir)).isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
    }

    @Test
    void newlyCreatedFileIsRestrictedToOwnerOnly() throws IOException {
        Path file = tempDir.resolve("secret.txt");
        Files.writeString(file, "sensitive");

        LocalFilePermissionHardener.securePath(file, false);

        assertThat(posixPermissions(file)).isEqualTo(LocalFilePermissionHardener.FILE_PERMISSIONS);
    }

    @Test
    void existingOverlyPermissiveDirectoryIsTightenedWithoutDeletingContents() throws IOException {
        Path dir = tempDir.resolve("existing");
        Files.createDirectory(dir);
        Path marker = dir.resolve("keep-me.txt");
        Files.writeString(marker, "user data must survive");
        setPosixPermissions(dir, "rwxr-xr-x"); // 過寬：0755

        LocalFilePermissionHardener.securePath(dir, false);

        assertThat(posixPermissions(dir)).isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).isEqualTo("user data must survive");
    }

    @Test
    void alreadySecurePathIsLeftUnchanged() throws IOException {
        Path dir = tempDir.resolve("already-secure");
        Files.createDirectory(dir);
        setPosixPermissions(dir, "rwx------");

        // 不應拋出、也不應改變任何東西（幂等）。
        LocalFilePermissionHardener.securePath(dir, false);

        assertThat(posixPermissions(dir)).isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
    }

    @Test
    void newlyCreatedPathFailsClosedWhenPermissionsCannotBeApplied() throws IOException {
        // 用一個「路徑不存在」的情境模擬權限套用失敗：securePath 對不存在的路徑
        // 呼叫 getFileAttributeView 仍會拿到 view，但 setPermissions 會因為
        // 底層 I/O 失敗（找不到檔案）拋出 IOException，正好演練 fail-closed 分支，
        // 不需要真的操作系統層級的唯讀父目錄（那類測試在 CI 容器裡常因跑在 root
        // 底下而失去意義：root 可以無視大部分權限限制）。
        Path missing = tempDir.resolve("does-not-exist/also-missing");

        assertThatThrownBy(() -> LocalFilePermissionHardener.securePath(missing, true))
                .isInstanceOf(SecurityHardeningException.class)
                .hasMessageContaining("新建路徑");
    }

    @Test
    void existingPathPermissionFailureDoesNotThrow() {
        // 既有路徑（newlyCreated=false）即使套用失敗也必須放行，不拋出例外、
        // 不阻擋呼叫端繼續運作——這是既有使用者資料不能被「修權限」這個動作
        // 意外鎖死服務的保證。
        Path missing = tempDir.resolve("does-not-exist/also-missing");

        LocalFilePermissionHardener.securePath(missing, false);
        // 沒有拋出例外即為通過。
    }

    @Test
    void periodicEntryTightensExistingRegularFileWithoutChangingItsData() throws IOException {
        Path file = tempDir.resolve("existing.mv.db");
        Files.writeString(file, "existing database bytes");
        setPosixPermissions(file, "rw-r--r--");

        LocalFilePermissionHardener.PosixFileHardeningResult result =
                LocalFilePermissionHardener.secureExistingPosixFile(file);

        assertThat(result).isEqualTo(LocalFilePermissionHardener.PosixFileHardeningResult.SECURED);
        assertThat(posixPermissions(file)).isEqualTo(LocalFilePermissionHardener.FILE_PERMISSIONS);
        assertThat(Files.readString(file)).isEqualTo("existing database bytes");
    }

    @Test
    void periodicEntryReportsAlreadySecureAndMissingFilesWithoutMutation() throws IOException {
        Path file = tempDir.resolve("already-secure.log");
        Files.writeString(file, "keep");
        setPosixPermissions(file, "rw-------");

        assertThat(LocalFilePermissionHardener.secureExistingPosixFile(file))
                .isEqualTo(LocalFilePermissionHardener.PosixFileHardeningResult.ALREADY_SECURE);
        assertThat(LocalFilePermissionHardener.secureExistingPosixFile(tempDir.resolve("missing.log")))
                .isEqualTo(LocalFilePermissionHardener.PosixFileHardeningResult.NOT_PRESENT);
        assertThat(Files.readString(file)).isEqualTo("keep");
    }

    @Test
    void periodicEntryDoesNotFollowSymlinksOrTreatDirectoriesAsFiles() throws IOException {
        Path target = tempDir.resolve("outside-target.log");
        Files.writeString(target, "must not be chmod through a link");
        setPosixPermissions(target, "rw-r--r--");
        Path link = tempDir.resolve("board.log");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException ex) {
            Assumptions.abort("此測試檔案系統不支援建立 symbolic link：" + ex.getClass().getSimpleName());
        }
        Path directory = Files.createDirectory(tempDir.resolve("board.mv.db"));
        setPosixPermissions(directory, "rwxr-xr-x");

        assertThat(LocalFilePermissionHardener.secureExistingPosixFile(link))
                .isEqualTo(LocalFilePermissionHardener.PosixFileHardeningResult.NOT_PRESENT);
        assertThat(LocalFilePermissionHardener.secureExistingPosixFile(directory))
                .isEqualTo(LocalFilePermissionHardener.PosixFileHardeningResult.NOT_PRESENT);
        assertThat(posixPermissions(target)).contains(PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
        assertThat(Files.readString(target)).isEqualTo("must not be chmod through a link");
        assertThat(directory).isDirectory();
    }

    @Test
    void periodicEntryReportsUnsupportedPosixFileSystemWithoutChangingData() throws IOException {
        Path archive = tempDir.resolve("non-posix.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path file = zip.getPath("/board.log");
            Files.writeString(file, "zip data stays intact");

            assertThat(LocalFilePermissionHardener.secureExistingPosixFile(file))
                    .isEqualTo(LocalFilePermissionHardener.PosixFileHardeningResult.UNSUPPORTED);
            assertThat(Files.readString(file)).isEqualTo("zip data stays intact");
        }
    }

    private static Set<PosixFilePermission> posixPermissions(Path path) throws IOException {
        return Files.getPosixFilePermissions(path);
    }

    private static void setPosixPermissions(Path path, String symbolic) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(symbolic));
    }
}

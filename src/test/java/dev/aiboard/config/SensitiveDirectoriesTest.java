package dev.aiboard.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #142：{@link SensitiveDirectories} 負責「建立＋收斂」的組合語意，特別是多層
 * 新建目錄（例如全新環境首次啟動的 {@code ~/.ai-project-board/backups}，家目錄
 * 底下兩層都可能一次建立）沿路每一層都要收斂，而不是只顧最內層。
 */
class SensitiveDirectoriesTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void assumePosix() {
        Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "此檔案系統不支援 POSIX 權限，略過");
    }

    @Test
    void createsMissingDirectoryWithRestrictedPermissions() throws IOException {
        Path dir = tempDir.resolve("fresh");

        SensitiveDirectories.ensureSecureDirectory(dir);

        assertThat(dir).isDirectory();
        assertThat(Files.getPosixFilePermissions(dir))
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
    }

    @Test
    void securesEveryNewlyCreatedAncestorInTheChain() throws IOException {
        // 模擬全新環境：~/.ai-project-board 與 ~/.ai-project-board/backups
        // 兩層都不存在，一次 createDirectories 建立。兩層都必須被收斂，不只是
        // 最內層——外層若維持預設 umask，同機其他使用者仍能列出裡面有哪些檔案。
        Path outer = tempDir.resolve("home-like");
        Path inner = outer.resolve("backups");

        SensitiveDirectories.ensureSecureDirectory(inner);

        assertThat(Files.getPosixFilePermissions(outer))
                .as("外層新建目錄也必須收斂")
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
        assertThat(Files.getPosixFilePermissions(inner))
                .as("內層新建目錄必須收斂")
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
    }

    @Test
    void existingDirectoryIsTightenedInPlaceWithoutTouchingParent() throws IOException {
        Path parent = tempDir.resolve("parent");
        Path dir = parent.resolve("existing");
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        SensitiveDirectories.ensureSecureDirectory(dir);

        assertThat(Files.getPosixFilePermissions(dir))
                .as("既有目錄本身要被收斂")
                .isEqualTo(LocalFilePermissionHardener.DIRECTORY_PERMISSIONS);
        assertThat(Files.getPosixFilePermissions(parent))
                .as("既有目錄的父目錄不屬於本次呼叫要管的範圍，不應被動")
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void doesNotDeleteExistingDataWhenTighteningPermissions() throws IOException {
        Path dir = tempDir.resolve("with-data");
        Files.createDirectories(dir);
        Path dataFile = dir.resolve("board.mv.db");
        Files.writeString(dataFile, "H:2-important-user-data");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        SensitiveDirectories.ensureSecureDirectory(dir);

        assertThat(dataFile).exists();
        assertThat(Files.readString(dataFile)).isEqualTo("H:2-important-user-data");
    }

    @Test
    void secureExistingFileAppliesOwnerOnlyPermissions() throws IOException {
        Path file = tempDir.resolve("board.log");
        Files.writeString(file, "log line");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        SensitiveDirectories.secureExistingFile(file, false);

        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(LocalFilePermissionHardener.FILE_PERMISSIONS);
    }

    @Test
    void newlyCreatedFileFailsClosedWhenSecuringFails() {
        Path missing = tempDir.resolve("nonexistent.tmp");

        assertThatThrownBy(() -> SensitiveDirectories.secureExistingFile(missing, true))
                .isInstanceOf(SecurityHardeningException.class);
    }
}

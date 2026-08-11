package dev.aiboard.config;

/**
 * 新建的敏感路徑（資料庫、備份、日誌、PID 目錄）無法套用僅限目前使用者的存取
 * 權限時拋出，代表啟動流程已依 fail-closed 原則中止。
 *
 * <p>只用於「新建」路徑：既有路徑收斂權限失敗不會拋出這個例外，見
 * {@link LocalFilePermissionHardener} 類別註解的完整說明。
 */
public class SecurityHardeningException extends RuntimeException {

    public SecurityHardeningException(String message, Throwable cause) {
        super(message, cause);
    }
}

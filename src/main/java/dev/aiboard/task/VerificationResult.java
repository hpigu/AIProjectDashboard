package dev.aiboard.task;

/**
 * complete_task 附帶的單筆驗證結果。FAILED 不可完成任務；NOT_RUN 代表這個
 * category 沒有適用的驗證方式（例如純文件任務沒有測試可跑），必須在 detail
 * 說明原因，不能無故略過驗證。
 */
public enum VerificationResult {
    PASSED,
    FAILED,
    NOT_RUN
}

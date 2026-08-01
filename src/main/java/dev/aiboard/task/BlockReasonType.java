package dev.aiboard.task;

/**
 * block_task 的單一主要原因分類。呼叫端只能挑一個最貼切的原因，
 * 不支援複合原因——若真的有多個因素，detail 裡描述清楚即可。
 */
public enum BlockReasonType {
    /** 在等看板上其他任務先完成；必須搭配至少一個同專案的 blockingTaskIds。 */
    DEPENDENCY,
    /** 在等使用者或其他人提供資訊、決策、權限等。 */
    USER_INPUT,
    /** 技術問題：不明錯誤、環境無法重現、程式邏輯卡關等。 */
    TECHNICAL,
    /** 開發環境本身的問題：埠號衝突、資源不足、看板/CI 故障等。 */
    ENVIRONMENT,
    /** 依賴看板外部的資源：第三方服務、外部 API、外部 repo 等。 */
    EXTERNAL,
    /** 以上皆不適用時的例外分類，detail 必須說明實際原因。 */
    OTHER
}

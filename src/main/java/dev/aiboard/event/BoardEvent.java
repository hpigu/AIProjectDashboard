package dev.aiboard.event;

import java.util.LinkedHashMap;
import java.util.Map;

public record BoardEvent(String type, Map<String, Object> payload) {

    public static BoardEvent projectCreated(Long projectId, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("name", name);
        return new BoardEvent("project.created", payload);
    }

    public static BoardEvent tasksCreated(Long projectId, int count) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("count", count);
        return new BoardEvent("tasks.created", payload);
    }

    /**
     * 任務狀態變更。
     *
     * <p>payload 帶 {@code title} 是為了讓訂閱端能組出人看得懂的訊息——前端的
     * BLOCKED 桌面通知若只有 {@code taskId}，內容會是「任務 #42 → BLOCKED」，
     * 讀了還是得回看板查是哪一個任務，通知本身就失去意義。
     *
     * <p>刻意不帶專案名稱：那需要在每個 publish 點多查一次 project，而前端本來
     * 就有專案清單，用 {@code projectId} 自行對照即可，不值得為此在寫入路徑上
     * 增加查詢。
     */
    public static BoardEvent taskStatusChanged(Long projectId, Long taskId, String title,
                                               String from, String to, String at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("taskId", taskId);
        payload.put("title", title);
        payload.put("from", from);
        payload.put("to", to);
        payload.put("at", at);
        return new BoardEvent("task.status_changed", payload);
    }

    public static BoardEvent taskUpdated(Long projectId, Long taskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("taskId", taskId);
        return new BoardEvent("task.updated", payload);
    }

    public static BoardEvent projectStatusChanged(Long projectId, String from, String to, String at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("from", from);
        payload.put("to", to);
        payload.put("at", at);
        return new BoardEvent("project.status_changed", payload);
    }
}

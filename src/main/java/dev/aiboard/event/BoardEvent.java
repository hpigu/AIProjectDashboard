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

    public static BoardEvent taskStatusChanged(Long projectId, Long taskId, String from, String to, String at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("taskId", taskId);
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
}

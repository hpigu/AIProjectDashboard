package dev.aiboard.config;

import dev.aiboard.common.TaskCategory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** Limits apply separately to each project and worker category, not to sessions. */
@Component
public class WorkerCapacity {
    private final Map<TaskCategory, Integer> limits = new EnumMap<>(TaskCategory.class);

    public WorkerCapacity(@Value("${board.capacity.backend:3}") String backend,
                          @Value("${board.capacity.frontend:1}") String frontend,
                          @Value("${board.capacity.test:1}") String test,
                          @Value("${board.capacity.infra:1}") String infra,
                          @Value("${board.capacity.doc:1}") String doc) {
        limits.put(TaskCategory.BACKEND, parse("BOARD_CAPACITY_BACKEND", backend));
        limits.put(TaskCategory.FRONTEND, parse("BOARD_CAPACITY_FRONTEND", frontend));
        limits.put(TaskCategory.TEST, parse("BOARD_CAPACITY_TEST", test));
        limits.put(TaskCategory.INFRA, parse("BOARD_CAPACITY_INFRA", infra));
        limits.put(TaskCategory.DOC, parse("BOARD_CAPACITY_DOC", doc));
    }

    private static int parse(String name, String value) {
        if (value != null && value.matches("[1-9][0-9]*")) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException(name + " must be a positive integer <= 2147483647");
    }

    /** Informational snapshot only; admission requires an atomic database guard. */
    public Snapshot snapshot(long projectId, TaskCategory category, long inProgress) {
        if (projectId <= 0 || inProgress < 0 || !limits.containsKey(category)) {
            throw new IllegalArgumentException("invalid capacity snapshot scope or count");
        }
        int limit = limits.get(category);
        return new Snapshot(projectId, category, limit, inProgress,
                Math.max(0L, limit - inProgress), inProgress >= limit);
    }

    public record Snapshot(long projectId, TaskCategory category, int limit, long inProgress,
                           long available, boolean full) { }
}

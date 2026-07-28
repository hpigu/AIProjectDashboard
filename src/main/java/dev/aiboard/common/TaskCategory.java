package dev.aiboard.common;

public enum TaskCategory {
    BACKEND,
    FRONTEND,
    INFRA,
    DOC,
    TEST,
    OTHER;

    public static TaskCategory fromStringOrOther(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return TaskCategory.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}

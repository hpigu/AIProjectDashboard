package dev.aiboard.common;

public enum TaskCategory {
    BACKEND,
    FRONTEND,
    INFRA,
    DOC,
    OTHER;

    public static TaskCategory fromStringOrOther(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TaskCategory.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}

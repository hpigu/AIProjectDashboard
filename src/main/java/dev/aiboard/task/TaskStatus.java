package dev.aiboard.task;

import java.util.Set;

/**
 * TODO -> IN_PROGRESS -> DONE
 *              |
 *           BLOCKED -> IN_PROGRESS
 */
public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    BLOCKED;

    /**
     * 同狀態視為合法（no-op），呼叫方須自行判斷是否為真正的轉移。
     */
    public boolean canTransitionTo(TaskStatus target) {
        if (this == target) {
            return true;
        }
        return allowedTargets().contains(target);
    }

    private Set<TaskStatus> allowedTargets() {
        return switch (this) {
            case TODO -> Set.of(IN_PROGRESS, BLOCKED);
            case IN_PROGRESS -> Set.of(DONE, BLOCKED);
            case BLOCKED -> Set.of(IN_PROGRESS, TODO);
            case DONE -> Set.of(IN_PROGRESS);
        };
    }
}

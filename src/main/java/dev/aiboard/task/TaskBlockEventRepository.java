package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskBlockEventRepository extends JpaRepository<TaskBlockEvent, Long> {

    List<TaskBlockEvent> findByTaskIdOrderByIdAsc(Long taskId);

    @Query("SELECT e.id AS eventId, e.reasonType AS reasonType, e.detail AS detail, "
            + "e.blockedBy AS blockedBy, e.blockedAt AS blockedAt, e.clearedAt AS clearedAt, "
            + "blocking.id AS blockingTaskId, blocking.title AS blockingTaskTitle, "
            + "blocking.status AS blockingTaskStatus "
            + "FROM TaskBlockEvent e "
            + "LEFT JOIN TaskBlockDependency d ON d.blockEventId = e.id "
            + "LEFT JOIN Task blocking ON blocking.id = d.blockingTaskId "
            + "WHERE e.id = :eventId ORDER BY d.id ASC")
    List<CurrentBlockerProjection> findCurrentBlocker(@Param("eventId") Long eventId);

    /**
     * 任務離開 BLOCKED（不論是透過 block_task 之外的既有 update_task_status resume，
     * 或其他任何轉移）時呼叫，把該任務尚未 clear 的事件補上 clearedAt。
     * 正常情況下同一時間至多一筆未 clear 的事件（block_task 只在 TODO/IN_PROGRESS
     * 轉入 BLOCKED，離開時一定會清），這裡用條件式 UPDATE 涵蓋所有既有列，
     * 不假設剛好只有一筆，語意更穩固。
     *
     * 刻意不用 clearAutomatically=true：那會呼叫 entityManager.clear()，把同一個
     * transaction 內先前用 findById 取得、還沒 flush 的 managed Task 一併 detach，
     * 導致呼叫端後續對該 Task 的欄位修改（例如 changeStatus）悄悄失效、永遠不會
     * 寫回資料庫。只保留 flushAutomatically，讓這條 UPDATE 執行前先送出目前
     * pending 的變更，避免順序問題，同時不影響後續對其他 managed entity 的追蹤。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE TaskBlockEvent e SET e.clearedAt = :clearedAt "
            + "WHERE e.taskId = :taskId AND e.clearedAt IS NULL")
    int clearAllOpenForTask(@Param("taskId") Long taskId, @Param("clearedAt") LocalDateTime clearedAt);

    interface CurrentBlockerProjection {
        Long getEventId();
        String getReasonType();
        String getDetail();
        String getBlockedBy();
        LocalDateTime getBlockedAt();
        LocalDateTime getClearedAt();
        Long getBlockingTaskId();
        String getBlockingTaskTitle();
        String getBlockingTaskStatus();
    }
}

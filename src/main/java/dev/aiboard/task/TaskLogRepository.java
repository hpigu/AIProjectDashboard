package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskLogRepository extends JpaRepository<TaskLog, Long> {

    List<TaskLog> findByTaskIdOrderByIdAsc(Long taskId);

    @Query("SELECT l.id AS id, l.fromStatus AS fromStatus, l.toStatus AS toStatus, "
            + "l.note AS note, l.createdAt AS createdAt FROM TaskLog l "
            + "WHERE l.taskId = :taskId ORDER BY l.id ASC")
    List<TaskHistoryProjection> findHistoryProjection(@Param("taskId") Long taskId);

    interface TaskHistoryProjection {
        Long getId();
        String getFromStatus();
        String getToStatus();
        String getNote();
        LocalDateTime getCreatedAt();
    }
}

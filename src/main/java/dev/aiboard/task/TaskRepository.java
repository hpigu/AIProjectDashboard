package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByProjectIdOrderBySortOrderAsc(Long projectId);

    List<Task> findByProjectIdAndStatusOrderBySortOrderAsc(Long projectId, String status);

    Optional<Task> findFirstByProjectIdAndCategoryAndStatusOrderBySortOrderAsc(
            Long projectId, String category, String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Task t SET t.status = 'IN_PROGRESS', t.assignee = :assignee, "
            + "t.claimedAt = :claimedAt, t.updatedAt = :claimedAt "
            + "WHERE t.id = :taskId AND t.status = 'TODO'")
    int claimIfTodo(@Param("taskId") Long taskId, @Param("assignee") String assignee,
                    @Param("claimedAt") LocalDateTime claimedAt);

    @Query("SELECT t FROM Task t WHERE t.projectId = :projectId "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:category IS NULL OR t.category = :category) "
            + "ORDER BY t.sortOrder ASC")
    List<Task> findByProjectIdAndOptionalFilters(@Param("projectId") Long projectId,
                                                  @Param("status") String status,
                                                  @Param("category") String category);

    @Query("SELECT COALESCE(MAX(t.sortOrder), -1) FROM Task t WHERE t.projectId = :projectId")
    int findMaxSortOrder(@Param("projectId") Long projectId);

    long countByProjectId(Long projectId);

    long countByProjectIdAndStatus(Long projectId, String status);

    @Query("SELECT t.projectId AS projectId, t.status AS status, COUNT(t) AS cnt "
            + "FROM Task t GROUP BY t.projectId, t.status")
    List<StatusCountProjection> countAllGroupedByProjectAndStatus();

    @Query("SELECT t.projectId AS projectId, MAX(t.updatedAt) AS lastActivity "
            + "FROM Task t GROUP BY t.projectId")
    List<LastActivityProjection> findLastActivityPerProject();

    interface StatusCountProjection {
        Long getProjectId();
        String getStatus();
        long getCnt();
    }

    interface LastActivityProjection {
        Long getProjectId();
        LocalDateTime getLastActivity();
    }
}

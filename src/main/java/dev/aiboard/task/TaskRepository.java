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

    List<Task> findByProjectIdAndStatusOrderBySortOrderAsc(Long projectId, TaskStatus status);

    List<Task> findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
            Long projectId, String category, TaskStatus status);

    @Query("SELECT t.id AS id, t.projectId AS projectId, t.title AS title, "
            + "t.description AS description, t.status AS status, t.category AS category, "
            + "t.sortOrder AS sortOrder, t.assignee AS assignee, t.claimedAt AS claimedAt, "
            + "t.createdAt AS createdAt, t.updatedAt AS updatedAt, t.version AS version, "
            + "t.currentBlockEventId AS currentBlockEventId "
            + "FROM Task t WHERE t.projectId = :projectId AND t.id = :taskId")
    Optional<TaskDetailProjection> findDetailByProjectIdAndId(
            @Param("projectId") Long projectId, @Param("taskId") Long taskId);

    /**
     * 原子認領。狀態改為 enum 之後，這裡的 'IN_PROGRESS'／'TODO' 字面值一併改為
     * 完整限定的 enum 常數——Hibernate 對 String 字面值有隱式轉換，但那是實作行為
     * 不是 JPQL 規格保證的；這是整個系統唯一的 compare-and-swap，不該建立在
     * 「目前這版 Hibernate 剛好會幫我轉」上面。
     *
     * <p>語意完全不變：仍是「只有 status 還是 TODO 時才更新」，影響零筆代表輸掉
     * 競爭，由 {@code TaskService.claimNextTask} 重取候選最多三次。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Task t SET t.status = dev.aiboard.task.TaskStatus.IN_PROGRESS, "
            + "t.assignee = :assignee, "
            + "t.claimedAt = :claimedAt, t.claimTokenHash = :claimTokenHash, "
            + "t.updatedAt = :claimedAt, t.version = t.version + 1 "
            + "WHERE t.id = :taskId AND t.status = dev.aiboard.task.TaskStatus.TODO")
    int claimIfTodo(@Param("taskId") Long taskId, @Param("assignee") String assignee,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("claimTokenHash") String claimTokenHash);

    @Query("SELECT t FROM Task t WHERE t.projectId = :projectId "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:category IS NULL OR t.category = :category) "
            + "ORDER BY t.sortOrder ASC")
    List<Task> findByProjectIdAndOptionalFilters(@Param("projectId") Long projectId,
                                                  @Param("status") TaskStatus status,
                                                  @Param("category") String category);

    @Query("SELECT COALESCE(MAX(t.sortOrder), -1) FROM Task t WHERE t.projectId = :projectId")
    int findMaxSortOrder(@Param("projectId") Long projectId);

    long countByProjectId(Long projectId);

    long countByProjectIdAndStatus(Long projectId, TaskStatus status);

    @Query("SELECT t.projectId AS projectId, t.status AS status, COUNT(t) AS cnt "
            + "FROM Task t GROUP BY t.projectId, t.status")
    List<StatusCountProjection> countAllGroupedByProjectAndStatus();

    @Query("SELECT t.projectId AS projectId, t.status AS status, COUNT(t) AS cnt, "
            + "MAX(t.updatedAt) AS lastActivity "
            + "FROM Task t WHERE t.projectId IN :projectIds GROUP BY t.projectId, t.status")
    List<ProjectSummaryCountProjection> countGroupedByProjectIdsAndStatus(
            @Param("projectIds") List<Long> projectIds);

    @Query("SELECT t.projectId AS projectId, MAX(t.updatedAt) AS lastActivity "
            + "FROM Task t GROUP BY t.projectId")
    List<LastActivityProjection> findLastActivityPerProject();

    interface StatusCountProjection {
        Long getProjectId();
        TaskStatus getStatus();
        long getCnt();
    }

    interface ProjectSummaryCountProjection extends StatusCountProjection {
        LocalDateTime getLastActivity();
    }

    interface LastActivityProjection {
        Long getProjectId();
        LocalDateTime getLastActivity();
    }

    interface TaskDetailProjection {
        Long getId();
        Long getProjectId();
        String getTitle();
        String getDescription();
        TaskStatus getStatus();
        String getCategory();
        Integer getSortOrder();
        String getAssignee();
        LocalDateTime getClaimedAt();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
        Long getVersion();
        Long getCurrentBlockEventId();
    }
}

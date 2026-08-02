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

    List<Task> findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
            Long projectId, String category, String status);

    @Query("SELECT t.id AS id, t.projectId AS projectId, t.title AS title, "
            + "t.description AS description, t.status AS status, t.category AS category, "
            + "t.sortOrder AS sortOrder, t.assignee AS assignee, t.claimedAt AS claimedAt, "
            + "t.createdAt AS createdAt, t.updatedAt AS updatedAt, t.version AS version, "
            + "t.currentBlockEventId AS currentBlockEventId "
            + "FROM Task t WHERE t.projectId = :projectId AND t.id = :taskId")
    Optional<TaskDetailProjection> findDetailByProjectIdAndId(
            @Param("projectId") Long projectId, @Param("taskId") Long taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Task t SET t.status = 'IN_PROGRESS', t.assignee = :assignee, "
            + "t.claimedAt = :claimedAt, t.claimTokenHash = :claimTokenHash, "
            + "t.updatedAt = :claimedAt, t.version = t.version + 1 "
            + "WHERE t.id = :taskId AND t.status = 'TODO'")
    int claimIfTodo(@Param("taskId") Long taskId, @Param("assignee") String assignee,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("claimTokenHash") String claimTokenHash);

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
        String getStatus();
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
        String getStatus();
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

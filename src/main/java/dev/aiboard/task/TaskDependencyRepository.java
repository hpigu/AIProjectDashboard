package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {

    List<TaskDependency> findByTaskIdOrderByDependsOnTaskIdAsc(Long taskId);

    List<TaskDependency> findByTaskIdIn(List<Long> taskIds);

    /**
     * 讀取單一專案完整的規劃相依關係。由 task_id 所屬專案界定範圍，讓相依圖可以
     * 用一筆查詢取得所有 edge，而不是逐一查每個 task 的前置。
     */
    @Query("SELECT d FROM TaskDependency d, Task t "
            + "WHERE d.taskId = t.id AND t.projectId = :projectId "
            + "ORDER BY d.dependsOnTaskId ASC, d.taskId ASC")
    List<TaskDependency> findByProjectId(@Param("projectId") Long projectId);

    void deleteByTaskId(Long taskId);

    /** 批次讀取前置任務，尚未 DONE 的項目先顯示。 */
    @Query("SELECT d.taskId AS taskId, t AS prerequisite FROM TaskDependency d, Task t "
            + "WHERE d.dependsOnTaskId = t.id AND d.taskId IN :taskIds "
            + "ORDER BY d.taskId ASC, CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END, t.id ASC")
    List<Prerequisite> findPrerequisitesForTasks(@Param("taskIds") List<Long> taskIds);

    @Query("SELECT t.id AS id, t.title AS title, t.status AS status, t.category AS category, "
            + "t.assignee AS assignee FROM TaskDependency d, Task t "
            + "WHERE d.dependsOnTaskId = t.id AND d.taskId = :taskId ORDER BY t.id ASC")
    List<RelatedTaskProjection> findPrerequisites(@Param("taskId") Long taskId);

    @Query("SELECT t.id AS id, t.title AS title, t.status AS status, t.category AS category, "
            + "t.assignee AS assignee FROM TaskDependency d, Task t "
            + "WHERE d.taskId = t.id AND d.dependsOnTaskId = :taskId ORDER BY t.id ASC")
    List<RelatedTaskProjection> findDownstreamTasks(@Param("taskId") Long taskId);

    interface Prerequisite {
        Long getTaskId();

        Task getPrerequisite();
    }

    interface RelatedTaskProjection {
        Long getId();
        String getTitle();
        TaskStatus getStatus();
        String getCategory();
        String getAssignee();
    }
}

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

    /**
     * 一次查出這批任務各自還在等哪些尚未 DONE 的前置任務。
     * 沒有未完成前置的任務不會出現在結果中，因此空結果代表全部可認領。
     * 認領過濾、清單標示與「在等誰」的說明都由這個查詢供應。
     */
    @Query("SELECT d.taskId AS taskId, t AS prerequisite FROM TaskDependency d, Task t "
            + "WHERE d.dependsOnTaskId = t.id "
            + "AND d.taskId IN :taskIds "
            + "AND t.status <> 'DONE' "
            + "ORDER BY d.taskId ASC, t.id ASC")
    List<UnfinishedPrerequisite> findUnfinishedPrerequisites(@Param("taskIds") List<Long> taskIds);

    @Query("SELECT t.id AS id, t.title AS title, t.status AS status, t.category AS category, "
            + "t.assignee AS assignee FROM TaskDependency d, Task t "
            + "WHERE d.dependsOnTaskId = t.id AND d.taskId = :taskId ORDER BY t.id ASC")
    List<RelatedTaskProjection> findPrerequisites(@Param("taskId") Long taskId);

    @Query("SELECT t.id AS id, t.title AS title, t.status AS status, t.category AS category, "
            + "t.assignee AS assignee FROM TaskDependency d, Task t "
            + "WHERE d.taskId = t.id AND d.dependsOnTaskId = :taskId ORDER BY t.id ASC")
    List<RelatedTaskProjection> findDownstreamTasks(@Param("taskId") Long taskId);

    interface UnfinishedPrerequisite {
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

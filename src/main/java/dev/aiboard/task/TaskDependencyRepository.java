package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {

    List<TaskDependency> findByTaskId(Long taskId);

    /**
     * 找出這批任務中，仍有前置任務尚未 DONE 的任務 id。
     * 用於認領時過濾候選：被回傳的任務代表還在等前置，不得發放。
     */
    @Query("SELECT DISTINCT d.taskId FROM TaskDependency d, Task t "
            + "WHERE d.dependsOnTaskId = t.id "
            + "AND d.taskId IN :taskIds "
            + "AND t.status <> 'DONE'")
    List<Long> findBlockedTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 列出指定任務尚未完成的前置任務，供訊息與前端說明「在等誰」。
     */
    @Query("SELECT t FROM TaskDependency d, Task t "
            + "WHERE d.dependsOnTaskId = t.id "
            + "AND d.taskId = :taskId "
            + "AND t.status <> 'DONE' "
            + "ORDER BY t.id ASC")
    List<Task> findUnfinishedPrerequisites(@Param("taskId") Long taskId);

    /**
     * 一次撈出多個任務的前置關係，避免前端／清單查詢時逐筆 N+1。
     */
    @Query("SELECT d FROM TaskDependency d WHERE d.taskId IN :taskIds")
    List<TaskDependency> findByTaskIdIn(@Param("taskIds") List<Long> taskIds);
}

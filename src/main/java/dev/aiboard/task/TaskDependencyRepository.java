package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {

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

    interface UnfinishedPrerequisite {
        Long getTaskId();

        Task getPrerequisite();
    }
}

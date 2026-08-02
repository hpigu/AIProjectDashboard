package dev.aiboard.web;

import dev.aiboard.task.TaskDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 唯讀任務詳情端點；與既有 board controller 分離，避免讓兩種 payload 耦合。 */
@RestController
public class TaskDetailController {

    private final TaskDetailService taskDetailService;

    public TaskDetailController(TaskDetailService taskDetailService) {
        this.taskDetailService = taskDetailService;
    }

    @GetMapping("/api/projects/{id}/tasks/{taskId}")
    public TaskDetailService.TaskDetail getTaskDetail(@PathVariable("id") Long id,
                                                       @PathVariable("taskId") Long taskId) {
        return taskDetailService.getTaskDetail(id, taskId);
    }
}

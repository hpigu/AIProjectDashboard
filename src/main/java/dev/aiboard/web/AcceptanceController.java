package dev.aiboard.web;
import dev.aiboard.acceptance.AcceptanceService;
import dev.aiboard.task.TaskDetailService;
import org.springframework.web.bind.annotation.*;

@RestController
public class AcceptanceController {
    private final AcceptanceService service;
    private final TaskDetailService details;
    public AcceptanceController(AcceptanceService service, TaskDetailService details) { this.service=service; this.details=details; }
    @GetMapping("/api/projects/{projectId}/tasks/{taskId}/acceptance")
    public AcceptanceService.Summary summary(@PathVariable Long projectId, @PathVariable Long taskId) {
        details.getTaskDetail(projectId,taskId);
        return service.summary(taskId);
    }
}

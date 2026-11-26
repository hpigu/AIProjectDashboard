package dev.aiboard.web;

import dev.aiboard.workflow.ProjectWorkflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectWorkflowController {
    private final ProjectWorkflowService workflows;
    public ProjectWorkflowController(ProjectWorkflowService workflows) { this.workflows = workflows; }
    @GetMapping("/api/projects/{id}/workflow")
    public ProjectWorkflowService.Summary get(@PathVariable Long id) { return workflows.getById(id); }
}

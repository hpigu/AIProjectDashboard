package dev.aiboard.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProjectListController {

    private final ProjectQuery projectQuery;

    public ProjectListController(ProjectQuery projectQuery) {
        this.projectQuery = projectQuery;
    }

    @GetMapping("/api/projects")
    public List<ProjectQuery.ProjectSummary> listProjects(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return projectQuery.listSummaries(includeArchived);
    }
}

package dev.aiboard.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@RestController
public class ProjectListController {

    private final ProjectQuery projectQuery;

    public ProjectListController(ProjectQuery projectQuery) {
        this.projectQuery = projectQuery;
    }

    @GetMapping("/api/projects")
    public List<ProjectQuery.ProjectSummary> listProjects(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "blocked", required = false) Boolean blocked,
            @RequestParam(name = "sort", required = false) String sort) {
        if (query == null && status == null && blocked == null && sort == null) {
            return projectQuery.listSummaries(includeArchived);
        }
        try {
            return projectQuery.listSummaries(query, status == null && includeArchived ? "ALL" : status,
                    blocked, sort);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}

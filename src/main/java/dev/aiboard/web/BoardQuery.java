package dev.aiboard.web;

import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BoardQuery {

    private static final List<String> COLUMNS = List.of("TODO", "IN_PROGRESS", "BLOCKED", "DONE");

    private final ProjectService projectService;
    private final TaskService taskService;

    public BoardQuery(ProjectService projectService, TaskService taskService) {
        this.projectService = projectService;
        this.taskService = taskService;
    }

    public BoardView getBoard(Long projectId) {
        ProjectService.ProjectDto project = projectService.getById(projectId);
        List<TaskService.TaskDto> tasks = taskService.listTasks(projectId, null);

        Map<String, List<TaskCard>> columns = new LinkedHashMap<>();
        for (String status : COLUMNS) {
            columns.put(status, new java.util.ArrayList<>());
        }
        for (TaskService.TaskDto task : tasks) {
            columns.computeIfAbsent(task.status(), s -> new java.util.ArrayList<>())
                    .add(new TaskCard(task.id(), task.title(), task.category(), task.sortOrder(),
                            task.assignee()));
        }

        return new BoardView(project.id(), project.name(), project.description(), columns);
    }

    public record TaskCard(Long id, String title, String category, Integer sortOrder,
                           String assignee) {
    }

    public record BoardView(Long id, String name, String description, Map<String, List<TaskCard>> columns) {
    }
}

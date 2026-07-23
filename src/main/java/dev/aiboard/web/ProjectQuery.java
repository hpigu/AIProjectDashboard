package dev.aiboard.web;

import dev.aiboard.project.Project;
import dev.aiboard.project.ProjectRepository;
import dev.aiboard.task.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProjectQuery {

    private static final List<String> STATUSES = List.of("TODO", "IN_PROGRESS", "BLOCKED", "DONE");

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    public ProjectQuery(ProjectRepository projectRepository, TaskRepository taskRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
    }

    public List<ProjectSummary> listSummaries(boolean includeArchived) {
        List<Project> projects = projectRepository.findAll().stream()
                .filter(p -> includeArchived || "ACTIVE".equals(p.getStatus()))
                .toList();

        Map<Long, Map<String, Long>> countsByProject = new HashMap<>();
        for (TaskRepository.StatusCountProjection row : taskRepository.countAllGroupedByProjectAndStatus()) {
            countsByProject
                    .computeIfAbsent(row.getProjectId(), id -> new HashMap<>())
                    .put(row.getStatus(), row.getCnt());
        }

        Map<Long, LocalDateTime> lastActivityByProject = new HashMap<>();
        for (TaskRepository.LastActivityProjection row : taskRepository.findLastActivityPerProject()) {
            lastActivityByProject.put(row.getProjectId(), row.getLastActivity());
        }

        List<ProjectSummary> summaries = projects.stream()
                .map(p -> toSummary(p, countsByProject.getOrDefault(p.getId(), Map.of()),
                        lastActivityByProject.getOrDefault(p.getId(), p.getUpdatedAt())))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        summaries.sort(Comparator
                .comparing((ProjectSummary s) -> !s.hasBlocked())
                .thenComparing(ProjectSummary::updatedAt, Comparator.reverseOrder()));

        return summaries;
    }

    private ProjectSummary toSummary(Project project, Map<String, Long> counts, LocalDateTime updatedAt) {
        Map<String, Long> fullCounts = new java.util.LinkedHashMap<>();
        long total = 0;
        for (String status : STATUSES) {
            long c = counts.getOrDefault(status, 0L);
            fullCounts.put(status, c);
            total += c;
        }
        boolean hasBlocked = fullCounts.get("BLOCKED") > 0;
        return new ProjectSummary(project.getId(), project.getName(), project.getStatus(),
                fullCounts, total, hasBlocked, updatedAt);
    }

    public record ProjectSummary(Long id, String name, String status, Map<String, Long> counts,
                                  long total, boolean hasBlocked, LocalDateTime updatedAt) {
    }
}

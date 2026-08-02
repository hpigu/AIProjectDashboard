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

    /**
     * Lists project summaries after applying project-level filters. Tasks are then aggregated for
     * exactly that project batch, avoiding both a full-board aggregate and per-project queries.
     */
    public List<ProjectSummary> listSummaries(String query, String status, Boolean blocked, String sort) {
        String normalizedPrefix = normalizePrefix(query);
        String statusFilter = StatusFilter.from(status).name();
        SortOrder sortOrder = SortOrder.from(sort);

        List<Project> projects = findProjects(normalizedPrefix, statusFilter);
        if (projects.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<String, Long>> countsByProject = new HashMap<>();
        Map<Long, LocalDateTime> lastActivityByProject = new HashMap<>();
        List<Long> projectIds = projects.stream().map(Project::getId).toList();
        for (TaskRepository.ProjectSummaryCountProjection row
                : taskRepository.countGroupedByProjectIdsAndStatus(projectIds)) {
            countsByProject
                    .computeIfAbsent(row.getProjectId(), id -> new HashMap<>())
                    .put(row.getStatus(), row.getCnt());
            lastActivityByProject.merge(row.getProjectId(), row.getLastActivity(),
                    (current, candidate) -> current.isAfter(candidate) ? current : candidate);
        }

        List<ProjectSummary> summaries = projects.stream()
                .map(project -> toSummary(project, countsByProject.getOrDefault(project.getId(), Map.of()),
                        lastActivityByProject.getOrDefault(project.getId(), project.getUpdatedAt())))
                .filter(summary -> blocked == null || summary.hasBlocked() == blocked)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        summaries.sort(comparatorFor(sortOrder));
        return summaries;
    }

    private List<Project> findProjects(String normalizedPrefix, String status) {
        if (normalizedPrefix == null) {
            return "ALL".equals(status) ? projectRepository.findAll() : projectRepository.findByStatus(status);
        }
        return "ALL".equals(status)
                ? projectRepository.findByNormalizedNamePrefix(normalizedPrefix)
                : projectRepository.findByNormalizedNamePrefixAndStatus(normalizedPrefix, status);
    }

    private static String normalizePrefix(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        return Project.normalizeName(query)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static Comparator<ProjectSummary> comparatorFor(SortOrder sortOrder) {
        return switch (sortOrder) {
            case UPDATED_DESC -> Comparator.comparing(ProjectSummary::updatedAt, Comparator.reverseOrder())
                    .thenComparing(ProjectSummary::id);
            case NAME -> Comparator.comparing(ProjectSummary::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ProjectSummary::id);
            case PROGRESS -> (left, right) -> {
                int progress = Double.compare(completionRatio(right), completionRatio(left));
                return progress != 0 ? progress : comparatorFor(SortOrder.UPDATED_DESC).compare(left, right);
            };
        };
    }

    private static double completionRatio(ProjectSummary summary) {
        return summary.total() == 0 ? 0 : (double) summary.counts().get("DONE") / summary.total();
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

    private enum StatusFilter {
        ACTIVE, ARCHIVED, ALL;

        static StatusFilter from(String value) {
            if (value == null || value.isBlank()) {
                return ACTIVE;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("status must be ACTIVE, ARCHIVED, or ALL");
            }
        }
    }

    private enum SortOrder {
        UPDATED_DESC, NAME, PROGRESS;

        static SortOrder from(String value) {
            if (value == null || value.isBlank()) {
                return UPDATED_DESC;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("sort must be UPDATED_DESC, NAME, or PROGRESS");
            }
        }
    }
}

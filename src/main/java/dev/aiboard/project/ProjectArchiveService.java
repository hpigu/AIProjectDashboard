package dev.aiboard.project;

import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.task.TaskRepository;
import dev.aiboard.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Project lifecycle mutations. Archive takes the project row lock before it counts tasks and
 * changes status; every project-scoped writer takes that same lock through ProjectService.
 */
@Service
public class ProjectArchiveService {

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final ProjectAuditRepository projectAuditRepository;
    private final BoardEventPublisher eventPublisher;

    public ProjectArchiveService(ProjectRepository projectRepository, TaskRepository taskRepository,
                                 ProjectAuditRepository projectAuditRepository,
                                 BoardEventPublisher eventPublisher) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.projectAuditRepository = projectAuditRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public ArchivePreview previewArchive(String projectName) {
        Project project = findByName(projectName);
        return new ArchivePreview(toDto(project), statusCounts(project.getId()), assignees(project.getId()));
    }

    @Transactional
    public ArchiveResult archive(String projectName, String reason, boolean inProgressConfirmed) {
        String normalizedReason = requireReason(reason);
        Project project = findByNameForUpdate(projectName);
        if ("ARCHIVED".equals(project.getStatus())) {
            throw new BoardException("專案「%s」已封存；請先恢復後再操作".formatted(project.getName()));
        }

        StatusCounts counts = statusCounts(project.getId());
        if (counts.inProgress() > 0 && !inProgressConfirmed) {
            throw new BoardException("專案「%s」目前有 %d 筆 IN_PROGRESS 任務；"
                    + "必須先向使用者再次明確確認，再以 inProgressConfirmed=true 封存"
                    .formatted(project.getName(), counts.inProgress()));
        }

        project.archive();
        ProjectAudit audit = projectAuditRepository.save(new ProjectAudit(
                project.getId(), "ARCHIVED", normalizedReason, counts));
        eventPublisher.publish(BoardEvent.projectStatusChanged(project.getId(), "ACTIVE", "ARCHIVED",
                project.getUpdatedAt().toString()));
        return new ArchiveResult(toDto(project), counts, audit.getOccurredAt());
    }

    @Transactional
    public ArchiveResult restore(String projectName, String reason) {
        String normalizedReason = requireReason(reason);
        Project project = findByNameForUpdate(projectName);
        if (!"ARCHIVED".equals(project.getStatus())) {
            throw new BoardException("專案「%s」目前為 ACTIVE，無需恢復".formatted(project.getName()));
        }

        StatusCounts counts = statusCounts(project.getId());
        project.restore();
        ProjectAudit audit = projectAuditRepository.save(new ProjectAudit(
                project.getId(), "RESTORED", normalizedReason, counts));
        eventPublisher.publish(BoardEvent.projectStatusChanged(project.getId(), "ARCHIVED", "ACTIVE",
                project.getUpdatedAt().toString()));
        return new ArchiveResult(toDto(project), counts, audit.getOccurredAt());
    }

    private Project findByName(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("專案名稱不可為空白");
        }
        return projectRepository.findByNormalizedName(Project.normalizeName(projectName))
                .orElseThrow(() -> new BoardException("找不到專案：「%s」".formatted(projectName)));
    }

    private Project findByNameForUpdate(String projectName) {
        Project found = findByName(projectName);
        return projectRepository.findByIdForUpdate(found.getId())
                .orElseThrow(() -> new BoardException("找不到專案：#" + found.getId()));
    }

    private StatusCounts statusCounts(Long projectId) {
        return new StatusCounts(
                taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.TODO.name()),
                taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.IN_PROGRESS.name()),
                taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.BLOCKED.name()),
                taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.DONE.name()));
    }

    private List<Assignee> assignees(Long projectId) {
        return taskRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .filter(task -> task.getAssignee() != null && !task.getAssignee().isBlank())
                .map(task -> new Assignee(task.getId(), task.getTitle(), task.getStatus(), task.getAssignee()))
                .toList();
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("封存或恢復必須填寫 reason，留下使用者授權的原因");
        }
        return reason.trim();
    }

    private ProjectService.ProjectDto toDto(Project project) {
        return new ProjectService.ProjectDto(project.getId(), project.getName(), project.getDescription(),
                project.getStatus());
    }

    public record StatusCounts(long todo, long inProgress, long blocked, long done) {
        public long total() {
            return todo + inProgress + blocked + done;
        }
    }

    public record Assignee(Long taskId, String title, String status, String assignee) {
    }

    public record ArchivePreview(ProjectService.ProjectDto project, StatusCounts counts,
                                 List<Assignee> assignees) {
    }

    public record ArchiveResult(ProjectService.ProjectDto project, StatusCounts counts,
                                LocalDateTime auditedAt) {
    }
}

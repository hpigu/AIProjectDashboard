package dev.aiboard.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.aiboard.common.BoardException;
import dev.aiboard.common.TaskCategory;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

@Service
public class TaskService {

    private static final int CLAIM_RETRY_LIMIT = 3;

    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectService projectService;
    private final BoardEventPublisher eventPublisher;

    public TaskService(TaskRepository taskRepository, TaskLogRepository taskLogRepository,
                        ProjectService projectService, BoardEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public List<TaskDto> createTasks(Long projectId, List<TaskInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("任務清單不可為空");
        }
        if (inputs.size() > 50) {
            throw new IllegalArgumentException("一次最多建立 50 筆任務");
        }
        for (TaskInput input : inputs) {
            if (input == null) {
                throw new IllegalArgumentException("任務內容不可為 null");
            }
            if (input.title() == null || input.title().isBlank()) {
                throw new IllegalArgumentException("任務標題不可為空白");
            }
            if (input.title().trim().length() > 300) {
                throw new IllegalArgumentException("任務標題不可超過 300 字");
            }
        }
        // Serialize sort-order allocation per project.
        projectService.assertExistsForUpdate(projectId);

        int startSortOrder = taskRepository.findMaxSortOrder(projectId) + 1;

        List<TaskDto> results = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            TaskInput input = inputs.get(i);
            TaskCategory category = TaskCategory.fromStringOrOther(input.category());
            Task task = new Task(projectId, input.title().trim(), input.description(),
                    category.name(), startSortOrder + i);
            Task saved = taskRepository.save(task);
            taskLogRepository.save(new TaskLog(saved.getId(), null, TaskStatus.TODO.name(), null));
            results.add(toDto(saved));
        }
        eventPublisher.publish(BoardEvent.tasksCreated(projectId, results.size()));
        return results;
    }

    @Transactional
    public TaskStatusChangeResult updateStatus(Long taskId, String targetStatusRaw, String note) {
        TaskStatus target = parseStatus(targetStatusRaw);
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));
        TaskStatus current = parseStatus(task.getStatus());

        if (!current.canTransitionTo(target)) {
            throw new BoardException(
                    "不合法的狀態轉移：#%d 目前是 %s，無法轉移至 %s".formatted(taskId, current, target));
        }

        if (target == TaskStatus.IN_PROGRESS
                && (task.getAssignee() == null || task.getClaimedAt() == null)) {
            throw new BoardException("任務 #" + taskId
                    + " 尚未認領；請使用 claim_next_task 開始任務");
        }
        if (current == TaskStatus.TODO && target == TaskStatus.BLOCKED
                && task.getAssignee() == null) {
            throw new BoardException("未認領的 TODO 任務不能標記為 BLOCKED");
        }

        boolean changed = current != target;
        if (changed) {
            task.changeStatus(target);
            taskLogRepository.save(new TaskLog(taskId, current.name(), target.name(), note));
            eventPublisher.publish(BoardEvent.taskStatusChanged(
                    task.getProjectId(), taskId, current.name(), target.name(),
                    task.getUpdatedAt().toString()));
        }

        long doneCount = taskRepository.countByProjectIdAndStatus(task.getProjectId(), TaskStatus.DONE.name());
        long totalCount = taskRepository.countByProjectId(task.getProjectId());
        ProjectService.ProjectDto project = projectService.getById(task.getProjectId());

        return new TaskStatusChangeResult(toDto(task), current, target, changed, project, doneCount, totalCount);
    }

    private TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("狀態不可為空白");
        }
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不合法的狀態：" + raw);
        }
    }

    public List<TaskDto> listTasks(Long projectId, String status) {
        return listTasks(projectId, status, null);
    }

    public List<TaskDto> listTasks(Long projectId, String status, String category) {
        projectService.assertExists(projectId);
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        String normalizedCategory = (category == null || category.isBlank()) ? null : category.trim().toUpperCase();
        List<Task> tasks = taskRepository.findByProjectIdAndOptionalFilters(projectId, normalizedStatus, normalizedCategory);
        return tasks.stream().map(this::toDto).toList();
    }

    @Transactional
    public ClaimNextTaskResult claimNextTask(String projectName, String categoryRaw, String assigneeRaw) {
        TaskCategory category = parseClaimCategory(categoryRaw);
        String assignee = normalizeAssignee(assigneeRaw);
        var project = projectService.findByNameIgnoreCase(projectName);
        if (project.isEmpty()) {
            return ClaimNextTaskResult.projectNotFound(projectService.listProjects(), category.name());
        }

        for (int attempt = 0; attempt < CLAIM_RETRY_LIMIT; attempt++) {
            var candidate = taskRepository
                    .findFirstByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                            project.get().id(), category.name(), TaskStatus.TODO.name());
            if (candidate.isEmpty()) {
                return ClaimNextTaskResult.noTask(project.get(), category.name());
            }

            LocalDateTime claimedAt = LocalDateTime.now();
            int updated = taskRepository.claimIfTodo(candidate.get().getId(), assignee, claimedAt);
            if (updated == 1) {
                Task claimed = taskRepository.findById(candidate.get().getId())
                        .orElseThrow(() -> new BoardException("認領後找不到任務：#" + candidate.get().getId()));
                taskLogRepository.save(new TaskLog(claimed.getId(), TaskStatus.TODO.name(),
                        TaskStatus.IN_PROGRESS.name(), "認領者：" + assignee));
                eventPublisher.publish(BoardEvent.taskStatusChanged(
                        claimed.getProjectId(), claimed.getId(), TaskStatus.TODO.name(),
                        TaskStatus.IN_PROGRESS.name(), claimed.getUpdatedAt().toString()));
                return ClaimNextTaskResult.claimed(project.get(), toDto(claimed), category.name());
            }
        }

        return ClaimNextTaskResult.noTask(project.get(), category.name());
    }

    public long countByProjectId(Long projectId) {
        return taskRepository.countByProjectId(projectId);
    }

    public long countByProjectIdAndStatus(Long projectId, String status) {
        return taskRepository.countByProjectIdAndStatus(projectId, status);
    }

    public List<TaskLogDto> getHistory(Long projectId, Long taskId) {
        projectService.assertExists(projectId);
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));
        if (!task.getProjectId().equals(projectId)) {
            throw new BoardException("任務 #" + taskId + " 不屬於專案 #" + projectId);
        }
        return taskLogRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .map(log -> new TaskLogDto(log.getId(), log.getTaskId(), log.getFromStatus(),
                        log.getToStatus(), log.getNote(), log.getCreatedAt()))
                .toList();
    }

    private TaskDto toDto(Task task) {
        return new TaskDto(task.getId(), task.getProjectId(), task.getTitle(), task.getDescription(),
                task.getStatus(), task.getCategory(), task.getSortOrder(),
                task.getAssignee(), task.getClaimedAt());
    }

    private TaskCategory parseClaimCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("任務分類不可為空白");
        }
        try {
            return TaskCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不合法的任務分類：" + raw);
        }
    }

    private String normalizeAssignee(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("認領者不可為空白");
        }
        String assignee = raw.trim();
        if (assignee.length() > 60) {
            throw new IllegalArgumentException("認領者名稱不可超過 60 字");
        }
        return assignee;
    }

    public record TaskInput(String title, String description, String category) {
    }

    public record TaskDto(Long id, Long projectId, String title, String description, String status,
                           String category, Integer sortOrder, String assignee,
                           LocalDateTime claimedAt) {
    }

    public record ClaimNextTaskResult(ProjectService.ProjectDto project, TaskDto task,
                                      List<ProjectService.ProjectDto> availableProjects,
                                      String category) {
        public static ClaimNextTaskResult claimed(ProjectService.ProjectDto project, TaskDto task,
                                                   String category) {
            return new ClaimNextTaskResult(project, task, List.of(), category);
        }

        public static ClaimNextTaskResult noTask(ProjectService.ProjectDto project, String category) {
            return new ClaimNextTaskResult(project, null, List.of(), category);
        }

        public static ClaimNextTaskResult projectNotFound(
                List<ProjectService.ProjectDto> availableProjects, String category) {
            return new ClaimNextTaskResult(null, null, availableProjects, category);
        }

        public boolean projectFound() {
            return project != null;
        }

        public boolean claimed() {
            return task != null;
        }
    }

    public record TaskLogDto(Long id, Long taskId, String fromStatus, String toStatus, String note,
                              java.time.LocalDateTime createdAt) {
    }

    public record TaskStatusChangeResult(TaskDto task, TaskStatus from, TaskStatus to, boolean changed,
                                          ProjectService.ProjectDto project, long doneCount, long totalCount) {
    }
}

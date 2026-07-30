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
    private final TaskDependencyRepository taskDependencyRepository;
    private final ProjectService projectService;
    private final BoardEventPublisher eventPublisher;

    public TaskService(TaskRepository taskRepository, TaskLogRepository taskLogRepository,
                        TaskDependencyRepository taskDependencyRepository,
                        ProjectService projectService, BoardEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskDependencyRepository = taskDependencyRepository;
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

        validateDependencies(projectId, inputs);

        List<TaskDto> results = new ArrayList<>(inputs.size());
        List<Long> createdIds = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            TaskInput input = inputs.get(i);
            TaskCategory category = TaskCategory.fromStringOrOther(input.category());
            Task task = new Task(projectId, input.title().trim(), input.description(),
                    category.name(), startSortOrder + i);
            Task saved = taskRepository.save(task);
            taskLogRepository.save(new TaskLog(saved.getId(), null, TaskStatus.TODO.name(), null));
            createdIds.add(saved.getId());
            results.add(toDto(saved));
        }

        // 相依關係在所有任務都有 id 之後才建立，批次內序號才解析得了。
        for (int i = 0; i < inputs.size(); i++) {
            TaskInput input = inputs.get(i);
            Long taskId = createdIds.get(i);
            for (Integer index : input.dependsOnIndexes()) {
                taskDependencyRepository.save(
                        new TaskDependency(taskId, createdIds.get(index - 1)));
            }
            for (Long dependsOnId : input.dependsOnTaskIds()) {
                taskDependencyRepository.save(new TaskDependency(taskId, dependsOnId));
            }
        }

        eventPublisher.publish(BoardEvent.tasksCreated(projectId, results.size()));
        return results;
    }

    private void validateDependencies(Long projectId, List<TaskInput> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            TaskInput input = inputs.get(i);
            int position = i + 1;

            for (Integer index : input.dependsOnIndexes()) {
                if (index == null || index < 1 || index > inputs.size()) {
                    throw new IllegalArgumentException(
                            "第 %d 筆任務的前置序號 %s 超出本批次範圍（1～%d）"
                                    .formatted(position, index, inputs.size()));
                }
                if (index == position) {
                    throw new IllegalArgumentException("第 %d 筆任務不可依賴自己".formatted(position));
                }
                if (index > position) {
                    throw new IllegalArgumentException(
                            "第 %d 筆任務的前置序號 %d 排在自己之後；前置任務必須先建立"
                                    .formatted(position, index));
                }
            }

            for (Long dependsOnId : input.dependsOnTaskIds()) {
                Task prerequisite = taskRepository.findById(dependsOnId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "第 %d 筆任務的前置任務 #%d 不存在".formatted(position, dependsOnId)));
                if (!prerequisite.getProjectId().equals(projectId)) {
                    throw new IllegalArgumentException(
                            "第 %d 筆任務的前置任務 #%d 屬於其他專案".formatted(position, dependsOnId));
                }
            }
        }
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

    public ProjectTasksResult listTasksByProjectRef(Long projectId, String projectName,
                                                     String status, String category) {
        boolean hasId = projectId != null;
        boolean hasName = projectName != null && !projectName.isBlank();
        if (!hasId && !hasName) {
            throw new IllegalArgumentException("projectId 與 projectName 至少需擇一提供");
        }

        ProjectService.ProjectDto project;
        if (hasId) {
            project = projectService.getById(projectId);
        } else {
            var found = projectService.findByNameIgnoreCase(projectName);
            if (found.isEmpty()) {
                return ProjectTasksResult.projectNotFound(projectService.listProjects());
            }
            project = found.get();
        }

        List<TaskDto> tasks = listTasks(project.id(), status, category);
        return ProjectTasksResult.found(project, tasks);
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
            List<Task> candidates = taskRepository
                    .findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                            project.get().id(), category.name(), TaskStatus.TODO.name());
            if (candidates.isEmpty()) {
                return ClaimNextTaskResult.noTask(project.get(), category.name());
            }

            // 前置任務尚未 DONE 的候選一律跳過，讓沒有相依的任務可以先做。
            List<Long> blocked = taskDependencyRepository.findBlockedTaskIds(
                    candidates.stream().map(Task::getId).toList());
            Task candidate = candidates.stream()
                    .filter(t -> !blocked.contains(t.getId()))
                    .findFirst()
                    .orElse(null);
            if (candidate == null) {
                return ClaimNextTaskResult.allBlocked(project.get(), category.name(),
                        describeBlockedCandidates(candidates));
            }

            LocalDateTime claimedAt = LocalDateTime.now();
            int updated = taskRepository.claimIfTodo(candidate.getId(), assignee, claimedAt);
            if (updated == 1) {
                Task claimed = taskRepository.findById(candidate.getId())
                        .orElseThrow(() -> new BoardException("認領後找不到任務：#" + candidate.getId()));
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

    /** 產生「#12 標題（等待 #8 前置任務）」形式的說明，讓呼叫者知道卡在哪。 */
    private List<String> describeBlockedCandidates(List<Task> candidates) {
        return candidates.stream()
                .map(task -> {
                    List<Task> waiting = taskDependencyRepository.findUnfinishedPrerequisites(task.getId());
                    String waitingLabel = waiting.stream()
                            .map(w -> "#%d %s".formatted(w.getId(), w.getTitle()))
                            .reduce((a, b) -> a + "、" + b)
                            .orElse("");
                    return "#%d %s（等待：%s）".formatted(task.getId(), task.getTitle(), waitingLabel);
                })
                .toList();
    }

    /** 查詢指定任務尚未完成的前置任務。 */
    public List<TaskDto> getUnfinishedPrerequisites(Long taskId) {
        return taskDependencyRepository.findUnfinishedPrerequisites(taskId).stream()
                .map(this::toDto)
                .toList();
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

    /**
     * @param dependsOnIndexes 同批次內的前置任務，以 1-based 序號指定
     *                         （規劃階段任務尚未有 id，只能用序號表達批次內相依）
     * @param dependsOnTaskIds 看板上既有任務的 id，作為前置任務
     */
    public record TaskInput(String title, String description, String category,
                             List<Integer> dependsOnIndexes, List<Long> dependsOnTaskIds) {
        public TaskInput(String title, String description, String category) {
            this(title, description, category, List.of(), List.of());
        }

        public List<Integer> dependsOnIndexes() {
            return dependsOnIndexes == null ? List.of() : dependsOnIndexes;
        }

        public List<Long> dependsOnTaskIds() {
            return dependsOnTaskIds == null ? List.of() : dependsOnTaskIds;
        }
    }

    public record TaskDto(Long id, Long projectId, String title, String description, String status,
                           String category, Integer sortOrder, String assignee,
                           LocalDateTime claimedAt) {
    }

    public record ClaimNextTaskResult(ProjectService.ProjectDto project, TaskDto task,
                                      List<ProjectService.ProjectDto> availableProjects,
                                      String category, List<String> blockedCandidates) {
        public static ClaimNextTaskResult claimed(ProjectService.ProjectDto project, TaskDto task,
                                                   String category) {
            return new ClaimNextTaskResult(project, task, List.of(), category, List.of());
        }

        public static ClaimNextTaskResult noTask(ProjectService.ProjectDto project, String category) {
            return new ClaimNextTaskResult(project, null, List.of(), category, List.of());
        }

        /** 該 category 還有 TODO，但全部都在等前置任務完成。 */
        public static ClaimNextTaskResult allBlocked(ProjectService.ProjectDto project, String category,
                                                      List<String> blockedCandidates) {
            return new ClaimNextTaskResult(project, null, List.of(), category, blockedCandidates);
        }

        public static ClaimNextTaskResult projectNotFound(
                List<ProjectService.ProjectDto> availableProjects, String category) {
            return new ClaimNextTaskResult(null, null, availableProjects, category, List.of());
        }

        public boolean projectFound() {
            return project != null;
        }

        public boolean claimed() {
            return task != null;
        }

        public boolean blockedByDependency() {
            return task == null && !blockedCandidates.isEmpty();
        }
    }

    public record TaskLogDto(Long id, Long taskId, String fromStatus, String toStatus, String note,
                              java.time.LocalDateTime createdAt) {
    }

    public record ProjectTasksResult(ProjectService.ProjectDto project, List<TaskDto> tasks,
                                      List<ProjectService.ProjectDto> availableProjects) {
        public static ProjectTasksResult found(ProjectService.ProjectDto project, List<TaskDto> tasks) {
            return new ProjectTasksResult(project, tasks, List.of());
        }

        public static ProjectTasksResult projectNotFound(List<ProjectService.ProjectDto> availableProjects) {
            return new ProjectTasksResult(null, List.of(), availableProjects);
        }

        public boolean projectFound() {
            return project != null;
        }
    }

    public record TaskStatusChangeResult(TaskDto task, TaskStatus from, TaskStatus to, boolean changed,
                                          ProjectService.ProjectDto project, long doneCount, long totalCount) {
    }
}

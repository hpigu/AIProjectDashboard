package dev.aiboard.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.aiboard.common.BoardException;
import dev.aiboard.common.TaskCategory;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Service
public class TaskService {

    private static final int CLAIM_RETRY_LIMIT = 3;

    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ProjectService projectService;
    private final BoardEventPublisher eventPublisher;
    private final ClaimTokenService claimTokenService;

    public TaskService(TaskRepository taskRepository, TaskLogRepository taskLogRepository,
                        TaskDependencyRepository taskDependencyRepository,
                        ProjectService projectService, BoardEventPublisher eventPublisher,
                        ClaimTokenService claimTokenService) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
        this.claimTokenService = claimTokenService;
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
        projectService.assertActiveForUpdate(projectId);

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
        List<TaskDependency> dependencies = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            TaskInput input = inputs.get(i);
            Long taskId = createdIds.get(i);
            for (Integer index : input.dependsOnIndexes()) {
                dependencies.add(new TaskDependency(taskId, createdIds.get(index - 1)));
            }
            for (Long dependsOnId : input.dependsOnTaskIds()) {
                dependencies.add(new TaskDependency(taskId, dependsOnId));
            }
        }
        taskDependencyRepository.saveAll(dependencies);

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

    /**
     * 相容既有呼叫方式（無 token）。舊契約：只要任務已認領即可操作。
     * 新契約疊加在同一個方法上——任務是否要求 token 完全看該筆任務自己有沒有
     * claimTokenHash：部署當下已存在、由舊流程認領的任務沒有 hash，不受影響；
     * 用新版 claim_next_task 認領、已經拿到 hash 的任務，才會要求呼叫端帶對
     * 的 token，否則一律拒絕。
     */
    @Transactional
    public TaskStatusChangeResult updateStatus(Long taskId, String targetStatusRaw, String note) {
        return updateStatus(taskId, targetStatusRaw, note, null);
    }

    @Transactional
    public TaskStatusChangeResult updateStatus(Long taskId, String targetStatusRaw, String note,
                                                String claimToken) {
        TaskStatus target = parseStatus(targetStatusRaw);
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));
        projectService.assertActiveForUpdate(task.getProjectId());
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

        // token 驗證：只有「這筆任務本身有 claimTokenHash」時才強制要求，
        // 讓部署前既有、以舊契約認領（沒有 token）的任務仍可用舊呼叫方式完成。
        boolean operatingOnOwnedClaim = current == TaskStatus.IN_PROGRESS || current == TaskStatus.BLOCKED;
        if (operatingOnOwnedClaim && task.getClaimTokenHash() != null) {
            if (claimToken == null || claimToken.isBlank()) {
                throw new BoardException("任務 #" + taskId
                        + " 需要認領時取得的 claim token 才能操作，請提供 claimToken");
            }
            if (!claimTokenService.matches(claimToken, task.getClaimTokenHash())) {
                throw new BoardException("任務 #" + taskId + " 的 claim token 不正確，無法操作");
            }
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

    /**
     * Leader 專用的遺失 token 重置流程。跳過 token 驗證，直接把任務打回 TODO
     * 並清空認領資訊（含 token hash）。一般 agent 不得呼叫這個方法，
     * MCP 層要用獨立的 reset 工具暴露，不能疊加在 update_task_status 上，
     * 避免一般 agent 用「忘記帶 token」繞過驗證。
     */
    @Transactional
    public TaskStatusChangeResult resetClaimAsLeader(Long taskId, String note) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));
        projectService.assertActiveForUpdate(task.getProjectId());
        TaskStatus current = parseStatus(task.getStatus());

        if (current == TaskStatus.DONE) {
            throw new BoardException("任務 #" + taskId + " 已完成，無需重置認領");
        }
        if (current == TaskStatus.TODO) {
            throw new BoardException("任務 #" + taskId + " 尚未被認領，無需重置");
        }

        task.changeStatus(TaskStatus.TODO);
        taskLogRepository.save(new TaskLog(taskId, current.name(), TaskStatus.TODO.name(),
                "leader 重置遺失的 claim token" + (note == null || note.isBlank() ? "" : "：" + note)));
        eventPublisher.publish(BoardEvent.taskStatusChanged(
                task.getProjectId(), taskId, current.name(), TaskStatus.TODO.name(),
                task.getUpdatedAt().toString()));

        long doneCount = taskRepository.countByProjectIdAndStatus(task.getProjectId(), TaskStatus.DONE.name());
        long totalCount = taskRepository.countByProjectId(task.getProjectId());
        ProjectService.ProjectDto project = projectService.getById(task.getProjectId());

        return new TaskStatusChangeResult(toDto(task), current, TaskStatus.TODO, true, project, doneCount, totalCount);
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
        projectService.assertActiveForUpdate(project.get().id());

        boolean contentionObserved = false;
        for (int attempt = 0; attempt < CLAIM_RETRY_LIMIT; attempt++) {
            List<Task> candidates = taskRepository
                    .findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                            project.get().id(), category.name(), TaskStatus.TODO.name());
            if (candidates.isEmpty()) {
                return contentionObserved
                        ? ClaimNextTaskResult.contended(project.get(), category.name())
                        : ClaimNextTaskResult.noTask(project.get(), category.name());
            }

            // 前置任務尚未 DONE 的候選一律跳過，讓沒有相依的任務可以先做。
            // 同一份查詢結果也用來說明「在等誰」，不必為此再查一次。
            Map<Long, List<TaskDto>> waiting = getUnfinishedPrerequisites(
                    candidates.stream().map(Task::getId).toList());
            Task candidate = candidates.stream()
                    .filter(t -> !waiting.containsKey(t.getId()))
                    .findFirst()
                    .orElse(null);
            if (candidate == null) {
                return ClaimNextTaskResult.allBlocked(project.get(), category.name(),
                        candidates.stream()
                                .map(t -> new BlockedCandidate(toDto(t),
                                        waiting.getOrDefault(t.getId(), List.of())))
                                .toList());
            }

            LocalDateTime claimedAt = LocalDateTime.now();
            // token 原文只存在這個區域變數與最終回傳值裡；claimIfTodo 只收 hash，
            // task_log 的 note 也只寫認領者名稱，原文不會被任何地方持久化。
            String claimToken = claimTokenService.generateToken();
            String claimTokenHash = claimTokenService.hash(claimToken);
            int updated = taskRepository.claimIfTodo(candidate.getId(), assignee, claimedAt, claimTokenHash);
            if (updated == 1) {
                Task claimed = taskRepository.findById(candidate.getId())
                        .orElseThrow(() -> new BoardException("認領後找不到任務：#" + candidate.getId()));
                taskLogRepository.save(new TaskLog(claimed.getId(), TaskStatus.TODO.name(),
                        TaskStatus.IN_PROGRESS.name(), "認領者：" + assignee));
                eventPublisher.publish(BoardEvent.taskStatusChanged(
                        claimed.getProjectId(), claimed.getId(), TaskStatus.TODO.name(),
                        TaskStatus.IN_PROGRESS.name(), claimed.getUpdatedAt().toString()));
                return ClaimNextTaskResult.claimed(project.get(), toDto(claimed), category.name(), claimToken);
            }
            contentionObserved = true;
        }

        // 三次 CAS 都輸掉後再讀一次，避免用過期候選推論目前狀態。即使競爭者已把
        // 唯一候選領走，也必須回 CONTENDED，讓 leader 重新盤點；不可把已觀察到的
        // 認領競爭誤報成「原本就沒有待辦」。若剩餘 TODO 全被前置卡住，則回傳更精確
        // 的 BLOCKED_BY_DEPENDENCY。
        List<Task> remainingCandidates = taskRepository
                .findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                        project.get().id(), category.name(), TaskStatus.TODO.name());
        if (!remainingCandidates.isEmpty()) {
            Map<Long, List<TaskDto>> waiting = getUnfinishedPrerequisites(
                    remainingCandidates.stream().map(Task::getId).toList());
            boolean allBlocked = remainingCandidates.stream()
                    .allMatch(task -> waiting.containsKey(task.getId()));
            if (allBlocked) {
                return ClaimNextTaskResult.allBlocked(project.get(), category.name(),
                        remainingCandidates.stream()
                                .map(task -> new BlockedCandidate(toDto(task),
                                        waiting.getOrDefault(task.getId(), List.of())))
                                .toList());
            }
        }
        return ClaimNextTaskResult.contended(project.get(), category.name());
    }

    /**
     * 一次查出多個任務各自還在等哪些尚未完成的前置任務。
     * 沒有未完成前置的任務不會出現在結果中，因此空 Map 代表全部可認領。
     */
    public Map<Long, List<TaskDto>> getUnfinishedPrerequisites(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TaskDto>> waiting = new LinkedHashMap<>();
        for (var row : taskDependencyRepository.findUnfinishedPrerequisites(taskIds)) {
            waiting.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>())
                    .add(toDto(row.getPrerequisite()));
        }
        return waiting;
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

    /** 被前置卡住的候選任務，以及它還在等哪些未完成的前置。 */
    public record BlockedCandidate(TaskDto task, List<TaskDto> waitingFor) {
    }

    public enum ClaimOutcome {
        CLAIMED,
        NO_TASK,
        BLOCKED_BY_DEPENDENCY,
        CONTENDED,
        PROJECT_NOT_FOUND
    }

    /**
     * @param claimToken 高熵 token 原文，只有認領成功那一刻回傳這一次；
     *                   DB 只存 hash，這個欄位絕不能被序列化進 log 或存進資料庫。
     */
    public record ClaimNextTaskResult(ClaimOutcome outcome,
                                      ProjectService.ProjectDto project, TaskDto task,
                                      List<ProjectService.ProjectDto> availableProjects,
                                      String category, List<BlockedCandidate> blockedCandidates,
                                      String claimToken) {
        public static ClaimNextTaskResult claimed(ProjectService.ProjectDto project, TaskDto task,
                                                   String category, String claimToken) {
            return new ClaimNextTaskResult(ClaimOutcome.CLAIMED,
                    project, task, List.of(), category, List.of(), claimToken);
        }

        public static ClaimNextTaskResult noTask(ProjectService.ProjectDto project, String category) {
            return new ClaimNextTaskResult(ClaimOutcome.NO_TASK,
                    project, null, List.of(), category, List.of(), null);
        }

        public static ClaimNextTaskResult contended(ProjectService.ProjectDto project, String category) {
            return new ClaimNextTaskResult(ClaimOutcome.CONTENDED,
                    project, null, List.of(), category, List.of(), null);
        }

        /** 該 category 還有 TODO，但全部都在等前置任務完成。 */
        public static ClaimNextTaskResult allBlocked(ProjectService.ProjectDto project, String category,
                                                      List<BlockedCandidate> blockedCandidates) {
            return new ClaimNextTaskResult(ClaimOutcome.BLOCKED_BY_DEPENDENCY,
                    project, null, List.of(), category, blockedCandidates, null);
        }

        public static ClaimNextTaskResult projectNotFound(
                List<ProjectService.ProjectDto> availableProjects, String category) {
            return new ClaimNextTaskResult(ClaimOutcome.PROJECT_NOT_FOUND,
                    null, null, availableProjects, category, List.of(), null);
        }

        public boolean projectFound() {
            return project != null;
        }

        public boolean claimed() {
            return outcome == ClaimOutcome.CLAIMED;
        }

        public boolean blockedByDependency() {
            return outcome == ClaimOutcome.BLOCKED_BY_DEPENDENCY;
        }

        public boolean contended() {
            return outcome == ClaimOutcome.CONTENDED;
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

package dev.aiboard.task;

import dev.aiboard.common.BoardException;
import dev.aiboard.common.TaskCategory;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Leader 任務規格編輯的業務邊界。
 *
 * <p>目前的「leader-only」是由 Claude/Codex plugin 的工具白名單限制工具可見性，
 * MCP server 本身沒有 caller identity，因此這不是 server-side authorization。服務應
 * 維持 localhost；若未來開放遠端存取，必須先新增真正的認證與授權。</p>
 */
@Service
public class TaskEditService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectService projectService;
    private final BoardEventPublisher eventPublisher;

    public TaskEditService(TaskRepository taskRepository,
                           TaskDependencyRepository dependencyRepository,
                           TaskLogRepository taskLogRepository,
                           ProjectService projectService,
                           BoardEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TaskEditResult updateTaskDetails(Long taskId, String title, String description,
                                            String category, Long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (title == null && description == null && category == null) {
            throw new BoardException("至少提供 title、description 或 category 其中一項");
        }

        Task task = getEditableTask(taskId, expectedVersion);
        TaskStatus status = parseStatus(task);
        if (status != TaskStatus.TODO && status != TaskStatus.BLOCKED) {
            throw new BoardException("任務 #%d 目前是 %s，只有 TODO/BLOCKED 可修改 title 或 description"
                    .formatted(taskId, status));
        }
        if (category != null && status != TaskStatus.TODO) {
            throw new BoardException("任務 #%d 目前是 %s，只有 TODO 可修改 category"
                    .formatted(taskId, status));
        }

        String normalizedTitle = title == null ? null : validateTitle(title);
        String normalizedCategory = category == null ? null : parseCategory(category).name();
        List<ValueChange> changes = new ArrayList<>();
        addChange(changes, "title", task.getTitle(), normalizedTitle);
        addChange(changes, "description", task.getDescription(), description);
        addChange(changes, "category", task.getCategory(), normalizedCategory);
        if (changes.isEmpty()) {
            return new TaskEditResult(task.getId(), task.getVersion(), false, List.of());
        }

        task.updateDetails(normalizedTitle, description, normalizedCategory);
        taskLogRepository.save(new TaskLog(taskId, status.name(), status.name(), auditNote(
                "update_task_details", changes)));
        Task saved = taskRepository.saveAndFlush(task);
        eventPublisher.publish(BoardEvent.taskUpdated(task.getProjectId(), taskId));
        return new TaskEditResult(saved.getId(), saved.getVersion(), true, List.copyOf(changes));
    }

    @Transactional
    public TaskDependencyEditResult setTaskDependencies(Long taskId,
                                                         List<Long> prerequisiteTaskIds,
                                                         Long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        Task task = getEditableTask(taskId, expectedVersion);
        TaskStatus status = parseStatus(task);
        if (status != TaskStatus.TODO) {
            throw new BoardException("任務 #%d 目前是 %s，只有 TODO 可修改前置相依"
                    .formatted(taskId, status));
        }

        List<Long> requested = prerequisiteTaskIds == null ? List.of() : prerequisiteTaskIds;
        if (requested.stream().anyMatch(Objects::isNull)) {
            throw new BoardException("前置任務 ID 不可為 null");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) {
            throw new BoardException("前置任務 ID 不可重複");
        }
        if (unique.contains(taskId)) {
            throw new BoardException("任務 #%d 不可依賴自己".formatted(taskId));
        }

        Map<Long, Task> prerequisites = new HashMap<>();
        if (!unique.isEmpty()) {
            taskRepository.findAllById(unique).forEach(item -> prerequisites.put(item.getId(), item));
        }
        for (Long prerequisiteId : unique) {
            Task prerequisite = prerequisites.get(prerequisiteId);
            if (prerequisite == null) {
                throw new BoardException("前置任務 #%d 不存在".formatted(prerequisiteId));
            }
            if (!prerequisite.getProjectId().equals(task.getProjectId())) {
                throw new BoardException("前置任務 #%d 屬於其他專案".formatted(prerequisiteId));
            }
        }
        assertNoCycle(task, unique);

        List<Long> oldIds = dependencyRepository.findByTaskIdOrderByDependsOnTaskIdAsc(taskId)
                .stream().map(TaskDependency::getDependsOnTaskId).toList();
        List<Long> newIds = unique.stream().sorted().toList();
        if (oldIds.equals(newIds)) {
            return new TaskDependencyEditResult(taskId, task.getVersion(), false, oldIds, newIds);
        }

        dependencyRepository.deleteByTaskId(taskId);
        dependencyRepository.flush();
        dependencyRepository.saveAll(newIds.stream()
                .map(prerequisiteId -> new TaskDependency(taskId, prerequisiteId)).toList());
        task.touch();
        List<ValueChange> changes = List.of(new ValueChange("prerequisiteTaskIds", oldIds, newIds));
        taskLogRepository.save(new TaskLog(taskId, status.name(), status.name(), auditNote(
                "set_task_dependencies", changes)));
        Task saved = taskRepository.saveAndFlush(task);
        eventPublisher.publish(BoardEvent.taskUpdated(task.getProjectId(), taskId));
        return new TaskDependencyEditResult(saved.getId(), saved.getVersion(), true, oldIds, newIds);
    }

    private Task getEditableTask(Long taskId, Long expectedVersion) {
        if (taskId == null) {
            throw new BoardException("taskId 不可為 null");
        }
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));
        projectService.assertActiveForUpdate(task.getProjectId());
        ProjectService.ProjectDto project = projectService.getById(task.getProjectId());
        if ("ARCHIVED".equalsIgnoreCase(project.status())) {
            throw new BoardException("專案「%s」已封存，禁止編輯任務".formatted(project.name()));
        }
        if (!expectedVersion.equals(task.getVersion())) {
            throw new BoardException("任務 #%d 已被其他操作更新（目前版本 %d，預期版本 %d），請重新讀取後再操作"
                    .formatted(taskId, task.getVersion(), expectedVersion));
        }
        return task;
    }

    private void assertNoCycle(Task task, Set<Long> proposedPrerequisites) {
        List<Long> projectTaskIds = taskRepository.findByProjectIdOrderBySortOrderAsc(task.getProjectId())
                .stream().map(Task::getId).toList();
        Map<Long, List<Long>> graph = new HashMap<>();
        for (TaskDependency dependency : dependencyRepository.findByTaskIdIn(projectTaskIds)) {
            if (!dependency.getTaskId().equals(task.getId())) {
                graph.computeIfAbsent(dependency.getTaskId(), ignored -> new ArrayList<>())
                        .add(dependency.getDependsOnTaskId());
            }
        }
        graph.put(task.getId(), List.copyOf(proposedPrerequisites));

        for (Long prerequisiteId : proposedPrerequisites) {
            ArrayDeque<Long> pending = new ArrayDeque<>();
            Set<Long> visited = new HashSet<>();
            pending.add(prerequisiteId);
            while (!pending.isEmpty()) {
                Long current = pending.removeFirst();
                if (current.equals(task.getId())) {
                    throw new BoardException("設定前置任務會形成循環相依：#%d 可回到 #%d"
                            .formatted(prerequisiteId, task.getId()));
                }
                if (visited.add(current)) {
                    pending.addAll(graph.getOrDefault(current, List.of()));
                }
            }
        }
    }

    private static void requireExpectedVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new BoardException("expectedVersion 必須是非負整數；請先重新讀取任務版本");
        }
    }

    private static String validateTitle(String title) {
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            throw new BoardException("任務標題不可為空白");
        }
        if (trimmed.length() > 300) {
            throw new BoardException("任務標題不可超過 300 字");
        }
        return trimmed;
    }

    private static TaskCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new BoardException("category 不可為空白");
        }
        try {
            return TaskCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BoardException("不支援的 category：" + category);
        }
    }

    private static TaskStatus parseStatus(Task task) {
        try {
            return task.getStatus();
        } catch (IllegalArgumentException e) {
            throw new BoardException("任務 #%d 的狀態無效：%s".formatted(task.getId(), task.getStatus()));
        }
    }

    private static void addChange(List<ValueChange> changes, String field, Object oldValue,
                                  Object requestedValue) {
        if (requestedValue != null && !Objects.equals(oldValue, requestedValue)) {
            changes.add(new ValueChange(field, oldValue, requestedValue));
        }
    }

    private static String auditNote(String operation, List<ValueChange> changes) {
        StringBuilder note = new StringBuilder("leader-only ").append(operation);
        for (ValueChange change : changes) {
            note.append("\n").append(change.field()).append(": old=")
                    .append(quote(change.oldValue())).append(", new=")
                    .append(quote(change.newValue()));
        }
        return note.toString();
    }

    private static String quote(Object value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.toString().replace("\\", "\\\\")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\"", "\\\"") + "\"";
    }

    public record ValueChange(String field, Object oldValue, Object newValue) {
    }

    public record TaskEditResult(Long taskId, Long version, boolean changed,
                                 List<ValueChange> changes) {
    }

    public record TaskDependencyEditResult(Long taskId, Long version, boolean changed,
                                           List<Long> oldPrerequisiteTaskIds,
                                           List<Long> newPrerequisiteTaskIds) {
    }
}

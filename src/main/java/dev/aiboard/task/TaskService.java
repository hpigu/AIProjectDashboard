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

@Service
public class TaskService {

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
        for (TaskInput input : inputs) {
            if (input.title() == null || input.title().isBlank()) {
                throw new IllegalArgumentException("任務標題不可為空白");
            }
        }
        projectService.assertExists(projectId);

        int startSortOrder = taskRepository.findMaxSortOrder(projectId) + 1;

        List<TaskDto> results = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            TaskInput input = inputs.get(i);
            TaskCategory category = TaskCategory.fromStringOrOther(input.category());
            Task task = new Task(projectId, input.title(), input.description(),
                    category == null ? null : category.name(), startSortOrder + i);
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
                task.getStatus(), task.getCategory(), task.getSortOrder());
    }

    public record TaskInput(String title, String description, String category) {
    }

    public record TaskDto(Long id, Long projectId, String title, String description, String status,
                           String category, Integer sortOrder) {
    }

    public record TaskLogDto(Long id, Long taskId, String fromStatus, String toStatus, String note,
                              java.time.LocalDateTime createdAt) {
    }

    public record TaskStatusChangeResult(TaskDto task, TaskStatus from, TaskStatus to, boolean changed,
                                          ProjectService.ProjectDto project, long doneCount, long totalCount) {
    }
}

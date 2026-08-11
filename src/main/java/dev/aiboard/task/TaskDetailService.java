package dev.aiboard.task;

import dev.aiboard.common.BoardException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 唯讀任務詳情查詢。所有集合各以一次 projection/JOIN 查詢載入，查詢次數不會隨
 * 相依、歷史、blocker dependency 或 verification 的筆數增加。
 */
@Service
public class TaskDetailService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskBlockEventRepository taskBlockEventRepository;
    private final TaskCompletionEvidenceRepository completionEvidenceRepository;

    public TaskDetailService(TaskRepository taskRepository,
                             TaskDependencyRepository taskDependencyRepository,
                             TaskLogRepository taskLogRepository,
                             TaskBlockEventRepository taskBlockEventRepository,
                             TaskCompletionEvidenceRepository completionEvidenceRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskBlockEventRepository = taskBlockEventRepository;
        this.completionEvidenceRepository = completionEvidenceRepository;
    }

    @Transactional(readOnly = true)
    public TaskDetail getTaskDetail(Long projectId, Long taskId) {
        TaskRepository.TaskDetailProjection task = taskRepository
                .findDetailByProjectIdAndId(projectId, taskId)
                .orElseThrow(() -> new BoardException(
                        "找不到專案 #%d 中的任務 #%d".formatted(projectId, taskId)));

        List<RelatedTask> prerequisites = taskDependencyRepository.findPrerequisites(taskId).stream()
                .map(this::toRelatedTask)
                .toList();
        List<RelatedTask> downstreamTasks = taskDependencyRepository.findDownstreamTasks(taskId).stream()
                .map(this::toRelatedTask)
                .toList();
        List<HistoryEntry> history = taskLogRepository.findHistoryProjection(taskId).stream()
                .map(row -> new HistoryEntry(row.getId(), row.getFromStatus(), row.getToStatus(),
                        row.getNote(), row.getCreatedAt()))
                .toList();

        CurrentBlocker currentBlocker = readCurrentBlocker(task.getCurrentBlockEventId());
        CompletionEvidence completionEvidence = readCurrentEvidence(taskId);

        return new TaskDetail(task.getId(), task.getProjectId(), task.getTitle(), task.getDescription(),
                task.getStatus().name(), task.getCategory(), task.getSortOrder(), task.getAssignee(),
                task.getClaimedAt(), task.getCreatedAt(), task.getUpdatedAt(), task.getVersion(),
                prerequisites, downstreamTasks, history, currentBlocker, completionEvidence);
    }

    private RelatedTask toRelatedTask(TaskDependencyRepository.RelatedTaskProjection row) {
        return new RelatedTask(row.getId(), row.getTitle(), row.getStatus().name(), row.getCategory(),
                row.getAssignee());
    }

    private CurrentBlocker readCurrentBlocker(Long eventId) {
        // 即使沒有 blocker 仍執行同一條固定查詢；NULL 不會命中任何事件。
        List<TaskBlockEventRepository.CurrentBlockerProjection> rows =
                taskBlockEventRepository.findCurrentBlocker(eventId);
        if (rows.isEmpty()) {
            return null;
        }
        var first = rows.getFirst();
        List<BlockingTask> blockingTasks = new ArrayList<>();
        for (var row : rows) {
            if (row.getBlockingTaskId() != null) {
                blockingTasks.add(new BlockingTask(row.getBlockingTaskId(), row.getBlockingTaskTitle(),
                        row.getBlockingTaskStatus()));
            }
        }
        return new CurrentBlocker(first.getEventId(), first.getReasonType(), first.getDetail(),
                first.getBlockedBy(), first.getBlockedAt(), first.getClearedAt(), blockingTasks);
    }

    private CompletionEvidence readCurrentEvidence(Long taskId) {
        List<TaskCompletionEvidenceRepository.CurrentEvidenceProjection> rows =
                completionEvidenceRepository.findCurrentEvidence(taskId);
        if (rows.isEmpty()) {
            return null;
        }
        var first = rows.getFirst();
        List<Verification> verifications = new ArrayList<>();
        for (var row : rows) {
            if (row.getVerificationId() != null) {
                verifications.add(new Verification(row.getVerificationId(), row.getVerificationName(),
                        row.getVerificationResult(), row.getVerificationDetail()));
            }
        }
        return new CompletionEvidence(first.getEvidenceId(), first.getSummary(), first.getChangedFiles(),
                first.getCommitRef(), first.getCompletedBy(), first.getCompletedAt(), verifications);
    }

    public record TaskDetail(Long id, Long projectId, String title, String description, String status,
                             String category, Integer sortOrder, String assignee,
                             LocalDateTime claimedAt, LocalDateTime createdAt, LocalDateTime updatedAt,
                             Long version, List<RelatedTask> prerequisites,
                             List<RelatedTask> downstreamTasks, List<HistoryEntry> history,
                             CurrentBlocker currentBlocker, CompletionEvidence completionEvidence) {
    }

    public record RelatedTask(Long id, String title, String status, String category, String assignee) {
    }

    public record HistoryEntry(Long id, String fromStatus, String toStatus, String note,
                               LocalDateTime createdAt) {
    }

    public record CurrentBlocker(Long id, String reasonType, String detail, String blockedBy,
                                 LocalDateTime blockedAt, LocalDateTime clearedAt,
                                 List<BlockingTask> blockingTasks) {
    }

    public record BlockingTask(Long id, String title, String status) {
    }

    public record CompletionEvidence(Long id, String summary, String changedFiles, String commitRef,
                                     String completedBy, LocalDateTime completedAt,
                                     List<Verification> verifications) {
    }

    public record Verification(Long id, String name, String result, String detail) {
    }
}

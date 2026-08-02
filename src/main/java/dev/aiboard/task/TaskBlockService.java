package dev.aiboard.task;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * #113 結構化 BLOCKED 流程。獨立於 TaskService，避免更動既有建構子簽名影響
 * 其他角色已經在寫的測試；只呼叫既有 Repository／Service，不重複 update_task_status
 * 既有的通用轉移邏輯（token 驗證、TODO/IN_PROGRESS 守衛等）本身不變。
 */
@Service
public class TaskBlockService {

    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskBlockEventRepository taskBlockEventRepository;
    private final TaskBlockDependencyRepository taskBlockDependencyRepository;
    private final ProjectService projectService;
    private final BoardEventPublisher eventPublisher;
    private final ClaimTokenService claimTokenService;

    public TaskBlockService(TaskRepository taskRepository, TaskLogRepository taskLogRepository,
                             TaskBlockEventRepository taskBlockEventRepository,
                             TaskBlockDependencyRepository taskBlockDependencyRepository,
                             ProjectService projectService, BoardEventPublisher eventPublisher,
                             ClaimTokenService claimTokenService) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskBlockEventRepository = taskBlockEventRepository;
        this.taskBlockDependencyRepository = taskBlockDependencyRepository;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
        this.claimTokenService = claimTokenService;
    }

    /**
     * 把任務標記為 BLOCKED，同時寫入一筆結構化的 task_block_event 快照。
     * 只有持有 claim token 的 assignee（或任務本身沒有 token hash 的舊資料）
     * 才能操作自己的任務；不得 block 別人的任務，也不得動到已封存專案的任務。
     */
    @Transactional
    public BlockResult blockTask(Long taskId, String claimToken, String reasonTypeRaw, String detail,
                                  List<Long> blockingTaskIds, Long expectedVersion) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));

        projectService.assertActiveForUpdate(task.getProjectId());
        ProjectService.ProjectDto project = projectService.getById(task.getProjectId());
        if (!"ACTIVE".equals(project.status())) {
            throw new BoardException("專案「%s」已封存，無法變更任務 #%d 的狀態"
                    .formatted(project.name(), taskId));
        }

        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (!current.canTransitionTo(TaskStatus.BLOCKED)) {
            throw new BoardException(
                    "不合法的狀態轉移：#%d 目前是 %s，無法轉移至 BLOCKED".formatted(taskId, current));
        }
        if (task.getAssignee() == null) {
            throw new BoardException("未認領的任務不能標記為 BLOCKED，請先 claim_next_task");
        }

        // 只有持有 token 的 assignee 能 block 自己的任務；沒有 token hash 的舊資料
        // 沿用 #112 的相容策略，不強制要求。
        if (task.getClaimTokenHash() != null) {
            if (claimToken == null || claimToken.isBlank()) {
                throw new BoardException("任務 #" + taskId
                        + " 需要認領時取得的 claim token 才能操作，請提供 claimToken");
            }
            if (!claimTokenService.matches(claimToken, task.getClaimTokenHash())) {
                throw new BoardException("任務 #" + taskId + " 的 claim token 不正確，無法操作");
            }
        }

        if (expectedVersion != null && !expectedVersion.equals(task.getVersion())) {
            throw new BoardException("任務 #" + taskId
                    + " 已被其他操作更新（目前版本 " + task.getVersion()
                    + "，預期版本 " + expectedVersion + "），請重新讀取後再操作");
        }

        BlockReasonType reasonType = parseReasonType(reasonTypeRaw);
        if (detail == null || detail.isBlank()) {
            throw new BoardException("block_task 必須填寫 detail，說明目前實際在等什麼");
        }
        String trimmedDetail = detail.trim();

        Set<Long> normalizedBlockingIds = normalizeBlockingIds(blockingTaskIds);
        if (reasonType == BlockReasonType.DEPENDENCY && normalizedBlockingIds.isEmpty()) {
            throw new BoardException(
                    "reasonType=DEPENDENCY 時必須至少指定一個 blockingTaskIds（在等哪個任務）");
        }
        List<Task> blockingTasks = new ArrayList<>();
        for (Long blockingId : normalizedBlockingIds) {
            if (blockingId.equals(taskId)) {
                throw new BoardException("任務 #" + taskId + " 不能把自己列為 blockingTaskIds");
            }
            Task blocking = taskRepository.findById(blockingId)
                    .orElseThrow(() -> new BoardException(
                            "blockingTaskIds 中的任務 #" + blockingId + " 不存在"));
            if (!blocking.getProjectId().equals(task.getProjectId())) {
                throw new BoardException(
                        "blockingTaskIds 中的任務 #" + blockingId + " 屬於其他專案，必須是同專案任務");
            }
            blockingTasks.add(blocking);
        }

        // 防禦性清除：正常流程下 BLOCKED -> 其他狀態時 currentBlockEventId 已在
        // changeStatus 清空，這裡不會有殘留；萬一有（例如资料修復後的異常狀態），
        // 也不讓舊事件永遠停留在「未 clear」，避免 audit 上出現兩筆同時開啟的事件。
        taskBlockEventRepository.clearAllOpenForTask(taskId, LocalDateTime.now());

        TaskBlockEvent event = new TaskBlockEvent(taskId, reasonType.name(), trimmedDetail, task.getAssignee());
        TaskBlockEvent savedEvent = taskBlockEventRepository.save(event);
        for (Task blocking : blockingTasks) {
            taskBlockDependencyRepository.save(new TaskBlockDependency(savedEvent.getId(), blocking.getId()));
        }

        task.changeStatus(TaskStatus.BLOCKED);
        task.setCurrentBlockEventId(savedEvent.getId());

        String logNote = "BLOCKED（%s）：%s".formatted(reasonType, trimmedDetail);
        taskLogRepository.save(new TaskLog(taskId, current.name(), TaskStatus.BLOCKED.name(), logNote));
        eventPublisher.publish(BoardEvent.taskStatusChanged(
                task.getProjectId(), taskId, current.name(), TaskStatus.BLOCKED.name(),
                task.getUpdatedAt().toString()));

        return new BlockResult(toDto(task), reasonType, trimmedDetail,
                blockingTasks.stream().map(this::toSummary).toList());
    }

    /**
     * 任務離開 BLOCKED（例如透過既有 update_task_status 轉回 IN_PROGRESS/TODO）時
     * 呼叫，把該任務尚未 clear 的 block 事件補上 clearedAt。Task 上的
     * currentBlockEventId 已經在 changeStatus 當下於記憶體與 DB 清空，這裡只補
     * audit 用的 clearedAt 時間戳，不影響「目前 blocker 已清除」這件事本身
     * （那件事由 currentBlockEventId IS NULL 決定，與這裡是否成功寫入無關）。
     */
    @Transactional
    public void clearOpenBlockEvents(Long taskId) {
        taskBlockEventRepository.clearAllOpenForTask(taskId, LocalDateTime.now());
    }

    /**
     * TaskService、TaskCompleteService 與 leader reset 都會在各自的狀態變更 transaction
     * 中同步發布同一種 BoardEvent。直接監聽這個既有事件，可在不把 block repository
     * 塞進 TaskService 建構子的前提下，讓所有「離開 BLOCKED」路徑一致補齊 audit
     * timestamp。MANDATORY 保證回填和狀態變更共用同一個 transaction；任一方失敗
     * 就一起 rollback，不會留下 status 與 audit trail 不一致的資料。
     */
    @EventListener(condition = "#event.type == 'task.status_changed'")
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearOpenBlockEventsAfterStatusChange(BoardEvent event) {
        if (!TaskStatus.BLOCKED.name().equals(event.payload().get("from"))
                || TaskStatus.BLOCKED.name().equals(event.payload().get("to"))) {
            return;
        }

        Object rawTaskId = event.payload().get("taskId");
        if (!(rawTaskId instanceof Number taskId)) {
            throw new IllegalStateException("task.status_changed 事件缺少有效 taskId");
        }
        clearOpenBlockEvents(taskId.longValue());
    }

    private BlockReasonType parseReasonType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("reasonType 不可為空白");
        }
        try {
            return BlockReasonType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不合法的 reasonType：" + raw
                    + "，限定 DEPENDENCY/USER_INPUT/TECHNICAL/ENVIRONMENT/EXTERNAL/OTHER");
        }
    }

    private Set<Long> normalizeBlockingIds(List<Long> blockingTaskIds) {
        if (blockingTaskIds == null) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : blockingTaskIds) {
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private TaskService.TaskDto toDto(Task task) {
        return new TaskService.TaskDto(task.getId(), task.getProjectId(), task.getTitle(),
                task.getDescription(), task.getStatus(), task.getCategory(), task.getSortOrder(),
                task.getAssignee(), task.getClaimedAt());
    }

    private TaskSummary toSummary(Task task) {
        return new TaskSummary(task.getId(), task.getTitle(), task.getStatus());
    }

    public record TaskSummary(Long id, String title, String status) {
    }

    public record BlockResult(TaskService.TaskDto task, BlockReasonType reasonType, String detail,
                               List<TaskSummary> blockingTasks) {
    }
}

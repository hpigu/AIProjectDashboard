package dev.aiboard.task;

import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.project.ProjectRepository;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #136 對結構化 block_task 的完整資料庫回歸。除了輸入契約，也驗證所有離開
 * BLOCKED 的路徑會和狀態變更在同一個 transaction 清除 current blocker、回填
 * cleared_at，且 append-only audit 不會被刪除。
 *
 * <p>#137 已放行 complete_task 從 BLOCKED 直接轉 DONE（只在 TaskCompleteService
 * 內特別處理，不放寬共用的 TaskStatus.canTransitionTo 狀態機），本測試沿用該
 * 行為驗證 completeFromBlocked 路徑一樣會清除 current blocker、保留歷史 audit。
 *
 * <p>本測試建立的任務一律透過 {@link TaskService#createTasks} 產生，依 #131
 * 方案 B1 一律 requireEvidence=true；因此涉及轉為 DONE 的路徑一律使用
 * complete_task（附完成證據），不使用 update_task_status 直接轉 DONE。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-block-integration-136;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-task-136-block-test.log"
})
@RecordApplicationEvents
class TaskBlockServiceIntegrationTest {

    private static final List<String> ALLOWED_REASONS = List.of(
            "DEPENDENCY", "USER_INPUT", "TECHNICAL", "ENVIRONMENT", "EXTERNAL", "OTHER");

    @Autowired private ProjectService projectService;
    @Autowired private TaskService taskService;
    @Autowired private TaskBlockService taskBlockService;
    @Autowired private TaskCompleteService taskCompleteService;
    @Autowired private TaskDetailService taskDetailService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskLogRepository taskLogRepository;
    @Autowired private TaskBlockEventRepository blockEventRepository;
    @Autowired private TaskBlockDependencyRepository blockDependencyRepository;
    @Autowired private TaskCompletionEvidenceRepository evidenceRepository;
    @Autowired private TaskCompletionVerificationRepository verificationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEvents publishedEvents;

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void cleanDatabase() {
        // task.current_block_event_id 與 task_block_event.task_id 形成刻意保留歷史用的
        // 雙向 FK；測試清庫時先斷開「目前事件」指標，正式流程不會刪除 audit。
        jdbcTemplate.update("UPDATE task SET current_block_event_id = NULL");
        verificationRepository.deleteAll();
        evidenceRepository.deleteAll();
        blockDependencyRepository.deleteAll();
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        blockEventRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void blockTask_requiresNonBlankDetail() {
        Claimed claimed = createClaimedTask();

        assertThatThrownBy(() -> taskBlockService.blockTask(
                claimed.taskId(), claimed.token(), "TECHNICAL", "  ", null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("detail");

        assertUnchangedInProgress(claimed.taskId());
    }

    @Test
    void blockTask_acceptsOnlyDocumentedReasonTypes() {
        for (String reason : ALLOWED_REASONS) {
            Claimed claimed = createClaimedTask();
            List<Long> blockers = reason.equals("DEPENDENCY")
                    ? List.of(createTodoTaskInProject(claimed.projectId())) : null;

            var result = taskBlockService.blockTask(
                    claimed.taskId(), claimed.token(), reason.toLowerCase(), "等待原因", blockers, null);

            assertThat(result.reasonType().name()).isEqualTo(reason);
            assertThat(taskRepository.findById(claimed.taskId()).orElseThrow().getStatus())
                    .isEqualTo("BLOCKED");
        }

        Claimed invalid = createClaimedTask();
        assertThatThrownBy(() -> taskBlockService.blockTask(
                invalid.taskId(), invalid.token(), "SECURITY", "未知分類", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不合法的 reasonType")
                .hasMessageContaining("DEPENDENCY/USER_INPUT/TECHNICAL/ENVIRONMENT/EXTERNAL/OTHER");
        assertUnchangedInProgress(invalid.taskId());
    }

    @Test
    void dependencyReason_requiresAtLeastOneBlockingTask() {
        Claimed claimed = createClaimedTask();

        assertThatThrownBy(() -> taskBlockService.blockTask(
                claimed.taskId(), claimed.token(), "DEPENDENCY", "等待前置", List.of(), null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("blockingTaskIds");

        assertUnchangedInProgress(claimed.taskId());
        assertThat(blockEventRepository.findByTaskIdOrderByIdAsc(claimed.taskId())).isEmpty();
    }

    /**
     * BLOCKED 是唯一一定需要人介入的狀態，前端的桌面通知就掛在這個事件上。
     * payload 少了 title，通知內容只會是「任務 #42 → BLOCKED」——讀了還是得回看板
     * 查是哪一個任務，通知本身就沒有意義了。因此把它當成對外契約來測，而不是
     * 只確認事件有發出去。
     */
    @Test
    void blockTask_publishesEventCarryingTaskTitleForDesktopNotifications() {
        Claimed claimed = createClaimedTask();

        taskBlockService.blockTask(
                claimed.taskId(), claimed.token(), "TECHNICAL", "等待上游修掉編碼問題", null, null);

        BoardEvent blockedEvent = publishedEvents.stream(BoardEvent.class)
                .filter(event -> "task.status_changed".equals(event.type()))
                .filter(event -> "BLOCKED".equals(event.payload().get("to")))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("沒有發出轉為 BLOCKED 的 task.status_changed 事件"));

        assertThat(blockedEvent.payload())
                .containsEntry("projectId", claimed.projectId())
                .containsEntry("taskId", claimed.taskId())
                .containsEntry("title", "受測任務")
                .containsEntry("from", "IN_PROGRESS");
    }

    @Test
    void claimToken_isRequiredAndCannotBeReusedAcrossTasks() {
        Claimed owner = createClaimedTask();
        Claimed victim = createClaimedTask();

        assertThatThrownBy(() -> taskBlockService.blockTask(
                victim.taskId(), null, "TECHNICAL", "缺少 token", null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("claimToken");
        assertThatThrownBy(() -> taskBlockService.blockTask(
                victim.taskId(), "偽造-token", "TECHNICAL", "錯誤 token", null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("token 不正確");
        assertThatThrownBy(() -> taskBlockService.blockTask(
                victim.taskId(), owner.token(), "TECHNICAL", "跨任務盜用", null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("token 不正確");

        assertUnchangedInProgress(victim.taskId());
        assertThat(blockEventRepository.findByTaskIdOrderByIdAsc(victim.taskId())).isEmpty();
    }

    @Test
    void blockTask_rejectsArchivedProjectWithoutWritingAudit() {
        Claimed claimed = createClaimedTask();
        jdbcTemplate.update("UPDATE project SET status = 'ARCHIVED' WHERE id = ?", claimed.projectId());

        assertThatThrownBy(() -> taskBlockService.blockTask(
                claimed.taskId(), claimed.token(), "OTHER", "不應寫入", null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("已封存");

        assertUnchangedInProgress(claimed.taskId());
        assertThat(blockEventRepository.findByTaskIdOrderByIdAsc(claimed.taskId())).isEmpty();
    }

    @Test
    void blockTask_rejectsStaleExpectedVersionWithoutPartialWrites() {
        Claimed claimed = createClaimedTask();
        long actualVersion = taskRepository.findById(claimed.taskId()).orElseThrow().getVersion();

        assertThatThrownBy(() -> taskBlockService.blockTask(
                claimed.taskId(), claimed.token(), "OTHER", "版本已過期", null, actualVersion + 1))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("預期版本");

        assertUnchangedInProgress(claimed.taskId());
        assertThat(blockEventRepository.findByTaskIdOrderByIdAsc(claimed.taskId())).isEmpty();
    }

    @Test
    void resume_clearsCurrentBlockerAndRetainsHistoricalAudit() {
        Blocked blocked = createBlockedTask("USER_INPUT", null);

        taskService.updateStatus(blocked.taskId(), "IN_PROGRESS", "使用者已回答", blocked.token());

        assertCleared(blocked);
        var detail = taskDetailService.getTaskDetail(blocked.projectId(), blocked.taskId());
        assertThat(detail.currentBlocker()).isNull();
        assertThat(detail.history()).extracting(TaskDetailService.HistoryEntry::toStatus)
                .contains("BLOCKED", "IN_PROGRESS");
    }

    @Test
    void leaderReset_clearsCurrentBlockerAndRetainsHistoricalAudit() {
        Blocked blocked = createBlockedTask("TECHNICAL", null);

        taskService.resetClaimAsLeader(blocked.taskId(), "agent 已離線");

        Task reset = taskRepository.findById(blocked.taskId()).orElseThrow();
        assertThat(reset.getStatus()).isEqualTo("TODO");
        assertThat(reset.getAssignee()).isNull();
        assertCleared(blocked);
        assertThat(taskDetailService.getTaskDetail(blocked.projectId(), blocked.taskId()).currentBlocker())
                .isNull();
    }

    @Test
    void completeFromBlocked_clearsCurrentBlockerAndRetainsHistoricalAudit() {
        Blocked blocked = createBlockedTask("TECHNICAL", null);

        taskCompleteService.completeTask(blocked.taskId(), blocked.token(), "阻礙已排除並完成",
                List.of(new TaskCompleteService.VerificationInput("回歸測試", "PASSED", null)),
                "src/main/Foo.java", "abc123", null);

        assertThat(taskRepository.findById(blocked.taskId()).orElseThrow().getStatus()).isEqualTo("DONE");
        assertCleared(blocked);
        assertThat(taskDetailService.getTaskDetail(blocked.projectId(), blocked.taskId()).currentBlocker())
                .isNull();
    }

    @Test
    void repeatedBlocks_keepPriorAuditAndExposeOnlyCurrentBlocker() {
        Blocked first = createBlockedTask("USER_INPUT", null);
        taskService.updateStatus(first.taskId(), "IN_PROGRESS", "第一次解除", first.token());

        Long blockerId = createTodoTaskInProject(first.projectId());
        var second = taskBlockService.blockTask(first.taskId(), first.token(), "DEPENDENCY",
                "改等前置任務", List.of(blockerId), null);

        List<TaskBlockEvent> history = blockEventRepository.findByTaskIdOrderByIdAsc(first.taskId());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().getId()).isEqualTo(first.eventId());
        assertThat(history.getFirst().getClearedAt()).isNotNull();
        assertThat(history.getLast().getClearedAt()).isNull();
        assertThat(second.blockingTasks()).extracting(TaskBlockService.TaskSummary::id)
                .containsExactly(blockerId);

        var current = taskDetailService.getTaskDetail(first.projectId(), first.taskId()).currentBlocker();
        assertThat(current).isNotNull();
        assertThat(current.id()).isEqualTo(history.getLast().getId());
        assertThat(current.detail()).isEqualTo("改等前置任務");
        assertThat(current.blockingTasks()).extracting(TaskDetailService.BlockingTask::id)
                .containsExactly(blockerId);
    }

    @Test
    void rollback_doesNotLeaveStatusAndClearedAtInconsistent() {
        Blocked blocked = createBlockedTask("EXTERNAL", null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            taskService.updateStatus(blocked.taskId(), "IN_PROGRESS", "稍後 rollback", blocked.token());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

        Task persisted = taskRepository.findById(blocked.taskId()).orElseThrow();
        TaskBlockEvent event = blockEventRepository.findById(blocked.eventId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("BLOCKED");
        assertThat(persisted.getCurrentBlockEventId()).isEqualTo(blocked.eventId());
        assertThat(event.getClearedAt()).isNull();
        assertThat(taskDetailService.getTaskDetail(blocked.projectId(), blocked.taskId()).currentBlocker())
                .isNotNull();
    }

    private Claimed createClaimedTask() {
        String projectName = "block-task-test-" + sequence.incrementAndGet();
        var project = projectService.createProject(projectName, null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("受測任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask(projectName, "BACKEND", "backend-dev");
        assertThat(claimed.claimed()).isTrue();
        return new Claimed(project.id(), claimed.task().id(), claimed.claimToken());
    }

    private Long createTodoTaskInProject(Long projectId) {
        taskService.createTasks(projectId, List.of(
                new TaskService.TaskInput("blocking-task-" + sequence.incrementAndGet(), null, "INFRA")));
        return taskRepository.findAll().stream()
                .filter(task -> task.getProjectId().equals(projectId) && task.getCategory().equals("INFRA"))
                .map(Task::getId)
                .max(Long::compareTo)
                .orElseThrow();
    }

    private Blocked createBlockedTask(String reasonType, List<Long> blockingTaskIds) {
        Claimed claimed = createClaimedTask();
        var result = taskBlockService.blockTask(claimed.taskId(), claimed.token(), reasonType,
                "原始阻礙說明", blockingTaskIds, null);
        Task persisted = taskRepository.findById(claimed.taskId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("BLOCKED");
        assertThat(persisted.getCurrentBlockEventId()).isNotNull();
        return new Blocked(claimed.projectId(), claimed.taskId(), claimed.token(),
                persisted.getCurrentBlockEventId(), result.detail());
    }

    private void assertUnchangedInProgress(Long taskId) {
        Task task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(task.getCurrentBlockEventId()).isNull();
    }

    private void assertCleared(Blocked blocked) {
        Task task = taskRepository.findById(blocked.taskId()).orElseThrow();
        TaskBlockEvent event = blockEventRepository.findById(blocked.eventId()).orElseThrow();
        assertThat(task.getCurrentBlockEventId()).isNull();
        assertThat(event.getClearedAt()).isNotNull();
        assertThat(event.getDetail()).isEqualTo(blocked.detail());
        assertThat(event.getReasonType()).isNotBlank();
    }

    private record Claimed(Long projectId, Long taskId, String token) {}
    private record Blocked(Long projectId, Long taskId, String token, Long eventId, String detail) {}
}

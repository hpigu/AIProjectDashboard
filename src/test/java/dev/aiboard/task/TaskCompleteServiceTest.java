package dev.aiboard.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCompleteServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskLogRepository taskLogRepository;

    @Mock
    private TaskCompletionEvidenceRepository evidenceRepository;

    @Mock
    private TaskCompletionVerificationRepository verificationRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private BoardEventPublisher eventPublisher;

    private final ClaimTokenService claimTokenService = new ClaimTokenService();

    private TaskCompleteService service;

    @BeforeEach
    void setUp() {
        service = new TaskCompleteService(taskRepository, taskLogRepository, evidenceRepository,
                verificationRepository, projectService, eventPublisher, claimTokenService);
    }

    private Task claimedTask(Long projectId, Long taskId, String title, String assignee) {
        Task task = new Task(projectId, title, null, "BACKEND", 0);
        setField(task, "id", taskId);
        setField(task, "assignee", assignee);
        setField(task, "claimedAt", LocalDateTime.now());
        setField(task, "status", TaskStatus.IN_PROGRESS.name());
        return task;
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = Task.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void stubHappyPathCollaborators(Long taskId, Long projectId) {
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));
        when(evidenceRepository.save(any(TaskCompletionEvidence.class)))
                .thenAnswer(invocation -> {
                    TaskCompletionEvidence evidence = invocation.getArgument(0);
                    setEvidenceId(evidence, 100L);
                    return evidence;
                });
        when(verificationRepository.save(any(TaskCompletionVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(1L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(2L);
    }

    private void setEvidenceId(TaskCompletionEvidence evidence, Long id) {
        try {
            Field field = TaskCompletionEvidence.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(evidence, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void completeTask_withPassedVerification_transitionsToDoneAndSavesEvidence() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "實作交易 CRUD API", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        stubHappyPathCollaborators(taskId, projectId);

        var verifications = List.of(new TaskCompleteService.VerificationInput(
                "./mvnw test", "PASSED", null));

        TaskCompleteService.CompleteResult result =
                service.completeTask(taskId, null, "完成 CRUD API", verifications, "Foo.java", "abc123", null);

        assertThat(task.getStatus()).isEqualTo("DONE");
        assertThat(result.task().status()).isEqualTo("DONE");
        assertThat(result.evidence().summary()).isEqualTo("完成 CRUD API");
        assertThat(result.evidence().verifications()).hasSize(1);
        assertThat(result.evidence().verifications().getFirst().result()).isEqualTo("PASSED");

        ArgumentCaptor<TaskLog> logCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getToStatus()).isEqualTo("DONE");

        ArgumentCaptor<BoardEvent> eventCaptor = ArgumentCaptor.forClass(BoardEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("task.status_changed");
        assertThat(eventCaptor.getValue().payload())
                .containsEntry("title", "實作交易 CRUD API")
                .containsEntry("to", "DONE");
    }

    @Test
    void completeTask_withNotRunAndReason_isAllowed() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "純文件任務", "docs");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        stubHappyPathCollaborators(taskId, projectId);

        var verifications = List.of(new TaskCompleteService.VerificationInput(
                "自動化測試", "NOT_RUN", "純文件變更，沒有適用的自動化測試"));

        TaskCompleteService.CompleteResult result =
                service.completeTask(taskId, null, "更新文件", verifications, null, null, null);

        assertThat(result.task().status()).isEqualTo("DONE");
        assertThat(result.evidence().verifications().getFirst().result()).isEqualTo("NOT_RUN");
    }

    @Test
    void completeTask_withoutSummary_throws() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "  ", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("summary");

        verify(evidenceRepository, never()).save(any());
        verify(taskLogRepository, never()).save(any());
    }

    @Test
    void completeTask_withoutVerifications_throws() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", List.of(), null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("verificationResults");

        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void completeTask_withFailedVerification_throwsAndDoesNotTransition() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput(
                "./mvnw test", "FAILED", "有兩個測試失敗"));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("FAILED");

        assertThat(task.getStatus()).isEqualTo("IN_PROGRESS");
        verify(evidenceRepository, never()).save(any());
        verify(taskLogRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void completeTask_withNotRunButNoReason_throws() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput(
                "自動化測試", "NOT_RUN", "  "));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("detail");
    }

    @Test
    void completeTask_withInvalidResultValue_throws() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput(
                "測試", "SKIPPED", null));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PASSED/FAILED/NOT_RUN");
    }

    @Test
    void completeTask_whenTaskIsTodo_throwsIllegalTransition() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = new Task(projectId, "任務", null, "BACKEND", 0);
        setField(task, "id", taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("TODO");
    }

    @Test
    void completeTask_whenProjectArchived_throws() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "封存專案", null, "ARCHIVED"));

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("封存");
    }

    @Test
    void completeTask_withStoredTokenHash_rejectsMissingToken() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "受保護任務", "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> service.completeTask(taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("claimToken");

        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void completeTask_withStoredTokenHash_rejectsWrongToken() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "受保護任務", "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> service.completeTask(taskId, "別人猜的token", "摘要", verifications, null, null, null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("不正確");
    }

    @Test
    void completeTask_withStoredTokenHash_acceptsCorrectToken() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "受保護任務", "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        stubHappyPathCollaborators(taskId, projectId);

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        TaskCompleteService.CompleteResult result = service.completeTask(
                taskId, "正確的token", "摘要", verifications, null, null, null);

        assertThat(result.task().status()).isEqualTo("DONE");
        assertThat(task.getClaimTokenHash()).isNull();
    }

    @Test
    void completeTask_withoutStoredTokenHash_worksWithoutClaimToken_backwardCompatible() {
        // 沿用 #112 的 per-task 相容策略：沒有 claimTokenHash 的舊任務不需要帶 token。
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "舊資料任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        stubHappyPathCollaborators(taskId, projectId);

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        TaskCompleteService.CompleteResult result =
                service.completeTask(taskId, null, "摘要", verifications, null, null, null);

        assertThat(result.task().status()).isEqualTo("DONE");
    }

    @Test
    void completeTask_withStaleExpectedVersion_throws() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> service.completeTask(
                taskId, null, "摘要", verifications, null, null, 999L))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("版本");

        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void getHistory_returnsAllEvidenceInOrder() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "任務", "backend-dev");
        org.mockito.Mockito.doNothing().when(projectService).assertExists(projectId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        TaskCompletionEvidence firstEvidence = new TaskCompletionEvidence(taskId, "第一次完成", null, null, "backend-dev");
        setEvidenceId(firstEvidence, 1L);
        when(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).thenReturn(List.of(firstEvidence));
        when(verificationRepository.findByEvidenceIdOrderByIdAsc(1L)).thenReturn(List.of(
                new TaskCompletionVerification(1L, "測試", "PASSED", null)));

        List<TaskCompleteService.EvidenceDto> history = service.getHistory(projectId, taskId);

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().summary()).isEqualTo("第一次完成");
        assertThat(history.getFirst().verifications()).hasSize(1);
    }

    @Test
    void getHistory_whenTaskBelongsToDifferentProject_throws() {
        Long taskId = 4L;
        Task task = claimedTask(99L, taskId, "任務", "backend-dev");
        org.mockito.Mockito.doNothing().when(projectService).assertExists(12L);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.getHistory(12L, taskId))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("不屬於專案");
    }
}

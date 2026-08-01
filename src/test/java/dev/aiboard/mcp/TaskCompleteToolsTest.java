package dev.aiboard.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskCompleteService;
import dev.aiboard.task.TaskService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCompleteToolsTest {

    @Mock
    private TaskCompleteService taskCompleteService;

    private TaskCompleteTools tools;

    @BeforeEach
    void setUp() {
        tools = new TaskCompleteTools(taskCompleteService);
    }

    private TaskService.TaskDto taskDto(Long id, Long projectId, String title, String status) {
        return new TaskService.TaskDto(id, projectId, title, null, status, "BACKEND", 0,
                "backend-dev", LocalDateTime.now());
    }

    @Test
    void completeTask_whenSuccessful_returnsFormattedMessage() {
        var task = taskDto(4L, 12L, "實作交易 CRUD API", "DONE");
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var evidence = new TaskCompleteService.EvidenceDto(1L, 4L, "完成 CRUD API", null, null,
                "backend-dev", LocalDateTime.now(), List.of(
                        new TaskCompleteService.VerificationDto(1L, "./mvnw test", "PASSED", null)));
        var result = new TaskCompleteService.CompleteResult(task, project, 3L, 8L, evidence);
        when(taskCompleteService.completeTask(eqLong(4L), any(), eqStr("完成 CRUD API"), any(),
                any(), any(), any())).thenReturn(result);

        String message = tools.completeTask(4L, null, "完成 CRUD API",
                List.of(new TaskCompleteTools.VerificationPayload("./mvnw test", "PASSED", null)),
                null, null, null);

        assertThat(message).contains("#4 實作交易 CRUD API 已完成 DONE")
                .contains("進度 3/8")
                .contains("完成 CRUD API")
                .contains("./mvnw test：PASSED");
    }

    @Test
    void completeTask_whenServiceRejects_propagatesBoardException() {
        when(taskCompleteService.completeTask(eqLong(4L), any(), any(), any(), any(), any(), any()))
                .thenThrow(new dev.aiboard.common.BoardException("有 verification 結果為 FAILED"));

        assertThatThrownBy(() -> tools.completeTask(4L, null, "摘要",
                List.of(new TaskCompleteTools.VerificationPayload("測試", "FAILED", "失敗原因")),
                null, null, null))
                .isInstanceOf(dev.aiboard.common.BoardException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void completeTask_whenConcurrentWriteWins_returnsClearBusinessError() {
        when(taskCompleteService.completeTask(eqLong(4L), any(), any(), any(), any(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> tools.completeTask(4L, null, "摘要",
                List.of(new TaskCompleteTools.VerificationPayload("測試", "PASSED", null)),
                null, null, null))
                .isInstanceOf(dev.aiboard.common.BoardException.class)
                .hasMessageContaining("重新讀取");
    }

    @Test
    void completeTask_passesClaimTokenAndExpectedVersionThrough() {
        var task = taskDto(4L, 12L, "任務", "DONE");
        var project = new ProjectService.ProjectDto(12L, "專案", null, "ACTIVE");
        var evidence = new TaskCompleteService.EvidenceDto(1L, 4L, "摘要", "Foo.java", "abc123",
                "backend-dev", LocalDateTime.now(), List.of(
                        new TaskCompleteService.VerificationDto(1L, "測試", "PASSED", null)));
        var result = new TaskCompleteService.CompleteResult(task, project, 1L, 1L, evidence);
        when(taskCompleteService.completeTask(4L, "my-token", "摘要",
                List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null)),
                "Foo.java", "abc123", 2L))
                .thenReturn(result);

        String message = tools.completeTask(4L, "my-token", "摘要",
                List.of(new TaskCompleteTools.VerificationPayload("測試", "PASSED", null)),
                "Foo.java", "abc123", 2L);

        assertThat(message).contains("已完成 DONE");
    }

    private static Long eqLong(Long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static String eqStr(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}

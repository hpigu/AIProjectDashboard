package dev.aiboard.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskService;
import dev.aiboard.task.TaskStatus;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectBoardToolsTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskService taskService;

    private ProjectBoardTools tools;

    @BeforeEach
    void setUp() {
        tools = new ProjectBoardTools(projectService, taskService);
    }

    @Test
    void createProject_whenNewProject_returnsCreatedMessage() {
        var dto = new ProjectService.ProjectDto(12L, "個人記帳 App", "記錄收支", "ACTIVE");
        when(projectService.createProject("個人記帳 App", "記錄收支"))
                .thenReturn(new ProjectService.ProjectCreationResult(dto, false));

        String result = tools.createProject("個人記帳 App", "記錄收支");

        assertThat(result).isEqualTo("專案已建立：#12 個人記帳 App");
    }

    @Test
    void createProject_whenAlreadyExists_returnsExistingMessage() {
        var dto = new ProjectService.ProjectDto(12L, "個人記帳 App", "記錄收支", "ACTIVE");
        when(projectService.createProject("個人記帳 App", null))
                .thenReturn(new ProjectService.ProjectCreationResult(dto, true));

        String result = tools.createProject("個人記帳 App", null);

        assertThat(result).isEqualTo("專案已存在：#12 個人記帳 App（沿用既有看板）");
    }

    @Test
    void createTasks_returnsCountSummaryMessage() {
        Long projectId = 12L;
        List<ProjectBoardTools.TaskInputPayload> payloads = List.of(
                new ProjectBoardTools.TaskInputPayload("任務一", null, "BACKEND"),
                new ProjectBoardTools.TaskInputPayload("任務二", null, "FRONTEND")
        );
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));
        when(taskService.countByProjectId(projectId)).thenReturn(8L);

        String result = tools.createTasks(projectId, payloads);

        assertThat(result).isEqualTo("已新增 2 筆任務至「個人記帳 App」（#12），目前共 8 筆待辦。");
    }

    @Test
    void createTasks_whenPayloadIsNull_returnsClearValidationError() {
        assertThatThrownBy(() -> tools.createTasks(12L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任務清單");
    }

    @Test
    void listTasks_formatsMarkdownGroupedByStatus() {
        Long projectId = 12L;
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));
        when(taskService.listTasks(projectId, null)).thenReturn(List.of(
                taskDto(1L, projectId, "需求釐清", null, "DONE", "DOC", 0, "docs"),
                taskDto(2L, projectId, "建立 schema", null, "TODO", "BACKEND", 1, null)
        ));
        when(taskService.listTasks(projectId, null, null)).thenReturn(List.of(
                taskDto(1L, projectId, "需求釐清", null, "DONE", "DOC", 0, "docs"),
                taskDto(2L, projectId, "建立 schema", null, "TODO", "BACKEND", 1, null)
        ));

        String result = tools.listTasks(projectId, null, null);

        assertThat(result).contains("## 個人記帳 App（#12）");
        assertThat(result).contains("進度：1/2 完成");
        assertThat(result).contains("### DONE (1)");
        assertThat(result).contains("- #1 需求釐清 [DOC] @docs");
        assertThat(result).contains("### TODO (1)");
        assertThat(result).contains("- #2 建立 schema [BACKEND]");
    }

    @Test
    void listTasks_withCategoryFilter_onlyShowsMatchingTasks() {
        Long projectId = 12L;
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));
        when(taskService.listTasks(projectId, null)).thenReturn(List.of(
                taskDto(1L, projectId, "需求釐清", null, "DONE", "DOC", 0, "docs"),
                taskDto(2L, projectId, "撰寫測試", null, "TODO", "TEST", 1, null)
        ));
        when(taskService.listTasks(projectId, null, "TEST")).thenReturn(List.of(
                taskDto(2L, projectId, "撰寫測試", null, "TODO", "TEST", 1, null)
        ));

        String result = tools.listTasks(projectId, null, "TEST");

        assertThat(result).contains("進度：1/2 完成");
        assertThat(result).contains("- #2 撰寫測試 [TEST]");
        assertThat(result).doesNotContain("需求釐清");
    }

    @Test
    void updateTaskStatus_whenChanged_returnsTransitionMessage() {
        var taskDto = taskDto(4L, 12L, "實作交易 CRUD API", null,
                "IN_PROGRESS", "BACKEND", 1, "backend-dev");
        var projectDto = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var result = new TaskService.TaskStatusChangeResult(
                taskDto, TaskStatus.IN_PROGRESS, TaskStatus.DONE, true, projectDto, 3L, 8L);
        when(taskService.updateStatus(4L, "DONE", null)).thenReturn(result);

        String message = tools.updateTaskStatus(4L, "DONE", null);

        assertThat(message).isEqualTo("#4 實作交易 CRUD API：IN_PROGRESS → DONE\n專案「個人記帳 App」進度 3/8");
    }

    @Test
    void updateTaskStatus_whenNoOp_returnsUnchangedMessage() {
        var taskDto = taskDto(4L, 12L, "實作交易 CRUD API", null,
                "TODO", "BACKEND", 1, null);
        var projectDto = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var result = new TaskService.TaskStatusChangeResult(
                taskDto, TaskStatus.TODO, TaskStatus.TODO, false, projectDto, 0L, 8L);
        when(taskService.updateStatus(4L, "TODO", null)).thenReturn(result);

        String message = tools.updateTaskStatus(4L, "TODO", null);

        assertThat(message).isEqualTo("#4 實作交易 CRUD API：狀態已是 TODO，未變更");
    }

    @Test
    void updateTaskStatus_whenConcurrentWriteWins_returnsClearBusinessError() {
        when(taskService.updateStatus(4L, "DONE", null))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> tools.updateTaskStatus(4L, "DONE", null))
                .isInstanceOf(dev.aiboard.common.BoardException.class)
                .hasMessageContaining("重新讀取");
    }

    @Test
    void claimNextTask_whenClaimed_formatsTaskDetails() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var task = taskDto(14L, 12L, "實作交易 CRUD API", "含分頁與驗收條件",
                "IN_PROGRESS", "BACKEND", 1, "backend-dev");
        when(taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev"))
                .thenReturn(TaskService.ClaimNextTaskResult.claimed(project, task, "BACKEND"));

        String result = tools.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result).isEqualTo(
                "已認領 #14「實作交易 CRUD API」[BACKEND]\n"
                        + "專案：個人記帳 App（#12）\n描述／驗收條件：含分頁與驗收條件");
    }

    @Test
    void claimNextTask_whenNoDescription_promptsToConfirmAcceptanceCriteria() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var task = taskDto(14L, 12L, "實作交易 CRUD API", null,
                "IN_PROGRESS", "BACKEND", 1, "backend-dev");
        when(taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev"))
                .thenReturn(TaskService.ClaimNextTaskResult.claimed(project, task, "BACKEND"));

        String result = tools.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result).contains("描述／驗收條件：（無，開工前建議跟使用者確認驗收條件）");
    }

    @Test
    void claimNextTask_whenNoTask_returnsNormalMessage() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev"))
                .thenReturn(TaskService.ClaimNextTaskResult.noTask(project, "BACKEND"));

        assertThat(tools.claimNextTask("個人記帳 App", "BACKEND", "backend-dev"))
                .isEqualTo("BACKEND 目前沒有待辦任務。");
    }

    @Test
    void claimNextTask_whenProjectMissing_listsExistingProjects() {
        var projects = List.of(
                new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE"),
                new ProjectService.ProjectDto(15L, "SMTP 監控工具", null, "ACTIVE"));
        when(taskService.claimNextTask("個人記帳", "BACKEND", "backend-dev"))
                .thenReturn(TaskService.ClaimNextTaskResult.projectNotFound(projects, "BACKEND"));

        String result = tools.claimNextTask("個人記帳", "BACKEND", "backend-dev");

        assertThat(result).contains("找不到專案「個人記帳」")
                .contains("#12 個人記帳 App")
                .contains("#15 SMTP 監控工具");
    }

    private static TaskService.TaskDto taskDto(Long id, Long projectId, String title,
                                                String description, String status,
                                                String category, Integer sortOrder,
                                                String assignee) {
        return new TaskService.TaskDto(id, projectId, title, description, status, category,
                sortOrder, assignee, assignee == null ? null : LocalDateTime.now());
    }
}

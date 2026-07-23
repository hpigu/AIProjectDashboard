package dev.aiboard.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskService;
import dev.aiboard.task.TaskStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    void listTasks_formatsMarkdownGroupedByStatus() {
        Long projectId = 12L;
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));
        when(taskService.listTasks(projectId, null)).thenReturn(List.of(
                new TaskService.TaskDto(1L, projectId, "需求釐清", null, "DONE", "DOC", 0),
                new TaskService.TaskDto(2L, projectId, "建立 schema", null, "TODO", "BACKEND", 1)
        ));

        String result = tools.listTasks(projectId, null);

        assertThat(result).contains("## 個人記帳 App（#12）");
        assertThat(result).contains("進度：1/2 完成");
        assertThat(result).contains("### DONE (1)");
        assertThat(result).contains("- #1 需求釐清 [DOC]");
        assertThat(result).contains("### TODO (1)");
        assertThat(result).contains("- #2 建立 schema [BACKEND]");
    }

    @Test
    void updateTaskStatus_whenChanged_returnsTransitionMessage() {
        var taskDto = new TaskService.TaskDto(4L, 12L, "實作交易 CRUD API", null, "IN_PROGRESS", "BACKEND", 1);
        var projectDto = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var result = new TaskService.TaskStatusChangeResult(
                taskDto, TaskStatus.IN_PROGRESS, TaskStatus.DONE, true, projectDto, 3L, 8L);
        when(taskService.updateStatus(4L, "DONE", null)).thenReturn(result);

        String message = tools.updateTaskStatus(4L, "DONE", null);

        assertThat(message).isEqualTo("#4 實作交易 CRUD API：IN_PROGRESS → DONE\n專案「個人記帳 App」進度 3/8");
    }

    @Test
    void updateTaskStatus_whenNoOp_returnsUnchangedMessage() {
        var taskDto = new TaskService.TaskDto(4L, 12L, "實作交易 CRUD API", null, "TODO", "BACKEND", 1);
        var projectDto = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var result = new TaskService.TaskStatusChangeResult(
                taskDto, TaskStatus.TODO, TaskStatus.TODO, false, projectDto, 0L, 8L);
        when(taskService.updateStatus(4L, "TODO", null)).thenReturn(result);

        String message = tools.updateTaskStatus(4L, "TODO", null);

        assertThat(message).isEqualTo("#4 實作交易 CRUD API：狀態已是 TODO，未變更");
    }
}

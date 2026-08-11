package dev.aiboard.web;

import dev.aiboard.task.DependencyGraphService;
import dev.aiboard.task.DependencyCycleException;
import dev.aiboard.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BoardController.class)
class BoardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BoardQuery boardQuery;

    @MockitoBean
    private TaskService taskService;

    @MockitoBean
    private DependencyGraphService dependencyGraphService;

    @Test
    void getBoard_allFourColumnKeysPresentEvenWhenEmpty() throws Exception {
        Map<String, List<BoardQuery.TaskCard>> columns = Map.of(
                "TODO", List.of(new BoardQuery.TaskCard(5L, "建立 schema", "BACKEND", 3, null, List.of())),
                "IN_PROGRESS", List.of(
                        new BoardQuery.TaskCard(6L, "實作 API", "BACKEND", 4, "backend-dev", List.of(5L))),
                "BLOCKED", List.of(),
                "DONE", List.of()
        );
        var board = new BoardQuery.BoardView(12L, "個人記帳 App", "描述", columns);
        when(boardQuery.getBoard(12L)).thenReturn(board);

        mockMvc.perform(get("/api/projects/12/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.columns.TODO").isArray())
                .andExpect(jsonPath("$.columns.TODO[0].id").value(5))
                .andExpect(jsonPath("$.columns.IN_PROGRESS").isArray())
                .andExpect(jsonPath("$.columns.IN_PROGRESS[0].assignee").value("backend-dev"))
                .andExpect(jsonPath("$.columns.BLOCKED").isArray())
                .andExpect(jsonPath("$.columns.BLOCKED").isEmpty())
                .andExpect(jsonPath("$.columns.DONE").isArray())
                .andExpect(jsonPath("$.columns.DONE").isEmpty());
    }

    @Test
    void getTaskHistory_returnsLogsOldestFirst() throws Exception {
        var logs = List.of(
                new TaskService.TaskLogDto(1L, 4L, null, "TODO", null, LocalDateTime.of(2026, 7, 23, 10, 0)),
                new TaskService.TaskLogDto(2L, 4L, "TODO", "IN_PROGRESS", null, LocalDateTime.of(2026, 7, 23, 11, 0))
        );
        when(taskService.getHistory(12L, 4L)).thenReturn(logs);

        mockMvc.perform(get("/api/projects/12/tasks/4/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toStatus").value("TODO"))
                .andExpect(jsonPath("$[1].fromStatus").value("TODO"))
                .andExpect(jsonPath("$[1].toStatus").value("IN_PROGRESS"));
    }

    @Test
    void getDependencies_returnsNodesAndPrerequisiteToTaskEdges() throws Exception {
        when(dependencyGraphService.getDependencyGraph(12L)).thenReturn(
                new DependencyGraphService.DependencyGraph(
                        List.of(new DependencyGraphService.DependencyNode(
                                5L, "建立 schema", "BACKEND", "DONE", "backend-dev", false),
                                new DependencyGraphService.DependencyNode(
                                        6L, "實作 API", "BACKEND", "TODO", null, true)),
                        List.of(new DependencyGraphService.DependencyEdge(5L, 6L))));

        mockMvc.perform(get("/api/projects/12/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].id").value(5))
                .andExpect(jsonPath("$.nodes[1].claimable").value(true))
                .andExpect(jsonPath("$.edges[0].prerequisiteTaskId").value(5))
                .andExpect(jsonPath("$.edges[0].taskId").value(6));
    }

    @Test
    void getDependencies_whenStoredGraphContainsCycle_returnsDiagnosticConflict() throws Exception {
        when(dependencyGraphService.getDependencyGraph(12L))
                .thenThrow(new DependencyCycleException("偵測到任務相依循環：#5 -> #6 -> #5"));

        mockMvc.perform(get("/api/projects/12/dependencies"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DEPENDENCY_CYCLE"))
                .andExpect(jsonPath("$.message").value("偵測到任務相依循環：#5 -> #6 -> #5"));
    }
}

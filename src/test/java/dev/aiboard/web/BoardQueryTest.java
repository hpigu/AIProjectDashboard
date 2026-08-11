package dev.aiboard.web;

import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardQueryTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskService taskService;

    private BoardQuery boardQuery;

    @BeforeEach
    void setUp() {
        boardQuery = new BoardQuery(projectService, taskService);
    }

    @Test
    void getBoard_exposesUnfinishedPrerequisitesPerTask() {
        Long projectId = 12L;
        when(projectService.getById(projectId)).thenReturn(
                new ProjectService.ProjectDto(projectId, "個人記帳 App", "描述", "ACTIVE"));
        when(taskService.listTasks(projectId, null)).thenReturn(List.of(
                taskDto(9L, projectId, "設定環境", "TODO", "INFRA", 0),
                taskDto(14L, projectId, "改後端", "TODO", "BACKEND", 1)));
        when(taskService.getUnfinishedPrerequisites(List.of(9L, 14L)))
                .thenReturn(Map.of(14L, List.of(
                        taskDto(9L, projectId, "設定環境", "TODO", "INFRA", 0))));

        BoardQuery.BoardView board = boardQuery.getBoard(projectId);

        List<BoardQuery.TaskCard> todo = board.columns().get("TODO");
        assertThat(todo).extracting(BoardQuery.TaskCard::id).containsExactly(9L, 14L);
        assertThat(todo.get(0).waitingFor()).isEmpty();
        assertThat(todo.get(1).waitingFor()).containsExactly(9L);
    }

    @Test
    void getBoard_whenNoDependencies_waitingForIsEmpty() {
        Long projectId = 12L;
        when(projectService.getById(projectId)).thenReturn(
                new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));
        when(taskService.listTasks(projectId, null)).thenReturn(List.of(
                taskDto(9L, projectId, "獨立任務", "TODO", "BACKEND", 0)));
        when(taskService.getUnfinishedPrerequisites(List.of(9L))).thenReturn(Map.of());

        BoardQuery.BoardView board = boardQuery.getBoard(projectId);

        assertThat(board.columns().get("TODO").getFirst().waitingFor()).isEmpty();
        assertThat(board.columns()).containsKeys("TODO", "IN_PROGRESS", "BLOCKED", "DONE");
    }

    private static TaskService.TaskDto taskDto(Long id, Long projectId, String title,
                                               String status, String category, int sortOrder) {
        return new TaskService.TaskDto(id, projectId, title, null, status, category, sortOrder,
                null, null);
    }
}

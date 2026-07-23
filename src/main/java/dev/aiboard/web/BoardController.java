package dev.aiboard.web;

import dev.aiboard.task.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BoardController {

    private final BoardQuery boardQuery;
    private final TaskService taskService;

    public BoardController(BoardQuery boardQuery, TaskService taskService) {
        this.boardQuery = boardQuery;
        this.taskService = taskService;
    }

    @GetMapping("/api/projects/{id}/board")
    public BoardQuery.BoardView getBoard(@PathVariable("id") Long id) {
        return boardQuery.getBoard(id);
    }

    @GetMapping("/api/projects/{id}/tasks/{taskId}/history")
    public List<TaskService.TaskLogDto> getTaskHistory(@PathVariable("id") Long id,
                                                          @PathVariable("taskId") Long taskId) {
        return taskService.getHistory(id, taskId);
    }
}

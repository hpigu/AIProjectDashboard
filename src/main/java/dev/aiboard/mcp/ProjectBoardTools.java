package dev.aiboard.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskService;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ProjectBoardTools {

    private final ProjectService projectService;
    private final TaskService taskService;

    public ProjectBoardTools(ProjectService projectService, TaskService taskService) {
        this.projectService = projectService;
        this.taskService = taskService;
    }

    @Tool(name = "create_project",
            description = "建立一個新的專案看板。當使用者開始規劃一個新專案、或明確要求把某個構想建成專案時呼叫。"
                    + "呼叫後會回傳 projectId，後續建立任務時需要用到。")
    public String createProject(
            @ToolParam(description = "專案名稱，最長 200 字") String name,
            @ToolParam(description = "專案目標描述", required = false) String description) {
        ProjectService.ProjectCreationResult result = projectService.createProject(name, description);
        ProjectService.ProjectDto project = result.project();
        if (result.alreadyExisted()) {
            return "專案已存在：#%d %s（沿用既有看板）".formatted(project.id(), project.name());
        }
        return "專案已建立：#%d %s".formatted(project.id(), project.name());
    }

    @Tool(name = "create_tasks",
            description = "一次把多個任務加進指定專案的看板。當你完成專案拆解、要把待辦事項記錄下來時呼叫。"
                    + "一次呼叫請帶入所有任務，不要一個一個呼叫。")
    public String createTasks(
            @ToolParam(description = "專案 ID") Long projectId,
            @ToolParam(description = "任務清單，至少 1 筆，最多 50 筆") List<TaskInputPayload> tasks) {
        List<TaskService.TaskInput> inputs = tasks.stream()
                .map(t -> new TaskService.TaskInput(t.title(), t.description(), t.category()))
                .toList();
        taskService.createTasks(projectId, inputs);

        ProjectService.ProjectDto project = projectService.getById(projectId);
        long total = taskService.countByProjectId(projectId);
        return "已新增 %d 筆任務至「%s」（#%d），目前共 %d 筆待辦。"
                .formatted(inputs.size(), project.name(), project.id(), total);
    }

    @Tool(name = "update_task_status",
            description = "更新單一任務的狀態。開始處理某個任務時先標記 IN_PROGRESS，"
                    + "完成後標記 DONE，遇到阻礙標記 BLOCKED 並在 note 說明原因。"
                    + "請在實際開始／完成工作的當下呼叫，不要等到最後才一次補登。")
    public String updateTaskStatus(
            @ToolParam(description = "任務 ID") Long taskId,
            @ToolParam(description = "目標狀態：TODO / IN_PROGRESS / DONE / BLOCKED") String status,
            @ToolParam(description = "變更原因，BLOCKED 時強烈建議填寫", required = false) String note) {
        TaskService.TaskStatusChangeResult result = taskService.updateStatus(taskId, status, note);
        TaskService.TaskDto task = result.task();

        if (!result.changed()) {
            return "#%d %s：狀態已是 %s，未變更".formatted(task.id(), task.title(), result.to());
        }
        return "#%d %s：%s → %s\n專案「%s」進度 %d/%d"
                .formatted(task.id(), task.title(), result.from(), result.to(),
                        result.project().name(), result.doneCount(), result.totalCount());
    }

    @Tool(name = "list_tasks",
            description = "查詢某專案目前的任務清單與進度。當使用者詢問專案進度、還有什麼要做、"
                    + "或你需要了解目前狀態才能繼續規劃時呼叫。"
                    + "帶入 category 可只列出特定類型的任務，例如 BACKEND。"
                    + "你被指派某個角色時，用它來確認還有哪些屬於你的工作。")
    public String listTasks(
            @ToolParam(description = "專案 ID") Long projectId,
            @ToolParam(description = "任務狀態篩選：TODO / IN_PROGRESS / DONE / BLOCKED，不填代表全部",
                    required = false) String status,
            @ToolParam(description = "任務分類篩選：BACKEND / FRONTEND / INFRA / DOC / TEST / OTHER，不填代表全部",
                    required = false) String category) {
        ProjectService.ProjectDto project = projectService.getById(projectId);
        List<TaskService.TaskDto> allTasks = taskService.listTasks(projectId, null);
        List<TaskService.TaskDto> filtered = taskService.listTasks(projectId, status, category);

        long doneCount = allTasks.stream().filter(t -> "DONE".equals(t.status())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("## %s（#%d）\n".formatted(project.name(), project.id()));
        sb.append("進度：%d/%d 完成\n".formatted(doneCount, allTasks.size()));

        var byStatus = filtered.stream()
                .collect(Collectors.groupingBy(TaskService.TaskDto::status, java.util.LinkedHashMap::new, Collectors.toList()));

        for (var entry : byStatus.entrySet()) {
            sb.append("### %s (%d)\n".formatted(entry.getKey(), entry.getValue().size()));
            for (TaskService.TaskDto task : entry.getValue()) {
                String categoryLabel = task.category() != null ? " [%s]".formatted(task.category()) : "";
                sb.append("- #%d %s%s\n".formatted(task.id(), task.title(), categoryLabel));
            }
        }

        return sb.toString();
    }

    public record TaskInputPayload(String title, String description, String category) {
    }
}

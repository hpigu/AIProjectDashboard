package dev.aiboard.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import dev.aiboard.common.BoardException;
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
                    + "一次呼叫請帶入所有任務，不要一個一個呼叫。"
                    + "description 建議寫成「描述 + 驗收條件」兩段，"
                    + "例如「實作交易 CRUD API\n驗收條件：四個端點都有、金額不接受負數」，"
                    + "認領任務的 agent 只看得到這個欄位，寫清楚才知道何時算做完。"
                    + "category 未填、空白或不合法時會歸類為 OTHER。")
    public String createTasks(
            @ToolParam(description = "專案 ID") Long projectId,
            @ToolParam(description = "任務清單，至少 1 筆，最多 50 筆") List<TaskInputPayload> tasks) {
        if (tasks == null) {
            throw new IllegalArgumentException("任務清單不可為空");
        }
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
            description = "更新單一任務的狀態。任務必須先用 claim_next_task 認領，才能進入 IN_PROGRESS；"
                    + "完成後標記 DONE，遇到阻礙標記 BLOCKED 並在 note 說明原因。"
                    + "改回 TODO 會一併清空 assignee 與 claimed_at。"
                    + "請在實際完成／卡住／歸還工作的當下呼叫，不要等到最後才一次補登。")
    public String updateTaskStatus(
            @ToolParam(description = "任務 ID") Long taskId,
            @ToolParam(description = "目標狀態：TODO / IN_PROGRESS / DONE / BLOCKED") String status,
            @ToolParam(description = "變更原因，BLOCKED 時強烈建議填寫", required = false) String note) {
        TaskService.TaskStatusChangeResult result;
        try {
            result = taskService.updateStatus(taskId, status, note);
        } catch (OptimisticLockingFailureException e) {
            throw new BoardException("任務 #" + taskId + " 已被其他 agent 更新，請重新讀取後再操作");
        }
        TaskService.TaskDto task = result.task();

        if (!result.changed()) {
            return "#%d %s：狀態已是 %s，未變更".formatted(task.id(), task.title(), result.to());
        }
        return "#%d %s：%s → %s\n專案「%s」進度 %d/%d"
                .formatted(task.id(), task.title(), result.from(), result.to(),
                        result.project().name(), result.doneCount(), result.totalCount());
    }

    @Tool(name = "claim_next_task",
            description = "認領指定專案、指定類別中最優先的一個待辦任務。此工具會原子性地"
                    + "把任務標記為 IN_PROGRESS 並記錄認領者，因此不會有兩個 agent"
                    + "拿到同一個任務。認領成功後立即開始執行該任務。")
    public String claimNextTask(
            @ToolParam(description = "專案名稱，精確比對但不分大小寫") String projectName,
            @ToolParam(description = "任務分類：BACKEND / FRONTEND / TEST / INFRA / DOC / OTHER")
                    String category,
            @ToolParam(description = "認領者角色名，例如 backend-dev") String assignee) {
        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask(projectName, category, assignee);
        if (!result.projectFound()) {
            String projects = result.availableProjects().isEmpty()
                    ? "（看板上目前沒有專案）"
                    : result.availableProjects().stream()
                            .map(p -> "#%d %s".formatted(p.id(), p.name()))
                            .collect(Collectors.joining("、"));
            return "找不到專案「%s」。看板上目前有：\n%s".formatted(projectName, projects);
        }
        if (!result.claimed()) {
            return "%s 目前沒有待辦任務。".formatted(result.category());
        }

        TaskService.TaskDto task = result.task();
        String description = task.description() == null || task.description().isBlank()
                ? "（無，開工前建議跟使用者確認驗收條件）" : task.description();
        return "已認領 #%d「%s」[%s]\n專案：%s（#%d）\n描述／驗收條件：%s"
                .formatted(task.id(), task.title(), task.category(),
                        result.project().name(), result.project().id(), description);
    }

    @Tool(name = "list_tasks",
            description = "查詢某專案目前的任務清單與進度。當使用者詢問專案進度、還有什麼要做、"
                    + "或你需要了解目前狀態才能繼續規劃時呼叫。"
                    + "projectId 與 projectName 請擇一提供；只知道專案名稱時用 projectName 即可，"
                    + "名稱比對規則與 claim_next_task 相同：完整名稱、不分大小寫。"
                    + "帶入 category 可只列出特定類型的任務，例如 BACKEND。"
                    + "你被指派某個角色時，用它來確認還有哪些屬於你的工作。"
                    + "要依任務內容決定分派或判斷先後順序時，帶入 includeDescription=true "
                    + "取得每筆任務的描述與驗收條件。")
    public String listTasks(
            @ToolParam(description = "專案 ID，與 projectName 擇一提供", required = false) Long projectId,
            @ToolParam(description = "專案名稱，精確比對但不分大小寫，與 projectId 擇一提供",
                    required = false) String projectName,
            @ToolParam(description = "任務狀態篩選：TODO / IN_PROGRESS / DONE / BLOCKED，不填代表全部",
                    required = false) String status,
            @ToolParam(description = "任務分類篩選：BACKEND / FRONTEND / INFRA / DOC / TEST / OTHER，不填代表全部",
                    required = false) String category,
            @ToolParam(description = "是否一併輸出每筆任務的描述與驗收條件，預設 false",
                    required = false) Boolean includeDescription) {
        TaskService.ProjectTasksResult result =
                taskService.listTasksByProjectRef(projectId, projectName, status, category);
        if (!result.projectFound()) {
            String projects = result.availableProjects().isEmpty()
                    ? "（看板上目前沒有專案）"
                    : result.availableProjects().stream()
                            .map(p -> "#%d %s".formatted(p.id(), p.name()))
                            .collect(Collectors.joining("、"));
            return "找不到專案「%s」。看板上目前有：\n%s".formatted(projectName, projects);
        }

        ProjectService.ProjectDto project = result.project();
        List<TaskService.TaskDto> allTasks = taskService.listTasks(project.id(), null);
        List<TaskService.TaskDto> filtered = result.tasks();

        long doneCount = allTasks.stream().filter(t -> "DONE".equals(t.status())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("## %s（#%d）\n".formatted(project.name(), project.id()));
        sb.append("進度：%d/%d 完成\n".formatted(doneCount, allTasks.size()));

        var byStatus = filtered.stream()
                .collect(Collectors.groupingBy(TaskService.TaskDto::status, java.util.LinkedHashMap::new, Collectors.toList()));

        boolean withDescription = Boolean.TRUE.equals(includeDescription);

        for (var entry : byStatus.entrySet()) {
            sb.append("### %s (%d)\n".formatted(entry.getKey(), entry.getValue().size()));
            for (TaskService.TaskDto task : entry.getValue()) {
                String categoryLabel = task.category() != null ? " [%s]".formatted(task.category()) : "";
                String assigneeLabel = task.assignee() != null ? " @" + task.assignee() : "";
                sb.append("- #%d %s%s%s\n"
                        .formatted(task.id(), task.title(), categoryLabel, assigneeLabel));
                if (withDescription) {
                    sb.append(indentDescription(task.description()));
                }
            }
        }

        return sb.toString();
    }

    private static String indentDescription(String description) {
        if (description == null || description.isBlank()) {
            return "  （無描述）\n";
        }
        return description.lines()
                .map(line -> line.isBlank() ? "" : "  " + line)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    public record TaskInputPayload(String title, String description, String category) {
    }
}

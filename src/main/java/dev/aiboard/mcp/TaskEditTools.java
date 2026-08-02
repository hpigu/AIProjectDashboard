package dev.aiboard.mcp;

import dev.aiboard.common.BoardException;
import dev.aiboard.task.TaskEditService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.List;

/** MCP adapter only; all validation and mutations live in TaskEditService. */
@Component
public class TaskEditTools {

    private static final String ACCESS_BOUNDARY = "leader 專用。第一階段只靠 Claude/Codex plugin "
            + "工具白名單限制 worker 看不到此工具；MCP server 無 caller identity，這不是後端授權。"
            + "服務必須維持 localhost，未建立 server-side 認證前不可對外暴露。";

    private final TaskEditService taskEditService;

    public TaskEditTools(TaskEditService taskEditService) {
        this.taskEditService = taskEditService;
    }

    @Tool(name = "update_task_details",
            description = ACCESS_BOUNDARY + "以 patch 語意修改任務規格：null 代表不修改；"
                    + "title/description 只允許 TODO 或 BLOCKED，category 只允許 TODO；"
                    + "IN_PROGRESS/DONE 與已封存專案拒絕。expectedVersion 必填，用於 optimistic locking。")
    public String updateTaskDetails(
            @ToolParam(description = "任務 ID") Long taskId,
            @ToolParam(description = "新標題；null 代表不修改", required = false) String title,
            @ToolParam(description = "新描述；null 代表不修改，空字串可清空", required = false)
                    String description,
            @ToolParam(description = "新分類；null 代表不修改", required = false) String category,
            @ToolParam(description = "讀取任務時取得的 version；版本不符會拒絕") Long expectedVersion) {
        try {
            TaskEditService.TaskEditResult result = taskEditService.updateTaskDetails(
                    taskId, title, description, category, expectedVersion);
            if (!result.changed()) {
                return "任務 #%d 的指定欄位沒有變更（version=%d）"
                        .formatted(result.taskId(), result.version());
            }
            return "已更新任務 #%d 的 %s（version=%d）"
                    .formatted(result.taskId(), result.changes().stream()
                            .map(TaskEditService.ValueChange::field).toList(), result.version());
        } catch (OptimisticLockingFailureException e) {
            throw new BoardException("任務 #" + taskId + " 已被其他操作更新，請重新讀取後再操作");
        }
    }

    @Tool(name = "set_task_dependencies",
            description = ACCESS_BOUNDARY + "以完整集合取代任務的前置相依；空陣列代表清空。"
                    + "只允許 TODO，且前置必須同專案、不可為自己、不可形成循環；"
                    + "已封存專案拒絕。expectedVersion 必填，用於 optimistic locking。")
    public String setTaskDependencies(
            @ToolParam(description = "任務 ID") Long taskId,
            @ToolParam(description = "完整前置任務 ID 清單；空陣列代表清空")
                    List<Long> prerequisiteTaskIds,
            @ToolParam(description = "讀取任務時取得的 version；版本不符會拒絕") Long expectedVersion) {
        try {
            TaskEditService.TaskDependencyEditResult result = taskEditService.setTaskDependencies(
                    taskId, prerequisiteTaskIds, expectedVersion);
            if (!result.changed()) {
                return "任務 #%d 的前置相依沒有變更（version=%d）：%s"
                        .formatted(result.taskId(), result.version(), result.newPrerequisiteTaskIds());
            }
            return "已更新任務 #%d 的前置相依：%s → %s（version=%d）"
                    .formatted(result.taskId(), result.oldPrerequisiteTaskIds(),
                            result.newPrerequisiteTaskIds(), result.version());
        } catch (OptimisticLockingFailureException e) {
            throw new BoardException("任務 #" + taskId + " 已被其他操作更新，請重新讀取後再操作");
        }
    }
}

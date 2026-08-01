package dev.aiboard.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import dev.aiboard.common.BoardException;
import dev.aiboard.task.TaskBlockService;
import dev.aiboard.task.TaskService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * #113 結構化 BLOCKED 流程的 MCP 入口。獨立於 ProjectBoardTools，避免更動
 * 既有建構子簽名波及其他角色正在維護的測試；只呼叫 Service，不放業務邏輯。
 */
@Component
public class TaskBlockTools {

    private final TaskBlockService taskBlockService;

    public TaskBlockTools(TaskBlockService taskBlockService) {
        this.taskBlockService = taskBlockService;
    }

    @Tool(name = "block_task",
            description = "把任務標記為 BLOCKED，並記錄結構化的阻塞原因，取代直接呼叫 "
                    + "update_task_status 轉 BLOCKED（那個舊路徑仍可用於單純轉移，但不會留下"
                    + "結構化原因）。reasonType 限定 DEPENDENCY／USER_INPUT／TECHNICAL／"
                    + "ENVIRONMENT／EXTERNAL／OTHER，只能選一個最貼切的主要原因；"
                    + "detail 必填，具體說明目前實際在等什麼、卡在哪裡。"
                    + "reasonType=DEPENDENCY 時，blockingTaskIds 必須至少帶一個同專案的任務 id，"
                    + "說明在等哪些任務先完成（這與看板既有的 task_dependency 前置關係是"
                    + "不同概念：task_dependency 是規劃階段就存在的固定相依，這裡只是"
                    + "這次卡住當下的快照，之後可能改變）。"
                    + "只有持有 claim token 的 assignee 能 block 自己的任務，"
                    + "帶入 claimToken 與認領時相同；沒有 token 的舊資料任務沿用舊行為。"
                    + "帶入 expectedVersion（可從 list_tasks 或前次操作結果取得任務版本）"
                    + "可避免在你讀取任務之後、實際送出 block 之前，任務已被別的操作改變。")
    public String blockTask(
            @ToolParam(description = "任務 ID") Long taskId,
            @ToolParam(description = "認領該任務時取得的 claim token；任務要求 token 時才需要提供",
                    required = false) String claimToken,
            @ToolParam(description = "主要阻塞原因：DEPENDENCY / USER_INPUT / TECHNICAL / "
                    + "ENVIRONMENT / EXTERNAL / OTHER") String reasonType,
            @ToolParam(description = "具體說明目前實際在等什麼、卡在哪裡；必填") String detail,
            @ToolParam(description = "reasonType=DEPENDENCY 時必填：正在等哪些同專案任務的 id",
                    required = false) List<Long> blockingTaskIds,
            @ToolParam(description = "預期的任務版本號，避免覆蓋掉你讀取之後別人做的變更",
                    required = false) Long expectedVersion) {
        TaskBlockService.BlockResult result;
        try {
            result = taskBlockService.blockTask(taskId, claimToken, reasonType, detail,
                    blockingTaskIds, expectedVersion);
        } catch (OptimisticLockingFailureException e) {
            throw new BoardException("任務 #" + taskId + " 已被其他 agent 更新，請重新讀取後再操作");
        }

        TaskService.TaskDto task = result.task();
        String waitingLabel = result.blockingTasks().isEmpty()
                ? ""
                : result.blockingTasks().stream()
                        .map(t -> "#%d %s（%s）".formatted(t.id(), t.title(), t.status()))
                        .collect(Collectors.joining("、", "\n正在等待：", ""));
        return ("#%d %s 已標記 BLOCKED\n原因：%s\n說明：%s%s")
                .formatted(task.id(), task.title(), result.reasonType(), result.detail(), waitingLabel);
    }
}

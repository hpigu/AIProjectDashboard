package dev.aiboard.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import dev.aiboard.common.BoardException;
import dev.aiboard.task.TaskCompleteService;
import dev.aiboard.task.TaskService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * #114 complete_task 與完成證據的 MCP 入口。獨立於 ProjectBoardTools，避免更動
 * 既有建構子簽名波及其他角色正在維護的測試；只呼叫 Service，不放業務邏輯。
 */
@Component
public class TaskCompleteTools {

    private final TaskCompleteService taskCompleteService;

    public TaskCompleteTools(TaskCompleteService taskCompleteService) {
        this.taskCompleteService = taskCompleteService;
    }

    @Tool(name = "complete_task",
            description = "把任務標記為 DONE，同時記錄結構化的完成證據，取代直接呼叫 "
                    + "update_task_status 轉 DONE（那個舊路徑仍相容既有沒有要求證據的任務，"
                    + "但不會留下結構化證據，建議所有新完成的任務改用這裡）。"
                    + "summary 必填，說明實際完成了什麼；verificationResults 至少一筆，"
                    + "每筆包含 name（驗證的是什麼，例如「./mvnw test」）、"
                    + "result（PASSED／FAILED／NOT_RUN）、detail（FAILED 或 NOT_RUN 時必填，"
                    + "說明結果內容或為什麼這個 category 沒有適用的驗證方式，"
                    + "例如純文件任務可以用 NOT_RUN 加原因）。"
                    + "只要有一筆 verification 是 FAILED，整個 complete_task 會被拒絕，"
                    + "請先解決問題或改用 block_task／update_task_status 標記 BLOCKED。"
                    + "這裡記錄的是你（agent）自行聲明的證據，系統不會、也不能代替你去驗證"
                    + "commitRef 是否真的存在於外部 repo、changedFiles 是否確實被修改，"
                    + "請誠實填寫，不要宣稱系統已經驗證過你沒有實際跑過的東西。"
                    + "只有持有 claim token 的 assignee 能完成自己的任務，"
                    + "帶入 claimToken 與認領時相同；沒有 token 的舊資料任務沿用舊行為。"
                    + "帶入 expectedVersion（可從 list_tasks 或前次操作結果取得任務版本）"
                    + "可避免在你讀取任務之後、實際送出 complete 之前，任務已被別的操作改變。")
    public String completeTask(
            @ToolParam(description = "任務 ID") Long taskId,
            @ToolParam(description = "認領該任務時取得的 claim token；任務要求 token 時才需要提供",
                    required = false) String claimToken,
            @ToolParam(description = "完成摘要，說明實際完成了什麼；必填") String summary,
            @ToolParam(description = "驗證結果清單，至少一筆；每筆含 name/result/detail，"
                    + "result 限定 PASSED/FAILED/NOT_RUN") List<VerificationPayload> verificationResults,
            @ToolParam(description = "變更的檔案清單或說明，選填", required = false) String changedFiles,
            @ToolParam(description = "對應的 commit hash 或參照，選填；這只是你自行聲明的紀錄，"
                    + "系統不會驗證它是否真的存在於外部 repo", required = false) String commitRef,
            @ToolParam(description = "預期的任務版本號，避免覆蓋掉你讀取之後別人做的變更",
                    required = false) Long expectedVersion) {
        List<TaskCompleteService.VerificationInput> inputs = verificationResults == null
                ? List.of()
                : verificationResults.stream()
                        .map(v -> new TaskCompleteService.VerificationInput(v.name(), v.result(), v.detail()))
                        .toList();

        TaskCompleteService.CompleteResult result;
        try {
            result = taskCompleteService.completeTask(taskId, claimToken, summary, inputs,
                    changedFiles, commitRef, expectedVersion);
        } catch (OptimisticLockingFailureException e) {
            throw new BoardException("任務 #" + taskId + " 已被其他 agent 更新，請重新讀取後再操作");
        }

        TaskService.TaskDto task = result.task();
        String verificationLabel = result.evidence().verifications().stream()
                .map(v -> "- %s：%s%s".formatted(v.name(), v.result(),
                        v.detail() == null || v.detail().isBlank() ? "" : "（" + v.detail() + "）"))
                .collect(Collectors.joining("\n"));
        return ("#%d %s 已完成 DONE\n專案「%s」進度 %d/%d\n摘要：%s\n驗證結果：\n%s")
                .formatted(task.id(), task.title(), result.project().name(),
                        result.doneCount(), result.totalCount(), result.evidence().summary(),
                        verificationLabel);
    }

    public record VerificationPayload(String name, String result, String detail) {
    }
}

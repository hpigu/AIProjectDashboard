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

    @Tool(name = "complete_task", description = "正式結案，須通過版本化需求契約、受控執行證據、獨立核准與專案Git流程。verificationResults僅為開發代理自測聲明，不會提升為可信證據。需要認領token，commitRef須符合受測codeRef；未驗收請先report_implementation。")
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

    @Tool(name="report_implementation", description="開發代理提交實作及自測聲明，保留 IN_PROGRESS/BLOCKED，不代表驗收，不解鎖相依。FAILED/NOT_RUN 需理由。")
    public TaskCompleteService.CompleteResult reportImplementation(
            @ToolParam(description="任務 ID") Long taskId,
            @ToolParam(description="認領 token", required=false) String claimToken,
            @ToolParam(description="實作摘要") String summary,
            @ToolParam(description="自測聲明，每項 name/result/detail") List<VerificationPayload> verificationResults,
            @ToolParam(description="變更檔案",required=false) String changedFiles,
            @ToolParam(description="實作版本",required=false) String commitRef,
            @ToolParam(description="預期任務版本",required=false) Long expectedVersion) {
        return taskCompleteService.reportImplementation(taskId,claimToken,summary,
            verificationResults==null ? List.of() : verificationResults.stream().map(v->new TaskCompleteService.VerificationInput(v.name(),v.result(),v.detail())).toList(),
            changedFiles,commitRef,expectedVersion);
    }

    public record VerificationPayload(String name, String result, String detail) {
    }
}

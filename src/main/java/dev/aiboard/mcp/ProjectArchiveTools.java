package dev.aiboard.mcp;

import dev.aiboard.project.ProjectArchiveService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/** MCP adapter only; archive authorization instructions are deliberately explicit. */
@Component
public class ProjectArchiveTools {

    private static final String LEADER_AUTHORIZATION = "僅限 leader 操作，且只能在使用者於「目前對話」"
            + "明確要求封存或恢復這個專案後呼叫；任務全數完成、收尾、沉默或先前對話都不是授權。"
            + "subagent 不得取得或呼叫這些工具。MCP server 沒有 caller identity，leader 必須自行遵守此邊界。";

    private final ProjectArchiveService projectArchiveService;

    public ProjectArchiveTools(ProjectArchiveService projectArchiveService) {
        this.projectArchiveService = projectArchiveService;
    }

    @Tool(name = "preview_archive_project",
            description = LEADER_AUTHORIZATION + "唯讀預覽專案的各任務狀態數與目前 assignee，"
                    + "不會改變任何資料。若有 IN_PROGRESS，archive_project 前必須再次取得使用者明確確認。")
    public String previewArchiveProject(
            @ToolParam(description = "專案名稱，精確比對但不分大小寫") String projectName) {
        ProjectArchiveService.ArchivePreview preview = projectArchiveService.previewArchive(projectName);
        return "%s（#%d）目前為 %s\n%s\n%s".formatted(
                preview.project().name(), preview.project().id(), preview.project().status(),
                formatCounts(preview.counts()), formatAssignees(preview));
    }

    @Tool(name = "archive_project",
            description = LEADER_AUTHORIZATION + "封存後專案所有寫入都會被拒絕，但 GET/read 仍可使用；"
                    + "任務、assignee、歷史與相依關係均會原樣保留。請先用 preview_archive_project。"
                    + "若 preview 顯示 IN_PROGRESS，只有再次得到使用者明確確認後才能把 inProgressConfirmed 設為 true。")
    public String archiveProject(
            @ToolParam(description = "專案名稱，精確比對但不分大小寫") String projectName,
            @ToolParam(description = "使用者當前明確授權的原因，會寫入不可覆寫的稽核紀錄") String reason,
            @ToolParam(description = "只有 preview 有 IN_PROGRESS 且已再次取得使用者明確確認時才可為 true",
                    required = false) Boolean inProgressConfirmed) {
        ProjectArchiveService.ArchiveResult result = projectArchiveService.archive(projectName, reason,
                Boolean.TRUE.equals(inProgressConfirmed));
        return "已封存「%s」（#%d）。%s\n稽核時間：%s".formatted(
                result.project().name(), result.project().id(), formatCounts(result.counts()), result.auditedAt());
    }

    @Tool(name = "restore_project",
            description = LEADER_AUTHORIZATION + "恢復已封存專案的寫入能力；既有任務、assignee、歷史與相依"
                    + "關係完全不變。reason 會寫入不可覆寫的稽核紀錄。")
    public String restoreProject(
            @ToolParam(description = "專案名稱，精確比對但不分大小寫") String projectName,
            @ToolParam(description = "使用者當前明確授權的原因，會寫入不可覆寫的稽核紀錄") String reason) {
        ProjectArchiveService.ArchiveResult result = projectArchiveService.restore(projectName, reason);
        return "已恢復「%s」（#%d）的寫入能力。%s\n稽核時間：%s".formatted(
                result.project().name(), result.project().id(), formatCounts(result.counts()), result.auditedAt());
    }

    private static String formatCounts(ProjectArchiveService.StatusCounts counts) {
        return "任務：TODO %d、IN_PROGRESS %d、BLOCKED %d、DONE %d（共 %d）".formatted(
                counts.todo(), counts.inProgress(), counts.blocked(), counts.done(), counts.total());
    }

    private static String formatAssignees(ProjectArchiveService.ArchivePreview preview) {
        if (preview.assignees().isEmpty()) {
            return "目前沒有 assignee。";
        }
        return "目前 assignee：" + preview.assignees().stream()
                .map(assignee -> "#%d %s [%s] @%s".formatted(assignee.taskId(), assignee.title(),
                        assignee.status(), assignee.assignee()))
                .collect(Collectors.joining("、"));
    }
}

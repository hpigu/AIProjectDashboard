package dev.aiboard.mcp;

import dev.aiboard.common.BoardException;
import dev.aiboard.workflow.ProjectWorkflowService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;

@Component
public class ProjectWorkflowTools {
    private final ProjectWorkflowService workflows;
    public ProjectWorkflowTools(ProjectWorkflowService workflows) { this.workflows = workflows; }

    @Tool(name="get_project_workflow", description="規劃前讀取專案有效 Git 流程、草稿、來源與缺項。空專案也可使用，已確認設定優先沿用。")
    public ProjectWorkflowService.Summary get(@ToolParam(description="完整專案名稱") String projectName) {
        return workflows.get(projectName);
    }

    @Tool(name="prepare_project_workflow", description="管理者保存 Git 流程草稿；先讀專案文件，區分規則、使用者決定與代理建議，只詢問缺項及矛盾。省略欄位沿用、空字串清空；草稿不取代有效設定，不能自行核准。")
    public ProjectWorkflowService.Summary prepare(
            @ToolParam(description="完整專案名稱") String projectName,
            @ToolParam(description="get_project_workflow 取得的 revision") Long expectedRevision,
            @ToolParam(description="七項 Git 設定，可部分提供", required=false) PolicyPayload gitPolicy,
            @ToolParam(description="欄位依據；kind=PROJECT_RULE/USER_DECISION/SUGGESTION；PROJECT_RULE 須附 ref 與 version", required=false) List<ProjectWorkflowService.SourceInput> sources,
            @ToolParam(description="待決問題，空陣列表示問題已解決", required=false) List<ProjectWorkflowService.QuestionInput> questions) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)
                || !(attrs.getRequest().getAttribute("board.managementActor") instanceof String actor))
            throw new BoardException("流程規劃需要管理身分");
        if (expectedRevision == null || expectedRevision < 0) throw new BoardException("必須提供目前流程 revision");
        return workflows.prepare(projectName, expectedRevision, gitPolicy == null ? null : gitPolicy.toInput(), sources, questions, actor);
    }

    @Tool(name="confirm_project_workflow", description="由獨立 PROJECT_POLICY 核准者確認精確草稿；需要 owner 簽章，管理憑證不能代替。使用固定核准工具檢視摘要並確認，不要求使用者手寫 JSON 或複製簽章。")
    public ProjectWorkflowService.Summary confirm(
            @ToolParam(description="受設定授權的核准身分") String actor,
            @ToolParam(description="固定核准工具產生的原始 JSON approvalPayload 加 approvalRef") String payload,
            @ToolParam(description="owner 簽署端產生 HMAC SHA256，不把金鑰交給 leader/worker") String signature) {
        return workflows.confirm(actor, payload, signature);
    }

    public record PolicyPayload(
            @ToolParam(description="起始分支", required=false) String baseBranch,
            @ToolParam(description="整合目標", required=false) String integrationTarget,
            @ToolParam(description="PR/MR 規則", required=false) String pullRequestPolicy,
            @ToolParam(description="必要檢查", required=false) String requiredChecks,
            @ToolParam(description="審查規則", required=false) String reviewPolicy,
            @ToolParam(description="合併權限", required=false) String mergeAuthority,
            @ToolParam(description="DONE 時機 ACCEPTED/INTEGRATED", required=false) String doneWhen) {
        ProjectWorkflowService.PolicyInput toInput() {
            return new ProjectWorkflowService.PolicyInput(baseBranch,integrationTarget,pullRequestPolicy,requiredChecks,reviewPolicy,mergeAuthority,doneWhen);
        }
    }
}

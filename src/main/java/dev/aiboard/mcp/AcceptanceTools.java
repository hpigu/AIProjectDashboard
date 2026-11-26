package dev.aiboard.mcp;

import dev.aiboard.acceptance.AcceptanceService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AcceptanceTools {
    private final AcceptanceService service;
    public AcceptanceTools(AcceptanceService service) { this.service=service; }
    @Tool(name="record_acceptance_event", description="接收專案範圍簽章驗收事件；不執行命令、不下載證據。CONTRACT/RUN/REVIEW/EXCEPTION/INTEGRATION/DISPATCH/RELEASE 需各自授權。開發代理自行聲明不能核准驗收。")
    public AcceptanceService.Summary record(
            @ToolParam(description="設定的簽署身分") String actor,
            @ToolParam(description="原始 JSON 字串（eventId/projectId/taskId/expectedRevision/kind/body）") String payload,
            @ToolParam(description="原始 payload UTF-8 的 HMAC SHA-256 小寫 hex；金鑰只能留在受控簽署端") String signature) {
        return service.record(actor,payload,signature);
    }
    @Tool(name="get_acceptance", description="讀取需求驗收摘要、待決事項、結案阻擋原因與版本化證據；不代表外部系統已驗收。")
    public AcceptanceService.Summary get(@ToolParam(description="任務 ID") Long taskId) { return service.summary(taskId); }
}

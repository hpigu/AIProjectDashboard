package dev.aiboard.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import dev.aiboard.mcp.ProjectBoardTools;
import dev.aiboard.mcp.RoleTools;
import dev.aiboard.mcp.TaskBlockTools;
import dev.aiboard.mcp.TaskCompleteTools;

@Configuration
public class McpToolConfig {

    /** 新增 @Tool 類別時務必一併加進來，否則工具不會出現在 MCP 端點上。 */
    @Bean
    public ToolCallbackProvider projectBoardToolCallbackProvider(ProjectBoardTools projectBoardTools,
                                                                  RoleTools roleTools,
                                                                  TaskBlockTools taskBlockTools,
                                                                  TaskCompleteTools taskCompleteTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(projectBoardTools, roleTools, taskBlockTools, taskCompleteTools)
                .build();
    }
}

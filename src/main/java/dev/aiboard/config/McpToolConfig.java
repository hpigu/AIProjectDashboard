package dev.aiboard.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import dev.aiboard.mcp.ProjectBoardTools;

@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider projectBoardToolCallbackProvider(ProjectBoardTools projectBoardTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(projectBoardTools)
                .build();
    }
}

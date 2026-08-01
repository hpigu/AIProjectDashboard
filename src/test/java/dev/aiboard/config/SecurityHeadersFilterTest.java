package dev.aiboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證安全 headers 套用在 UI 靜態資源、REST、SSE 與 /mcp 端點上，
 * 且不會讓 /mcp 因為 header 設定而失效（仍能收到回應，不是被過濾器擋掉）。
 *
 * 全程使用 MockMvc（而非真的開 socket/HttpClient 連線嵌入式 Tomcat）：
 * SSE 端點的 {@code SseEmitter(0L)} 不會逾時、/mcp 的 streamable transport
 * 也可能保持連線，若用真連線測試，測試結束後 Spring Boot 的 GracefulShutdown
 * 會等待這些「仍在進行中」的 async request 而卡住約 30 秒。MockMvc 不佔用
 * 真實網路連線與 Tomcat 執行緒，仍會完整跑過 filter chain
 * （含 {@link SecurityHeadersFilter}）與實際 controller/router，足以驗證
 * headers 存在且端點未被擋掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:security-headers-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-security-headers-test.log"
})
@AutoConfigureMockMvc
class SecurityHeadersFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexHtml_hasCspAndOtherSecurityHeaders() throws Exception {
        var result = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andReturn();

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("default-src 'self'");
        assertThat(csp).contains("script-src 'self' 'unsafe-eval'");
        assertThat(csp).doesNotContain("unsafe-inline");
        assertThat(csp).contains("connect-src 'self'");
        assertThat(csp).contains("frame-ancestors 'none'");
        assertThat(csp).contains("object-src 'none'");
    }

    @Test
    void restApi_hasSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void sseEndpoint_hasSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void mcpEndpoint_stillReachable_withSecurityHeadersPresent() throws Exception {
        String requestBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";

        // 不因 header 設定被擋：必須實際到達 MCP router（不是被過濾器短路成 404），
        // 是否回傳合法 MCP 回應由 spring-ai 自行處理，這裡只驗證未被我們的 header 設定攔下，
        // 且安全 headers 仍有附上。
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(404))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}

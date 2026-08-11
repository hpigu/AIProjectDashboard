package dev.aiboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 DNS rebinding 防護：非本機的 Host 或 Origin 一律 403，且真正的本機使用
 * 路徑（瀏覽器同源請求、curl、MCP client）完全不受影響。
 *
 * <p>沿用 {@link SecurityHeadersFilterTest} 的 MockMvc 策略，理由相同：SSE 與
 * /mcp 用真連線測試會讓 GracefulShutdown 卡住等待 async request。MockMvc 仍會
 * 完整跑過 filter chain，足以驗證擋與不擋。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:origin-guard-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-origin-guard-test.log"
})
@AutoConfigureMockMvc
class LocalOriginGuardFilterTest {

    private static final String MCP_INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loopbackHostWithoutOrigin_isAllowed() throws Exception {
        // 非瀏覽器 client（curl、MCP client）的典型形狀：有 Host、沒有 Origin。
        mockMvc.perform(get("/api/projects").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").header("Host", "localhost:8080"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").header("Host", "[::1]:8080"))
                .andExpect(status().isOk());
    }

    @Test
    void sameOriginBrowserRequest_isAllowed() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header("Host", "localhost:8080")
                        .header("Origin", "http://localhost:8080"))
                .andExpect(status().isOk());
    }

    @Test
    void rebindingGet_withAttackerHostAndNoOrigin_isRejected() throws Exception {
        // DNS rebinding 之後瀏覽器認定同源，GET 不會送 Origin —— 只剩 Host 能擋。
        mockMvc.perform(get("/api/projects").header("Host", "evil.example:8080"))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOriginPostToMcp_isRejectedBeforeReachingRouter() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("Host", "localhost:8080")
                        .header("Origin", "http://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE))
                .andExpect(status().isForbidden());
    }

    @Test
    void localMcpClientCall_isNotBlocked() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("Host", "127.0.0.1:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("本機 MCP client 不得被 guard 擋下")
                        .isNotEqualTo(403));
    }

    @Test
    void opaqueOrigin_isRejected() throws Exception {
        // sandboxed iframe / file:// 頁面會送 Origin: null，不可當成「沒有 Origin」。
        mockMvc.perform(get("/api/projects")
                        .header("Host", "localhost:8080")
                        .header("Origin", "null"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staticUi_isAlsoProtected() throws Exception {
        // rebinding 之後攻擊者頁面同樣讀得到 UI 與其中的資料流，靜態資源不能豁免。
        mockMvc.perform(get("/index.html").header("Host", "evil.example:8080"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectedResponse_stillCarriesSecurityHeaders_andDoesNotEchoAttackerInput() throws Exception {
        var result = mockMvc.perform(get("/api/projects").header("Host", "evil.example:8080"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("被拒絕的 Host 原文不得反射回呼叫方")
                .doesNotContain("evil.example")
                .contains("BOARD_ALLOWED_HOSTS");
    }

    @Test
    void requestWithoutHostHeader_fallsBackToContainerResolvedServerName() throws Exception {
        // HTTP/1.0 不帶 Host；容器解析出來的 serverName 才是判斷依據。瀏覽器一律
        // 送 Host，因此這條退路不會被 rebinding 利用。
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk());
    }

    /**
     * 白名單組裝屬於純字串處理，直接對 filter 實例驗證，不需要 Spring context。
     */
    @Test
    void loopbackAliasesAlwaysPresent_wildcardBindAddressAddsNothing() {
        var filter = new LocalOriginGuardFilter("0.0.0.0", "");

        assertThat(filter.allowedHosts())
                .contains("localhost", "127.0.0.1", "::1")
                .doesNotContain("0.0.0.0");
    }

    @Test
    void explicitBindAddressIsTrusted() {
        var filter = new LocalOriginGuardFilter("192.168.1.5", "");

        assertThat(filter.allowedHosts()).contains("192.168.1.5", "localhost");
    }

    @Test
    void configuredHostsAreNormalized() {
        var filter = new LocalOriginGuardFilter("127.0.0.1", " Board.Local:8080 ,, [fe80::1]:8080 ");

        assertThat(filter.allowedHosts()).contains("board.local", "fe80::1");
    }
}

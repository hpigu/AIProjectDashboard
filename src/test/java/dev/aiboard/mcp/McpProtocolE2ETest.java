package dev.aiboard.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 協定層的端到端測試（#25）。
 *
 * <p><b>為什麼需要這一層</b>：其餘的 MCP 測試（例如 {@link ProjectBoardToolsTest}）
 * 都是把 service mock 掉、直接呼叫 tool 物件的方法。那驗證的是 Java 方法的行為，
 * 完全不經過 agent 實際會走的路徑：
 * <ul>
 *   <li>{@code @Tool}／{@code @ToolParam} 產生的 JSON Schema——參數改名、必填與否
 *       改動，對 Java 呼叫端毫無影響，但會直接讓 agent 呼叫失敗</li>
 *   <li>JSON-RPC 封包與 Streamable HTTP 傳輸（session id、SSE 分幀）</li>
 *   <li>{@code initialize} 回應中的 serverInfo</li>
 *   <li>例外如何被轉成 agent 讀得到的錯誤</li>
 * </ul>
 * 這些全都可能改壞而測試依然全綠——{@code /mcp} 是 agent 看得到的唯一介面，
 * 卻是整個專案唯一沒有測試覆蓋的一層。
 *
 * <p>實際發生過的兩個例子，正是這一層該擋下來的：{@code claim_next_task} 的參數
 * 名為 {@code projectName}（不是直覺的 {@code projectRef}）；MCP {@code serverInfo.version}
 * 曾寫死在 {@code application.yml}，跳版後仍回報舊版號而沒有任何測試察覺。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-protocol-e2e;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-mcp-e2e-test.log",
        // 這個測試只驗協定層，不需要排程備份在背景寫檔。
        "board.backup.schedule.enabled=false"
})
class McpProtocolE2ETest {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    /**
     * 這份清單就是對 agent 的公開契約。少一個代表某個能力悄悄消失了，多一個代表
     * 有新工具沒有被記錄下來——兩種都應該是「刻意的決定」，而不是沒人注意到的副作用。
     */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "archive_project", "block_task", "claim_next_task", "complete_task",
            "create_project", "create_tasks", "get_role", "list_roles", "list_tasks",
            "preview_archive_project", "reset_task_claim", "restore_project",
            "set_task_dependencies", "update_task_details", "update_task_status", "upsert_role");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String sessionId;
    private final AtomicInteger requestId = new AtomicInteger(1);

    @BeforeEach
    void openSession() {
        sessionId = null;
        JsonNode initResult = rpc("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "e2e-test", "version", "1")));
        assertThat(initResult).isNotNull();
        notification("notifications/initialized");
    }

    @Test
    void initializeAnnouncesServerIdentityAndToolCapability() {
        sessionId = null;
        JsonNode result = rpc("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "e2e-test", "version", "1")));

        assertThat(result.path("protocolVersion").asText()).isEqualTo(PROTOCOL_VERSION);
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("project-board");
        assertThat(result.path("capabilities").has("tools")).isTrue();

        // serverInfo.version 曾經寫死在 application.yml，跳版到 3.1.0 之後仍回報
        // 3.0.0，而且一路綠燈。
        //
        // 這裡不寫死期望的版號（那只會變成每次跳版都要跟著改的樣板，而且它自己
        // 就是下一個會忘記更新的地方），而是要求 MCP 的 serverInfo.version 與
        // /api/health 的 version 一致——後者來自建置期產生的 build-info.properties。
        // 兩個來源必須指向同一個事實；只要有人再把版號寫死，這個斷言就會紅。
        String mcpVersion = result.path("serverInfo").path("version").asText();
        String healthVersion = readTree(restTemplate.getForObject(
                "http://localhost:" + port + "/api/health", String.class))
                .path("version").asText();

        assertThat(healthVersion)
                .as("/api/health 應回報實際版號（不是 unknown）")
                .matches("^\\d+\\.\\d+\\.\\d+.*");
        assertThat(mcpVersion)
                .as("MCP serverInfo.version 必須與 /api/health 的版號一致，兩者都應源自 pom 的 project.version")
                .isEqualTo(healthVersion);
    }

    @Test
    void toolsListExposesTheFullPublicToolContract() {
        JsonNode result = rpc("tools/list", Map.of());

        // 刻意逐一取頂層的 name 而不是用 findValuesAsText："name" 這個欄位名在
        // inputSchema 底下也存在（create_project 就有一個叫 name 的參數），
        // 遞迴搜尋會把參數名混進工具名單裡。
        List<String> names = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            names.add(tool.path("name").asText());
        }

        assertThat(names.stream().sorted().toList())
                .containsExactlyElementsOf(EXPECTED_TOOLS.stream().sorted().toList());
    }

    @Test
    void toolSchemasPinDownParameterNamesAgentsMustSend() {
        JsonNode tools = rpc("tools/list", Map.of()).path("tools");

        // claim_next_task 用 projectName，不是 projectRef。參數改名對 Java 呼叫端
        // 無感，但會讓每一個 agent 的呼叫直接失敗。
        JsonNode claim = toolNamed(tools, "claim_next_task");
        assertThat(propertyNames(claim)).contains("projectName", "category", "assignee");
        assertThat(requiredNames(claim))
                .containsExactlyInAnyOrder("projectName", "category", "assignee");

        // block_task 的 claimToken 刻意是選填（leader 也能代為標記），detail 則是
        // 必填——「說明目前實際在等什麼」是這個工具存在的意義。
        JsonNode block = toolNamed(tools, "block_task");
        assertThat(propertyNames(block))
                .contains("taskId", "claimToken", "reasonType", "detail", "blockingTaskIds");
        assertThat(requiredNames(block)).contains("taskId", "reasonType", "detail");
        assertThat(requiredNames(block)).doesNotContain("claimToken");

        // 每個工具都必須有非空 description：那是 agent 判斷「何時該呼叫我」的唯一依據。
        for (JsonNode tool : tools) {
            assertThat(tool.path("description").asText())
                    .as("tool %s 應有 description", tool.path("name").asText())
                    .isNotBlank();
        }
    }

    @Test
    void toolCallExecutesThroughTheWireAndReturnsReadableContent() {
        JsonNode created = callTool("create_project",
                Map.of("name", "協定層測試專案", "description", "由 E2E 測試建立"));

        assertThat(created.path("isError").asBoolean()).isFalse();
        assertThat(textContent(created)).contains("協定層測試專案");

        JsonNode listed = callTool("list_tasks", Map.of("projectName", "協定層測試專案"));
        assertThat(listed.path("isError").asBoolean()).isFalse();
        assertThat(textContent(listed)).isNotBlank();
    }

    @Test
    void businessRuleViolationsComeBackAsReadableToolErrorsNotTransportFailures() {
        // 不存在的專案。重點是 agent 拿得到「可讀的錯誤訊息」而不是 HTTP 500 或
        // 一個空回應——後者會讓 agent 無從判斷該怎麼修正。
        JsonNode result = callTool("claim_next_task", Map.of(
                "projectName", "這個專案不存在-" + System.nanoTime(),
                "category", "BACKEND",
                "assignee", "backend-dev"));

        assertThat(textContent(result))
                .as("錯誤內容應可讀，讓 agent 知道發生什麼事")
                .isNotBlank();
    }

    @Test
    void unknownToolIsRejectedRatherThanSilentlyIgnored() {
        JsonNode response = rpcRaw("tools/call", Map.of(
                "name", "no_such_tool", "arguments", Map.of()));

        boolean reportedAsRpcError = response.has("error");
        boolean reportedAsToolError = response.path("result").path("isError").asBoolean();
        assertThat(reportedAsRpcError || reportedAsToolError)
                .as("呼叫不存在的工具必須回報錯誤，回應為 %s", response)
                .isTrue();
    }

    @Test
    void requestWithoutSessionIsRejectedSoTransportStateIsNotOptional() {
        String savedSession = sessionId;
        sessionId = null;
        try {
            ResponseEntity<String> response = post(jsonRpc(
                    requestId.getAndIncrement(), "tools/list", Map.of()));
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("缺少 Mcp-Session-Id 的 tools/list 不應被當成正常請求處理，實際回應 %s",
                            response.getStatusCode())
                    .isFalse();
        } finally {
            sessionId = savedSession;
        }
    }

    // ---------------------------------------------------------------------
    // 極簡 MCP over Streamable HTTP 客戶端
    //
    // 刻意不引入 MCP client SDK：這個測試要驗的正是「線上實際傳了什麼」，
    // 用官方 client 會把 schema 與分幀細節一起抽象掉，等於用被測物件驗自己。
    // ---------------------------------------------------------------------

    private JsonNode rpc(String method, Object params) {
        JsonNode response = rpcRaw(method, params);
        assertThat(response.has("error"))
                .as("%s 不應回傳 JSON-RPC error：%s", method, response.path("error"))
                .isFalse();
        return response.path("result");
    }

    private JsonNode rpcRaw(String method, Object params) {
        ResponseEntity<String> response = post(jsonRpc(requestId.getAndIncrement(), method, params));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s 的 HTTP 狀態應為 2xx，實際 %s，body=%s",
                        method, response.getStatusCode(), response.getBody())
                .isTrue();

        String returnedSession = response.getHeaders().getFirst("Mcp-Session-Id");
        if (returnedSession != null && !returnedSession.isBlank()) {
            sessionId = returnedSession;
        }
        return parseBody(response.getBody());
    }

    private void notification(String method) {
        post(Map.of("jsonrpc", "2.0", "method", method));
    }

    private JsonNode callTool(String name, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", name, "arguments", arguments));
    }

    private Map<String, Object> jsonRpc(int id, String method, Object params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private ResponseEntity<String> post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Streamable HTTP 要求客戶端同時接受兩種型別：伺服器可以回單一 JSON，
        // 也可以改用 SSE 串流回應，客戶端不能假設是哪一種。
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        try {
            return restTemplate.exchange("http://localhost:" + port + "/mcp",
                    HttpMethod.POST, new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
        } catch (Exception ex) {
            throw new IllegalStateException("送出 MCP 請求失敗", ex);
        }
    }

    /**
     * 回應可能是單一 JSON 物件，也可能是 SSE 分幀（{@code event: message} 後接
     * 一行 {@code data: {...}}）。兩種都要能解析——這正是真實 client 必須處理的事。
     */
    private JsonNode parseBody(String body) {
        assertThat(body).as("MCP 回應不應為空").isNotNull();
        String payload = body.strip();
        if (payload.startsWith("{")) {
            return readTree(payload);
        }
        String data = payload.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).strip())
                .reduce("", String::concat);
        assertThat(data).as("SSE 回應中找不到 data 欄位：%s", body).isNotBlank();
        return readTree(data);
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("無法解析 MCP 回應：" + json, ex);
        }
    }

    private JsonNode toolNamed(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("tools/list 中找不到工具：" + name);
    }

    private List<String> propertyNames(JsonNode tool) {
        List<String> names = new ArrayList<>();
        tool.path("inputSchema").path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<String> requiredNames(JsonNode tool) {
        return toTextList(tool.path("inputSchema").path("required"));
    }

    private List<String> toTextList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            values.add(item.asText());
        }
        return values;
    }

    private String textContent(JsonNode toolResult) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : toolResult.path("content")) {
            sb.append(item.path("text").asText());
        }
        if (sb.isEmpty() && toolResult.has("error")) {
            sb.append(toolResult.path("error").toString());
        }
        return sb.toString();
    }
}

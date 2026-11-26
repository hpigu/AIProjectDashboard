package dev.aiboard.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class ManagementAuthorizationFilterTest {
    static final String ADMIN="isolated-management-secret-at-least-32";
    record Result(boolean reached, int status, String body) {}
    Result call(String configured, String header, String body, String path) throws Exception {
        var request=new MockHttpServletRequest("POST",path);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        if(header!=null) request.addHeader("Authorization",header);
        var response=new MockHttpServletResponse();
        var reached=new AtomicBoolean(); var replay=new StringBuilder();
        new ManagementAuthorizationFilter(configured).doFilter(request,response,(req,res) -> {
            reached.set(true); replay.append(new String(req.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
            // A downstream parser must be able to obtain the same buffered body again.
            assertThat(((HttpServletRequest)req).getReader().readLine()).isEqualTo(body);
        });
        return new Result(reached.get(),response.getStatus(),replay.toString());
    }
    String tool(String name) { return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""+name+"\",\"arguments\":{}}}"; }
    @Test void missingWrongAndWorkerTokensNeverGrantManagementRights() throws Exception {
        for(String tool:new String[]{"create_project","create_tasks","reset_task_claim","update_task_details","set_task_dependencies","preview_archive_project","archive_project","restore_project","upsert_role","prepare_project_workflow"}) {
            for(String token:new String[]{"", "Bearer wrong", "Bearer worker-claim-token"}) {
                var result=call(ADMIN,token,tool(tool),"/mcp");
                assertThat(result.reached()).isFalse(); assertThat(result.status()).isEqualTo(403);
            }
            assertThat(call(ADMIN,"Bearer "+ADMIN,tool(tool),"/mcp").reached()).isTrue();
        }
    }
    @Test void absentOrTooShortServerSecretFailsClosedEvenWithMatchingHeader() throws Exception {
        assertThat(call("",null,tool("create_tasks"),"/mcp").status()).isEqualTo(403);
        assertThat(call("short","Bearer short",tool("create_tasks"),"/mcp").status()).isEqualTo(403);
    }
    @Test void validCredentialReplaysExactBodyOnBothMcpPaths() throws Exception {
        String original=tool("create_project");
        for(String path:new String[]{"/mcp","/mcp/"}) {
            var result=call(ADMIN,"Bearer "+ADMIN,original,path);
            assertThat(result.reached()).isTrue(); assertThat(result.body()).isEqualTo(original);
        }
    }
    @Test void readAndLifecycleCallsDoNotRequireManagementCredential() throws Exception {
        for(String name:new String[]{"get_role","list_tasks","get_acceptance","claim_next_task","block_task","report_implementation","complete_task","update_task_status","record_acceptance_event"}) {
            var result=call("",null,tool(name),"/mcp");
            assertThat(result.reached()).isTrue(); assertThat(result.body()).isEqualTo(tool(name));
        }
        assertThat(call("",null,"{\"method\":\"tools/list\"}","/mcp").reached()).isTrue();
    }
    @Test void oversizedMalformedAndBatchRequestsAreRejectedBeforeController() throws Exception {
        assertThat(call(ADMIN,"Bearer "+ADMIN,"x".repeat(65537),"/mcp").status()).isEqualTo(413);
        assertThat(call(ADMIN,"Bearer "+ADMIN,"not json","/mcp").status()).isEqualTo(400);
        assertThat(call(ADMIN,"Bearer "+ADMIN,"["+tool("create_project")+"]","/mcp").status()).isEqualTo(400);
    }
}

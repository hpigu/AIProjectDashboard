package dev.aiboard.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Independent leader transport credential; worker claim tokens grant no management rights. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ManagementAuthorizationFilter extends OncePerRequestFilter {
    private static final Set<String> MANAGEMENT = Set.of("create_project","create_tasks","reset_task_claim",
            "update_task_details","set_task_dependencies","preview_archive_project","archive_project","restore_project","upsert_role","prepare_project_workflow");
    private final String token;
    private dev.aiboard.acceptance.ManagementAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureAudit(dev.aiboard.acceptance.ManagementAuditService audit) { this.audit=audit; }
    private final JsonMapper json=JsonMapper.builder().build();
    public ManagementAuthorizationFilter(@Value("${BOARD_MANAGEMENT_TOKEN:}") String token) { this.token=token; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if(!request.getMethod().equals("POST")) { chain.doFilter(request,response); return; }
        byte[] bytes=request.getInputStream().readNBytes(65537);
        if(bytes.length>65536) { response.sendError(413); return; }
        try {
            var envelope=json.readTree(bytes);
            if(envelope.isArray()) { response.sendError(400); return; }
            if(envelope.path("method").asText().equals("tools/call") && MANAGEMENT.contains(envelope.path("params").path("name").asText())) {
                String header=request.getHeader("Authorization");
                boolean authorized=token.length()>=32 && header!=null && MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8));
                if(audit!=null) audit.record(envelope.path("params").path("name").asText(),authorized);
                if(!authorized) {
                    response.sendError(403,"Management credential required"); return;
                }
                request.setAttribute("board.managementActor", "configured-management-credential");
            }
        } catch (RuntimeException e) { response.sendError(400); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public int read(byte[] b,int off,int len) { return input.read(b,off,len); }
                    public boolean isFinished() { return input.available()==0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous MCP request body"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
        },response);
    }
}

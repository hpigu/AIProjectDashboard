package dev.aiboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Optional cloud routing boundary. Authentication MUST be enforced by cloudflared's
 * Access JWT validation on both hostnames; header presence is never authentication.
 * Browser credentials cannot reach MCP through the read-only web hostname.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class CloudRouteGuardFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final String webHost;
    private final String mcpHost;

    public CloudRouteGuardFilter(
            @Value("${board.cloud.enabled:false}") boolean enabled,
            @Value("${board.cloud.web-host:}") String webHost,
            @Value("${board.cloud.mcp-host:}") String mcpHost,
            @Value("${server.address:127.0.0.1}") String address) {
        this.enabled = enabled;
        this.webHost = webHost.toLowerCase(Locale.ROOT);
        this.mcpHost = mcpHost.toLowerCase(Locale.ROOT);
        if (enabled && (!"127.0.0.1".equals(address)
                || !validHostname(this.webHost) || !validHostname(this.mcpHost)
                || this.webHost.equals(this.mcpHost))) {
            throw new IllegalArgumentException("Cloud mode requires loopback binding and two distinct DNS hostnames");
        }
    }

    private static boolean validHostname(String host) {
        return host.length() <= 253 && host.contains(".")
                && host.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                && !host.matches("[0-9.]+") && !host.endsWith(".localhost");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String host = request.getHeader("Host");
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean read = Set.of("GET", "HEAD").contains(method);
        boolean allowed = false;
        // Reject ambiguous paths before servlet routing can interpret them differently.
        if (path != null && !path.matches(".*[%\\\\;].*") && !path.contains("//")
                && !path.matches(".*(?:^|/)\\.\\.?(?:/|$).*")) {
            if (host.equals(mcpHost) || host.equals(mcpHost + ":443")) {
                allowed = path.equals("/mcp") && Set.of("GET", "POST", "DELETE").contains(method)
                        && request.getHeader("Origin") == null;
            } else if (host.equals(webHost) || host.equals(webHost + ":443")) {
                allowed = read && !path.equals("/mcp") && !path.startsWith("/mcp/");
            } else if (host.equals("127.0.0.1") || host.startsWith("127.0.0.1:")) {
                allowed = read && Set.of("/api/health/live", "/api/health/ready").contains(path);
            }
        }
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"forbidden_cloud_route\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

package dev.aiboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudRouteGuardFilterTest {
    private final CloudRouteGuardFilter cloud = new CloudRouteGuardFilter(
            true, "board.example.com", "mcp.example.com", "127.0.0.1");

    private boolean passes(CloudRouteGuardFilter filter, String host, String method, String path,
                           String origin) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Host", host);
        if (origin != null) request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> passed.set(true));
        if (!passed.get()) assertThat(response.getStatus()).isEqualTo(403);
        return passed.get();
    }

    @Test
    void browserHostCannotReachMcpEvenWithBrowserCredentials() throws Exception {
        for (String method : new String[]{"GET", "POST", "DELETE", "HEAD"}) {
            assertThat(passes(cloud, "board.example.com", method, "/mcp", null)).isFalse();
        }
        assertThat(passes(cloud, "board.example.com", "GET", "/mcp/anything", null)).isFalse();
        assertThat(passes(cloud, "board.example.com", "GET", "/api/projects", null)).isTrue();
        assertThat(passes(cloud, "board.example.com", "GET", "/api/events", null)).isTrue();
        assertThat(passes(cloud, "board.example.com", "POST", "/api/projects", null)).isFalse();
    }

    @Test
    void machineHostIsOnlyMcpAndRejectsBrowserOrigins() throws Exception {
        for (String method : new String[]{"GET", "POST", "DELETE"}) {
            assertThat(passes(cloud, "mcp.example.com", method, "/mcp", null)).isTrue();
        }
        assertThat(passes(cloud, "mcp.example.com", "POST", "/mcp", "https://board.example.com")).isFalse();
        assertThat(passes(cloud, "mcp.example.com", "GET", "/api/projects", null)).isFalse();
        assertThat(passes(cloud, "mcp.example.com", "GET", "/", null)).isFalse();
        assertThat(passes(cloud, "mcp.example.com", "POST", "/mcp/", null)).isFalse();
    }

    @Test
    void ambiguousPathsAndUnknownHostsFailClosed() throws Exception {
        for (String path : new String[]{"//mcp", "/x/../mcp", "/./mcp", "/%6dcp", "/mcp;x", "/x\\mcp"}) {
            assertThat(passes(cloud, "board.example.com", "GET", path, null)).isFalse();
        }
        assertThat(passes(cloud, "attacker.example.com", "POST", "/mcp", null)).isFalse();
        assertThat(passes(cloud, "127.0.0.1:18081", "POST", "/mcp", null)).isFalse();
        assertThat(passes(cloud, "127.0.0.1:18081", "GET", "/api/health/ready", null)).isTrue();
    }

    @Test
    void cloudConfigurationFailsClosedAndLocalDefaultRemainsCompatible() throws Exception {
        assertThatThrownBy(() -> new CloudRouteGuardFilter(true, "", "", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudRouteGuardFilter(true, "board.example.com", "mcp.example.com", "0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudRouteGuardFilter(true, "board.example.com", "board.example.com", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(passes(new CloudRouteGuardFilter(false, "", "", "127.0.0.1"),
                "127.0.0.1:8081", "POST", "/mcp", null)).isTrue();
    }
}

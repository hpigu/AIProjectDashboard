package dev.aiboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 BOARD_ALLOWED_HOSTS 這條逃生門確實可用：刻意要開放的來源加得進去，
 * 沒列出的來源仍然被擋。獨立成一個測試類別是因為它需要不同的
 * {@code board.security.allowed-hosts} 屬性，也就是不同的 Spring context。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:origin-guard-allowlist-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-origin-guard-allowlist-test.log",
        "board.security.allowed-hosts=192.168.1.20:8080, board.local"
})
@AutoConfigureMockMvc
class LocalOriginGuardAllowlistTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void configuredHosts_areAllowed_portInConfigIsIgnored() throws Exception {
        mockMvc.perform(get("/api/projects").header("Host", "192.168.1.20:8080"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").header("Host", "board.local"))
                .andExpect(status().isOk());
    }

    @Test
    void configuredHost_alsoAcceptedAsOrigin() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header("Host", "board.local")
                        .header("Origin", "http://board.local"))
                .andExpect(status().isOk());
    }

    @Test
    void unlistedHost_isStillRejected() throws Exception {
        mockMvc.perform(get("/api/projects").header("Host", "evil.example"))
                .andExpect(status().isForbidden());
    }
}

package dev.aiboard.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectListController.class)
class ProjectListControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectQuery projectQuery;

    @Test
    void listProjects_returnsCountsWithAllFourKeysPresent() throws Exception {
        Map<String, Long> counts = Map.of("TODO", 4L, "IN_PROGRESS", 1L, "BLOCKED", 0L, "DONE", 3L);
        var summary = new ProjectQuery.ProjectSummary(12L, "個人記帳 App", "ACTIVE", counts, 8L, false,
                LocalDateTime.of(2026, 7, 23, 14, 2));
        when(projectQuery.listSummaries(false)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].counts.TODO").value(4))
                .andExpect(jsonPath("$[0].counts.IN_PROGRESS").value(1))
                .andExpect(jsonPath("$[0].counts.BLOCKED").value(0))
                .andExpect(jsonPath("$[0].counts.DONE").value(3))
                .andExpect(jsonPath("$[0].total").value(8))
                .andExpect(jsonPath("$[0].hasBlocked").value(false));
    }

    @Test
    void listProjects_blockedProjectSortedFirst() throws Exception {
        var blocked = new ProjectQuery.ProjectSummary(2L, "SMTP 監控工具", "ACTIVE",
                Map.of("TODO", 8L, "IN_PROGRESS", 0L, "BLOCKED", 1L, "DONE", 0L), 9L, true,
                LocalDateTime.of(2026, 7, 23, 10, 0));
        var normal = new ProjectQuery.ProjectSummary(1L, "個人記帳 App", "ACTIVE",
                Map.of("TODO", 4L, "IN_PROGRESS", 1L, "BLOCKED", 0L, "DONE", 3L), 8L, false,
                LocalDateTime.of(2026, 7, 23, 12, 0));
        when(projectQuery.listSummaries(false)).thenReturn(List.of(blocked, normal));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].hasBlocked").value(true));
    }

    @Test
    void listProjects_withIncludeArchivedParam_passesThrough() throws Exception {
        when(projectQuery.listSummaries(true)).thenReturn(List.of());

        mockMvc.perform(get("/api/projects").param("includeArchived", "true"))
                .andExpect(status().isOk());
    }
}

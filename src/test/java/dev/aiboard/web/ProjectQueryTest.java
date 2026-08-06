package dev.aiboard.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.aiboard.project.Project;
import dev.aiboard.project.ProjectRepository;
import dev.aiboard.task.TaskRepository;
import dev.aiboard.task.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectQueryTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    private ProjectQuery projectQuery;

    @BeforeEach
    void setUp() {
        projectQuery = new ProjectQuery(projectRepository, taskRepository);
    }

    @Test
    void listSummaries_aggregatesCountsWithoutNPlusOneQueries() {
        Project p1 = new Project("個人記帳 App", null);
        Project p2 = new Project("SMTP 監控工具", null);
        setId(p1, 1L);
        setId(p2, 2L);
        when(projectRepository.findAll()).thenReturn(List.of(p1, p2));

        when(taskRepository.countAllGroupedByProjectAndStatus()).thenReturn(List.of(
                statusCount(1L, TaskStatus.TODO, 4), statusCount(1L, TaskStatus.IN_PROGRESS, 1), statusCount(1L, TaskStatus.DONE, 3),
                statusCount(2L, TaskStatus.TODO, 8), statusCount(2L, TaskStatus.BLOCKED, 1)
        ));
        when(taskRepository.findLastActivityPerProject()).thenReturn(List.of(
                lastActivity(1L, LocalDateTime.of(2026, 7, 23, 10, 0)),
                lastActivity(2L, LocalDateTime.of(2026, 7, 23, 12, 0))
        ));

        List<ProjectQuery.ProjectSummary> summaries = projectQuery.listSummaries(false);

        // 這兩個聚合查詢各只呼叫一次，證明沒有逐專案 N+1 查詢
        verify(taskRepository, times(1)).countAllGroupedByProjectAndStatus();
        verify(taskRepository, times(1)).findLastActivityPerProject();

        assertThat(summaries).hasSize(2);

        ProjectQuery.ProjectSummary smtp = summaries.stream().filter(s -> s.id() == 2L).findFirst().orElseThrow();
        assertThat(smtp.hasBlocked()).isTrue();
        assertThat(smtp.counts()).containsEntry("BLOCKED", 1L).containsEntry("TODO", 8L);
        assertThat(smtp.total()).isEqualTo(9L);

        ProjectQuery.ProjectSummary board = summaries.stream().filter(s -> s.id() == 1L).findFirst().orElseThrow();
        assertThat(board.hasBlocked()).isFalse();
        assertThat(board.counts()).containsEntry("TODO", 4L).containsEntry("IN_PROGRESS", 1L)
                .containsEntry("BLOCKED", 0L).containsEntry("DONE", 3L);
        assertThat(board.total()).isEqualTo(8L);

        // hasBlocked 的排最前
        assertThat(summaries.get(0).id()).isEqualTo(2L);
    }

    @Test
    void listSummaries_projectWithNoTasks_countsAreAllZero() {
        Project p1 = new Project("新專案", null);
        setId(p1, 3L);
        when(projectRepository.findAll()).thenReturn(List.of(p1));
        when(taskRepository.countAllGroupedByProjectAndStatus()).thenReturn(List.of());
        when(taskRepository.findLastActivityPerProject()).thenReturn(List.of());

        List<ProjectQuery.ProjectSummary> summaries = projectQuery.listSummaries(false);

        assertThat(summaries).hasSize(1);
        ProjectQuery.ProjectSummary summary = summaries.get(0);
        assertThat(summary.counts()).containsEntry("TODO", 0L).containsEntry("IN_PROGRESS", 0L)
                .containsEntry("BLOCKED", 0L).containsEntry("DONE", 0L);
        assertThat(summary.total()).isEqualTo(0L);
        assertThat(summary.hasBlocked()).isFalse();
    }

    @Test
    void listSummaries_excludesArchivedByDefault() {
        Project active = new Project("進行中專案", null);
        setId(active, 1L);
        Project archived = archivedProject("封存專案", 2L);
        when(projectRepository.findAll()).thenReturn(List.of(active, archived));
        when(taskRepository.countAllGroupedByProjectAndStatus()).thenReturn(List.of());
        when(taskRepository.findLastActivityPerProject()).thenReturn(List.of());

        List<ProjectQuery.ProjectSummary> summaries = projectQuery.listSummaries(false);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).id()).isEqualTo(1L);
    }

    @Test
    void listSummaries_includeArchivedTrue_includesBoth() {
        Project active = new Project("進行中專案", null);
        setId(active, 1L);
        Project archived = archivedProject("封存專案", 2L);
        when(projectRepository.findAll()).thenReturn(List.of(active, archived));
        when(taskRepository.countAllGroupedByProjectAndStatus()).thenReturn(List.of());
        when(taskRepository.findLastActivityPerProject()).thenReturn(List.of());

        List<ProjectQuery.ProjectSummary> summaries = projectQuery.listSummaries(true);

        assertThat(summaries).hasSize(2);
    }

    @Test
    void listSummaries_prefixFilterEscapesLikeMetacharactersAndUsesOneBatchAggregate() {
        Project matching = new Project("A_B release", null);
        setId(matching, 8L);
        when(projectRepository.findByNormalizedNamePrefixAndStatus("a\\_b", "ACTIVE"))
                .thenReturn(List.of(matching));
        when(taskRepository.countGroupedByProjectIdsAndStatus(List.of(8L))).thenReturn(List.of());

        List<ProjectQuery.ProjectSummary> summaries = projectQuery.listSummaries(
                " A_B ", "ACTIVE", null, "NAME");

        assertThat(summaries).extracting(ProjectQuery.ProjectSummary::id).containsExactly(8L);
        verify(projectRepository).findByNormalizedNamePrefixAndStatus("a\\_b", "ACTIVE");
        verify(taskRepository, times(1)).countGroupedByProjectIdsAndStatus(List.of(8L));
    }

    private static void setId(Project project, Long id) {
        try {
            var field = Project.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(project, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Project archivedProject(String name, Long id) {
        Project project = new Project(name, null);
        setId(project, id);
        try {
            var field = Project.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(project, "ARCHIVED");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return project;
    }

    private static TaskRepository.StatusCountProjection statusCount(Long projectId, TaskStatus status, long cnt) {
        return new TaskRepository.StatusCountProjection() {
            public Long getProjectId() { return projectId; }
            public TaskStatus getStatus() { return status; }
            public long getCnt() { return cnt; }
        };
    }

    private static TaskRepository.LastActivityProjection lastActivity(Long projectId, LocalDateTime at) {
        return new TaskRepository.LastActivityProjection() {
            public Long getProjectId() { return projectId; }
            public LocalDateTime getLastActivity() { return at; }
        };
    }
}

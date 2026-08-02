package dev.aiboard.task;

import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependencyGraphServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskDependencyRepository dependencyRepository;

    @Mock
    private ProjectService projectService;

    private DependencyGraphService service;

    @BeforeEach
    void setUp() {
        service = new DependencyGraphService(taskRepository, dependencyRepository, projectService);
    }

    @Test
    void getDependencyGraph_returnsClaimabilityFromTheSamePrerequisiteRuleAsClaiming() {
        Long projectId = 12L;
        Task done = task(5L, "完成 schema", "DONE", "BACKEND", "backend-dev");
        Task ready = task(6L, "實作 API", "TODO", "BACKEND", null);
        Task waiting = task(7L, "實作頁面", "TODO", "FRONTEND", null);
        Task inProgress = task(8L, "測試 API", "IN_PROGRESS", "TEST", "qa");
        when(taskRepository.findByProjectIdOrderBySortOrderAsc(projectId))
                .thenReturn(List.of(done, ready, waiting, inProgress));
        when(dependencyRepository.findByProjectId(projectId)).thenReturn(List.of(
                new TaskDependency(6L, 5L), new TaskDependency(7L, 6L)));

        DependencyGraphService.DependencyGraph graph = service.getDependencyGraph(projectId);

        assertThat(graph.nodes()).extracting(DependencyGraphService.DependencyNode::id,
                        DependencyGraphService.DependencyNode::claimable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(5L, false),
                        org.assertj.core.groups.Tuple.tuple(6L, true),
                        org.assertj.core.groups.Tuple.tuple(7L, false),
                        org.assertj.core.groups.Tuple.tuple(8L, false));
        assertThat(graph.edges()).containsExactly(
                new DependencyGraphService.DependencyEdge(5L, 6L),
                new DependencyGraphService.DependencyEdge(6L, 7L));
        verify(projectService).assertExists(projectId);
        verify(taskRepository).findByProjectIdOrderBySortOrderAsc(projectId);
        verify(dependencyRepository).findByProjectId(projectId);
        verifyNoMoreInteractions(taskRepository, dependencyRepository);
    }

    @Test
    void getDependencyGraph_whenThereAreNoDependencies_returnsAllNodesAndNoEdges() {
        Long projectId = 12L;
        Task task = task(5L, "獨立任務", "TODO", "BACKEND", null);
        when(taskRepository.findByProjectIdOrderBySortOrderAsc(projectId)).thenReturn(List.of(task));
        when(dependencyRepository.findByProjectId(projectId)).thenReturn(List.of());

        DependencyGraphService.DependencyGraph graph = service.getDependencyGraph(projectId);

        assertThat(graph.nodes()).containsExactly(new DependencyGraphService.DependencyNode(
                5L, "獨立任務", "BACKEND", "TODO", null, true));
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void getDependencyGraph_whenStoredDependenciesFormACycle_returnsDiagnosticError() {
        Long projectId = 12L;
        Task first = mock(Task.class);
        Task second = mock(Task.class);
        when(first.getId()).thenReturn(5L);
        when(second.getId()).thenReturn(6L);
        when(taskRepository.findByProjectIdOrderBySortOrderAsc(projectId)).thenReturn(List.of(first, second));
        when(dependencyRepository.findByProjectId(projectId)).thenReturn(List.of(
                new TaskDependency(5L, 6L), new TaskDependency(6L, 5L)));

        assertThatThrownBy(() -> service.getDependencyGraph(projectId))
                .isInstanceOf(DependencyCycleException.class)
                .hasMessageContaining("循環")
                .hasMessageContaining("#5")
                .hasMessageContaining("#6");
    }

    private static Task task(Long id, String title, String status, String category, String assignee) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getTitle()).thenReturn(title);
        when(task.getStatus()).thenReturn(status);
        when(task.getCategory()).thenReturn(category);
        when(task.getAssignee()).thenReturn(assignee);
        return task;
    }
}

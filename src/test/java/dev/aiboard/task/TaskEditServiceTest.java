package dev.aiboard.task;

import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/** Contract tests for leader-only task editing. */
@ExtendWith(MockitoExtension.class)
class TaskEditServiceTest {

    @Mock private TaskRepository taskRepository;
    @Mock private TaskDependencyRepository dependencyRepository;
    @Mock private TaskLogRepository taskLogRepository;
    @Mock private ProjectService projectService;
    @Mock private BoardEventPublisher eventPublisher;

    private TaskEditService service;

    @BeforeEach
    void setUp() {
        service = new TaskEditService(taskRepository, dependencyRepository, taskLogRepository,
                projectService, eventPublisher);
    }

    @Test
    void updateTaskDetails_rejectsInProgressTaskEvenWhenVersionMatches() {
        Task task = task(20L, 7L, TaskStatus.IN_PROGRESS);
        when(taskRepository.findById(20L)).thenReturn(Optional.of(task));
        when(projectService.getById(7L)).thenReturn(activeProject(7L));

        assertThatThrownBy(() -> service.updateTaskDetails(20L, "new title", null, null, 0L))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("IN_PROGRESS")
                .hasMessageContaining("TODO/BLOCKED");

        verify(taskLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(taskRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setTaskDependencies_rejectsIndirectCycleBeforeReplacingExistingEdges() {
        Task target = task(3L, 7L, TaskStatus.TODO);
        Task first = task(1L, 7L, TaskStatus.TODO);
        Task second = task(2L, 7L, TaskStatus.TODO);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(target));
        when(projectService.getById(7L)).thenReturn(activeProject(7L));
        when(taskRepository.findAllById(any())).thenReturn(List.of(first));
        when(taskRepository.findByProjectIdOrderBySortOrderAsc(7L))
                .thenReturn(List.of(first, second, target));
        // Existing graph: #1 -> #2 -> #3. Adding #3 -> #1 must be rejected.
        when(dependencyRepository.findByTaskIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(
                new TaskDependency(1L, 2L), new TaskDependency(2L, 3L)));

        assertThatThrownBy(() -> service.setTaskDependencies(3L, List.of(1L), 0L))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("循環相依")
                .hasMessageContaining("#1")
                .hasMessageContaining("#3");

        verify(dependencyRepository, never()).deleteByTaskId(3L);
        verify(taskLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static Task task(Long id, Long projectId, TaskStatus status) {
        Task task = new Task(projectId, "task-" + id, null, "BACKEND", id.intValue());
        set(task, "id", id);
        set(task, "status", status);
        set(task, "version", 0L);
        set(task, "claimedAt", LocalDateTime.now());
        return task;
    }

    private static ProjectService.ProjectDto activeProject(Long id) {
        return new ProjectService.ProjectDto(id, "project", null, "ACTIVE");
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}

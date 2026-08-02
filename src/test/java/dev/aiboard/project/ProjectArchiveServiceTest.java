package dev.aiboard.project;

import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectArchiveServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private ProjectAuditRepository auditRepository;
    @Mock private BoardEventPublisher eventPublisher;

    private ProjectArchiveService service;

    @BeforeEach
    void setUp() {
        service = new ProjectArchiveService(projectRepository, taskRepository, auditRepository, eventPublisher);
    }

    @Test
    void archive_requiresRenewedConfirmationWhenLockedSnapshotHasInProgressTasks() {
        Project project = project(7L, "archive race");
        when(projectRepository.findByNormalizedName("archive race")).thenReturn(Optional.of(project));
        when(projectRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(project));
        when(taskRepository.countByProjectIdAndStatus(7L, "TODO")).thenReturn(0L);
        when(taskRepository.countByProjectIdAndStatus(7L, "IN_PROGRESS")).thenReturn(1L);
        when(taskRepository.countByProjectIdAndStatus(7L, "BLOCKED")).thenReturn(0L);
        when(taskRepository.countByProjectIdAndStatus(7L, "DONE")).thenReturn(2L);

        assertThatThrownBy(() -> service.archive("archive race", "user approved", false))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("IN_PROGRESS")
                .hasMessageContaining("再次明確確認");

        assertThat(project.getStatus()).isEqualTo("ACTIVE");
        verify(auditRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void archive_writesOneAuditFromThePostLockStatusSnapshot() {
        Project project = project(7L, "archive audit");
        when(projectRepository.findByNormalizedName("archive audit")).thenReturn(Optional.of(project));
        when(projectRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(project));
        when(taskRepository.countByProjectIdAndStatus(7L, "TODO")).thenReturn(1L);
        when(taskRepository.countByProjectIdAndStatus(7L, "IN_PROGRESS")).thenReturn(0L);
        when(taskRepository.countByProjectIdAndStatus(7L, "BLOCKED")).thenReturn(2L);
        when(taskRepository.countByProjectIdAndStatus(7L, "DONE")).thenReturn(3L);
        when(auditRepository.save(any(ProjectAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectArchiveService.ArchiveResult result = service.archive("archive audit", "batch completed", false);

        assertThat(result.project().status()).isEqualTo("ARCHIVED");
        ArgumentCaptor<ProjectAudit> audit = ArgumentCaptor.forClass(ProjectAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getProjectId()).isEqualTo(7L);
        assertThat(result.counts().todo()).isEqualTo(1L);
        assertThat(result.counts().blocked()).isEqualTo(2L);
        assertThat(result.counts().done()).isEqualTo(3L);
        verify(projectRepository).findByIdForUpdate(7L);
    }

    private static Project project(Long id, String name) {
        Project project = new Project(name, null);
        try {
            Field field = Project.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(project, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        return project;
    }
}

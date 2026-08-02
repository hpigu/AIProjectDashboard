package dev.aiboard.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ensures task detail reads stay bounded as histories and dependency lists grow. */
@ExtendWith(MockitoExtension.class)
class TaskDetailServiceTest {

    @Mock private TaskRepository taskRepository;
    @Mock private TaskDependencyRepository dependencyRepository;
    @Mock private TaskLogRepository taskLogRepository;
    @Mock private TaskBlockEventRepository blockEventRepository;
    @Mock private TaskCompletionEvidenceRepository evidenceRepository;

    private TaskDetailService service;

    @BeforeEach
    void setUp() {
        service = new TaskDetailService(taskRepository, dependencyRepository, taskLogRepository,
                blockEventRepository, evidenceRepository);
    }

    @Test
    void getTaskDetail_usesFixedQueryCountForLargeHistoryAndDependencySets() {
        long taskId = 99L;
        when(taskRepository.findDetailByProjectIdAndId(7L, taskId))
                .thenReturn(Optional.of(detail(taskId)));
        List<TaskDependencyRepository.RelatedTaskProjection> related = IntStream.range(0, 400)
                .mapToObj(index -> related((long) index + 1)).toList();
        when(dependencyRepository.findPrerequisites(taskId)).thenReturn(related);
        when(dependencyRepository.findDownstreamTasks(taskId)).thenReturn(related);
        List<TaskLogRepository.TaskHistoryProjection> history = IntStream.range(0, 1_000)
                .mapToObj(index -> history((long) index + 1)).toList();
        when(taskLogRepository.findHistoryProjection(taskId)).thenReturn(history);
        when(blockEventRepository.findCurrentBlocker(null)).thenReturn(List.of());
        when(evidenceRepository.findCurrentEvidence(taskId)).thenReturn(List.of());

        TaskDetailService.TaskDetail result = service.getTaskDetail(7L, taskId);

        assertThat(result.prerequisites()).hasSize(400);
        assertThat(result.downstreamTasks()).hasSize(400);
        assertThat(result.history()).hasSize(1_000);
        verify(taskRepository, times(1)).findDetailByProjectIdAndId(7L, taskId);
        verify(dependencyRepository, times(1)).findPrerequisites(taskId);
        verify(dependencyRepository, times(1)).findDownstreamTasks(taskId);
        verify(taskLogRepository, times(1)).findHistoryProjection(taskId);
        verify(blockEventRepository, times(1)).findCurrentBlocker(null);
        verify(evidenceRepository, times(1)).findCurrentEvidence(taskId);
    }

    private static TaskRepository.TaskDetailProjection detail(long id) {
        return new TaskRepository.TaskDetailProjection() {
            public Long getId() { return id; }
            public Long getProjectId() { return 7L; }
            public String getTitle() { return "large task"; }
            public String getDescription() { return null; }
            public String getStatus() { return "TODO"; }
            public String getCategory() { return "TEST"; }
            public Integer getSortOrder() { return 0; }
            public String getAssignee() { return null; }
            public LocalDateTime getClaimedAt() { return null; }
            public LocalDateTime getCreatedAt() { return LocalDateTime.now(); }
            public LocalDateTime getUpdatedAt() { return LocalDateTime.now(); }
            public Long getVersion() { return 0L; }
            public Long getCurrentBlockEventId() { return null; }
        };
    }

    private static TaskDependencyRepository.RelatedTaskProjection related(long id) {
        return new TaskDependencyRepository.RelatedTaskProjection() {
            public Long getId() { return id; }
            public String getTitle() { return "task-" + id; }
            public String getStatus() { return "TODO"; }
            public String getCategory() { return "BACKEND"; }
            public String getAssignee() { return null; }
        };
    }

    private static TaskLogRepository.TaskHistoryProjection history(long id) {
        return new TaskLogRepository.TaskHistoryProjection() {
            public Long getId() { return id; }
            public String getFromStatus() { return "TODO"; }
            public String getToStatus() { return "IN_PROGRESS"; }
            public String getNote() { return "log-" + id; }
            public LocalDateTime getCreatedAt() { return LocalDateTime.now(); }
        };
    }
}

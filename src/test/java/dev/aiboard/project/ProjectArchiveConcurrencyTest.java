package dev.aiboard.project;

import dev.aiboard.task.Task;
import dev.aiboard.task.TaskLogRepository;
import dev.aiboard.task.TaskRepository;
import dev.aiboard.task.TaskService;
import dev.aiboard.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** Archive and task mutations share the project lock, so their terminal state is serializable. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive-concurrency;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-archive-concurrency-test.log"
})
class ProjectArchiveConcurrencyTest {

    @Autowired private ProjectService projectService;
    @Autowired private ProjectArchiveService archiveService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectAuditRepository auditRepository;
    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskLogRepository taskLogRepository;

    @BeforeEach
    void cleanDatabase() {
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        auditRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void concurrentArchiveAndCompletionLeavesOneAuditedArchivedSnapshot() throws Exception {
        var project = projectService.createProject("archive concurrent", null).project();
        Long taskId = taskService.createTasks(project.id(),
                List.of(new TaskService.TaskInput("finish", null, "TEST"))).getFirst().id();
        var claim = taskService.claimNextTask("archive concurrent", "TEST", "qa");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var archive = executor.submit(() -> {
                ready.countDown();
                start.await();
                archiveService.archive("archive concurrent", "confirmed race", true);
                return true;
            });
            var complete = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    taskService.updateStatus(taskId, "DONE", null, claim.claimToken());
                    return true;
                } catch (RuntimeException ignored) {
                    return false;
                }
            });

            ready.await();
            start.countDown();
            assertThat(archive.get()).isTrue();
            boolean completedBeforeArchive = complete.get();

            assertThat(projectRepository.findById(project.id()).orElseThrow().getStatus())
                    .isEqualTo("ARCHIVED");
            assertThat(auditRepository.count()).isEqualTo(1L);
            Task persisted = taskRepository.findById(taskId).orElseThrow();
            assertThat(persisted.getStatus())
                    .isEqualTo(completedBeforeArchive ? TaskStatus.DONE : TaskStatus.IN_PROGRESS);
            assertThat(taskLogRepository.findByTaskIdOrderByIdAsc(taskId)).hasSize(completedBeforeArchive ? 3 : 2);
        }
    }
}

package dev.aiboard.task;

import dev.aiboard.project.ProjectRepository;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:claim-concurrency;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-claim-test.log"
})
class TaskClaimConcurrencyTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskLogRepository taskLogRepository;

    @BeforeEach
    void cleanDatabase() {
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void simultaneousClaimsNeverReturnSameTaskToTwoWorkers() throws Exception {
        var project = projectService.createProject("併發認領測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("唯一後端任務", "驗證 compare-and-swap", "BACKEND")));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<TaskService.ClaimNextTaskResult> first = executor.submit(
                    () -> claimAfterBarrier(ready, start, "backend-dev-a"));
            Future<TaskService.ClaimNextTaskResult> second = executor.submit(
                    () -> claimAfterBarrier(ready, start, "backend-dev-b"));

            ready.await();
            start.countDown();
            List<TaskService.ClaimNextTaskResult> results = List.of(first.get(), second.get());

            assertThat(results).filteredOn(TaskService.ClaimNextTaskResult::claimed).hasSize(1);
            assertThat(results).filteredOn(result -> !result.claimed()).hasSize(1);
            Task persisted = taskRepository.findAll().getFirst();
            assertThat(persisted.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(persisted.getAssignee()).isIn("backend-dev-a", "backend-dev-b");
            assertThat(persisted.getClaimedAt()).isNotNull();
        }
    }

    private TaskService.ClaimNextTaskResult claimAfterBarrier(
            CountDownLatch ready, CountDownLatch start, String assignee) throws InterruptedException {
        ready.countDown();
        start.await();
        return taskService.claimNextTask("併發認領測試", "BACKEND", assignee);
    }
}

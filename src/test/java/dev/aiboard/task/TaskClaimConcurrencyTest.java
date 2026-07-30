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

    @Test
    void taskWithUnfinishedPrerequisiteIsNeverHandedOut() {
        var project = projectService.createProject("相依守衛測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("設定環境", null, "INFRA"),
                new TaskService.TaskInput("改後端", null, "BACKEND", List.of(1), List.of())));

        TaskService.ClaimNextTaskResult blocked =
                taskService.claimNextTask("相依守衛測試", "BACKEND", "backend-dev");

        assertThat(blocked.claimed()).isFalse();
        assertThat(blocked.blockedByDependency()).isTrue();
        assertThat(blocked.blockedCandidates()).anySatisfy(
                text -> assertThat(text).contains("改後端").contains("設定環境"));

        // 前置任務走完整流程到 DONE，被卡住的任務才應該解鎖。
        TaskService.ClaimNextTaskResult infra =
                taskService.claimNextTask("相依守衛測試", "INFRA", "infra");
        assertThat(infra.claimed()).isTrue();
        taskService.updateStatus(infra.task().id(), "DONE", null);

        TaskService.ClaimNextTaskResult unlocked =
                taskService.claimNextTask("相依守衛測試", "BACKEND", "backend-dev");
        assertThat(unlocked.claimed()).isTrue();
        assertThat(unlocked.task().title()).isEqualTo("改後端");
    }

    @Test
    void simultaneousClaimsSkipBlockedTaskAndAgreeOnTheFreeOne() throws Exception {
        var project = projectService.createProject("相依併發測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("前置任務", null, "INFRA"),
                new TaskService.TaskInput("被卡住的後端任務", null, "BACKEND", List.of(1), List.of()),
                new TaskService.TaskInput("可以先做的後端任務", null, "BACKEND")));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<TaskService.ClaimNextTaskResult> first = executor.submit(
                    () -> claimAfterBarrier(ready, start, "相依併發測試", "backend-dev-a"));
            Future<TaskService.ClaimNextTaskResult> second = executor.submit(
                    () -> claimAfterBarrier(ready, start, "相依併發測試", "backend-dev-b"));

            ready.await();
            start.countDown();
            List<TaskService.ClaimNextTaskResult> results = List.of(first.get(), second.get());

            // 只有無相依的那筆能被認領，且兩個 worker 之中只有一個拿到。
            assertThat(results).filteredOn(TaskService.ClaimNextTaskResult::claimed).hasSize(1);
            TaskService.ClaimNextTaskResult winner = results.stream()
                    .filter(TaskService.ClaimNextTaskResult::claimed)
                    .findFirst()
                    .orElseThrow();
            assertThat(winner.task().title()).isEqualTo("可以先做的後端任務");

            Task stillBlocked = taskRepository.findAll().stream()
                    .filter(t -> "被卡住的後端任務".equals(t.getTitle()))
                    .findFirst()
                    .orElseThrow();
            assertThat(stillBlocked.getStatus()).isEqualTo("TODO");
            assertThat(stillBlocked.getAssignee()).isNull();
        }
    }

    private TaskService.ClaimNextTaskResult claimAfterBarrier(
            CountDownLatch ready, CountDownLatch start, String assignee) throws InterruptedException {
        return claimAfterBarrier(ready, start, "併發認領測試", assignee);
    }

    private TaskService.ClaimNextTaskResult claimAfterBarrier(
            CountDownLatch ready, CountDownLatch start, String projectName, String assignee)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return taskService.claimNextTask(projectName, "BACKEND", assignee);
    }
}

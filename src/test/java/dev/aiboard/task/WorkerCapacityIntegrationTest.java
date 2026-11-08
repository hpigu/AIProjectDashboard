package dev.aiboard.task;

import dev.aiboard.common.BoardException;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:capacity-integration;DB_CLOSE_DELAY=-1",
        "logging.file.name=/tmp/ai-board-capacity-integration.log",
        "board.capacity.backend=1"
})
class WorkerCapacityIntegrationTest {
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired TaskRepository repository;
    @Autowired TaskCompleteService complete;

    @Test
    void concurrentResumesCompeteForOneSlot() throws Exception {
        var project = projects.createProject("resume race", null).project();
        tasks.createTasks(project.id(), List.of(new TaskService.TaskInput("one", null, "BACKEND"),
                new TaskService.TaskInput("two", null, "BACKEND")));
        var first = tasks.claimNextTask("resume race", "BACKEND", "one");
        tasks.updateStatus(first.task().id(), "BLOCKED", null, first.claimToken());
        var second = tasks.claimNextTask("resume race", "BACKEND", "two");
        tasks.updateStatus(second.task().id(), "BLOCKED", null, second.claimToken());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> resumeAfter(start, first));
            var b = pool.submit(() -> resumeAfter(start, second));
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
            assertThat(repository.countByProjectIdAndCategoryAndStatus(project.id(), "BACKEND", TaskStatus.IN_PROGRESS)).isEqualTo(1);
        }
    }

    private boolean resumeAfter(CountDownLatch start, TaskService.ClaimNextTaskResult claim) throws Exception {
        start.await();
        try {
            tasks.updateStatus(claim.task().id(), "IN_PROGRESS", null, claim.claimToken());
            return true;
        } catch (BoardException e) {
            assertThat(e).hasMessageContaining("CAPACITY_FULL");
            return false;
        }
    }

    @Test
    void completedTaskCannotReopenWhenCapacityIsFull() {
        var project = projects.createProject("reopen capacity", null).project();
        tasks.createTasks(project.id(), List.of(new TaskService.TaskInput("done", null, "BACKEND"),
                new TaskService.TaskInput("next", null, "BACKEND")));
        var first = tasks.claimNextTask("reopen capacity", "BACKEND", "one");
        complete.completeTask(first.task().id(), first.claimToken(), "done",
                List.of(new TaskCompleteService.VerificationInput("checked", "PASSED", null)), null, null, null);
        var second = tasks.claimNextTask("reopen capacity", "BACKEND", "two");
        assertThat(second.claimed()).isTrue();
        assertThatThrownBy(() -> tasks.updateStatus(first.task().id(), "IN_PROGRESS", null))
                .isInstanceOf(BoardException.class).hasMessageContaining("CAPACITY_FULL");
    }

    @Test
    void concurrentClaimsRespectProjectCategoryLimitAndTransitions() throws Exception {
        var project = projects.createProject("capacity race", null).project();
        tasks.createTasks(project.id(), List.of(
                new TaskService.TaskInput("one", null, "BACKEND"),
                new TaskService.TaskInput("two", null, "BACKEND"),
                new TaskService.TaskInput("front", null, "FRONTEND")));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return tasks.claimNextTask("capacity race", "BACKEND", "a"); });
            var b = pool.submit(() -> { start.await(); return tasks.claimNextTask("capacity race", "BACKEND", "b"); });
            start.countDown();
            var results = List.of(a.get(), b.get());
            assertThat(results).extracting(TaskService.ClaimNextTaskResult::outcome)
                    .containsExactlyInAnyOrder(TaskService.ClaimOutcome.CLAIMED, TaskService.ClaimOutcome.CAPACITY_FULL);
            var first = results.stream().filter(TaskService.ClaimNextTaskResult::claimed).findFirst().orElseThrow();
            assertThat(first.capacity().inProgress()).isEqualTo(1);
            tasks.updateStatus(first.task().id(), "IN_PROGRESS", null, first.claimToken());
            tasks.updateStatus(first.task().id(), "BLOCKED", null, first.claimToken());
            var second = tasks.claimNextTask("capacity race", "BACKEND", "second");
            assertThat(second.claimed()).isTrue();
            assertThatThrownBy(() -> tasks.updateStatus(first.task().id(), "IN_PROGRESS", null, first.claimToken()))
                    .isInstanceOf(BoardException.class).hasMessageContaining("CAPACITY_FULL");
            assertThat(tasks.claimNextTask("capacity race", "FRONTEND", "front").claimed()).isTrue();
            var other = projects.createProject("capacity other", null).project();
            tasks.createTasks(other.id(), List.of(new TaskService.TaskInput("other", null, "BACKEND")));
            assertThat(tasks.claimNextTask("capacity other", "BACKEND", "other").claimed()).isTrue();
            var filtered = tasks.listTasksByProjectRef(project.id(), null, "TODO", "DOC");
            assertThat(filtered.tasks()).isEmpty();
            assertThat(filtered.capacities()).filteredOn(c -> c.category().name().equals("BACKEND"))
                    .singleElement().satisfies(c -> assertThat(c.inProgress()).isEqualTo(1));
            tasks.updateStatus(second.task().id(), "BLOCKED", null, second.claimToken());
            tasks.updateStatus(first.task().id(), "IN_PROGRESS", null, first.claimToken());
            assertThat(repository.countByProjectIdAndCategoryAndStatus(project.id(), "BACKEND", TaskStatus.IN_PROGRESS)).isEqualTo(1);
        }
    }
}

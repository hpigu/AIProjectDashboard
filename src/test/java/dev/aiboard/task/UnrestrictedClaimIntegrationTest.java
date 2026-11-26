package dev.aiboard.task;

import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:unrestricted-claims;DB_CLOSE_DELAY=-1",
    "logging.file.name=/tmp/ai-board-unrestricted-claims.log"
})
class UnrestrictedClaimIntegrationTest {
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired TaskRepository repository;

    @Test
    void independentProjectsCanClaimTwoAndFourBackendTasks() {
        for (int count : List.of(2, 4)) {
            String name = "independent-" + count;
            var project = projects.createProject(name, null).project();
            tasks.createTasks(project.id(), java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new TaskService.TaskInput("task " + i, null, "BACKEND")).toList());
            for (int i = 0; i < count; i++) assertThat(tasks.claimNextTask(name, "BACKEND", "worker-" + i).claimed()).isTrue();
            assertThat(repository.countByProjectIdAndCategoryAndStatus(project.id(), "BACKEND", TaskStatus.IN_PROGRESS)).isEqualTo(count);
        }
    }

    @Test
    void concurrentResumesDoNotCompeteForRoleQuota() throws Exception {
        var project = projects.createProject("resume-unrestricted", null).project();
        tasks.createTasks(project.id(), List.of(new TaskService.TaskInput("one", null, "FRONTEND"),
            new TaskService.TaskInput("two", null, "FRONTEND")));
        var first = tasks.claimNextTask(project.name(), "FRONTEND", "one");
        var second = tasks.claimNextTask(project.name(), "FRONTEND", "two");
        tasks.updateStatus(first.task().id(), "BLOCKED", null, first.claimToken());
        tasks.updateStatus(second.task().id(), "BLOCKED", null, second.claimToken());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return tasks.updateStatus(first.task().id(), "IN_PROGRESS", null, first.claimToken()); });
            var b = pool.submit(() -> { start.await(); return tasks.updateStatus(second.task().id(), "IN_PROGRESS", null, second.claimToken()); });
            start.countDown();
            assertThat(a.get().changed()).isTrue();
            assertThat(b.get().changed()).isTrue();
        }
        assertThat(repository.countByProjectIdAndCategoryAndStatus(project.id(), "FRONTEND", TaskStatus.IN_PROGRESS)).isEqualTo(2);
    }
}

package dev.aiboard.task;

import dev.aiboard.project.ProjectRepository;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:business-concurrency;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-business-concurrency-test.log"
})
class BusinessConcurrencyTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void simultaneousCaseVariantProjectCreationReturnsOneProject() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ProjectService.ProjectCreationResult> first = executor.submit(
                    () -> createProjectAfterBarrier(ready, start, "Demo"));
            Future<ProjectService.ProjectCreationResult> second = executor.submit(
                    () -> createProjectAfterBarrier(ready, start, " demo "));

            ready.await();
            start.countDown();

            List<ProjectService.ProjectCreationResult> results = List.of(first.get(), second.get());
            assertThat(results).extracting(result -> result.project().id()).doesNotContainNull();
            assertThat(results.get(0).project().id()).isEqualTo(results.get(1).project().id());
            assertThat(projectRepository.count()).isEqualTo(1);
        }
    }

    @Test
    void simultaneousTaskBatchesReceiveUniqueSortOrders() throws Exception {
        var project = projectService.createProject("排序測試", null).project();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<TaskService.TaskDto>> first = executor.submit(
                    () -> createTasksAfterBarrier(ready, start, project.id(), "A"));
            Future<List<TaskService.TaskDto>> second = executor.submit(
                    () -> createTasksAfterBarrier(ready, start, project.id(), "B"));

            ready.await();
            start.countDown();
            first.get();
            second.get();

            assertThat(taskRepository.findByProjectIdOrderBySortOrderAsc(project.id()))
                    .extracting(Task::getSortOrder)
                    .containsExactlyInAnyOrder(0, 1, 2, 3)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void optimisticLockAllowsOnlyOneConcurrentTaskWrite() throws Exception {
        var project = projectService.createProject("版本鎖測試", null).project();
        Long taskId = taskService.createTasks(project.id(),
                List.of(new TaskService.TaskInput("同時更新", null, "BACKEND")))
                .getFirst().id();

        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch write = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> done = executor.submit(
                    () -> updateDirectlyAfterBarrier(taskId, TaskStatus.DONE, loaded, write));
            Future<Boolean> blocked = executor.submit(
                    () -> updateDirectlyAfterBarrier(taskId, TaskStatus.BLOCKED, loaded, write));

            loaded.await();
            write.countDown();

            assertThat(List.of(done.get(), blocked.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    private ProjectService.ProjectCreationResult createProjectAfterBarrier(
            CountDownLatch ready, CountDownLatch start, String name) throws InterruptedException {
        ready.countDown();
        start.await();
        return projectService.createProject(name, null);
    }

    private List<TaskService.TaskDto> createTasksAfterBarrier(
            CountDownLatch ready, CountDownLatch start, Long projectId, String prefix)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return taskService.createTasks(projectId, List.of(
                new TaskService.TaskInput(prefix + "1", null, "BACKEND"),
                new TaskService.TaskInput(prefix + "2", null, "BACKEND")));
    }

    private boolean updateDirectlyAfterBarrier(Long taskId, TaskStatus target,
                                                CountDownLatch loaded, CountDownLatch write) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Task task = taskRepository.findById(taskId).orElseThrow();
                loaded.countDown();
                await(write);
                task.changeStatus(target);
                taskRepository.flush();
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrency test", e);
        }
    }
}

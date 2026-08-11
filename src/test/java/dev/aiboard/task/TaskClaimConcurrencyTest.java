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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private TaskCompleteService taskCompleteService;

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
            assertThat(persisted.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
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
        assertThat(blocked.blockedCandidates()).anySatisfy(candidate -> {
            assertThat(candidate.task().title()).isEqualTo("改後端");
            assertThat(candidate.waitingFor()).extracting(TaskService.TaskDto::title)
                    .containsExactly("設定環境");
        });

        // 前置任務走完整流程到 DONE，被卡住的任務才應該解鎖。
        // #131：createTasks 建立的任務一律 require_evidence=true，
        // 必須改用 complete_task 才能轉 DONE。
        TaskService.ClaimNextTaskResult infra =
                taskService.claimNextTask("相依守衛測試", "INFRA", "infra");
        assertThat(infra.claimed()).isTrue();
        assertThat(infra.claimToken()).isNotBlank();
        taskCompleteService.completeTask(infra.task().id(), infra.claimToken(), "環境設定完成",
                List.of(new TaskCompleteService.VerificationInput("手動確認", "PASSED", null)),
                null, null, null);

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
            assertThat(stillBlocked.getStatus()).isEqualTo(TaskStatus.TODO);
            assertThat(stillBlocked.getAssignee()).isNull();
        }
    }

    @Test
    void secondAgentWithoutTokenCannotHijackFirstAgentsClaim() {
        // #112：認領成功後拿到 claimToken，另一個 agent（就算知道 taskId）沒有正確
        // token 就不能動這筆任務——就算它猜一個假 token 也一樣被拒絕。
        //
        // #131：createTasks 建立的任務一律 require_evidence=true，用 updateStatus
        // 轉 DONE 會先被 require_evidence 守衛擋下，不論 token 對不對——這樣就無法
        // 驗證「token 保護」本身了。這裡改用繞過 createTasks、require_evidence=false
        // 的既有資料任務（模擬 migration 前既有資料），讓中段的冒充者斷言仍然只在
        // 驗證 token 所有權，不被新守衛掩蓋；結尾用 complete_task 收尾，證明
        // 正確 token 持有者不論走哪條完成路徑都能成功。
        var project = projectService.createProject("token 所有權測試", null).project();
        Task legacyTask = new Task(project.id(), "受保護任務", null, "BACKEND", 0);
        legacyTask.setRequireEvidence(false);
        taskRepository.save(legacyTask);

        TaskService.ClaimNextTaskResult claimedByA =
                taskService.claimNextTask("token 所有權測試", "BACKEND", "backend-dev-a");
        assertThat(claimedByA.claimed()).isTrue();
        Long taskId = claimedByA.task().id();

        // 冒充者不帶 token、或帶假 token，都不能把任務標成 DONE。
        assertThatThrownBy(() -> taskService.updateStatus(taskId, "DONE", null, null))
                .isInstanceOf(dev.aiboard.common.BoardException.class);
        assertThatThrownBy(() -> taskService.updateStatus(taskId, "DONE", null, "假冒的token"))
                .isInstanceOf(dev.aiboard.common.BoardException.class);

        Task stillInProgress = taskRepository.findById(taskId).orElseThrow();
        assertThat(stillInProgress.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);

        // 真正的認領者帶對 token 才能完成（走 complete_task，同樣受 token 保護）。
        TaskCompleteService.CompleteResult result = taskCompleteService.completeTask(
                taskId, claimedByA.claimToken(), "完成受保護任務",
                List.of(new TaskCompleteService.VerificationInput("手動確認", "PASSED", null)),
                null, null, null);
        assertThat(result.task().status()).isEqualTo("DONE");
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void concurrentUpdateAttemptsOnlyTheTokenHolderSucceeds() throws Exception {
        // #131：這裡驗證的是「併發下只有正確 token 一方能成功」這個核心語意，
        // 用的是通用 update_task_status 路徑。若任務走 createTasks（一律
        // require_evidence=true），兩邊都會先被 require_evidence 守衛擋下，
        // 併發保護就完全沒被覆蓋到了。改用繞過 createTasks、require_evidence=false
        // 的既有資料任務（模擬既有資料，行為不變），才能真正重建這個併發保護測試。
        var project = projectService.createProject("token 併發保護測試", null).project();
        Task legacyTask = new Task(project.id(), "受保護任務", null, "BACKEND", 0);
        legacyTask.setRequireEvidence(false);
        taskRepository.save(legacyTask);

        TaskService.ClaimNextTaskResult claimedByA =
                taskService.claimNextTask("token 併發保護測試", "BACKEND", "backend-dev-a");
        Long taskId = claimedByA.task().id();
        String realToken = claimedByA.claimToken();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            // 一個 worker 帶正確 token，另一個帶假 token，同時嘗試完成同一筆任務。
            Future<Boolean> withRealToken = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    taskService.updateStatus(taskId, "DONE", null, realToken);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
            Future<Boolean> withFakeToken = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    taskService.updateStatus(taskId, "DONE", null, "偽造的token");
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });

            ready.await();
            start.countDown();
            boolean realSucceeded = withRealToken.get();
            boolean fakeSucceeded = withFakeToken.get();

            assertThat(realSucceeded).isTrue();
            assertThat(fakeSucceeded).isFalse();
            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
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

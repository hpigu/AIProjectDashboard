package dev.aiboard.task;

import dev.aiboard.project.ProjectRepository;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 針對 #114 complete_task 的整合測試：驗證實際跑在 H2（含 V9 migration schema、
 * ddl-auto=validate）之上時，evidence／verification 確實落地、append-only、
 * 以及與既有 claim/update_status 流程串接時的相容性。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-complete-integration;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-task-complete-test.log"
})
class TaskCompleteServiceIntegrationTest {

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

    @Autowired
    private TaskCompletionEvidenceRepository evidenceRepository;

    @Autowired
    private TaskCompletionVerificationRepository verificationRepository;

    @BeforeEach
    void cleanDatabase() {
        verificationRepository.deleteAll();
        evidenceRepository.deleteAll();
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void completeTask_persistsEvidenceAndVerificationsAndTransitionsToDone() {
        var project = projectService.createProject("complete_task 整合測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("實作某功能", null, "BACKEND")));
        var claimed = taskService.claimNextTask("complete_task 整合測試", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();

        var result = taskCompleteService.completeTask(taskId, claimed.claimToken(),
                "完成功能實作", List.of(
                        new TaskCompleteService.VerificationInput("./mvnw test", "PASSED", null),
                        new TaskCompleteService.VerificationInput("手動驗證", "NOT_RUN", "本地無法啟動外部服務")),
                "Foo.java, Bar.java", "abc123", null);

        assertThat(result.task().status()).isEqualTo("DONE");
        Task persisted = taskRepository.findById(taskId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("DONE");
        assertThat(persisted.getClaimTokenHash()).isNull();

        List<TaskCompletionEvidence> evidences = evidenceRepository.findByTaskIdOrderByIdAsc(taskId);
        assertThat(evidences).hasSize(1);
        TaskCompletionEvidence evidence = evidences.getFirst();
        assertThat(evidence.getSummary()).isEqualTo("完成功能實作");
        assertThat(evidence.getCommitRef()).isEqualTo("abc123");
        assertThat(evidence.getCompletedBy()).isEqualTo("backend-dev");

        List<TaskCompletionVerification> verifications =
                verificationRepository.findByEvidenceIdOrderByIdAsc(evidence.getId());
        assertThat(verifications).hasSize(2);
        assertThat(verifications).extracting(TaskCompletionVerification::getResult)
                .containsExactly("PASSED", "NOT_RUN");
    }

    @Test
    void completeTask_withoutClaimToken_whenTaskHasTokenHash_isRejected() {
        var project = projectService.createProject("token 保護測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("受保護任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("token 保護測試", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();

        var verifications = List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null));

        assertThatThrownBy(() -> taskCompleteService.completeTask(
                taskId, null, "摘要", verifications, null, null, null))
                .isInstanceOf(dev.aiboard.common.BoardException.class)
                .hasMessageContaining("claimToken");

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void completeTask_reopenedTask_appendsNewEvidenceWithoutDeletingOld() {
        var project = projectService.createProject("重開任務測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("重開任務測試", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();
        String token = claimed.claimToken();

        taskCompleteService.completeTask(taskId, token, "第一次完成",
                List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null)),
                null, null, null);
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo("DONE");

        // DONE -> IN_PROGRESS 是既有合法轉移；重開後沒有 claimTokenHash（DONE 時已清空），
        // 沿用 #112 相容策略不強制帶 token。
        taskService.updateStatus(taskId, "IN_PROGRESS", "重新檢視");
        var reopened = taskRepository.findById(taskId).orElseThrow();
        assertThat(reopened.getAssignee()).isEqualTo("backend-dev");

        var secondResult = taskCompleteService.completeTask(taskId, null, "第二次完成（修正問題）",
                List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null)),
                null, null, null);

        assertThat(secondResult.task().status()).isEqualTo("DONE");
        List<TaskCompletionEvidence> evidences = evidenceRepository.findByTaskIdOrderByIdAsc(taskId);
        assertThat(evidences).hasSize(2);
        assertThat(evidences).extracting(TaskCompletionEvidence::getSummary)
                .containsExactly("第一次完成", "第二次完成（修正問題）");
    }

    @Test
    void completeTask_withFailedVerification_doesNotPersistEvidenceOrChangeStatus() {
        var project = projectService.createProject("FAILED 測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("FAILED 測試", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();

        var verifications = List.of(
                new TaskCompleteService.VerificationInput("./mvnw test", "FAILED", "2 個測試失敗"));

        assertThatThrownBy(() -> taskCompleteService.completeTask(
                taskId, claimed.claimToken(), "摘要", verifications, null, null, null))
                .isInstanceOf(dev.aiboard.common.BoardException.class)
                .hasMessageContaining("FAILED");

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void updateTaskStatus_legacyTaskWithoutRequireEvidence_stillWorksDirectlyToDone() {
        // #131 之後：update_task_status 對 require_evidence=false 的任務
        // （模擬 migration 前既有資料，行為完全不變）行為與 #114 之前一致——
        // #112 已確立的 per-task token 策略維持不變，通用工具仍可用於已認領
        // 任務的既有流程，只是不會產生結構化完成證據
        // （那是 complete_task 額外提供的能力）。
        var project = projectService.createProject("相容性測試", null).project();
        Task legacyTask = new Task(project.id(), "任務", null, "BACKEND", 0);
        legacyTask.setRequireEvidence(false);
        taskRepository.save(legacyTask);
        var claimed = taskService.claimNextTask("相容性測試", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();

        var result = taskService.updateStatus(taskId, "DONE", null, claimed.claimToken());

        assertThat(result.changed()).isTrue();
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo("DONE");
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void updateTaskStatus_newlyCreatedTaskRequiringEvidence_isRejectedDirectlyToDone() {
        // #131 方案 B1：createTasks 建立的任務一律 require_evidence=true，
        // 不可再用 update_task_status 直接轉 DONE，必須改用 complete_task。
        var project = projectService.createProject("evidence 強制測試", null).project();
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("evidence 強制測試", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();

        assertThatThrownBy(() -> taskService.updateStatus(taskId, "DONE", null, claimed.claimToken()))
                .isInstanceOf(dev.aiboard.common.BoardException.class)
                .hasMessageContaining("complete_task");

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void completeTask_worksForBothRequireEvidenceTrueAndFalseTasks() {
        // #131：complete_task 是不受 require_evidence 影響的完成路徑——不論任務
        // 是新建（require_evidence=true）還是既有資料（require_evidence=false），
        // 都必須能透過 complete_task 正常完成。
        var project = projectService.createProject("complete_task 相容 evidence 測試", null).project();

        // 新建任務：require_evidence=true。
        taskService.createTasks(project.id(), List.of(
                new TaskService.TaskInput("新建任務", null, "BACKEND")));
        var claimedNew = taskService.claimNextTask(
                "complete_task 相容 evidence 測試", "BACKEND", "backend-dev");
        Long newTaskId = claimedNew.task().id();

        var resultForNew = taskCompleteService.completeTask(newTaskId, claimedNew.claimToken(),
                "完成新建任務",
                List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null)),
                null, null, null);
        assertThat(resultForNew.task().status()).isEqualTo("DONE");
        assertThat(taskRepository.findById(newTaskId).orElseThrow().getStatus()).isEqualTo("DONE");

        // 既有資料任務：require_evidence=false（繞過 createTasks）。
        Task legacyTask = new Task(project.id(), "既有資料任務", null, "BACKEND", 1);
        legacyTask.setRequireEvidence(false);
        taskRepository.save(legacyTask);
        var claimedLegacy = taskService.claimNextTask(
                "complete_task 相容 evidence 測試", "BACKEND", "backend-dev");
        Long legacyTaskId = claimedLegacy.task().id();

        var resultForLegacy = taskCompleteService.completeTask(legacyTaskId, claimedLegacy.claimToken(),
                "完成既有資料任務",
                List.of(new TaskCompleteService.VerificationInput("測試", "PASSED", null)),
                null, null, null);
        assertThat(resultForLegacy.task().status()).isEqualTo("DONE");
        assertThat(taskRepository.findById(legacyTaskId).orElseThrow().getStatus()).isEqualTo("DONE");
    }
}

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
    @Autowired private TaskBlockService blockService;

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

    @Autowired private org.springframework.jdbc.core.JdbcTemplate cleanup;

    @BeforeEach
    void cleanDatabase() {
        cleanup.update("UPDATE task SET current_block_event_id=NULL");
        cleanup.update("DELETE FROM task_block_dependency");
        cleanup.update("DELETE FROM task_block_event");
        verificationRepository.deleteAll();
        evidenceRepository.deleteAll();
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void completeTask_selfReportedPassedDoesNotCreateTrustedEvidence() {
        var project = projectService.createProject("signed gate fixture", null).project();
        taskService.createTasks(project.id(), List.of(new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("signed gate fixture", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();
        assertThatThrownBy(() -> taskCompleteService.completeTask(taskId, claimed.claimToken(), "代理自行聲明",
                List.of(new TaskCompleteService.VerificationInput("自測", "PASSED", null)), null, "abc123", null))
                .hasMessageContaining("不能結案");
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
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

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void completeTask_legacyEvidenceRemainsUnverifiedAndUnchanged() {
        var project = projectService.createProject("signed gate fixture", null).project();
        taskService.createTasks(project.id(), List.of(new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("signed gate fixture", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();
        // 舊版完成聲明保留供查閱，不會自動升級成可信驗收證據。
        evidenceRepository.save(new TaskCompletionEvidence(taskId, "舊版完成聲明", null, "old", "backend-dev"));
        assertThatThrownBy(() -> taskCompleteService.completeTask(taskId, claimed.claimToken(), "代理自行聲明",
                List.of(new TaskCompleteService.VerificationInput("自測", "PASSED", null)), null, "abc123", null))
                .hasMessageContaining("不能結案");
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).hasSize(1);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId).getFirst().getSummary()).isEqualTo("舊版完成聲明");
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

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void updateTaskStatus_legacyTaskAlsoRequiresTrustedAcceptance() {
        var project = projectService.createProject("signed gate fixture", null).project();
        taskService.createTasks(project.id(), List.of(new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("signed gate fixture", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();
        var legacy = taskRepository.findById(taskId).orElseThrow(); legacy.setRequireEvidence(false); taskRepository.save(legacy);
        assertThatThrownBy(() -> taskService.updateStatus(taskId, "DONE", null, claimed.claimToken()))
                .hasMessageContaining("complete_task");
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
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

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }

    @Test
    void completeTask_gateAppliesToBothNewAndLegacyTasks() {
        var project = projectService.createProject("signed gate fixture", null).project();
        taskService.createTasks(project.id(), List.of(new TaskService.TaskInput("任務", null, "BACKEND")));
        var claimed = taskService.claimNextTask("signed gate fixture", "BACKEND", "backend-dev");
        Long taskId = claimed.task().id();
        assertThatThrownBy(() -> taskCompleteService.completeTask(taskId, claimed.claimToken(), "代理自行聲明",
                List.of(new TaskCompleteService.VerificationInput("自測", "PASSED", null)), null, "abc123", null))
                .hasMessageContaining("不能結案");
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
        var legacy = taskRepository.findById(taskId).orElseThrow(); legacy.setRequireEvidence(false); taskRepository.save(legacy);
        assertThatThrownBy(() -> taskCompleteService.completeTask(taskId, claimed.claimToken(), "代理自行聲明",
                List.of(new TaskCompleteService.VerificationInput("自測", "PASSED", null)), null, "abc123", null))
                .hasMessageContaining("不能結案");
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(taskId)).isEmpty();
    }


    @Test
    void implementationReportsAppendDeclarationsAndKeepStateTokenAndVersionProtection() {
        var project=projectService.createProject("implementation report fixture",null).project();
        taskService.createTasks(project.id(),List.of(new TaskService.TaskInput("reported implementation",null,"BACKEND")));
        var claim=taskService.claimNextTask("implementation report fixture","BACKEND","developer");
        Long id=claim.task().id(); Task before=taskRepository.findById(id).orElseThrow();
        long version=before.getVersion(); String hash=before.getClaimTokenHash();
        var passed=List.of(new TaskCompleteService.VerificationInput("self test","PASSED",null));
        var first=taskCompleteService.reportImplementation(id,claim.claimToken(),"self declaration",passed,"Users.java","untrusted-ref",version);
        assertThat(first.task().status()).isEqualTo("IN_PROGRESS");
        Task after=taskRepository.findById(id).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(version+1);
        assertThat(after.getClaimTokenHash()).isEqualTo(hash);
        assertThatThrownBy(() -> taskCompleteService.reportImplementation(id,"wrong-token","bad",passed,null,null,null)).hasMessageContaining("token");
        assertThatThrownBy(() -> taskCompleteService.reportImplementation(id,claim.claimToken(),"stale",passed,null,null,version)).hasMessageContaining("已被其他操作更新");
        taskCompleteService.reportImplementation(id,claim.claimToken(),"failure honestly reported",
            List.of(new TaskCompleteService.VerificationInput("integration","FAILED","fixture assertion failed")),null,null,version+1);
        assertThat(evidenceRepository.findByTaskIdOrderByIdAsc(id)).extracting(TaskCompletionEvidence::getSummary)
            .containsExactly("self declaration","failure honestly reported");
        assertThat(taskRepository.findById(id).orElseThrow().getVersion()).isEqualTo(version+2);
        assertThatThrownBy(() -> taskCompleteService.completeTask(id,claim.claimToken(),"try close",passed,null,null,null)).hasMessageContaining("不能結案");
    }

    @Test
    void implementationReportOnBlockedTaskPreservesCurrentBlocker() {
        var project=projectService.createProject("blocked implementation fixture",null).project();
        taskService.createTasks(project.id(),List.of(new TaskService.TaskInput("reported blocked implementation",null,"BACKEND")));
        var claim=taskService.claimNextTask("blocked implementation fixture","BACKEND","developer");
        Long id=claim.task().id();
        blockService.blockTask(id,claim.claimToken(),"TECHNICAL","fixture dependency unavailable",List.of(),null);
        Task before=taskRepository.findById(id).orElseThrow();
        taskCompleteService.reportImplementation(id,claim.claimToken(),"partial work",
            List.of(new TaskCompleteService.VerificationInput("integration","NOT_RUN","fixture service unavailable")),null,null,before.getVersion());
        Task after=taskRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(after.getCurrentBlockEventId()).isEqualTo(before.getCurrentBlockEventId()).isNotNull();
        assertThat(after.getClaimTokenHash()).isEqualTo(before.getClaimTokenHash());
        assertThat(after.getVersion()).isEqualTo(before.getVersion()+1);
    }

}

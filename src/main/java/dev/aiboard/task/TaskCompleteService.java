package dev.aiboard.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;

import java.util.ArrayList;
import java.util.List;

/**
 * #114 complete_task 與完成證據。獨立於 TaskService，避免更動既有建構子簽名
 * 影響其他角色已經在寫、或既有沿用 {@code new TaskService(...)} 的測試——同一個
 * 模式在 #113 的 TaskBlockService 已採用過。
 *
 * <p>這條路徑不取代、也不修改既有 {@link TaskService#updateStatus} 直接把任務轉
 * DONE 的能力：#112 已確立「per-task 強制」——只有該任務本身有 claimTokenHash
 * 時才要求 token，部署前既有、沒有 token 的任務仍走舊契約。若讓通用
 * update_task_status 對所有任務一律拒絕轉 DONE，會讓「目前正在使用看板、任務
 * 本身沒有要求 evidence」的既有流程當場停擺，包括這一輪其他角色正在進行的任務。
 * complete_task 是額外提供、更嚴謹的替代路徑：它是唯一能同時完成任務並寫入
 * 結構化驗證證據的方式，鼓勵新流程改用這裡，但不強制回溯既有任務。
 *
 * <p>這裡記錄的證據是呼叫端（agent）的自行聲明。server 不會、也無法驗證
 * commitRef 是否真的存在於外部 repo、changedFiles 是否確實被修改——只原樣存
 * 下來供追溯，不代表任何外部驗證已經發生。
 */
@Service
public class TaskCompleteService {

    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskCompletionEvidenceRepository evidenceRepository;
    private final TaskCompletionVerificationRepository verificationRepository;
    private final ProjectService projectService;
    private final BoardEventPublisher eventPublisher;
    private final ClaimTokenService claimTokenService;

    public TaskCompleteService(TaskRepository taskRepository, TaskLogRepository taskLogRepository,
                                TaskCompletionEvidenceRepository evidenceRepository,
                                TaskCompletionVerificationRepository verificationRepository,
                                ProjectService projectService, BoardEventPublisher eventPublisher,
                                ClaimTokenService claimTokenService) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
        this.claimTokenService = claimTokenService;
    }

    /**
     * 把任務轉為 DONE，同一個 transaction 內寫入完成證據（summary + 至少一筆
     * verification）。summary 必填；verification 至少一筆，result 為
     * PASSED／FAILED／NOT_RUN，FAILED 不可完成，NOT_RUN 必須附上 detail 說明
     * 為什麼這個 category 沒有適用的驗證方式。證據 append-only：任務重開後再次
     * 完成會新增新的一筆，既有 evidence 永久保留，不刪除、不覆寫。
     */
    @Transactional
    public CompleteResult completeTask(Long taskId, String claimToken, String summary,
                                        List<VerificationInput> verifications, String changedFiles,
                                        String commitRef, Long expectedVersion) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));

        ProjectService.ProjectDto project = projectService.getById(task.getProjectId());
        if (!"ACTIVE".equals(project.status())) {
            throw new BoardException("專案「%s」已封存，無法變更任務 #%d 的狀態"
                    .formatted(project.name(), taskId));
        }

        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (!current.canTransitionTo(TaskStatus.DONE)) {
            throw new BoardException(
                    "不合法的狀態轉移：#%d 目前是 %s，無法轉移至 DONE".formatted(taskId, current));
        }

        // 只有持有 token 的 assignee 能完成自己的任務；沿用 #112 的 per-task 相容
        // 策略，沒有 token hash 的舊資料任務不強制要求。
        if (task.getClaimTokenHash() != null) {
            if (claimToken == null || claimToken.isBlank()) {
                throw new BoardException("任務 #" + taskId
                        + " 需要認領時取得的 claim token 才能操作，請提供 claimToken");
            }
            if (!claimTokenService.matches(claimToken, task.getClaimTokenHash())) {
                throw new BoardException("任務 #" + taskId + " 的 claim token 不正確，無法操作");
            }
        }

        if (expectedVersion != null && !expectedVersion.equals(task.getVersion())) {
            throw new BoardException("任務 #" + taskId
                    + " 已被其他操作更新（目前版本 " + task.getVersion()
                    + "，預期版本 " + expectedVersion + "），請重新讀取後再操作");
        }

        if (summary == null || summary.isBlank()) {
            throw new BoardException("complete_task 必須填寫 summary，說明實際完成了什麼");
        }
        String trimmedSummary = summary.trim();

        if (verifications == null || verifications.isEmpty()) {
            throw new BoardException("complete_task 必須附上至少一筆 verificationResults");
        }

        List<NormalizedVerification> normalized = new ArrayList<>(verifications.size());
        for (VerificationInput input : verifications) {
            normalized.add(validateVerification(taskId, input));
        }

        boolean anyFailed = normalized.stream().anyMatch(v -> v.result() == VerificationResult.FAILED);
        if (anyFailed) {
            throw new BoardException("任務 #" + taskId
                    + " 有 verification 結果為 FAILED，不能標記 DONE；請先解決或改標記為 BLOCKED");
        }

        String assignee = task.getAssignee();
        TaskCompletionEvidence evidence = new TaskCompletionEvidence(taskId, trimmedSummary,
                normalizeBlank(changedFiles), normalizeBlank(commitRef),
                assignee != null ? assignee : "unknown");
        TaskCompletionEvidence savedEvidence = evidenceRepository.save(evidence);
        List<TaskCompletionVerification> savedVerifications = new ArrayList<>(normalized.size());
        for (NormalizedVerification v : normalized) {
            savedVerifications.add(verificationRepository.save(new TaskCompletionVerification(
                    savedEvidence.getId(), v.name(), v.result().name(), v.detail())));
        }

        task.changeStatus(TaskStatus.DONE);

        String logNote = "complete_task：%s".formatted(trimmedSummary);
        taskLogRepository.save(new TaskLog(taskId, current.name(), TaskStatus.DONE.name(), logNote));
        eventPublisher.publish(BoardEvent.taskStatusChanged(
                task.getProjectId(), taskId, current.name(), TaskStatus.DONE.name(),
                task.getUpdatedAt().toString()));

        long doneCount = taskRepository.countByProjectIdAndStatus(task.getProjectId(), TaskStatus.DONE.name());
        long totalCount = taskRepository.countByProjectId(task.getProjectId());

        return new CompleteResult(toDto(task), project, doneCount, totalCount,
                toEvidenceDto(savedEvidence, savedVerifications));
    }

    private NormalizedVerification validateVerification(Long taskId, VerificationInput input) {
        if (input == null) {
            throw new BoardException("verificationResults 內不可包含空白項目");
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new BoardException("每筆 verification 都必須有 name，說明驗證的是什麼");
        }
        VerificationResult result = parseResult(input.result());
        String detail = input.detail() == null ? null : input.detail().trim();
        if ((result == VerificationResult.NOT_RUN || result == VerificationResult.FAILED)
                && (detail == null || detail.isBlank())) {
            throw new BoardException("任務 #" + taskId + " 的 verification「" + input.name().trim()
                    + "」結果為 " + result + " 時必須填寫 detail 說明原因");
        }
        return new NormalizedVerification(input.name().trim(), result, detail);
    }

    private VerificationResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("verification 的 result 不可為空白");
        }
        try {
            return VerificationResult.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不合法的 verification result：" + raw + "，限定 PASSED/FAILED/NOT_RUN");
        }
    }

    private String normalizeBlank(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    public List<EvidenceDto> getHistory(Long projectId, Long taskId) {
        projectService.assertExists(projectId);
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BoardException("找不到任務：#" + taskId));
        if (!task.getProjectId().equals(projectId)) {
            throw new BoardException("任務 #" + taskId + " 不屬於專案 #" + projectId);
        }
        List<EvidenceDto> result = new ArrayList<>();
        for (TaskCompletionEvidence evidence : evidenceRepository.findByTaskIdOrderByIdAsc(taskId)) {
            List<TaskCompletionVerification> verifications =
                    verificationRepository.findByEvidenceIdOrderByIdAsc(evidence.getId());
            result.add(toEvidenceDto(evidence, verifications));
        }
        return result;
    }

    private EvidenceDto toEvidenceDto(TaskCompletionEvidence evidence,
                                       List<TaskCompletionVerification> verifications) {
        List<VerificationDto> verificationDtos = verifications.stream()
                .map(v -> new VerificationDto(v.getId(), v.getName(), v.getResult(), v.getDetail()))
                .toList();
        return new EvidenceDto(evidence.getId(), evidence.getTaskId(), evidence.getSummary(),
                evidence.getChangedFiles(), evidence.getCommitRef(), evidence.getCompletedBy(),
                evidence.getCompletedAt(), verificationDtos);
    }

    private TaskService.TaskDto toDto(Task task) {
        return new TaskService.TaskDto(task.getId(), task.getProjectId(), task.getTitle(),
                task.getDescription(), task.getStatus(), task.getCategory(), task.getSortOrder(),
                task.getAssignee(), task.getClaimedAt());
    }

    /**
     * @param name   這筆驗證做的是什麼，例如「./mvnw test」「手動驗證 API 回應」
     * @param result PASSED／FAILED／NOT_RUN
     * @param detail FAILED／NOT_RUN 時必填，說明結果或無法執行的原因
     */
    public record VerificationInput(String name, String result, String detail) {
    }

    private record NormalizedVerification(String name, VerificationResult result, String detail) {
    }

    public record VerificationDto(Long id, String name, String result, String detail) {
    }

    public record EvidenceDto(Long id, Long taskId, String summary, String changedFiles, String commitRef,
                               String completedBy, java.time.LocalDateTime completedAt,
                               List<VerificationDto> verifications) {
    }

    public record CompleteResult(TaskService.TaskDto task, ProjectService.ProjectDto project,
                                  long doneCount, long totalCount, EvidenceDto evidence) {
    }
}

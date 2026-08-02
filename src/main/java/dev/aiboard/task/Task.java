package dev.aiboard.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    private String status = TaskStatus.TODO.name();

    @Column(length = 30)
    private String category;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(length = 60)
    private String assignee;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    /** claim token 的安全雜湊；只有 hash，原文從不落庫。TODO/DONE 必為 NULL。 */
    @Column(name = "claim_token_hash", length = 128)
    private String claimTokenHash;

    /**
     * #131 方案 B1：這筆任務完成時是否必須經由 complete_task 產生結構化完成
     * 證據。只在建立當下由 {@link TaskService#createTasks} 決定（一律 true），
     * 之後不可再變更；既有資料遷移後維持 FALSE，update_task_status 對它們的
     * 行為不受影響，與 claimTokenHash 是否為 NULL 屬於同一種 per-task 相容模式。
     */
    @Column(name = "require_evidence", nullable = false)
    private boolean requireEvidence = false;

    /**
     * 目前有效的 BLOCKED 事件（指向 task_block_event.id）；只有 BLOCKED 狀態會非
     * NULL。離開 BLOCKED 時清空這個指標，但該筆事件本身在 task_block_event 永久
     * 保留、只補上 clearedAt，供歷史追溯。
     */
    @Column(name = "current_block_event_id")
    private Long currentBlockEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Task() {
    }

    public Task(Long projectId, String title, String description, String category, int sortOrder) {
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.sortOrder = sortOrder;
        this.status = TaskStatus.TODO.name();
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getCategory() {
        return category;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public String getAssignee() {
        return assignee;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public String getClaimTokenHash() {
        return claimTokenHash;
    }

    public void setClaimTokenHash(String claimTokenHash) {
        this.claimTokenHash = claimTokenHash;
    }

    public boolean isRequireEvidence() {
        return requireEvidence;
    }

    /** 只在建立任務當下由 {@link TaskService#createTasks} 呼叫一次，之後不可變更。 */
    public void setRequireEvidence(boolean requireEvidence) {
        this.requireEvidence = requireEvidence;
    }

    public Long getCurrentBlockEventId() {
        return currentBlockEventId;
    }

    /** block_task 呼叫成功後，把目前 blocker 指向新建立的事件。 */
    public void setCurrentBlockEventId(Long currentBlockEventId) {
        this.currentBlockEventId = currentBlockEventId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    /** Leader 編輯任務規格時使用；狀態與輸入限制由 TaskEditService 統一驗證。 */
    public void updateDetails(String title, String description, String category) {
        if (title != null) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (category != null) {
            this.category = category;
        }
        touch();
    }

    /** 相依集合本身不在 Task entity 上，仍需 touch 以提供同一筆任務的 optimistic lock。 */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public void changeStatus(TaskStatus newStatus) {
        this.status = newStatus.name();
        if (newStatus == TaskStatus.TODO) {
            this.assignee = null;
            this.claimedAt = null;
            this.claimTokenHash = null;
        }
        if (newStatus == TaskStatus.DONE) {
            this.claimTokenHash = null;
        }
        // BLOCKED 保留 claimTokenHash：認領者仍擁有這筆任務，之後要 resume/complete
        // 還是得用同一把 token；IN_PROGRESS 本身也保留（正常認領中）。
        if (newStatus != TaskStatus.BLOCKED) {
            // 離開 BLOCKED（包含重新變成 BLOCKED 前的中繼狀態）時清空目前 blocker 指標；
            // task_block_event 那一列本身不動，由呼叫端另外呼叫 clear() 補上 clearedAt。
            this.currentBlockEventId = null;
        }
        this.updatedAt = LocalDateTime.now();
    }
}

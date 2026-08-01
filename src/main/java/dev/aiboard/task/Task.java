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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
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
        this.updatedAt = LocalDateTime.now();
    }
}

package dev.aiboard.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Append-only project lifecycle audit entry. */
@Entity
@Table(name = "project_audit")
public class ProjectAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @Column(name = "todo_count", nullable = false)
    private long todoCount;

    @Column(name = "in_progress_count", nullable = false)
    private long inProgressCount;

    @Column(name = "blocked_count", nullable = false)
    private long blockedCount;

    @Column(name = "done_count", nullable = false)
    private long doneCount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected ProjectAudit() {
    }

    public ProjectAudit(Long projectId, String action, String reason, ProjectArchiveService.StatusCounts counts) {
        this.projectId = projectId;
        this.action = action;
        this.reason = reason;
        this.todoCount = counts.todo();
        this.inProgressCount = counts.inProgress();
        this.blockedCount = counts.blocked();
        this.doneCount = counts.done();
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}

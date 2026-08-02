package dev.aiboard.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Append-only 的 BLOCKED 事件記錄。每次 block_task 呼叫新增一列；
 * 解除阻塞時只補上 clearedAt，既有欄位絕不覆寫，保留完整歷史快照。
 */
@Entity
@Table(name = "task_block_event")
public class TaskBlockEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "reason_type", nullable = false, length = 30)
    private String reasonType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String detail;

    @Column(name = "blocked_by", nullable = false, length = 60)
    private String blockedBy;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    protected TaskBlockEvent() {
    }

    public TaskBlockEvent(Long taskId, String reasonType, String detail, String blockedBy) {
        this.taskId = taskId;
        this.reasonType = reasonType;
        this.detail = detail;
        this.blockedBy = blockedBy;
        this.blockedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getReasonType() {
        return reasonType;
    }

    public String getDetail() {
        return detail;
    }

    public String getBlockedBy() {
        return blockedBy;
    }

    public LocalDateTime getBlockedAt() {
        return blockedAt;
    }

    public LocalDateTime getClearedAt() {
        return clearedAt;
    }

    /** 標記這次 block 事件已解除；只補這一個欄位，其餘保持原樣供追溯。 */
    public void clear() {
        this.clearedAt = LocalDateTime.now();
    }
}

package dev.aiboard.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Append-only 的完成證據記錄。每次 complete_task 成功呼叫新增一列；任務重開
 * 後再次完成會再新增一列，既有列絕不覆寫或刪除，保留完整歷史。
 *
 * 這是呼叫端（agent）自行聲明的證據，server 不會、也無法驗證 commitRef 是否
 * 真的存在於外部 repo、changedFiles 是否確實被修改——只原樣記錄下來供追溯。
 */
@Entity
@Table(name = "task_completion_evidence")
public class TaskCompletionEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "changed_files", columnDefinition = "TEXT")
    private String changedFiles;

    @Column(name = "commit_ref", length = 200)
    private String commitRef;

    @Column(name = "completed_by", nullable = false, length = 60)
    private String completedBy;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    protected TaskCompletionEvidence() {
    }

    public TaskCompletionEvidence(Long taskId, String summary, String changedFiles, String commitRef,
                                   String completedBy) {
        this.taskId = taskId;
        this.summary = summary;
        this.changedFiles = changedFiles;
        this.commitRef = commitRef;
        this.completedBy = completedBy;
        this.completedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getSummary() {
        return summary;
    }

    public String getChangedFiles() {
        return changedFiles;
    }

    public String getCommitRef() {
        return commitRef;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}

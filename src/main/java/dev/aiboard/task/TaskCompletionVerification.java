package dev.aiboard.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 某筆 {@link TaskCompletionEvidence} 底下的單一驗證項目，例如一次測試執行、
 * 一次手動檢查。與 evidence 一樣是 append-only，不會事後修改。
 */
@Entity
@Table(name = "task_completion_verification")
public class TaskCompletionVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evidence_id", nullable = false)
    private Long evidenceId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(columnDefinition = "TEXT")
    private String detail;

    protected TaskCompletionVerification() {
    }

    public TaskCompletionVerification(Long evidenceId, String name, String result, String detail) {
        this.evidenceId = evidenceId;
        this.name = name;
        this.result = result;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getEvidenceId() {
        return evidenceId;
    }

    public String getName() {
        return name;
    }

    public String getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }
}

package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskCompletionEvidenceRepository extends JpaRepository<TaskCompletionEvidence, Long> {

    List<TaskCompletionEvidence> findByTaskIdOrderByIdAsc(Long taskId);

    @Query("SELECT e.id AS evidenceId, e.summary AS summary, e.changedFiles AS changedFiles, "
            + "e.commitRef AS commitRef, e.completedBy AS completedBy, e.completedAt AS completedAt, "
            + "v.id AS verificationId, v.name AS verificationName, v.result AS verificationResult, "
            + "v.detail AS verificationDetail FROM TaskCompletionEvidence e "
            + "LEFT JOIN TaskCompletionVerification v ON v.evidenceId = e.id "
            + "WHERE e.taskId = :taskId AND e.id = "
            + "(SELECT MAX(latest.id) FROM TaskCompletionEvidence latest WHERE latest.taskId = :taskId) "
            + "ORDER BY v.id ASC")
    List<CurrentEvidenceProjection> findCurrentEvidence(@Param("taskId") Long taskId);

    interface CurrentEvidenceProjection {
        Long getEvidenceId();
        String getSummary();
        String getChangedFiles();
        String getCommitRef();
        String getCompletedBy();
        LocalDateTime getCompletedAt();
        Long getVerificationId();
        String getVerificationName();
        String getVerificationResult();
        String getVerificationDetail();
    }
}

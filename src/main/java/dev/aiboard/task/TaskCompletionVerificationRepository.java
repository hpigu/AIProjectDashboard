package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskCompletionVerificationRepository extends JpaRepository<TaskCompletionVerification, Long> {

    List<TaskCompletionVerification> findByEvidenceIdOrderByIdAsc(Long evidenceId);
}

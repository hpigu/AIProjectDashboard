package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskCompletionEvidenceRepository extends JpaRepository<TaskCompletionEvidence, Long> {

    List<TaskCompletionEvidence> findByTaskIdOrderByIdAsc(Long taskId);
}

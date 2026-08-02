package dev.aiboard.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectAuditRepository extends JpaRepository<ProjectAudit, Long> {
}

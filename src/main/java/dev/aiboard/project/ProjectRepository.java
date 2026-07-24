package dev.aiboard.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    // 查找大小寫不敏感，但 DB 的 UNIQUE(name) 仍大小寫敏感——已知的極端並發限制，見 CLAUDE.md「已知限制」。
    Optional<Project> findByNameIgnoreCase(String name);

    List<Project> findAllByOrderByIdAsc();
}

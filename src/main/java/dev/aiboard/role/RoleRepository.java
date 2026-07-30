package dev.aiboard.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByNameAndProjectId(String name, Long projectId);

    Optional<Role> findByNameAndProjectIdIsNull(String name);

    List<Role> findAllByProjectIdIsNull();

    List<Role> findAllByProjectId(Long projectId);
}

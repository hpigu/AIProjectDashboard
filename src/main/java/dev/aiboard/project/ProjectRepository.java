package dev.aiboard.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByNormalizedName(String normalizedName);

    List<Project> findByStatus(String status);

    @Query("SELECT p FROM Project p "
            + "WHERE p.normalizedName LIKE CONCAT(:namePrefix, '%') ESCAPE '\\'")
    List<Project> findByNormalizedNamePrefix(@Param("namePrefix") String namePrefix);

    @Query("SELECT p FROM Project p "
            + "WHERE p.normalizedName LIKE CONCAT(:namePrefix, '%') ESCAPE '\\' "
            + "AND p.status = :status")
    List<Project> findByNormalizedNamePrefixAndStatus(@Param("namePrefix") String namePrefix,
                                                       @Param("status") String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") Long id);

    List<Project> findAllByOrderByIdAsc();
}

package dev.aiboard.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskBlockDependencyRepository extends JpaRepository<TaskBlockDependency, Long> {

    List<TaskBlockDependency> findByBlockEventIdOrderByIdAsc(Long blockEventId);

    List<TaskBlockDependency> findByBlockEventIdIn(List<Long> blockEventIds);
}

package dev.aiboard.project;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;

import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final BoardEventPublisher eventPublisher;

    public ProjectService(ProjectRepository projectRepository, BoardEventPublisher eventPublisher) {
        this.projectRepository = projectRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ProjectCreationResult createProject(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("專案名稱不可為空白");
        }
        String trimmedName = name.trim();

        return projectRepository.findByNameIgnoreCase(trimmedName)
                .map(existing -> new ProjectCreationResult(toDto(existing), true))
                .orElseGet(() -> createNew(trimmedName, description));
    }

    private ProjectCreationResult createNew(String trimmedName, String description) {
        try {
            Project saved = projectRepository.save(new Project(trimmedName, description));
            eventPublisher.publish(BoardEvent.projectCreated(saved.getId(), saved.getName()));
            return new ProjectCreationResult(toDto(saved), false);
        } catch (DataIntegrityViolationException e) {
            // Concurrent create_project call with the same name won the race; fall back to idempotent lookup.
            return projectRepository.findByNameIgnoreCase(trimmedName)
                    .map(existing -> new ProjectCreationResult(toDto(existing), true))
                    .orElseThrow(() -> e);
        }
    }

    public ProjectDto getById(Long id) {
        return projectRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new BoardException("找不到專案：#" + id));
    }

    public Optional<ProjectDto> findByNameIgnoreCase(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("專案名稱不可為空白");
        }
        return projectRepository.findByNameIgnoreCase(name.trim()).map(this::toDto);
    }

    public List<ProjectDto> listProjects() {
        return projectRepository.findAllByOrderByIdAsc().stream().map(this::toDto).toList();
    }

    public void assertExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new BoardException("找不到專案：#" + projectId);
        }
    }

    private ProjectDto toDto(Project project) {
        return new ProjectDto(project.getId(), project.getName(), project.getDescription(), project.getStatus());
    }

    public record ProjectDto(Long id, String name, String description, String status) {
    }

    public record ProjectCreationResult(ProjectDto project, boolean alreadyExisted) {
    }
}

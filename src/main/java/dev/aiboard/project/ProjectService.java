package dev.aiboard.project;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import dev.aiboard.common.BoardException;

import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectCreationExecutor projectCreationExecutor;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectCreationExecutor projectCreationExecutor) {
        this.projectRepository = projectRepository;
        this.projectCreationExecutor = projectCreationExecutor;
    }

    public ProjectCreationResult createProject(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("專案名稱不可為空白");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 200) {
            throw new IllegalArgumentException("專案名稱不可超過 200 字");
        }
        String normalizedName = Project.normalizeName(trimmedName);

        return projectRepository.findByNormalizedName(normalizedName)
                .map(existing -> new ProjectCreationResult(toDto(existing), true))
                .orElseGet(() -> createNew(trimmedName, normalizedName, description));
    }

    private ProjectCreationResult createNew(String trimmedName, String normalizedName,
                                            String description) {
        try {
            Project saved = projectCreationExecutor.create(trimmedName, description);
            return new ProjectCreationResult(toDto(saved), false);
        } catch (DataIntegrityViolationException e) {
            // The insert runs in its own transaction, so this lookup is safe after a concurrent conflict.
            return projectRepository.findByNormalizedName(normalizedName)
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
        return projectRepository.findByNormalizedName(Project.normalizeName(name)).map(this::toDto);
    }

    public List<ProjectDto> listProjects() {
        return projectRepository.findAllByOrderByIdAsc().stream().map(this::toDto).toList();
    }

    public void assertExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new BoardException("找不到專案：#" + projectId);
        }
    }

    public void assertExistsForUpdate(Long projectId) {
        projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BoardException("找不到專案：#" + projectId));
    }

    private ProjectDto toDto(Project project) {
        return new ProjectDto(project.getId(), project.getName(), project.getDescription(), project.getStatus());
    }

    public record ProjectDto(Long id, String name, String description, String status) {
    }

    public record ProjectCreationResult(ProjectDto project, boolean alreadyExisted) {
    }
}

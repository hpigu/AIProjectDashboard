package dev.aiboard.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectCreationExecutor projectCreationExecutor;

    private ProjectService projectService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        projectService = new ProjectService(projectRepository, projectCreationExecutor);
    }

    @Test
    void createProject_whenNameIsNew_savesAndReturnsNotExisted() {
        when(projectRepository.findByNormalizedName("個人記帳 app")).thenReturn(Optional.empty());
        Project saved = new Project("個人記帳 App", "記錄收支");
        when(projectCreationExecutor.create("個人記帳 App", "記錄收支")).thenReturn(saved);

        ProjectService.ProjectCreationResult result = projectService.createProject("個人記帳 App", "記錄收支");

        assertThat(result.alreadyExisted()).isFalse();
        assertThat(result.project().name()).isEqualTo("個人記帳 App");
        verify(projectCreationExecutor).create("個人記帳 App", "記錄收支");
    }

    @Test
    void createProject_whenNameAlreadyExists_returnsExistingWithoutSaving() {
        Project existing = new Project("個人記帳 App", "記錄收支");
        when(projectRepository.findByNormalizedName("個人記帳 app")).thenReturn(Optional.of(existing));

        ProjectService.ProjectCreationResult result = projectService.createProject("個人記帳 App", "不同描述");

        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.project().name()).isEqualTo("個人記帳 App");
        verify(projectCreationExecutor, never()).create(any(), any());
    }

    @Test
    void createProject_whenNameIsBlank_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> projectService.createProject("   ", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createProject_whenNameIsNull_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> projectService.createProject(null, "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createProject_whenNameExceedsTwoHundredCharacters_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> projectService.createProject("x".repeat(201), "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @Test
    void findByNameIgnoreCase_trimsAndDelegatesToCaseInsensitiveLookup() {
        Project existing = new Project("SMTP Tool", null);
        when(projectRepository.findByNormalizedName("smtp tool")).thenReturn(Optional.of(existing));

        var result = projectService.findByNameIgnoreCase("  smtp tool ");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("SMTP Tool");
    }

    @Test
    void createProject_whenConcurrentInsertWins_returnsExistingProject() {
        Project existing = new Project("Demo", null);
        when(projectRepository.findByNormalizedName("demo"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(projectCreationExecutor.create("Demo", null))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        ProjectService.ProjectCreationResult result = projectService.createProject("Demo", null);

        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.project().name()).isEqualTo("Demo");
    }

    @Test
    void listProjects_returnsIdOrderFromRepository() {
        when(projectRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                new Project("第一個", null), new Project("第二個", null)));

        assertThat(projectService.listProjects())
                .extracting(ProjectService.ProjectDto::name)
                .containsExactly("第一個", "第二個");
    }
}

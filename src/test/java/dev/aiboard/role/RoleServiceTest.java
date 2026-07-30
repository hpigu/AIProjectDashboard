package dev.aiboard.role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.aiboard.project.ProjectService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private ProjectService projectService;

    private RoleService roleService;

    @BeforeEach
    void setUp() {
        roleService = new RoleService(roleRepository, projectService);
    }

    @Test
    void getRole_whenProjectHasOverride_returnsOverride() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        Role override = new Role("backend-dev", "BACKEND", "本專案覆寫指引", 12L);
        when(roleRepository.findByNameAndProjectId("backend-dev", 12L)).thenReturn(Optional.of(override));

        RoleService.GetRoleResult result = roleService.getRole("backend-dev", "個人記帳 App");

        assertThat(result.found()).isTrue();
        assertThat(result.isOverride()).isTrue();
        assertThat(result.role().instructions()).isEqualTo("本專案覆寫指引");
        verify(roleRepository, never()).findByNameAndProjectIdIsNull(any());
    }

    @Test
    void getRole_whenProjectHasNoOverride_fallsBackToGeneric() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(roleRepository.findByNameAndProjectId("backend-dev", 12L)).thenReturn(Optional.empty());
        Role generic = new Role("backend-dev", "BACKEND", "通用指引", null);
        when(roleRepository.findByNameAndProjectIdIsNull("backend-dev")).thenReturn(Optional.of(generic));

        RoleService.GetRoleResult result = roleService.getRole("backend-dev", "個人記帳 App");

        assertThat(result.found()).isTrue();
        assertThat(result.isOverride()).isFalse();
        assertThat(result.role().instructions()).isEqualTo("通用指引");
    }

    @Test
    void getRole_withoutProjectName_returnsGenericDirectly() {
        Role generic = new Role("backend-dev", "BACKEND", "通用指引", null);
        when(roleRepository.findByNameAndProjectIdIsNull("backend-dev")).thenReturn(Optional.of(generic));

        RoleService.GetRoleResult result = roleService.getRole("backend-dev", null);

        assertThat(result.found()).isTrue();
        assertThat(result.isOverride()).isFalse();
        verify(projectService, never()).findByNameIgnoreCase(any());
    }

    @Test
    void getRole_whenRoleDoesNotExist_listsAvailableRoleNames() {
        when(roleRepository.findByNameAndProjectIdIsNull("unknown-role")).thenReturn(Optional.empty());
        when(roleRepository.findAllByProjectIdIsNull()).thenReturn(List.of(
                new Role("backend-dev", "BACKEND", "指引", null),
                new Role("frontend-dev", "FRONTEND", "指引", null)));

        RoleService.GetRoleResult result = roleService.getRole("unknown-role", null);

        assertThat(result.found()).isFalse();
        assertThat(result.availableRoleNames()).containsExactly("backend-dev", "frontend-dev");
    }

    @Test
    void getRole_whenProjectNotFound_returnsProjectNotFound() {
        when(projectService.findByNameIgnoreCase("不存在")).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of(
                new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE")));

        RoleService.GetRoleResult result = roleService.getRole("backend-dev", "不存在");

        assertThat(result.found()).isFalse();
        assertThat(result.projectLookupFailed()).isTrue();
        assertThat(result.availableProjects()).extracting(ProjectService.ProjectDto::name)
                .containsExactly("個人記帳 App");
    }

    @Test
    void upsertRole_whenNoExistingGenericRole_createsNew() {
        when(roleRepository.findByNameAndProjectIdIsNull("backend-dev")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleService.UpsertRoleResult result = roleService.upsertRole(
                "backend-dev", "BACKEND", "通用指引", null);

        assertThat(result.created()).isTrue();
        assertThat(result.role().name()).isEqualTo("backend-dev");
        assertThat(result.role().projectId()).isNull();
    }

    @Test
    void upsertRole_whenGenericRoleExists_updatesInPlace() {
        Role existing = new Role("backend-dev", "BACKEND", "舊指引", null);
        when(roleRepository.findByNameAndProjectIdIsNull("backend-dev")).thenReturn(Optional.of(existing));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleService.UpsertRoleResult result = roleService.upsertRole(
                "backend-dev", "BACKEND", "新指引", null);

        assertThat(result.created()).isFalse();
        assertThat(result.role().instructions()).isEqualTo("新指引");
        verify(roleRepository, never()).save(argThat(role -> role != existing));
    }

    @Test
    void upsertRole_calledTwiceForSameProject_updatesRatherThanDuplicates() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        Role existing = new Role("backend-dev", "BACKEND", "第一次寫的覆寫", 12L);
        when(roleRepository.findByNameAndProjectId("backend-dev", 12L)).thenReturn(Optional.of(existing));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleService.UpsertRoleResult result = roleService.upsertRole(
                "backend-dev", "BACKEND", "第二次更新的覆寫", "個人記帳 App");

        assertThat(result.created()).isFalse();
        assertThat(result.role().instructions()).isEqualTo("第二次更新的覆寫");
        assertThat(result.role().projectId()).isEqualTo(12L);
    }

    @Test
    void upsertRole_whenProjectNameGiven_createsProjectScopedOverride() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(roleRepository.findByNameAndProjectId("backend-dev", 12L)).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleService.UpsertRoleResult result = roleService.upsertRole(
                "backend-dev", "BACKEND", "本專案覆寫指引", "個人記帳 App");

        assertThat(result.created()).isTrue();
        assertThat(result.role().projectId()).isEqualTo(12L);
        assertThat(result.project().name()).isEqualTo("個人記帳 App");
    }

    @Test
    void upsertRole_whenProjectNotFound_returnsProjectNotFound() {
        when(projectService.findByNameIgnoreCase("不存在")).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of());

        RoleService.UpsertRoleResult result = roleService.upsertRole(
                "backend-dev", "BACKEND", "指引", "不存在");

        assertThat(result.projectLookupFailed()).isTrue();
        verify(roleRepository, never()).save(any());
    }

    @Test
    void upsertRole_whenInstructionsBlank_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> roleService.upsertRole("backend-dev", "BACKEND", "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listRoles_withoutProjectName_returnsGenericRolesOnly() {
        when(roleRepository.findAllByProjectIdIsNull()).thenReturn(List.of(
                new Role("backend-dev", "BACKEND", "指引", null)));

        RoleService.ListRolesResult result = roleService.listRoles(null);

        assertThat(result.roles()).hasSize(1);
        assertThat(result.roles().get(0).isOverride()).isFalse();
        verify(projectService, never()).findByNameIgnoreCase(any());
    }

    @Test
    void listRoles_withProjectName_marksOverriddenRoles() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(roleRepository.findAllByProjectIdIsNull()).thenReturn(List.of(
                new Role("backend-dev", "BACKEND", "通用指引", null),
                new Role("frontend-dev", "FRONTEND", "通用指引", null)));
        when(roleRepository.findAllByProjectId(12L)).thenReturn(List.of(
                new Role("backend-dev", "BACKEND", "覆寫指引", 12L)));

        RoleService.ListRolesResult result = roleService.listRoles("個人記帳 App");

        assertThat(result.roles()).hasSize(2);
        assertThat(result.roles().stream()
                .filter(r -> r.name().equals("backend-dev")).findFirst().orElseThrow().isOverride())
                .isTrue();
        assertThat(result.roles().stream()
                .filter(r -> r.name().equals("frontend-dev")).findFirst().orElseThrow().isOverride())
                .isFalse();
    }

    @Test
    void listRoles_whenProjectNotFound_returnsAvailableProjects() {
        when(projectService.findByNameIgnoreCase("不存在")).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of(
                new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE")));

        RoleService.ListRolesResult result = roleService.listRoles("不存在");

        assertThat(result.projectRequestedButNotFound()).isTrue();
        assertThat(result.availableProjects()).extracting(ProjectService.ProjectDto::name)
                .containsExactly("個人記帳 App");
    }
}

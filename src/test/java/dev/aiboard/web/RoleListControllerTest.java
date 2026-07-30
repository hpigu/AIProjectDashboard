package dev.aiboard.web;

import dev.aiboard.project.ProjectService;
import dev.aiboard.role.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleListControllerTest {

    @Mock
    private RoleService roleService;

    @Test
    void listRoles_withoutProjectName_returnsGenericRolesWithInstructions() {
        when(roleService.listRoles(null)).thenReturn(RoleService.ListRolesResult.found(null, List.of(
                new RoleService.RoleSummary("backend-dev", "BACKEND", false))));
        var role = new RoleService.RoleDto(1L, "backend-dev", "BACKEND", "通用指引內容", null);
        when(roleService.getRole("backend-dev", null))
                .thenReturn(RoleService.GetRoleResult.found(role, false, null));

        RoleListController controller = new RoleListController(roleService);
        List<RoleListController.RoleView> result = controller.listRoles(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("backend-dev");
        assertThat(result.get(0).category()).isEqualTo("BACKEND");
        assertThat(result.get(0).instructions()).isEqualTo("通用指引內容");
        assertThat(result.get(0).isOverride()).isFalse();
    }

    @Test
    void listRoles_withProjectOverride_marksIsOverrideTrue() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(roleService.listRoles("個人記帳 App")).thenReturn(RoleService.ListRolesResult.found(project, List.of(
                new RoleService.RoleSummary("backend-dev", "BACKEND", true),
                new RoleService.RoleSummary("frontend-dev", "FRONTEND", false))));

        var overrideRole = new RoleService.RoleDto(2L, "backend-dev", "BACKEND", "本專案覆寫指引", 12L);
        when(roleService.getRole("backend-dev", "個人記帳 App"))
                .thenReturn(RoleService.GetRoleResult.found(overrideRole, true, project));
        var genericRole = new RoleService.RoleDto(1L, "frontend-dev", "FRONTEND", "通用指引", null);
        when(roleService.getRole("frontend-dev", "個人記帳 App"))
                .thenReturn(RoleService.GetRoleResult.found(genericRole, false, project));

        RoleListController controller = new RoleListController(roleService);
        List<RoleListController.RoleView> result = controller.listRoles("個人記帳 App");

        assertThat(result).hasSize(2);
        var backend = result.stream().filter(r -> r.name().equals("backend-dev")).findFirst().orElseThrow();
        assertThat(backend.isOverride()).isTrue();
        assertThat(backend.instructions()).isEqualTo("本專案覆寫指引");

        var frontend = result.stream().filter(r -> r.name().equals("frontend-dev")).findFirst().orElseThrow();
        assertThat(frontend.isOverride()).isFalse();
        assertThat(frontend.instructions()).isEqualTo("通用指引");
    }

    @Test
    void listRoles_whenProjectNotFound_returnsEmptyList() {
        when(roleService.listRoles("不存在")).thenReturn(RoleService.ListRolesResult.projectNotFound(List.of()));

        RoleListController controller = new RoleListController(roleService);
        List<RoleListController.RoleView> result = controller.listRoles("不存在");

        assertThat(result).isEmpty();
    }
}

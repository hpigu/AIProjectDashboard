package dev.aiboard.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.aiboard.project.ProjectService;
import dev.aiboard.role.RoleService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleToolsTest {

    @Mock
    private RoleService roleService;

    private RoleTools tools;

    @BeforeEach
    void setUp() {
        tools = new RoleTools(roleService);
    }

    @Test
    void getRole_whenOverrideFound_showsOverrideLabel() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var role = new RoleService.RoleDto(1L, "backend-dev", "BACKEND", "本專案覆寫指引", 12L);
        when(roleService.getRole("backend-dev", "個人記帳 App"))
                .thenReturn(RoleService.GetRoleResult.found(role, true, project));

        String result = tools.getRole("backend-dev", "個人記帳 App");

        assertThat(result).contains("backend-dev");
        assertThat(result).contains("個人記帳 App 專案覆寫版");
        assertThat(result).contains("本專案覆寫指引");
    }

    @Test
    void getRole_whenGenericFound_showsGenericLabel() {
        var role = new RoleService.RoleDto(1L, "backend-dev", "BACKEND", "通用指引", null);
        when(roleService.getRole("backend-dev", null))
                .thenReturn(RoleService.GetRoleResult.found(role, false, null));

        String result = tools.getRole("backend-dev", null);

        assertThat(result).contains("backend-dev");
        assertThat(result).contains("通用版");
        assertThat(result).contains("通用指引");
    }

    @Test
    void getRole_whenNotFound_listsAvailableRoles() {
        when(roleService.getRole("unknown-role", null))
                .thenReturn(RoleService.GetRoleResult.roleNotFound(List.of("backend-dev", "frontend-dev")));

        String result = tools.getRole("unknown-role", null);

        assertThat(result).contains("找不到角色「unknown-role」")
                .contains("backend-dev")
                .contains("frontend-dev");
    }

    @Test
    void getRole_whenProjectNotFound_listsAvailableProjects() {
        var projects = List.of(new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE"));
        when(roleService.getRole("backend-dev", "不存在"))
                .thenReturn(RoleService.GetRoleResult.projectNotFound(projects));

        String result = tools.getRole("backend-dev", "不存在");

        assertThat(result).contains("找不到專案「不存在」").contains("#12 個人記帳 App");
    }

    @Test
    void listRoles_marksOverrideRoles() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        when(roleService.listRoles("個人記帳 App")).thenReturn(RoleService.ListRolesResult.found(project, List.of(
                new RoleService.RoleSummary("backend-dev", "BACKEND", true),
                new RoleService.RoleSummary("frontend-dev", "FRONTEND", false))));

        String result = tools.listRoles("個人記帳 App");

        assertThat(result).contains("backend-dev [BACKEND]（本專案覆寫）");
        assertThat(result).contains("frontend-dev [FRONTEND]");
        assertThat(result).doesNotContain("frontend-dev [FRONTEND]（本專案覆寫）");
    }

    @Test
    void listRoles_withoutProjectName_showsGenericHeading() {
        when(roleService.listRoles(null)).thenReturn(RoleService.ListRolesResult.found(null, List.of(
                new RoleService.RoleSummary("backend-dev", "BACKEND", false))));

        String result = tools.listRoles(null);

        assertThat(result).contains("## 通用角色");
    }

    @Test
    void listRoles_whenProjectNotFound_listsAvailableProjects() {
        var projects = List.of(new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE"));
        when(roleService.listRoles("不存在")).thenReturn(RoleService.ListRolesResult.projectNotFound(projects));

        String result = tools.listRoles("不存在");

        assertThat(result).contains("找不到專案「不存在」").contains("#12 個人記帳 App");
    }

    @Test
    void upsertRole_whenCreated_returnsCreatedMessage() {
        var role = new RoleService.RoleDto(1L, "backend-dev", "BACKEND", "通用指引", null);
        when(roleService.upsertRole("backend-dev", "BACKEND", "通用指引", null))
                .thenReturn(RoleService.UpsertRoleResult.created(role, null));

        String result = tools.upsertRole("backend-dev", "BACKEND", "通用指引", null);

        assertThat(result).isEqualTo("已建立角色「backend-dev」（通用版）");
    }

    @Test
    void upsertRole_whenUpdatedWithProjectScope_returnsUpdatedMessage() {
        var project = new ProjectService.ProjectDto(12L, "個人記帳 App", null, "ACTIVE");
        var role = new RoleService.RoleDto(1L, "backend-dev", "BACKEND", "新指引", 12L);
        when(roleService.upsertRole("backend-dev", "BACKEND", "新指引", "個人記帳 App"))
                .thenReturn(RoleService.UpsertRoleResult.updated(role, project));

        String result = tools.upsertRole("backend-dev", "BACKEND", "新指引", "個人記帳 App");

        assertThat(result).isEqualTo("已更新角色「backend-dev」（「個人記帳 App」專案覆寫版）");
    }

    @Test
    void upsertRole_whenProjectNotFound_listsAvailableProjects() {
        when(roleService.upsertRole("backend-dev", "BACKEND", "指引", "不存在"))
                .thenReturn(RoleService.UpsertRoleResult.projectNotFound(List.of()));

        String result = tools.upsertRole("backend-dev", "BACKEND", "指引", "不存在");

        assertThat(result).contains("找不到專案「不存在」");
    }

    @Test
    void upsertRole_withoutOptionalCategoryAndProjectName_stillWorks() {
        var role = new RoleService.RoleDto(1L, "backend-dev", null, "通用指引", null);
        when(roleService.upsertRole("backend-dev", null, "通用指引", null))
                .thenReturn(RoleService.UpsertRoleResult.created(role, null));

        String result = tools.upsertRole("backend-dev", null, "通用指引", null);

        assertThat(result).isEqualTo("已建立角色「backend-dev」（通用版）");
    }
}

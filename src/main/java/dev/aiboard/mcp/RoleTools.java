package dev.aiboard.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import dev.aiboard.project.ProjectService;
import dev.aiboard.role.RoleService;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RoleTools {

    private final RoleService roleService;

    public RoleTools(RoleService roleService) {
        this.roleService = roleService;
    }

    @Tool(name = "list_roles",
            description = "列出可用的角色（名稱、分類、是通用指引還是專案覆寫）。"
                    + "帶 projectName 時，若該專案對某角色有覆寫版本，會取代通用版顯示；"
                    + "不帶 projectName 只列出通用角色。想知道某角色的完整工作指引請改用 get_role。")
    public String listRoles(
            @ToolParam(description = "專案名稱，精確比對但不分大小寫；不填只列出通用角色",
                    required = false) String projectName) {
        RoleService.ListRolesResult result = roleService.listRoles(projectName);
        if (result.projectRequestedButNotFound()) {
            return projectNotFoundMessage(projectName, result.availableProjects());
        }

        if (result.roles().isEmpty()) {
            return "目前沒有任何角色。可用 upsert_role 建立。";
        }

        StringBuilder sb = new StringBuilder();
        if (result.project() != null) {
            sb.append("## %s（#%d）可用角色\n".formatted(result.project().name(), result.project().id()));
        } else {
            sb.append("## 通用角色\n");
        }
        for (RoleService.RoleSummary role : result.roles()) {
            String categoryLabel = role.category() != null && !role.category().isBlank()
                    ? " [%s]".formatted(role.category()) : "";
            String overrideLabel = role.isOverride() ? "（本專案覆寫）" : "";
            sb.append("- %s%s%s\n".formatted(role.name(), categoryLabel, overrideLabel));
        }
        return sb.toString();
    }

    @Tool(name = "get_role",
            description = "取得某個角色的完整工作指引。帶 projectName 時，若該專案有覆寫版本就回傳覆寫版，"
                    + "否則回傳通用版；不帶 projectName 一律回傳通用版。"
                    + "認領任務前呼叫這個工具了解該以什麼身分、遵守什麼規則工作。"
                    + "角色不存在時會列出現有角色供選。")
    public String getRole(
            @ToolParam(description = "角色名稱，例如 backend-dev") String name,
            @ToolParam(description = "專案名稱，精確比對但不分大小寫；用來套用該專案的覆寫版指引",
                    required = false) String projectName) {
        RoleService.GetRoleResult result = roleService.getRole(name, projectName);
        if (result.projectLookupFailed()) {
            return projectNotFoundMessage(projectName, result.availableProjects());
        }
        if (!result.found()) {
            String names = result.availableRoleNames().isEmpty()
                    ? "（目前沒有任何角色）"
                    : String.join("、", result.availableRoleNames());
            return "找不到角色「%s」。目前可用的角色有：\n%s".formatted(name, names);
        }

        RoleService.RoleDto role = result.role();
        String categoryLabel = role.category() != null && !role.category().isBlank()
                ? " [%s]".formatted(role.category()) : "";
        String scopeLabel = result.isOverride()
                ? "（%s 專案覆寫版）".formatted(result.project().name())
                : "（通用版）";
        return "## %s%s %s\n\n%s".formatted(role.name(), categoryLabel, scopeLabel, role.instructions());
    }

    @Tool(name = "upsert_role",
            description = "建立或更新角色的工作指引。不帶 projectName 時操作通用版；"
                    + "帶 projectName 時操作該專案的覆寫版本（不影響通用版與其他專案）。"
                    + "同名同範圍（通用或同一專案）再次呼叫是更新，不會產生重複角色。")
    public String upsertRole(
            @ToolParam(description = "角色名稱，例如 backend-dev") String name,
            @ToolParam(description = "角色分類，例如 BACKEND，非強制格式", required = false) String category,
            @ToolParam(description = "完整工作指引內容") String instructions,
            @ToolParam(description = "專案名稱，精確比對但不分大小寫；不填代表建立/更新通用版",
                    required = false) String projectName) {
        RoleService.UpsertRoleResult result = roleService.upsertRole(name, category, instructions, projectName);
        if (result.projectLookupFailed()) {
            return projectNotFoundMessage(projectName, result.availableProjects());
        }

        RoleService.RoleDto role = result.role();
        String scopeLabel = result.project() != null
                ? "「%s」專案覆寫版".formatted(result.project().name())
                : "通用版";
        String verb = result.created() ? "已建立" : "已更新";
        return "%s角色「%s」（%s）".formatted(verb, role.name(), scopeLabel);
    }

    private static String projectNotFoundMessage(String projectName,
                                                  List<ProjectService.ProjectDto> availableProjects) {
        String projects = availableProjects.isEmpty()
                ? "（看板上目前沒有專案）"
                : availableProjects.stream()
                        .map(p -> "#%d %s".formatted(p.id(), p.name()))
                        .collect(Collectors.joining("、"));
        return "找不到專案「%s」。看板上目前有：\n%s".formatted(projectName, projects);
    }
}

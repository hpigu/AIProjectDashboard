package dev.aiboard.web;

import dev.aiboard.role.RoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 唯讀角色清單，供前端顯示每個角色的分類與完整工作指引。
 * 不帶 projectName 只回通用角色；帶 projectName 會用該專案的覆寫版取代同名通用角色，
 * 並標示 isOverride，讓畫面分得出哪些是通用、哪些是專案覆寫。
 */
@RestController
public class RoleListController {

    private final RoleService roleService;

    public RoleListController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/api/roles")
    public List<RoleView> listRoles(
            @RequestParam(name = "projectName", required = false) String projectName) {
        RoleService.ListRolesResult result = roleService.listRoles(projectName);
        if (result.projectRequestedButNotFound()) {
            return List.of();
        }

        return result.roles().stream()
                .map(summary -> {
                    RoleService.GetRoleResult detail = roleService.getRole(summary.name(), projectName);
                    String instructions = detail.found() ? detail.role().instructions() : "";
                    return new RoleView(summary.name(), summary.category(), instructions, summary.isOverride());
                })
                .toList();
    }

    public record RoleView(String name, String category, String instructions, boolean isOverride) {
    }
}

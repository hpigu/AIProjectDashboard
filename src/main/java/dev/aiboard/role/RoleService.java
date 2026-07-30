package dev.aiboard.role;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.aiboard.project.ProjectService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final ProjectService projectService;

    public RoleService(RoleRepository roleRepository, ProjectService projectService) {
        this.roleRepository = roleRepository;
        this.projectService = projectService;
    }

    /**
     * 列出可用角色。帶 projectName 時，同名角色以專案覆寫版取代通用版；
     * 找不到專案時回傳 projectNotFound 讓呼叫端列出現有專案。
     */
    public ListRolesResult listRoles(String projectName) {
        List<Role> generic = roleRepository.findAllByProjectIdIsNull();

        if (projectName == null || projectName.isBlank()) {
            return ListRolesResult.found(null, toSummaries(generic, false));
        }

        var project = projectService.findByNameIgnoreCase(projectName);
        if (project.isEmpty()) {
            return ListRolesResult.projectNotFound(projectService.listProjects());
        }

        List<Role> overrides = roleRepository.findAllByProjectId(project.get().id());
        Map<String, Role> byName = new LinkedHashMap<>();
        for (Role role : generic) {
            byName.put(role.getName(), role);
        }
        Map<String, Boolean> isOverride = new LinkedHashMap<>();
        for (Role role : generic) {
            isOverride.put(role.getName(), false);
        }
        for (Role role : overrides) {
            byName.put(role.getName(), role);
            isOverride.put(role.getName(), true);
        }

        List<RoleSummary> summaries = byName.values().stream()
                .map(role -> new RoleSummary(role.getName(), role.getCategory(),
                        isOverride.getOrDefault(role.getName(), false)))
                .toList();
        return ListRolesResult.found(project.get(), summaries);
    }

    private List<RoleSummary> toSummaries(List<Role> roles, boolean override) {
        return roles.stream()
                .map(role -> new RoleSummary(role.getName(), role.getCategory(), override))
                .toList();
    }

    /**
     * 取得角色工作指引：先找專案專屬覆寫，找不到再回通用版；兩者都沒有則回 notFound。
     */
    public GetRoleResult getRole(String name, String projectName) {
        String normalizedName = normalizeName(name);

        ProjectService.ProjectDto project = null;
        if (projectName != null && !projectName.isBlank()) {
            var found = projectService.findByNameIgnoreCase(projectName);
            if (found.isEmpty()) {
                return GetRoleResult.projectNotFound(projectService.listProjects());
            }
            project = found.get();

            Optional<Role> override = roleRepository.findByNameAndProjectId(normalizedName, project.id());
            if (override.isPresent()) {
                return GetRoleResult.found(toDto(override.get()), true, project);
            }
        }

        Optional<Role> generic = roleRepository.findByNameAndProjectIdIsNull(normalizedName);
        if (generic.isPresent()) {
            return GetRoleResult.found(toDto(generic.get()), false, project);
        }

        return GetRoleResult.roleNotFound(listAllRoleNames());
    }

    /**
     * 建立或更新角色指引。projectName 有值時操作該專案的覆寫版，否則操作通用版。
     */
    @Transactional
    public UpsertRoleResult upsertRole(String name, String category, String instructions, String projectName) {
        String normalizedName = normalizeName(name);
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("角色指引 instructions 不可為空白");
        }

        Long projectId = null;
        ProjectService.ProjectDto project = null;
        if (projectName != null && !projectName.isBlank()) {
            var found = projectService.findByNameIgnoreCase(projectName);
            if (found.isEmpty()) {
                return UpsertRoleResult.projectNotFound(projectService.listProjects());
            }
            project = found.get();
            projectId = project.id();
        }

        Optional<Role> existing = projectId == null
                ? roleRepository.findByNameAndProjectIdIsNull(normalizedName)
                : roleRepository.findByNameAndProjectId(normalizedName, projectId);

        if (existing.isPresent()) {
            Role role = existing.get();
            role.updateInstructions(category, instructions);
            Role saved = roleRepository.save(role);
            return UpsertRoleResult.updated(toDto(saved), project);
        }

        Role role = new Role(normalizedName, category, instructions, projectId);
        Role saved = roleRepository.save(role);
        return UpsertRoleResult.created(toDto(saved), project);
    }

    /**
     * 只在角色尚未存在時建立，已存在就原樣保留。
     * 供啟動時匯入初始指引用：看板上的內容是正本，使用者透過 upsert_role
     * 調整過的指引不該被重啟覆寫回程式碼裡的版本。
     */
    @Transactional
    public UpsertRoleResult createRoleIfAbsent(String name, String category, String instructions,
                                                String projectName) {
        String normalizedName = normalizeName(name);
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("角色指引 instructions 不可為空白");
        }

        Long projectId = null;
        ProjectService.ProjectDto project = null;
        if (projectName != null && !projectName.isBlank()) {
            var found = projectService.findByNameIgnoreCase(projectName);
            if (found.isEmpty()) {
                return UpsertRoleResult.projectNotFound(projectService.listProjects());
            }
            project = found.get();
            projectId = project.id();
        }

        Optional<Role> existing = projectId == null
                ? roleRepository.findByNameAndProjectIdIsNull(normalizedName)
                : roleRepository.findByNameAndProjectId(normalizedName, projectId);
        if (existing.isPresent()) {
            return UpsertRoleResult.kept(toDto(existing.get()), project);
        }

        Role saved = roleRepository.save(new Role(normalizedName, category, instructions, projectId));
        return UpsertRoleResult.created(toDto(saved), project);
    }

    private List<String> listAllRoleNames() {
        List<Role> generic = roleRepository.findAllByProjectIdIsNull();
        List<String> names = new ArrayList<>();
        for (Role role : generic) {
            names.add(role.getName());
        }
        return names;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("角色名稱不可為空白");
        }
        return name.trim();
    }

    private RoleDto toDto(Role role) {
        return new RoleDto(role.getId(), role.getName(), role.getCategory(), role.getInstructions(),
                role.getProjectId());
    }

    public record RoleDto(Long id, String name, String category, String instructions, Long projectId) {
    }

    /** 角色摘要：名稱、分類、是否為某專案的覆寫版本。 */
    public record RoleSummary(String name, String category, boolean isOverride) {
    }

    public record ListRolesResult(ProjectService.ProjectDto project, List<RoleSummary> roles,
                                   List<ProjectService.ProjectDto> availableProjects,
                                   boolean projectNotFound) {
        public static ListRolesResult found(ProjectService.ProjectDto project, List<RoleSummary> roles) {
            return new ListRolesResult(project, roles, List.of(), false);
        }

        public static ListRolesResult projectNotFound(List<ProjectService.ProjectDto> availableProjects) {
            return new ListRolesResult(null, List.of(), availableProjects, true);
        }

        public boolean projectRequestedButNotFound() {
            return projectNotFound;
        }
    }

    public record GetRoleResult(RoleDto role, boolean isOverride, ProjectService.ProjectDto project,
                                 List<String> availableRoleNames,
                                 List<ProjectService.ProjectDto> availableProjects,
                                 boolean projectNotFound) {
        public static GetRoleResult found(RoleDto role, boolean isOverride, ProjectService.ProjectDto project) {
            return new GetRoleResult(role, isOverride, project, List.of(), List.of(), false);
        }

        public static GetRoleResult roleNotFound(List<String> availableRoleNames) {
            return new GetRoleResult(null, false, null, availableRoleNames, List.of(), false);
        }

        public static GetRoleResult projectNotFound(List<ProjectService.ProjectDto> availableProjects) {
            return new GetRoleResult(null, false, null, List.of(), availableProjects, true);
        }

        public boolean found() {
            return role != null;
        }

        public boolean projectLookupFailed() {
            return projectNotFound;
        }
    }

    public record UpsertRoleResult(RoleDto role, boolean created, ProjectService.ProjectDto project,
                                    List<ProjectService.ProjectDto> availableProjects,
                                    boolean projectNotFound) {
        public static UpsertRoleResult created(RoleDto role, ProjectService.ProjectDto project) {
            return new UpsertRoleResult(role, true, project, List.of(), false);
        }

        public static UpsertRoleResult updated(RoleDto role, ProjectService.ProjectDto project) {
            return new UpsertRoleResult(role, false, project, List.of(), false);
        }

        /** 角色已存在、內容原樣保留（createRoleIfAbsent 用）。 */
        public static UpsertRoleResult kept(RoleDto role, ProjectService.ProjectDto project) {
            return new UpsertRoleResult(role, false, project, List.of(), false);
        }

        public static UpsertRoleResult projectNotFound(List<ProjectService.ProjectDto> availableProjects) {
            return new UpsertRoleResult(null, false, null, availableProjects, true);
        }

        public boolean projectLookupFailed() {
            return projectNotFound;
        }
    }
}

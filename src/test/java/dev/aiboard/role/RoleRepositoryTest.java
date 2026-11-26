package dev.aiboard.role;

import dev.aiboard.project.Project;
import dev.aiboard.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 對真正跑過 V6 migration 的 H2 資料庫驗證 role 的兩層設計與唯一性約束，
 * 而非憑假設。特別驗證 NULL（通用指引）在 UNIQUE 索引下的實際行為。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:role-repository;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-role-repository-test.log"
})
class RoleRepositoryTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RoleSeeder roleSeeder;

    @Autowired
    private RoleService roleService;

    @BeforeEach
    void cleanDatabase() {
        roleRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void seederDoesNotInstallOverridesForAProjectNamedAgentDashboard() {
        Project project = projectRepository.save(new Project("agentdashboard", null));

        roleSeeder.run(new DefaultApplicationArguments());

        assertThat(roleRepository.findAllByProjectId(project.getId())).isEmpty();
        assertThat(roleService.listRoles("AgentDashboard").roles())
                .hasSize(5)
                .allSatisfy(role -> assertThat(role.isOverride()).isFalse());
        var generic = roleService.getRole("backend-dev", null);
        var effective = roleService.getRole("backend-dev", "AgentDashboard");
        assertThat(effective.role()).isEqualTo(generic.role());
    }

    @Test
    void seederKeepsExistingGenericAndProjectInstructionsOnRepeatedStartup() {
        Project project = projectRepository.save(new Project("AgentDashboard", null));
        roleService.upsertRole("backend-dev", "BACKEND", "客製通用指引", null);
        roleService.upsertRole("backend-dev", "BACKEND", "客製專案指引", project.getName());

        roleSeeder.run(new DefaultApplicationArguments());
        roleSeeder.run(new DefaultApplicationArguments());

        assertThat(roleRepository.findAllByProjectIdIsNull()).extracting(Role::getName)
                .containsExactlyInAnyOrder("backend-dev", "frontend-dev", "qa", "infra", "docs");
        assertThat(roleRepository.findAllByProjectId(project.getId())).hasSize(1);
        assertThat(roleService.getRole("backend-dev", null).role().instructions()).isEqualTo("客製通用指引");
        var effective = roleService.getRole("backend-dev", project.getName());
        assertThat(effective.isOverride()).isTrue();
        assertThat(effective.role().instructions()).isEqualTo("客製專案指引");
    }

    @Test
    void genericRoleAndProjectOverrideCanCoexistWithSameName() {
        Project project = projectRepository.save(new Project("記帳 App", null));

        roleRepository.saveAndFlush(new Role("backend-dev", "BACKEND", "通用指引", null));
        roleRepository.saveAndFlush(
                new Role("backend-dev", "BACKEND", "本專案覆寫指引", project.getId()));

        assertThat(roleRepository.findByNameAndProjectIdIsNull("backend-dev")).isPresent();
        assertThat(roleRepository.findByNameAndProjectId("backend-dev", project.getId()))
                .isPresent();
        assertThat(roleRepository.findAll()).hasSize(2);
    }

    @Test
    void duplicateGenericRoleWithSameNameIsRejected() {
        roleRepository.saveAndFlush(new Role("backend-dev", "BACKEND", "通用指引", null));

        assertThatThrownBy(() ->
                roleRepository.saveAndFlush(new Role("backend-dev", "BACKEND", "另一份通用指引", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateRoleWithSameNameInSameProjectIsRejected() {
        Project project = projectRepository.save(new Project("記帳 App", null));
        roleRepository.saveAndFlush(new Role("backend-dev", "BACKEND", "覆寫指引", project.getId()));

        assertThatThrownBy(() -> roleRepository.saveAndFlush(
                new Role("backend-dev", "BACKEND", "另一份覆寫指引", project.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameRoleNameAcrossDifferentProjectsIsAllowed() {
        Project projectA = projectRepository.save(new Project("專案 A", null));
        Project projectB = projectRepository.save(new Project("專案 B", null));

        roleRepository.saveAndFlush(new Role("backend-dev", "BACKEND", "A 的覆寫", projectA.getId()));
        roleRepository.saveAndFlush(new Role("backend-dev", "BACKEND", "B 的覆寫", projectB.getId()));

        assertThat(roleRepository.findAllByProjectId(projectA.getId())).hasSize(1);
        assertThat(roleRepository.findAllByProjectId(projectB.getId())).hasSize(1);
    }
}

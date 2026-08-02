package dev.aiboard.task;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies an already-deployed V9 schema can advance through the read-performance migrations. */
class MigrationCompatibilityTest {

    @Test
    void oldV9DatabaseMigratesToLatestAndGainsTaskHistoryIndex() throws Exception {
        String url = "jdbc:h2:mem:legacy-v9-to-v11;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("9").load().migrate();

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();

        Set<String> indexes = new HashSet<>();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var rows = connection.getMetaData().getIndexInfo(null, "PUBLIC", "TASK_LOG", false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) {
                    indexes.add(name.toUpperCase());
                }
            }
        }

        assertThat(indexes).contains("IDX_TASK_LOG_TASK_ID_ID");
    }

    /**
     * #131 方案 B1：既有資料（部署 V12 之前建立的任務）遷移後 require_evidence
     * 必須維持 FALSE，不回填，update_task_status 對它們的行為才會完全不變。
     */
    @Test
    void existingTaskFromBeforeV12MigratesWithRequireEvidenceFalse() throws Exception {
        String url = "jdbc:h2:mem:legacy-v11-to-v12;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("11").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute(
                    "INSERT INTO project (id, name, normalized_name, status, created_at, updated_at) "
                            + "VALUES (1, '既有專案', '既有專案', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            connection.createStatement().execute(
                    "INSERT INTO task (id, project_id, title, status, category, sort_order, "
                            + "created_at, updated_at, version) "
                            + "VALUES (1, 1, '既有任務', 'DONE', 'OTHER', 0, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT require_evidence FROM task WHERE id = 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean("require_evidence")).isFalse();
        }
    }
}

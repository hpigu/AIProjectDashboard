package dev.aiboard.task;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies an already-deployed V9 schema can advance through the read-performance migrations. */
class MigrationCompatibilityTest {

    @TempDir
    Path tempDir;

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

    /**
     * #150：用真正的 file-backed H2 模擬從舊 repo 搬來的 V1 資料庫。測試刻意把
     * 路徑、專案名稱、任務描述與歷史紀錄都放入空白及非 ASCII 字元，並在每個中繼
     * 版本寫完資料後都執行 SHUTDOWN，確保後續步驟真的是另一個行程可能做的冷
     * 升級，而不是同一個記憶體資料庫連線內的假象。額外在 V5（{@code
     * task_dependency} 剛建表）寫入第二個任務與一筆相依關係，證明「較舊但已
     * 累積相依資料的資料庫」升級到最新版後，相依關係本身（不只是表結構）不
     * 遺失——涵蓋驗收條件裡「資料／任務／相依完整」的相依部分。
     */
    @Test
    void oldFileBackedV1DatabaseUpgradesToLatestWithoutLosingUserDataAndDependencies() throws Exception {
        Path databaseDir = tempDir.resolve("舊 repo with spaces").resolve("data");
        Files.createDirectories(databaseDir);
        String url = "jdbc:h2:file:" + databaseDir.resolve("board") + ";DB_CLOSE_ON_EXIT=FALSE";

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("1").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var project = connection.prepareStatement(
                    "INSERT INTO project (id, name, description, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                project.setLong(1, 101L);
                project.setString(2, "  舊專案 É  ");
                project.setString(3, "保留 emoji 🚦、引號 ' 與換行\n第二行");
                project.setString(4, "ACTIVE");
                project.executeUpdate();
            }
            try (var task = connection.prepareStatement(
                    "INSERT INTO task (id, project_id, title, description, status, category, sort_order, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                task.setLong(1, 201L);
                task.setLong(2, 101L);
                task.setString(3, "舊任務 / old task");
                task.setString(4, "敏感字樣 PASSWORD=should-stay-in-db-only");
                task.setString(5, "DONE");
                task.setNull(6, java.sql.Types.VARCHAR);
                task.setInt(7, 7);
                task.executeUpdate();
            }
            try (var log = connection.prepareStatement(
                    "INSERT INTO task_log (id, task_id, from_status, to_status, note, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                log.setLong(1, 301L);
                log.setLong(2, 201L);
                log.setString(3, "IN_PROGRESS");
                log.setString(4, "DONE");
                log.setString(5, "舊歷史不可遺失");
                log.executeUpdate();
            }
            connection.createStatement().execute("SHUTDOWN");
        }

        // task_dependency 要到 V5 才存在；先只升到 V5，寫入第二個舊任務與一筆
        // 相依關係，代表「陌生人的舊資料庫已經跑過幾版、累積了相依資料」，而不是
        // 全新建表後才插入，再次 SHUTDOWN 後繼續升到最新版。
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("5").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var task = connection.prepareStatement(
                    "INSERT INTO task (id, project_id, title, description, status, category, sort_order, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                task.setLong(1, 202L);
                task.setLong(2, 101L);
                task.setString(3, "前置任務 / blocking task");
                task.setString(4, "被 201 相依的舊任務");
                task.setString(5, "TODO");
                task.setString(6, "OTHER");
                task.setInt(7, 6);
                task.executeUpdate();
            }
            try (var dependency = connection.prepareStatement(
                    "INSERT INTO task_dependency (id, task_id, depends_on_task_id, created_at) "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                dependency.setLong(1, 401L);
                dependency.setLong(2, 201L);
                dependency.setLong(3, 202L);
                dependency.executeUpdate();
            }
            connection.createStatement().execute("SHUTDOWN");
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var row = connection.createStatement().executeQuery(
                    "SELECT name, normalized_name, description, status FROM project WHERE id = 101")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("name")).isEqualTo("  舊專案 É  ");
                assertThat(row.getString("normalized_name")).isEqualTo("舊專案 é");
                assertThat(row.getString("description")).isEqualTo("保留 emoji 🚦、引號 ' 與換行\n第二行");
                assertThat(row.getString("status")).isEqualTo("ACTIVE");
            }

            try (var row = connection.createStatement().executeQuery(
                    "SELECT title, description, status, category, sort_order, version, assignee, claimed_at, "
                            + "claim_token_hash, current_block_event_id, require_evidence FROM task WHERE id = 201")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("title")).isEqualTo("舊任務 / old task");
                assertThat(row.getString("description")).isEqualTo("敏感字樣 PASSWORD=should-stay-in-db-only");
                assertThat(row.getString("status")).isEqualTo("DONE");
                assertThat(row.getString("category")).isEqualTo("OTHER");
                assertThat(row.getInt("sort_order")).isEqualTo(7);
                assertThat(row.getLong("version")).isZero();
                assertThat(row.getString("assignee")).isNull();
                assertThat(row.getTimestamp("claimed_at")).isNull();
                assertThat(row.getString("claim_token_hash")).isNull();
                assertThat(row.getObject("current_block_event_id")).isNull();
                assertThat(row.getBoolean("require_evidence")).isFalse();
            }

            try (var row = connection.createStatement().executeQuery(
                    "SELECT from_status, to_status, note FROM task_log WHERE id = 301")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("from_status")).isEqualTo("IN_PROGRESS");
                assertThat(row.getString("to_status")).isEqualTo("DONE");
                assertThat(row.getString("note")).isEqualTo("舊歷史不可遺失");
            }

            try (var row = connection.createStatement().executeQuery(
                    "SELECT status, sort_order FROM task WHERE id = 202")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("TODO");
                assertThat(row.getInt("sort_order")).isEqualTo(6);
            }

            try (var row = connection.createStatement().executeQuery(
                    "SELECT task_id, depends_on_task_id FROM task_dependency WHERE id = 401")) {
                assertThat(row.next())
                        .as("升級前於 V5 建立的 task_dependency 資料列必須存活")
                        .isTrue();
                assertThat(row.getLong("task_id")).isEqualTo(201L);
                assertThat(row.getLong("depends_on_task_id")).isEqualTo(202L);
            }

            Set<String> tables = new HashSet<>();
            try (var rows = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
                while (rows.next()) {
                    tables.add(rows.getString("TABLE_NAME").toUpperCase());
                }
            }
            assertThat(tables).contains(
                    "PROJECT", "TASK", "TASK_LOG", "TASK_DEPENDENCY", "ROLE",
                    "TASK_BLOCK_EVENT", "TASK_BLOCK_DEPENDENCY",
                    "TASK_COMPLETION_EVIDENCE", "TASK_COMPLETION_VERIFICATION", "PROJECT_AUDIT");
        }

        // 再次冷開啟，證明升級成品不是只有 migration 連線尚未關閉時才能讀。
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var row = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM project p JOIN task t ON t.project_id = p.id "
                             + "JOIN task_log l ON l.task_id = t.id WHERE p.id = 101")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1)).isEqualTo(1);
            connection.createStatement().execute("SHUTDOWN");
        }
    }
}

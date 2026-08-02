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
}

package org.com.workflow.persistence;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;

/** A real SQLite database on a temp file, one per test, with the production schema applied. */
final class SqliteTestSupport {

    private SqliteTestSupport() {
    }

    static JdbcTemplate freshDatabase(Path directory) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("test.db")
                + "?transaction_mode=IMMEDIATE&busy_timeout=5000");
        applySchema(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private static void applySchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
    }
}

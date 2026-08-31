package org.com.workflow.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Replaces Boot's auto-configured JdbcTemplate so SQLite's constraint errors are categorised.
 *
 * <p>Only active outside the {@code postgres} profile: PostgreSQL's driver already reports unique
 * violations with SQLState 23505, which Boot's default {@code SQLErrorCodeSQLExceptionTranslator}
 * translates correctly on its own, so the postgres profile keeps Boot's auto-configured bean.
 */
@Configuration
@Profile("!postgres")
public class PersistenceConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setExceptionTranslator(new SqliteExceptionTranslator());
        return jdbcTemplate;
    }
}

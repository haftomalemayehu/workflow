package org.com.workflow.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Replaces Boot's auto-configured JdbcTemplate so SQLite's constraint errors are categorised. */
@Configuration
public class PersistenceConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setExceptionTranslator(new SqliteExceptionTranslator());
        return jdbcTemplate;
    }
}

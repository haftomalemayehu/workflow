package org.com.workflow.service;

import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.WorkflowDefinition;
import org.com.workflow.persistence.JdbcStepInstanceRepository;
import org.com.workflow.persistence.JdbcWorkflowDefinitionRepository;
import org.com.workflow.persistence.JdbcWorkflowRunRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/** The workflow from the exercise brief, and a service wired to a real SQLite file. */
final class SchedulerTestFixtures {

    static final WorkflowDefinition MODEL_PUBLISH = new WorkflowDefinition("model-publish", List.of(
            new StepDefinition("load-model", 10, 1, List.of()),
            new StepDefinition("validate-schema", 9, 2, List.of("load-model")),
            new StepDefinition("write-audit", 5, 1, List.of("load-model")),
            new StepDefinition("persist-metadata", 8, 1, List.of("validate-schema")),
            new StepDefinition("publish-event", 7, 1, List.of("persist-metadata", "write-audit"))));

    private SchedulerTestFixtures() {
    }

    static WorkflowSchedulerService serviceOn(Path directory) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("scheduler.db")
                + "?transaction_mode=IMMEDIATE&busy_timeout=5000");

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        return new WorkflowSchedulerService(
                new JdbcWorkflowDefinitionRepository(jdbc),
                new JdbcWorkflowRunRepository(jdbc),
                new JdbcStepInstanceRepository(jdbc),
                transactions);
    }
}

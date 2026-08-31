package org.com.workflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the packaged configuration, not a class. The scheduler's concurrency story depends on
 * SQLite taking its write lock at the start of a transaction: without {@code transaction_mode=
 * IMMEDIATE} two concurrent claims both begin as readers, both try to upgrade, and one dies with
 * SQLITE_BUSY.
 *
 * <p>This existed as a real defect — the test fixtures set the parameter while application.yaml did
 * not, so the suite was green while the running application returned 500s under concurrent load.
 */
@SpringBootTest(properties = "WORKFLOW_DB=target/config-test.db")
class DatasourceConfigurationTest {

    @Autowired
    Environment environment;

    @Test
    void theShippedDatasourceTakesTheWriteLockImmediately() {
        String url = environment.getProperty("spring.datasource.url");

        assertThat(url).contains("transaction_mode=IMMEDIATE");
    }

    @Test
    void theShippedDatasourceWaitsRatherThanFailingFastOnABusyDatabase() {
        String url = environment.getProperty("spring.datasource.url");

        assertThat(url).contains("busy_timeout=");
    }
}

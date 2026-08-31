package org.com.workflow.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteExceptionTranslatorTest {

    private final SqliteExceptionTranslator translator = new SqliteExceptionTranslator();

    @Test
    void translatesASqliteUniqueConstraintViolationToDuplicateKeyException() {
        SQLException uniqueViolation = new SQLException(
                "UNIQUE constraint failed: workflow_run.workflow_name, workflow_run.request_id",
                null, 19);

        DataAccessException translated =
                translator.translate("insert", "INSERT INTO workflow_run ...", uniqueViolation);

        assertThat(translated).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void delegatesAConstraintViolationThatIsNotAUniqueViolation() {
        // Same SQLite vendor code (19, SQLITE_CONSTRAINT) as a unique violation, but a different
        // constraint kind — must not be misclassified as a duplicate key. The delegate
        // (SQLExceptionSubclassTranslator, unconfigured) doesn't recognise a plain SQLException
        // either, and returns null per SQLExceptionTranslator's contract for "can't translate this".
        SQLException foreignKeyViolation =
                new SQLException("FOREIGN KEY constraint failed", null, 19);

        DataAccessException translated =
                translator.translate("insert", "INSERT INTO step_dependency ...", foreignKeyViolation);

        assertThat(translated).isNull();
    }

    @Test
    void delegatesAnExceptionWithAnUnrelatedVendorCode() {
        SQLException locked = new SQLException("database is locked", null, 5);

        DataAccessException translated =
                translator.translate("update", "UPDATE step_instance ...", locked);

        assertThat(translated).isNull();
    }

    @Test
    void delegatesAConstraintViolationWithNoMessageRatherThanNpeing() {
        // getMessage() can legitimately return null; the short-circuit && must stop before
        // calling .contains() on it.
        SQLException noMessage = new SQLException(null, null, 19);

        DataAccessException translated =
                translator.translate("insert", "INSERT INTO workflow_run ...", noMessage);

        assertThat(translated).isNull();
    }
}

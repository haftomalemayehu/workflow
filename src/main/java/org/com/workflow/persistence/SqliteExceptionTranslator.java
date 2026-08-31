package org.com.workflow.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import java.sql.SQLException;

/**
 * SQLite reports a unique-constraint violation with a null SQLState and vendor code 19, which
 * Spring's default translators cannot categorise — they hand back an UncategorizedSQLException.
 *
 * <p>That matters because idempotent run creation relies on catching {@link DuplicateKeyException}
 * when a concurrent submitter wins the race. Without this translator that catch can never fire and
 * the loser of the race gets a 500 instead of the existing run.
 */
public class SqliteExceptionTranslator implements SQLExceptionTranslator {

    private static final int SQLITE_CONSTRAINT = 19;

    private final SQLExceptionTranslator delegate = new SQLExceptionSubclassTranslator();

    @Override
    public DataAccessException translate(String task, String sql, SQLException ex) {
        if (isUniqueConstraintViolation(ex)) {
            return new DuplicateKeyException(
                    "%s; SQL [%s]; %s".formatted(task, sql, ex.getMessage()), ex);
        }
        return delegate.translate(task, sql, ex);
    }

    private static boolean isUniqueConstraintViolation(SQLException ex) {
        return ex.getErrorCode() == SQLITE_CONSTRAINT
                && ex.getMessage() != null
                && ex.getMessage().contains("UNIQUE constraint failed");
    }
}

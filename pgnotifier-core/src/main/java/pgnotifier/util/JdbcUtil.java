/*
 * Copyright 2016-2026 Nos Doughty
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pgnotifier.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility methods for executing JDBC queries with consistent resource management and error handling.
 *
 * @author Nos Doughty
 */
public class JdbcUtil {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(JdbcUtil.class);

    /**
     * Executes a query using the provided connection and helper callbacks.
     *
     * @param conn              JDBC connection to use
     * @param sql               SQL query string
     * @param statementConfigurer configures the {@link PreparedStatement} (bind parameters, etc.)
     * @param resultsProcessor  converts the {@link ResultSet} into a result value
     * @param params            parameters used only for logging in case of failure
     * @param <T>               result type
     * @return processed result
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * String name = JdbcUtil.query(
     *         connection,
     *         "SELECT name FROM users WHERE id = ?",
     *         stmt -> stmt.setLong(1, 42L),
     *         rs -> rs.next() ? rs.getString(1) : null,
     *         42L);
     * }</pre>
     */
    public static <T> @Nullable T query(
            final Connection conn,
            final String sql,
            final Consumer<PreparedStatement> statementConfigurer,
            final Function<ResultSet, T> resultsProcessor,
            final Object... params) {

        PreparedStatement statement = null;

        ResultSet resultset = null;

        T result = null;

        try {

            statement = Objects.requireNonNull(conn).prepareStatement(sql);

            Objects.requireNonNull(statementConfigurer).accept(statement);

            resultset = statement.executeQuery();

            result = Objects.requireNonNull(resultsProcessor).apply(resultset);

        } catch (final SQLException e) {

            rethrow(e, sql, params);

        } finally {

            closeQuietly(resultset);

            closeQuietly(statement);

        }

        return result;
    }

    private static void closeQuietly(final @Nullable Statement statement) {

        if (statement != null) {

            try {

                statement.close();

            } catch (SQLException e) {

                logger.error("Ignoring exception on Statement.close()", e);

            }

        }

    }

    private static void closeQuietly(final @Nullable ResultSet resultSet) {

        if (resultSet != null) {

            try {

                resultSet.close();

            } catch (SQLException e) {

                logger.error("Ignoring exception on ResultSet.close()", e);

            }

        }

    }

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "JDBC helper failures are intentionally converted to unchecked exceptions.")
    private static void rethrow(final SQLException cause, final String sql, final Object... params) {

        final String causeMessage = cause.getMessage() != null ?
                cause.getMessage() :
                "";

        final String msg = causeMessage + " Query: " +
                sql +
                " Parameters: " +
                (params != null ?
                        Arrays.deepToString(params) :
                        "[]");

        final SQLException e = new SQLException(
                msg,
                cause.getSQLState(),
                cause.getErrorCode());

        e.setNextException(cause);

        throw new RuntimeException(e);

    }

}

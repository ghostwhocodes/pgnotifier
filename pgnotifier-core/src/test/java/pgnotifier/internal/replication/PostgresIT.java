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

package pgnotifier.internal.replication;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pgnotifier.util.JdbcUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic integration test that brings up a PostgreSQL instance using Testcontainers
 * and verifies that our JDBC utilities can talk to it.
 *
 * @author Nos Doughty
 */
@Testcontainers
class PostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("pgnotifier_test")
                    .withUsername("pgnotifier")
                    .withPassword("pgnotifier");

    @Test
    void jdbcUtilCanQueryRunningPostgres() throws Exception {

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE TABLE IF NOT EXISTS wal_test (id SERIAL PRIMARY KEY, data text)");

            statement.execute("INSERT INTO wal_test (data) VALUES ('a')");
            statement.execute("INSERT INTO wal_test (data) VALUES ('b')");
            statement.execute("INSERT INTO wal_test (data) VALUES ('c')");

            final Integer count = JdbcUtil.query(
                    connection,
                    "SELECT COUNT(*) FROM wal_test",
                    ps -> {},
                    rs -> {
                        try {
                            if (!rs.next()) {
                                return 0;
                            }
                            return rs.getInt(1);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });

            assertThat(count).isEqualTo(3);

        }

    }

}

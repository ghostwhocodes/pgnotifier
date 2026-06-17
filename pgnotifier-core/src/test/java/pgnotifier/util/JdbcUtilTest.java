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

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JdbcUtilTest {

    @Test
    void queryExecutesStatementAndProcessesResults() throws Exception {
        Connection connection = mock(Connection.class, withSettings().withoutAnnotations());
        PreparedStatement stmt = mock(PreparedStatement.class, withSettings().withoutAnnotations());
        ResultSet rs = mock(ResultSet.class, withSettings().withoutAnnotations());

        when(connection.prepareStatement("SELECT 1")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(1);

        Integer result = JdbcUtil.query(
                connection,
                "SELECT 1",
                ps -> {},
                r -> {
                    try {
                        return r.next() ? r.getInt(1) : 0;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });

        assertThat(result).isEqualTo(1);
        verify(stmt).close();
        verify(rs).close();
    }

    @Test
    void queryWrapsSqlExceptionWithDetailedMessage() throws Exception {
        Connection connection = mock(Connection.class, withSettings().withoutAnnotations());
        when(connection.prepareStatement("SELECT * FROM test")).thenThrow(
                new SQLException("fail", "STATE", 999));

        assertThatThrownBy(() -> JdbcUtil.query(
                connection,
                "SELECT * FROM test",
                ps -> {},
                rs -> 0,
                "param"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SELECT * FROM test")
                .hasMessageContaining("Parameters: [param]");
    }

    @Test
    void queryIgnoresCloseFailures() throws Exception {
        Connection connection = mock(Connection.class, withSettings().withoutAnnotations());
        PreparedStatement stmt = mock(PreparedStatement.class, withSettings().withoutAnnotations());
        ResultSet rs = mock(ResultSet.class, withSettings().withoutAnnotations());

        when(connection.prepareStatement("SELECT 1")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        doThrow(new SQLException("stmt close")).when(stmt).close();
        doThrow(new SQLException("rs close")).when(rs).close();

        Integer result = JdbcUtil.query(connection, "SELECT 1", ps -> {}, ignored -> 1);

        assertThat(result).isEqualTo(1);
        verify(stmt).close();
        verify(rs).close();
    }
}

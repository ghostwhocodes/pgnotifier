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

package pgnotifier.helper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Simple helper that continuously inserts rows into test tables,
 * useful when manually driving replication during development.
 *
 * @author Nos Doughty
 */
public class Inserter {

    public static void listenToNotifyMessage() throws SQLException {

        Connection connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5433/postgres", "postgres",
                                                            "password");

        PreparedStatement insertStatement = connection.prepareStatement(
                "INSERT INTO wal_test.some_table (data) values (?)");

        PreparedStatement insertStatement2 = connection.prepareStatement(
                "INSERT INTO wal_test.another_table (data) values (?)");


        long x = 0;

        while (true) {

            insertStatement.setString(1, Instant.now().toString());
            insertStatement.executeUpdate();

            insertStatement2.setString(1, Instant.now().toString());
            insertStatement2.executeUpdate();

            x++;

            System.out.println(x);

            try {

                Thread.sleep(500);

            } catch (InterruptedException e) {

                // just wake up is all

            }

        }

    }

    public static void main(String[] args) throws SQLException {

        listenToNotifyMessage();

    }

}

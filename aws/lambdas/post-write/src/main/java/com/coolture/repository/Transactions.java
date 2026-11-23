package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;

import java.sql.Connection;
import java.sql.SQLException;

final class Transactions {

    private Transactions() {}

    interface Work<T> {
        T run(Connection conn) throws SQLException;
    }

    interface VoidWork {
        void run(Connection conn) throws SQLException;
    }

    /** Commits what work did, or rolls all of it back if it throws. */
    static <T> T inTransaction(Work<T> work) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    static void inTransaction(VoidWork work) throws SQLException {
        inTransaction(conn -> {
            work.run(conn);
            return null;
        });
    }
}

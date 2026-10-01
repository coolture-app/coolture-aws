package com.coolture.common.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class ParamBinderTest {

    private PreparedStatement stmt;
    private Connection conn;
    private ParamBinder binder;

    @BeforeEach
    void setUp() {
        stmt = mock(PreparedStatement.class);
        conn = mock(Connection.class);
        binder = new ParamBinder(stmt, conn);
    }

    @Test
    void bindUUID_incrementsIndexAndBindsObject() throws SQLException {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        binder.bindUUID(id1).bindUUID(id2);

        verify(stmt).setObject(1, id1);
        verify(stmt).setObject(2, id2);
    }

    @Test
    void bindString_incrementsIndex() throws SQLException {
        binder.bindString("first").bindString("second");

        verify(stmt).setString(1, "first");
        verify(stmt).setString(2, "second");
    }

    @Test
    void bindInt_incrementsIndex() throws SQLException {
        binder.bindInt(10).bindInt(20);

        verify(stmt).setInt(1, 10);
        verify(stmt).setInt(2, 20);
    }

    @Test
    void bindDouble_whenNonNull_setsDouble() throws SQLException {
        binder.bindDouble(50.5);

        verify(stmt).setDouble(1, 50.5);
    }

    @Test
    void bindDouble_whenNull_setsNull() throws SQLException {
        binder.bindDouble(null);

        verify(stmt).setNull(1, Types.DOUBLE);
    }

    @Test
    void bindTimestamp_whenNonNull_setsTimestamp() throws SQLException {
        Instant now = Instant.now();
        binder.bindTimestamp(now);

        verify(stmt).setTimestamp(1, Timestamp.from(now));
    }

    @Test
    void bindTimestamp_whenNull_setsNullTimestamp() throws SQLException {
        binder.bindTimestamp(null);

        verify(stmt).setTimestamp(1, null);
    }

    @Test
    void bindBoolean_setsBoolean() throws SQLException {
        binder.bindBoolean(true).bindBoolean(false);

        verify(stmt).setBoolean(1, true);
        verify(stmt).setBoolean(2, false);
    }

    @Test
    void bindVarcharArray_whenNotEmpty_createsAndSetsArray() throws SQLException {
        Array sqlArray = mock(Array.class);
        String[] values = new String[]{"a", "b"};
        when(conn.createArrayOf("varchar", values)).thenReturn(sqlArray);

        binder.bindVarcharArray(values);

        verify(conn).createArrayOf("varchar", values);
        verify(stmt).setArray(1, sqlArray);
    }

    @Test
    void bindVarcharArray_whenNullOrEmpty_setsNull() throws SQLException {
        binder.bindVarcharArray(null).bindVarcharArray(new String[0]);

        verify(stmt).setNull(1, Types.ARRAY);
        verify(stmt).setNull(2, Types.ARRAY);
    }

    @Test
    void bindStringFilterPair_bindsTwice() throws SQLException {
        binder.bindStringFilterPair("ACTIVE");

        verify(stmt).setString(1, "ACTIVE");
        verify(stmt).setString(2, "ACTIVE");
    }

    @Test
    void bindStringFilterTriple_bindsThrice() throws SQLException {
        binder.bindStringFilterTriple("query");

        verify(stmt).setString(1, "query");
        verify(stmt).setString(2, "query");
        verify(stmt).setString(3, "query");
    }

    @Test
    void bindCursorParams_bindsThreeTimestampsAndOneUUID() throws SQLException {
        Instant cursorTime = Instant.now();
        UUID cursorId = UUID.randomUUID();

        binder.bindCursorParams(cursorTime, cursorId);

        Timestamp ts = Timestamp.from(cursorTime);
        verify(stmt).setTimestamp(1, ts);
        verify(stmt).setTimestamp(2, ts);
        verify(stmt).setTimestamp(3, ts);
        verify(stmt).setObject(4, cursorId);
    }

    @Test
    void statement_returnsOriginalStatement() {
        assertSame(stmt, binder.statement());
    }
}

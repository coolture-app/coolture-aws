package com.coolture.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * Wrapper around PreparedStatement that auto-increments the parameter index.
 * Eliminates error-prone manual parameter numbering (e.g. findFeed had 38 manually numbered params).
 */
public class ParamBinder {

    private final PreparedStatement stmt;
    private final Connection conn;
    private int index = 1;

    public ParamBinder(PreparedStatement stmt, Connection conn) {
        this.stmt = stmt;
        this.conn = conn;
    }

    public ParamBinder bindUUID(UUID value) throws SQLException {
        stmt.setObject(index++, value);
        return this;
    }

    public ParamBinder bindString(String value) throws SQLException {
        stmt.setString(index++, value);
        return this;
    }

    public ParamBinder bindInt(int value) throws SQLException {
        stmt.setInt(index++, value);
        return this;
    }

    public ParamBinder bindDouble(Double value) throws SQLException {
        if (value != null) {
            stmt.setDouble(index++, value);
        } else {
            stmt.setNull(index++, Types.DOUBLE);
        }
        return this;
    }

    public ParamBinder bindTimestamp(Instant value) throws SQLException {
        stmt.setTimestamp(index++, value != null ? Timestamp.from(value) : null);
        return this;
    }

    public ParamBinder bindBoolean(boolean value) throws SQLException {
        stmt.setBoolean(index++, value);
        return this;
    }

    public ParamBinder bindObject(Object value) throws SQLException {
        stmt.setObject(index++, value);
        return this;
    }

    /**
     * Binds a varchar array, or NULL if the list is null/empty.
     */
    public ParamBinder bindVarcharArray(String[] values) throws SQLException {
        if (values != null && values.length > 0) {
            stmt.setArray(index++, conn.createArrayOf("varchar", values));
        } else {
            stmt.setNull(index++, Types.ARRAY);
        }
        return this;
    }

    /**
     * Binds a UUID array, or NULL if the list is null/empty.
     */
    public ParamBinder bindUUIDArray(UUID[] values) throws SQLException {
        if (values != null && values.length > 0) {
            stmt.setArray(index++, conn.createArrayOf("uuid", values));
        } else {
            stmt.setNull(index++, Types.ARRAY);
        }
        return this;
    }

    public ParamBinder bindNullableUUID(UUID value) throws SQLException {
        if (value != null) {
            stmt.setObject(index++, value);
        } else {
            stmt.setNull(index++, Types.OTHER);
        }
        return this;
    }

    /**
     * Binds the same String value twice — for the common SQL pattern:
     * (?::varchar IS NULL OR column = ?::varchar)
     */
    public ParamBinder bindStringFilterPair(String value) throws SQLException {
        stmt.setString(index++, value);
        stmt.setString(index++, value);
        return this;
    }

    /**
     * Binds the same Instant value twice — for nullable timestamp filter pattern.
     */
    public ParamBinder bindTimestampFilterPair(Instant value) throws SQLException {
        Timestamp ts = value != null ? Timestamp.from(value) : null;
        stmt.setTimestamp(index++, ts);
        stmt.setTimestamp(index++, ts);
        return this;
    }

    /**
     * Binds the same varchar array twice — for the pattern:
     * (?::varchar[] IS NULL OR column && ?::varchar[])
     */
    public ParamBinder bindVarcharArrayFilterPair(String[] values) throws SQLException {
        bindVarcharArray(values);
        bindVarcharArray(values);
        return this;
    }

    /**
     * Binds the same UUID twice — for the pattern:
     * (?::uuid IS NULL OR column = ?::uuid)
     */
    public ParamBinder bindUUIDFilterPair(UUID value) throws SQLException {
        stmt.setObject(index++, value);
        stmt.setObject(index++, value);
        return this;
    }

    /**
     * Binds the same String value three times — for the text search pattern:
     * (?::varchar IS NULL OR LOWER(title) LIKE ... OR LOWER(description) LIKE ...)
     */
    public ParamBinder bindStringFilterTriple(String value) throws SQLException {
        stmt.setString(index++, value);
        stmt.setString(index++, value);
        stmt.setString(index++, value);
        return this;
    }

    /**
     * Binds a cursor triple: timestamp, timestamp, timestamp, uuid — for the pattern:
     * (?::timestamptz IS NULL OR created_at < ? OR (created_at = ? AND id < ?))
     */
    public ParamBinder bindCursorParams(Instant cursorCreatedAt, UUID cursorId) throws SQLException {
        Timestamp ts = cursorCreatedAt != null ? Timestamp.from(cursorCreatedAt) : null;
        stmt.setTimestamp(index++, ts);
        stmt.setTimestamp(index++, ts);
        stmt.setTimestamp(index++, ts);
        stmt.setObject(index++, cursorId);
        return this;
    }

    public PreparedStatement statement() {
        return stmt;
    }
}

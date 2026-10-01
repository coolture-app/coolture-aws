package com.coolture.common.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConfig {

    private static final HikariDataSource dataSource;

    static {
        HikariConfig config = new HikariConfig();
        String host = getEnvOrDefault("DB_HOST", "host.docker.internal");
        String port = getEnvOrDefault("DB_PORT", "5430");
        String name = getEnvOrDefault("DB_NAME", "coolture");
        String user = getEnvOrDefault("DB_USERNAME", "coolture_admin");
        String pass = getEnvOrDefault("DB_PASSWORD", "admin");

        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s", host, port, name));
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(5000);

        dataSource = new HikariDataSource(config);
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static HikariDataSource getDataSource() {
        return dataSource;
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}

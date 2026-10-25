package com.coolture.common.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import software.amazon.awssdk.regions.Region;

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

        long connectionTimeoutMs = Long.parseLong(getEnvOrDefault("DB_CONNECTION_TIMEOUT_MS", "5000"));
        boolean iamAuth = Boolean.parseBoolean(getEnvOrDefault("DB_IAM_AUTH", "false"));

        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s?connectTimeout=%d%s",
            host, port, name, Math.max(1, connectionTimeoutMs / 1000), iamAuth ? "&sslmode=require" : "");
        if (iamAuth) {
            Region region = Region.of(getEnvOrDefault("AWS_REGION", "eu-north-1"));
            config.setDataSource(new IamAuthDataSource(jdbcUrl, host, Integer.parseInt(port), user, region));
        } else {
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user);
            config.setPassword(pass);
        }
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setIdleTimeout(60000);
        config.setConnectionTimeout(connectionTimeoutMs);

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

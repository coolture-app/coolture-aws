package com.coolture.common.db;

import org.postgresql.ds.PGSimpleDataSource;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

import java.sql.Connection;
import java.sql.SQLException;

final class IamAuthDataSource extends PGSimpleDataSource {

    private final transient RdsUtilities rdsUtilities;
    private final String host;
    private final int port;

    IamAuthDataSource(String jdbcUrl, String host, int port, String username, Region region) {
        this.host = host;
        this.port = port;
        this.rdsUtilities = RdsUtilities.builder()
            .region(region)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
        setUrl(jdbcUrl);
        setUser(username);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return super.getConnection(getUser(), generateToken());
    }

    String generateToken() {
        return rdsUtilities.generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
            .hostname(host)
            .port(port)
            .username(getUser())
            .build());
    }
}

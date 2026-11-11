package com.coolture.common.db;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamAuthDataSourceTest {

    @Test
    void generateToken_signsConnectRequestForConfiguredHostAndUser() {
        System.setProperty("aws.accessKeyId", "AKIAEXAMPLE");
        System.setProperty("aws.secretAccessKey", "secret");
        IamAuthDataSource dataSource = new IamAuthDataSource(
            "jdbc:postgresql://db.example.com:5432/coolture?sslmode=require",
            "db.example.com", 5432, "coolture_app", Region.EU_NORTH_1);

        String token = dataSource.generateToken();

        URI uri = URI.create("https://" + token);
        Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
            .map(p -> p.split("=", 2))
            .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        assertEquals("db.example.com:5432", uri.getHost() + ":" + uri.getPort());
        assertEquals("connect", query.get("Action"));
        assertEquals("coolture_app", query.get("DBUser"));
        assertTrue(query.get("X-Amz-Credential").contains("/eu-north-1/rds-db/aws4_request"));
        assertEquals("coolture_app", dataSource.getUser());
    }
}

package com.coolture;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;

import java.util.Collections;
import java.util.Map;

public final class ApiGatewayEvents {

    private ApiGatewayEvents() {}

    public static APIGatewayV2HTTPEvent get(String rawPath) {
        return request("GET", rawPath, null, null);
    }

    public static APIGatewayV2HTTPEvent get(String rawPath, Map<String, String> queryParams) {
        return request("GET", rawPath, queryParams, null);
    }

    public static APIGatewayV2HTTPEvent get(String rawPath, Map<String, String> queryParams, Map<String, String> pathParams) {
        return request("GET", rawPath, queryParams, pathParams);
    }

    public static APIGatewayV2HTTPEvent request(String method, String rawPath,
                                                  Map<String, String> queryParams,
                                                  Map<String, String> pathParams) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setRawPath(rawPath);
        event.setQueryStringParameters(queryParams != null ? queryParams : Collections.emptyMap());
        event.setPathParameters(pathParams);

        APIGatewayV2HTTPEvent.RequestContext requestContext = new APIGatewayV2HTTPEvent.RequestContext();
        APIGatewayV2HTTPEvent.RequestContext.Http http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setMethod(method);
        http.setPath(rawPath);
        requestContext.setHttp(http);
        event.setRequestContext(requestContext);

        return event;
    }
}
package com.coolture.user;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;

import java.util.Collections;
import java.util.Map;

public final class ApiGatewayEvents {

    private ApiGatewayEvents() {}

    public static APIGatewayV2HTTPEvent request(String method, String rawPath, String body, Map<String, String> pathParams) {
        return request(method, rawPath, body, pathParams, Collections.emptyMap());
    }

    public static APIGatewayV2HTTPEvent request(
            String method,
            String rawPath,
            String body,
            Map<String, String> pathParams,
            Map<String, String> queryParams) {

        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setRawPath(rawPath);
        event.setBody(body);
        event.setPathParameters(pathParams);
        event.setQueryStringParameters(queryParams != null ? queryParams : Collections.emptyMap());

        APIGatewayV2HTTPEvent.RequestContext requestContext = new APIGatewayV2HTTPEvent.RequestContext();
        APIGatewayV2HTTPEvent.RequestContext.Http http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setMethod(method);
        http.setPath(rawPath);
        requestContext.setHttp(http);
        event.setRequestContext(requestContext);

        return event;
    }
}

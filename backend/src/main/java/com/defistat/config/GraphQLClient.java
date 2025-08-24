package com.defistat.config;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class GraphQLClient {
    private final RestTemplate restTemplate;

    public GraphQLClient() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Sends a GraphQL POST request to a given URL and returns the decoded JSON as Map.
     * @param url GraphQL endpoint
     * @param query GraphQL query string
     * @param variables nullable map of variables
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String url, String query, Map<String, Object> variables) {
        Map<String, Object> payload = Map.of(
                "query", query,
                "variables", variables == null ? Map.of() : variables
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
        Object body = restTemplate.postForObject(url, request, Object.class);

        if (body == null) {
            throw new IllegalStateException("GraphQL response body is null");
        }
        if (!(body instanceof Map<?,?> m)) {
            throw new IllegalStateException("Unexpected GraphQL response type: " + body.getClass());
        }
        return (Map<String, Object>) m;
    }
}
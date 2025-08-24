// com.defistat.merkl.MerklConfig.java
package com.defistat.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;


import java.time.Duration;
import java.util.List;

@Configuration
public class MerklConfig {
    @Bean
    public RestTemplate merklRestTemplate(
            RestTemplateBuilder builder,
            @Value("${app.merkl.timeoutMs:8000}") long timeoutMs
    ) {
        // Apache HttpClient with timeouts (Boot 3 uses HttpComponents 5)
        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(rc)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        // tiny logging interceptor (you can replace with proper logger if needed)
        ClientHttpRequestInterceptor logIt = (req, body, exec) -> {
            System.out.println("[Merkl REST] → " + req.getMethod() + " " + req.getURI());
            var resp = exec.execute(req, body);
            System.out.println("[Merkl REST] ← " + resp.getStatusCode());
            return resp;
        };

        return builder
                .additionalInterceptors(List.of(logIt))
                .requestFactory(() -> factory)
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

}
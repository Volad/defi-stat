// com.defistat.merkl.MerklConfig.java
package com.defistat.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;


import java.time.Duration;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Qualifier("merklRestTemplate")
    public RestTemplate merklRestTemplate() {
        return buildRestTemplate(5, 15, "Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko");
    }

    @Bean
    @Qualifier("eulerScanRestTemplate")
    public RestTemplate eulerScanRestTemplate() {
        return buildRestTemplate(5, 15, "Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko");
    }

    private RestTemplate buildRestTemplate(int connectTimeoutSec, int readTimeoutSec, String userAgent) {
        RequestConfig rc = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(connectTimeoutSec))
                .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSec))
                .setResponseTimeout(Timeout.ofSeconds(readTimeoutSec))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(rc)
                .build();
        HttpComponentsClientHttpRequestFactory f = new HttpComponentsClientHttpRequestFactory(httpClient);
        f.setConnectTimeout(connectTimeoutSec * 1000);
        f.setReadTimeout(readTimeoutSec * 1000);

        RestTemplate rt = new RestTemplate(f);
        rt.getInterceptors().add((request, body, execution) -> {
            HttpHeaders h = request.getHeaders();
//            h.set(HttpHeaders.USER_AGENT, "curl/8.4.0");
//            h.set(HttpHeaders.ACCEPT, "*/*");
//            h.set(HttpHeaders.CONTENT_TYPE, "application/json");
//            h.remove(HttpHeaders.ACCEPT_ENCODING);
//            h.remove(HttpHeaders.ACCEPT_LANGUAGE);
//            h.remove(HttpHeaders.REFERER);
            return execution.execute(request, body);
        });
        return rt;
    }

}
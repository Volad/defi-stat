// src/main/java/com/defistat/eulerscan/EulerScanClient.java
package com.defistat.web3;

import com.defistat.web3.dto.eulerscan.EulerScanHourlyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight client for EulerScan hourly historical endpoint.
 * GET {baseUrl}/historical/hourly?chain={chain}&vault={vault}
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EulerScanClient {

    private final RestTemplate eulerScanRestTemplate;
    private final EulerScanProps props;

    /** Properties holder wired from application.yml. */
    @Component
    @lombok.Data
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.euler.eulerscan")
    public static class EulerScanProps {
        private String baseUrl;
        private java.util.Map<String,String> chainMap = java.util.Map.of();
    }

    /**
     * Fetch hourly series for given (network, vault).
     * We filter by [from,to] locally (EulerScan returns a long history).
     */
    public List<EulerScanHourlyResponse.Snapshot> getHourly(
            String network, String vaultAddress, Instant from, Instant to) {

        String chain = props.chainMap.getOrDefault(network, network);
        String url = props.baseUrl + "/historical/hourly?chain=" + chain + "&vault=" + vaultAddress;
        try {
            EulerScanHourlyResponse resp = eulerScanRestTemplate.getForObject(URI.create(url), EulerScanHourlyResponse.class);
            if (resp == null || resp.getSnapshots() == null) return List.of();

            long fromSec = from != null ? from.getEpochSecond() : Long.MIN_VALUE;
            long toSec   = to   != null ? to.getEpochSecond()   : Long.MAX_VALUE;

            List<EulerScanHourlyResponse.Snapshot> out = new ArrayList<>();
            for (var s : resp.getSnapshots()) {
                long ts = s.getTimestamp() != null ? s.getTimestamp() : -1;
                if (ts >= fromSec && ts <= toSec) out.add(s);
            }
            return out;
        } catch (RestClientException e) {
            log.warn("[EulerScan] hourly fetch failed for net={} vault={} : {}", network, vaultAddress, e.getMessage());
            return List.of();
        }
    }


}
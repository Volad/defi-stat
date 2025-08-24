// com.defistat.merkl.MerklClientRt.java
package com.defistat.web3;

import com.defistat.web3.dto.merkl.MerklOpportunity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

/**
 * Blocking client for Merkl API using RestTemplate.
 * Supports flexible JSON shapes: array root, {data:[...]}, or single object.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MerklClient {

    private final RestTemplate merklRestTemplate;

    @Value("${app.merkl.baseUrl:https://api.merkl.xyz}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<MerklOpportunity>> LIST_REF = new TypeReference<>() {};

    /**
     * Fetch all opportunities for a given chain/protocol (blocking).
     * Adjust query param names ("type" vs "protocol") to match Merkl's API.
     */
    public List<MerklOpportunity> findAll(String chainId, String protocol) {
        Assert.hasText(chainId, "chainId required");
        Assert.hasText(protocol, "protocol required");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v4/opportunities")
                .queryParam("chainId", chainId)
                .queryParam("type", protocol) // e.g., EULER
                .build(true).toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, "defistat/merkl-client");
        HttpEntity<Void> req = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = merklRestTemplate.exchange(uri, HttpMethod.GET, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new RuntimeException("Merkl HTTP " + resp.getStatusCode());
            }
            return decode(resp.getBody());
        } catch (HttpStatusCodeException httpEx) {
            throw new RuntimeException("Merkl HTTP error " + httpEx.getStatusCode() + ": " + httpEx.getResponseBodyAsString(), httpEx);
        } catch (Exception e) {
            throw new RuntimeException("Merkl call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch by identifier (target vault address). Useful for on-demand lookups.
     */
    public List<MerklOpportunity> findByIdentifier(String identifier) {
        Assert.hasText(identifier, "identifier required");
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v4/opportunities")
                .queryParam("identifier", identifier)
                .build(true).toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, "defistat/merkl-client");
        HttpEntity<Void> req = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = merklRestTemplate.exchange(uri, HttpMethod.GET, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new RuntimeException("Merkl HTTP " + resp.getStatusCode());
            }
            return decode(resp.getBody());
        } catch (HttpStatusCodeException httpEx) {
            throw new RuntimeException("Merkl HTTP error " + httpEx.getStatusCode() + ": " + httpEx.getResponseBodyAsString(), httpEx);
        } catch (Exception e) {
            throw new RuntimeException("Merkl call failed: " + e.getMessage(), e);
        }
    }

    /** Flexible JSON decoder: array root, {data:[...]}, or single object. */
    private List<MerklOpportunity> decode(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || root.isNull()) return List.of();
            if (root.isArray()) {
                return mapper.readValue(json, LIST_REF);
            } else if (root.isObject()) {
                JsonNode data = root.get("data");
                if (data != null && data.isArray()) {
                    return mapper.readerFor(LIST_REF).readValue(data);
                }
                MerklOpportunity one = mapper.treeToValue(root, MerklOpportunity.class);
                return one != null ? List.of(one) : List.of();
            }
            return List.of();
        } catch (Exception e) {
            throw new RuntimeException("Merkl decode error: " + e.getMessage(), e);
        }
    }
}
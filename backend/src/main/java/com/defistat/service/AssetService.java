package com.defistat.service;

import com.defistat.api.dto.AssetDTO;
import com.defistat.config.AppProps;
import com.defistat.config.GraphQLClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronous service that fetches Euler assets (vaults) from a network-specific subgraph.
 * - Discovers the correct top-level list field (e.g., "vaults", "evaults", "markets") by probing candidates.
 * - Paginates synchronously until an empty/short page is returned.
 */
@Service
public class AssetService {

    private final GraphQLClient client;
    private final AppProps props;

    // Cache resolved list-field per network (e.g. avalanche -> "evaults")
    private final Map<String, String> resolvedFieldByNetwork = new ConcurrentHashMap<>();

    // Known candidates across Euler subgraphs
    private static final List<String> CANDIDATE_FIELDS =
            List.of("eulerVaults", "eulerVaults", "eVaults", "creditVaults", "markets");

    public AssetService(GraphQLClient client, AppProps props) {
        this.client = client;
        this.props = props;
    }

    private String subgraphUrl(String network) {
        var net = props.getNetwork().get(network);
        if (net == null || net.getSubgraph() == null || net.getSubgraph().getUrl() == null) {
            throw new IllegalArgumentException("Subgraph url is not configured for network: " + network);
        }
        return net.getSubgraph().getUrl();
    }

    /**
     * Resolve which top-level list field exists for this network by probing candidates.
     * Caches the result per network to avoid re-probing.
     */
    public String resolveVaultField(String network) {
        String cached = resolvedFieldByNetwork.get(network);
        if (cached != null) return cached;

        String url = subgraphUrl(network);

        for (String field : CANDIDATE_FIELDS) {
            try {
                String q = "query Test($first:Int!,$skip:Int!){ " + field + "(first:$first,skip:$skip){ id } }";
                Map<String, Object> resp = client.post(url, q, Map.of("first", 1, "skip", 0));
                // If the request doesn't throw, we consider this field valid.
                // Some subgraphs may return {"data":{"field":[]}} — also valid.
                resolvedFieldByNetwork.put(network, field);
                return field;
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        throw new IllegalStateException("No known vault-list field found for network '" + network
                + "'. Tried: " + CANDIDATE_FIELDS);
    }

    private static String buildVaultsQuery(String field) {
        // Request basic info for vault + underlying
        return "query Vaults($first:Int!,$skip:Int!){ " + field +
                "(first:$first,skip:$skip,orderBy:id,orderDirection:asc) {" +
                "  id symbol name evault" +
                "} }";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractList(Map<String, Object> resp, String field) {
        Object dataObj = resp.get("data");
        if (!(dataObj instanceof Map<?,?> data)) return List.of();
        Object listObj = data.get(field);
        if (listObj instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /**
     * Fetch one page synchronously.
     */
    public List<Map<String, Object>> fetchAssetsPage(String network, String field, int first, int skip) {
        String url = subgraphUrl(network);
        String query = buildVaultsQuery(field);
        Map<String, Object> resp = client.post(url, query, Map.of("first", first, "skip", skip));
        return extractList(resp, field);
    }

    /**
     * Public API: fetch ALL assets synchronously with auto field-resolution and paging.
     * @param network      e.g. "avalanche"
     * @param verifiedOnly placeholder; wire governedPerspective later if needed
     * @param pageSize     page size (defaults to 500 if <=0)
     */
    public List<AssetDTO> fetchAllAssets(String network, boolean verifiedOnly, int pageSize) {
        int first = pageSize > 0 ? pageSize : 500;
        String field = resolveVaultField(network);

        List<AssetDTO> result = new ArrayList<>();
        int skip = 0;

        while (true) {
            List<Map<String, Object>> page = fetchAssetsPage(network, field, first, skip);
            if (page.isEmpty()) break;

            for (Map<String, Object> v : page) {
                result.add(mapVaultToDto(v));
            }

            if (page.size() < first) break;  // last page reached
            skip += first;
        }

        // TODO: if verifiedOnly is true, filter by a verified set (governedPerspective)
        return result;
    }

    @SuppressWarnings("unchecked")
    private static AssetDTO mapVaultToDto(Map<String, Object> v) {
        AssetDTO dto = new AssetDTO();
        dto.vaultAddress = (String) v.get("id");
        dto.vaultSymbol  = (String) v.get("symbol");
        dto.vaultName    = (String) v.get("name");
        return dto;
    }
}
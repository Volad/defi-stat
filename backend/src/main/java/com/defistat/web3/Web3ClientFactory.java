// com.defistat.web3.Web3ClientFactory.java
package com.defistat.web3;

import com.defistat.config.AppProps;
import com.defistat.web3.exception.RetryableRpcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Builds and manages multiple Web3j clients per network and fails over
 * between RPC endpoints on rate-limit or IO errors.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Web3ClientFactory {

    private final AppProps props;

    /**
     * Endpoint state with simple penalty window (circuit half-open).
     */
    private static class Endpoint {
        final String url;
        final Web3j web3j;
        volatile Instant penaltyUntil = Instant.EPOCH;

        Endpoint(String url) {
            this.url = url;
            this.web3j = Web3j.build(new HttpService(url));
        }

        boolean isAvailable() {
            return Instant.now().isAfter(penaltyUntil);
        }

        void penalize(Duration d) {
            penaltyUntil = Instant.now().plus(d);
        }
    }

    /**
     * Network -> endpoints ring
     */
    private final Map<String, List<Endpoint>> endpointsByNet = new ConcurrentHashMap<>();
    /**
     * Network -> round-robin index
     */
    private final Map<String, Integer> rrIndex = new ConcurrentHashMap<>();

    // ----- public helpers for ancillary lenses (if you kept them here) -----
    public String getUtilsLens(String network) {
        var net = props.require(network);
        return net.getUtilsLens();
    }

    // ----- core failover executor -----

    /**
     * Execute a function against Web3j with RPC failover.
     *
     * @param network network key (e.g. "base")
     * @param fn      function to execute; MUST throw RetryableRpcException for logical rate-limit responses
     * @param <T>     return type
     */
    public <T> T executeWithFailover(String network, Function<Web3j, T> fn) {
        List<Endpoint> ring = getOrInit(network);
        if (ring.isEmpty()) throw new IllegalStateException("No RPC URLs configured for network: " + network);

        final int total = ring.size();
        int start = rrIndex.compute(network, (k, v) -> v == null ? 0 : (v + 1) % total);
        int tried = 0;

        // backoff grows per attempt, and penalizes an endpoint for a short window
        final Duration baseBackoff = Duration.ofMillis(400);
        final Duration maxBackoff = Duration.ofSeconds(5);
        final Duration penalty = Duration.ofSeconds(20);

        Throwable last = null;

        while (tried < total) {
            int idx = (start + tried) % total;
            Endpoint ep = ring.get(idx);

            if (!ep.isAvailable()) {
                tried++;
                continue;
            }

            try {
                T out = fn.apply(ep.web3j);
                // success: remember index for next round-robin start
                rrIndex.put(network, idx);
                return out;
            } catch (RetryableRpcException ex) {
                last = ex;
                log.warn("[web3 failover] Retryable on {}: {}", ep.url, ex.getMessage());
                // penalize endpoint for a short time window
                ep.penalize(penalty);
            } catch (Exception ex) {
                last = ex;
                // IO or transport errors are considered retryable to next endpoint
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                if (isRetryableTransport(msg)) {
                    log.warn("[web3 failover] Transport retryable on {}: {}", ep.url, ex.toString());
                    ep.penalize(penalty);
                } else {
                    // non-retryable (e.g., contract revert) -> fail fast
                    log.error("[web3 failover] Non-retryable on {}: {}", ep.url, ex.toString());
                    throw ex;
                }
            }

            // simple exponential backoff with cap
            try {
                long pow = Math.min(tried, 4); // cap growth
                long delay = Math.min(baseBackoff.toMillis() * (1L << pow), maxBackoff.toMillis());
                Thread.sleep(delay);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }

            tried++;
        }

        if (last instanceof RuntimeException re) throw re;
        throw new RuntimeException("All RPC endpoints failed for network=" + network, last);
    }

    private boolean isRetryableTransport(String msg) {
        if (msg.isEmpty()) return true;
        // heuristics for public RPCs
        return msg.contains("429") ||
                msg.contains("rate limit") ||
                msg.contains("over rate") ||
                msg.contains("1015") ||            // Cloudflare code used by some RPCs
                msg.contains("timeout") ||
                msg.contains("connection") ||
                msg.contains("refused") ||
                msg.contains("unexpected end of stream");
    }

    private List<Endpoint> getOrInit(String network) {
        return endpointsByNet.computeIfAbsent(network, net -> {
            var netCfg = props.require(net);
            var urls = netCfg.getRpcUrls();
            if (urls == null || urls.isEmpty()) return List.of();
            List<Endpoint> list = new ArrayList<>(urls.size());
            for (String u : urls) list.add(new Endpoint(u));
            log.info("Initialized {} RPC endpoints for {}: {}", list.size(), net, urls);
            return list;
        });
    }
}
package com.defistat.service;

import com.defistat.api.dto.AssetDTO;
import com.defistat.config.AppProps;
import com.defistat.model.AssetSnapshot;
import com.defistat.repo.AssetSnapshotRepo;
import com.defistat.web3.EulerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Periodically fetches all vaults from subgraph (per network),
 * then queries on-chain Lens/Vault to compute borrow/supply APY and utilization,
 * and stores snapshots in MongoDB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllAssetsPollingService {

    private final AssetService assetService;        // uses GraphQL subgraph (synchronous)
    private final EulerClient eulerClient;          // on-chain calls (synchronous)
    private final AssetSnapshotRepo snapshotRepo;
    private final AppProps props;

    // Simple "last run" guard per network to honor configured interval
    private final Map<String, Long> lastRunSec = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Poll one network (e.g., "avalanche"): fetch list of assets, then snapshot each.
     */
    public void pollNetwork(String network) {

        try {
            // 1) subgraph: full list of vaults
            List<AssetDTO> assets = assetService.fetchAllAssets(network, false, 500);

            var tsTick = Instant.now();

            // 2) on-chain per vault: APYs and utilization
            Instant ts = Instant.now();
            List<AssetSnapshot> batch = new ArrayList<>(assets.size());

            for (AssetDTO a : assets) {
                log.info("Start getting snapshot for {} {} at tsTick {}", network, a.vaultAddress, tsTick);
                try {
                    var snap = eulerClient.fetchSingle(network, a.vaultAddress);
                    AssetSnapshot row = AssetSnapshot.builder()
                            .network(network)
                            .vaultAddressOriginal(a.vaultAddress)
                            .vaultAddress(a.vaultAddress.toLowerCase())
                            .ts(ts)
                            .tsTick(tsTick)
                            .borrowApyPct(snap.borrowApyPct)
                            .supplyApyPct(snap.supplyApyPct)
                            .utilizationPct(snap.utilizationPct)
                            .vaultSymbol(a.vaultSymbol)
                            .vaultName(a.vaultName)
                            .build();
                    batch.add(row);
                } catch (Exception ex) {
                    // Log and continue with other assets; don't stop the whole batch.
                    log.error("[assets-poll] failed to fetch snapshot for {} {}: {}", network, a.vaultAddress, ex.getMessage());
                }
            }

            if (!batch.isEmpty()) snapshotRepo.saveAll(batch);

        } catch (Exception e) {
            // subgraph/network failure — log and let scheduler retry next minute
            System.err.println("[assets-poll] subgraph failed for network " + network + ": " + e.getMessage());
        }
    }
}
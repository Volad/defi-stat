package com.defistat.api;

import com.defistat.model.AssetSnapshot;
import com.defistat.repo.AssetSnapshotRepo;
import com.defistat.service.AllAssetsPollingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Read-only endpoints to query asset snapshots (time series).
 */
@RestController
@RequestMapping("/api/v1/asset-snapshots")
@RequiredArgsConstructor
public class AssetSnapshotController {

    private final AssetSnapshotRepo repo;
    private final AllAssetsPollingService allAssetsPollingService;

    /**
     * Latest snapshot for given network & vault.
     */
    @GetMapping("/latest")
    public AssetSnapshot latest(
            @RequestParam String network,
            @RequestParam String vault
    ) {
        return repo.findTopByNetworkAndVaultAddressOrderByTsDesc(network, vault.toLowerCase());
    }

    /**
     * Time range for given network & vault (inclusive). ISO-8601 instants.
     */
    @GetMapping
    public List<AssetSnapshot> range(
            @RequestParam String network,
            @RequestParam String vault,
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        return repo.findByNetworkAndVaultAddressAndTsBetweenOrderByTsAsc(network, vault, from, to);
    }

    @PostMapping("/poll")
    public void poll(
            @RequestParam String network
    ) {
        allAssetsPollingService.pollNetwork(network);
    }
}
package com.defistat.repo;

import com.defistat.model.AssetSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AssetSnapshotRepo extends MongoRepository<AssetSnapshot, String> {
    List<AssetSnapshot> findByNetworkAndVaultAddressAndTsBetweenOrderByTsAsc(
            String network, String vaultAddress, Instant from, Instant to);

    AssetSnapshot findTopByNetworkAndVaultAddressOrderByTsDesc(String network, String vaultAddress);

    AssetSnapshot findTopByNetworkAndVaultAddressAndTsLessThanEqualOrderByTsDesc(String network, String collateral, Instant ts);
}
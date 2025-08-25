package com.defistat.repo;


import com.defistat.model.RewardOpportunityDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RewardOpportunityRepository extends MongoRepository<RewardOpportunityDocument, String> {

    Optional<RewardOpportunityDocument> findBySourceAndOpportunityIdAndTs(String source, String opportunityId, Instant ts);

    /**
     * Latest record at or before a given timestamp T for (network, vault, role).
     * Used to resolve the campaign state/APR exactly "as of" snapshot time.
     */
    Optional<RewardOpportunityDocument>
    findTopByNetworkAndVaultAddressAndRoleAndTsLessThanEqualOrderByTsDesc(
            String network, String vaultAddress, String role, Instant ts);

    /**
     * Any record existence check for (network, vault, role), regardless of T.
     * Used to decide whether to fall back to user-provided APR when DB is empty.
     */
    Optional<RewardOpportunityDocument>
    findTopByNetworkAndVaultAddressAndRoleOrderByTsDesc(
            String network, String vaultAddress, String role);

    /** Earliest record for (network, vault, role). Used to decide "before first record". */
    Optional<RewardOpportunityDocument>
    findTopByNetworkAndVaultAddressAndRoleOrderByTsAsc(
            String network, String vaultAddress, String role);
}
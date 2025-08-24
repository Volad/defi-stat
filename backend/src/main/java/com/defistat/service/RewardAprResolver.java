// com.defistat.rewards.RewardLookupService.java
package com.defistat.service;

import com.defistat.model.RewardOpportunityDocument;
import com.defistat.repo.RewardOpportunityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RewardAprResolver {

    private final RewardOpportunityRepository repo;

    /**
     * @param network      Network name (e.g., "avalanche", "base")
     * @param vault        Target vault address (lowercase or checksummed consistently across the app)
     * @param role         "collateral" or "borrow"
     * @param atTs         Snapshot timestamp (tsTick) to resolve APR "as of"
     * @param userProvided Optional user-provided APR (percent); used only if DB is empty
     * @return Effective rewards APR (%) to feed into ROE math
     */
    public double resolve(String network, String vault, String role, Instant atTs, Double userProvided) {
        final String net = network.toLowerCase(Locale.ROOT);

        // Check if there is any record at all for this (network, vault, role).
        // If there is nothing in DB, we fall back to the user-provided APR (or 0).
        boolean anyInDb = repo
                .findTopByNetworkAndVaultAddressAndRoleOrderByTsDesc(net, vault, role)
                .isPresent();

        if (!anyInDb) {
            // Rule #1
            return userProvided != null ? userProvided : 0.0;
        }

        // DB has records: pick the latest one at or before the snapshot time.
        // If nothing <= T exists (all records are after T), we treat it as "no active campaign at T" → APR = 0.
        return repo.findTopByNetworkAndVaultAddressAndRoleAndTsLessThanEqualOrderByTsDesc(net, vault, role, atTs)
                .map(doc -> aprOrZeroIfEnded(doc))
                .orElse(0.0);
    }

    /**
     * Returns APR if the campaign was LIVE in this record; otherwise 0.
     * We assume the "status" reflects the campaign state at this record's timestamp.
     */
    private double aprOrZeroIfEnded(RewardOpportunityDocument doc) {
        final String status = doc.getStatus() == null ? "" : doc.getStatus();
        final boolean live = "LIVE".equalsIgnoreCase(status);

        if (!live) {
            // Rule #3: campaign ended (or not live) at/before the requested time → APR = 0
            return 0.0;
        }
        // Use the stored APR; null is interpreted as 0
        return doc.getRewardApyPct() == null ? 0.0 : doc.getRewardApyPct();
    }
}
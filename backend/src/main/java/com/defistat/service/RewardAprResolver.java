// com.defistat.rewards.RewardAprResolver.java
package com.defistat.service;

import com.defistat.model.RewardOpportunityDocument;
import com.defistat.repo.RewardOpportunityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

/**
 * Resolves reward APR (%) for ROE according to these rules:
 *
 * 1) BEFORE the first DB record exists for (network, vault, role):
 *    - Use userProvided APR (if null -> 0).
 *
 * 2) ON/AFTER the first DB record exists:
 *    - Ignore userProvided entirely.
 *    - Use the latest DB record <= T (snapshot time):
 *       - if status == LIVE  -> return stored APR (null -> 0).
 *       - else               -> return 0 (campaign ended / inactive).
 */
@Service
@RequiredArgsConstructor
public class RewardAprResolver {

    private final RewardOpportunityRepository repo;

    /**
     * @param network      Network name (e.g., "avalanche", "base")
     * @param vault        Vault address (normalized consistently across the app)
     * @param role         "collateral" or "borrow"
     * @param atTs         Snapshot timestamp to resolve APR "as of"
     * @param userProvided Optional user-provided APR (%) — only used BEFORE the first DB record exists
     * @return Effective rewards APR (%) for ROE math
     */
    public double resolve(String network, String vault, String role, Instant atTs, Double userProvided) {
        final String net = network.toLowerCase(Locale.ROOT);

        // Find the earliest record for (net, vault, role).
        // If there's no record at all -> PRIOR to first record by definition -> use userProvided or 0.
        var earliestOpt = repo.findTopByNetworkAndVaultAddressAndRoleOrderByTsAsc(net, vault, role);
        if (earliestOpt.isEmpty()) {
            return userProvided != null ? userProvided : 0.0;
        }

        // If the requested time is BEFORE the earliest record ts -> use userProvided or 0.
        var earliest = earliestOpt.get();
        if (atTs != null && atTs.isBefore(earliest.getTs())) {
            return userProvided != null ? userProvided : 0.0;
        }

        // From this point on (on/after earliest record): userProvided MUST be ignored.
        // We always resolve from DB at or before T; if not LIVE -> 0.
        var asOfOpt = repo.findTopByNetworkAndVaultAddressAndRoleAndTsLessThanEqualOrderByTsDesc(net, vault, role,
                atTs != null ? atTs : Instant.now());

        // Defensive: if for some reason there is no record <= T (e.g., atTs way in the past but not before earliest),
        // we still treat it as "no active campaign as of T" -> 0.
        if (asOfOpt.isEmpty()) {
            return 0.0;
        }

        var doc = asOfOpt.get();
        return aprIfLiveElseZero(doc);
    }

    /** Returns APR if campaign is LIVE in this record; otherwise 0. */
    private double aprIfLiveElseZero(RewardOpportunityDocument doc) {
        final String status = doc.getStatus() == null ? "" : doc.getStatus();
        final boolean live = "LIVE".equalsIgnoreCase(status);
        if (!live) return 0.0;
        Double apr = doc.getRewardApyPct();
        return apr == null ? 0.0 : apr;
    }
}
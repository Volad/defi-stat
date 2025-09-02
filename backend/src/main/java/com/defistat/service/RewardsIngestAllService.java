// com.defistat.service.RewardsIngestAllService.java
package com.defistat.service;

import com.defistat.config.AppProps;
import com.defistat.model.RewardOpportunityDocument;
import com.defistat.repo.RewardOpportunityRepository;
import com.defistat.web3.MerklClient;
import com.defistat.web3.dto.merkl.MerklOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pulls all Merkl opportunities for a chain/protocol and persists adapted documents.
 * Writes a NEW document ONLY when 'status' or 'rewardApyPct' actually changed.
 * If the last record has the same 'ts' as the new one, we update it in-place (prevent duplicates for the same timestamp).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewardsIngestAllService {

    private static final double EPS_APR = 1e-6; // minimal apr change to treat as "changed" (to filter out floating noise)

    private final MerklClient merklClient;
    private final RewardOpportunityRepository repo;
    private final AppProps appProps;

    /**
     * Ingest all opportunities for given network/protocol from Merkl.
     * Returns number of saved/updated documents.
     */
    public int ingestAll(String network, String protocol) {
        final String net = normalize(network);
        List<MerklOpportunity> list = merklClient.findAll(appProps.require(net).getChainId(), protocol);

        int changedCount = 0;
        for (MerklOpportunity o : list) {
            try {
                RewardOpportunityDocument incoming = adapt(net, protocol, o);
                if (incoming == null) continue;

                // 1) If there is already a record with the SAME (source, opportunityId, ts), update it (dedup on same ts)
                Optional<RewardOpportunityDocument> sameTs = repo.findBySourceAndOpportunityIdAndTs(
                        incoming.getSource(), incoming.getOpportunityId(), incoming.getTs());

                if (sameTs.isPresent()) {
                    RewardOpportunityDocument cur = sameTs.get();
                    // overwrite mutable fields (no new version is created)
                    overwriteMutableFields(cur, incoming);
                    repo.save(cur);
                    log.debug("[MerklIngest] Updated same-ts record (no version bump) id={} ts={}", cur.getOpportunityId(), cur.getTs());
                    changedCount++;
                    continue;
                }

                // 2) Otherwise get the latest record for same opportunity (prefer source+opportunityId; fallback to net+vault+role)
                RewardOpportunityDocument last = repo
                        .findTopBySourceAndOpportunityIdOrderByTsDesc(incoming.getSource(), incoming.getOpportunityId())
                        .orElseGet(() -> repo
                                .findTopByNetworkAndVaultAddressAndRoleOrderByTsDesc(
                                        incoming.getNetwork(), incoming.getVaultAddress(), incoming.getRole()
                                ).orElse(null));

                if (last == null) {
                    // First ever record for this opportunity → create
                    repo.save(incoming);
                    log.info("[MerklIngest] New reward entry: vault={} role={} status={} apr={}",
                            incoming.getVaultAddress(), incoming.getRole(), incoming.getStatus(), incoming.getRewardApyPct());
                    changedCount++;
                } else {
                    // 3) Compare by status or APR
                    boolean statusChanged = !Objects.equals(safe(last.getStatus()), safe(incoming.getStatus()));
                    double aprPrev = last.getRewardApyPct() == null ? 0.0 : last.getRewardApyPct();
                    double aprNew  = incoming.getRewardApyPct() == null ? 0.0 : incoming.getRewardApyPct();
                    boolean aprChanged = Math.abs(aprPrev - aprNew) > EPS_APR;

                    if (statusChanged || aprChanged) {
                        // Create NEW document (versioning-by-change)
                        repo.save(incoming);
                        log.info("[MerklIngest] Change detected → new version. vault={} role={} status {}→{} apr {}→{}",
                                incoming.getVaultAddress(), incoming.getRole(),
                                safe(last.getStatus()), safe(incoming.getStatus()),
                                aprPrev, aprNew);
                        changedCount++;
                    } else {
                        // No semantic change → do nothing (keep history clean)
                        log.debug("[MerklIngest] No change for vault={} role={} (status={}, apr={})",
                                incoming.getVaultAddress(), incoming.getRole(), incoming.getStatus(), aprNew);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to ingest opportunity id={} : {}", o != null ? o.getId() : "null", e.getMessage());
            }
        }
        log.info("Merkl ingest finished: {} saved/updated for {}/{}", changedCount, network, protocol);
        return changedCount;
    }

    /**
     * Map raw Merkl opportunity to our storage document.
     * Returns null when essential fields are missing.
     */
    private RewardOpportunityDocument adapt(String network, String protocol, MerklOpportunity o) {
        if (o == null) return null;

        // Map action -> role (we store as "collateral" or "borrow")
        String role = (o.getAction() != null && o.getAction().equalsIgnoreCase("BORROW")) ? "borrow" : "collateral";

        // Vault address: prefer explorerAddress, fallback to identifier
        String vault = firstNonEmpty(o.getExplorerAddress(), o.getIdentifier());
        if (isEmpty(vault)) return null;

        // Timestamp: prefer Merkl aprRecord.timestamp, else minute-rounded now()
        Instant ts = minuteFloor(nowUtc());
        try {
            if (o.getAprRecord() != null && o.getAprRecord().getTimestamp() != null) {
                long sec = Long.parseLong(o.getAprRecord().getTimestamp());
                ts = Instant.ofEpochSecond(sec);
            }
        } catch (Exception ignore) { /* keep rounded now() */ }

        // Rewards breakdown
        var rewards = (o.getRewardsRecord() == null || o.getRewardsRecord().getBreakdowns() == null)
                ? Collections.<RewardOpportunityDocument.RewardTokenValue>emptyList()
                : o.getRewardsRecord().getBreakdowns().stream()
                .map(b -> RewardOpportunityDocument.RewardTokenValue.builder()
                        .tokenAddress(b.getToken() != null ? b.getToken().getAddress() : null)
                        .symbol(b.getToken() != null ? b.getToken().getSymbol() : null)
                        .decimals(b.getToken() != null ? b.getToken().getDecimals() : null)
                        .priceUsd(b.getToken() != null ? b.getToken().getPrice() : null)
                        .amount(parseDoubleSafe(b.getAmount()))
                        .valueUsd(b.getValue())
                        .distributionType(b.getDistributionType())
                        .campaignId(b.getCampaignId())
                        .build())
                .toList();

        return RewardOpportunityDocument.builder()
                .network(normalize(network))
                .protocol(protocol)
                .vaultAddress(normalize(vault))
                .role(role)
                .rewardApyPct(o.getApr())   // Merkl "cumulated" APR for the opportunity
                .tvlUsd(o.getTvl())
                .name(o.getName())
                .status(o.getStatus())
                .depositUrl(o.getDepositUrl())
                .ts(ts)
                .source("merkl")
                .opportunityId(o.getId())
                .chainId(o.getChainId())
                .rewards(rewards)
                .build();
    }

    /** Overwrite fields that can change for the same timestamp (dedup-path). */
    private static void overwriteMutableFields(RewardOpportunityDocument target, RewardOpportunityDocument src) {
        target.setRewardApyPct(src.getRewardApyPct());
        target.setTvlUsd(src.getTvlUsd());
        target.setName(src.getName());
        target.setVaultAddress(src.getVaultAddress()); // already lower-cased by adapt
        target.setStatus(src.getStatus());
        target.setDepositUrl(src.getDepositUrl());
        target.setRewards(src.getRewards());
        target.setProtocol(src.getProtocol());
        target.setRole(src.getRole());
        target.setNetwork(src.getNetwork());
        target.setChainId(src.getChainId());
        // keep ts as is (same ts)
        // keep source & opportunityId (identity)
    }

    // ----------------- small helpers -----------------

    private static String normalize(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonEmpty(String a, String b) {
        return !isEmpty(a) ? a : (!isEmpty(b) ? b : null);
    }

    private static Double parseDoubleSafe(String s) {
        try {
            return (s == null) ? null : Double.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant nowUtc() {
        return Instant.now();
    }

    private static Instant minuteFloor(Instant t) {
        return t.minusSeconds(t.getEpochSecond() % 60);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
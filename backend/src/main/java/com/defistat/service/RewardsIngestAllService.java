// com.defistat.rewards.RewardsIngestAllService.java
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

/**
 * Pull all Merkl opportunities for a chain/protocol and persist adapted documents.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewardsIngestAllService {

    private final MerklClient merklClient;
    private final RewardOpportunityRepository repo;
    private final AppProps appProps;

    /**
     * Reactive ingestion: fetch all by (chainId, protocol) and persist.
     */
    public int ingestAll(String network, String protocol) {
        List<MerklOpportunity> list = merklClient.findAll(appProps.require(network).getChainId(), protocol);
        int saved = 0;
        for (MerklOpportunity o : list) {
            try {
                RewardOpportunityDocument doc = adapt(network, protocol, o);
                if (doc == null) continue;

                var existing = repo.findBySourceAndOpportunityIdAndTs(doc.getSource(), doc.getOpportunityId(), doc.getTs());
                if (existing.isPresent()) {
                    var cur = existing.get();
                    cur.setRewardApyPct(doc.getRewardApyPct());
                    cur.setTvlUsd(doc.getTvlUsd());
                    cur.setName(doc.getName());
                    cur.setVaultAddress(doc.getVaultAddress().toLowerCase());
                    cur.setStatus(doc.getStatus());
                    cur.setDepositUrl(doc.getDepositUrl());
                    cur.setRewards(doc.getRewards());
                    repo.save(cur);
                } else {
                    repo.save(doc);
                }
                saved++;
            } catch (Exception e) {
                log.warn("Failed to save opportunity {}: {}", o.getId(), e.getMessage());
            }
        }
        log.info("Merkl ingest saved/updated {} docs for {} / {}", saved, network, protocol);
        return saved;
    }

    private RewardOpportunityDocument adapt(String network, String protocol, MerklOpportunity o) {
        // Map action -> role
        String role = (o.getAction() != null && o.getAction().equalsIgnoreCase("BORROW")) ? "borrow" : "collateral";

        // Pick vault address
        String vault = firstNonEmpty(o.getExplorerAddress(), o.getIdentifier());
        if (isEmpty(vault)) return null;

        // Use Merkl timestamp if provided; else minute-rounded now()
        Instant ts = minuteFloor(nowUtc());
        try {
            if (o.getAprRecord() != null && o.getAprRecord().getTimestamp() != null) {
                long sec = Long.parseLong(o.getAprRecord().getTimestamp());
                ts = Instant.ofEpochSecond(sec);
            }
        } catch (Exception ignore) { /* keep rounded now() */ }

        List<RewardOpportunityDocument.RewardTokenValue> rewards = Collections.emptyList();
        if (o.getRewardsRecord() != null && o.getRewardsRecord().getBreakdowns() != null) {
            rewards = o.getRewardsRecord().getBreakdowns().stream()
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
        }

        return RewardOpportunityDocument.builder()
                .network(network.toLowerCase(Locale.ROOT))
                .protocol(protocol)
                .vaultAddress(vault.toLowerCase())
                .role(role)
                .rewardApyPct(o.getApr())        // cumulated APR already aggregated by Merkl
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

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonEmpty(String a, String b) {
        return !isEmpty(a) ? a : (!isEmpty(b) ? b : null);
    }

    private static Double parseDoubleSafe(String s) {
        try {
            return s == null ? null : Double.valueOf(s);
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
}
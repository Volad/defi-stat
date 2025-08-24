// com.defistat.rewards.model.RewardOpportunityDocument.java
package com.defistat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Adapted reward opportunity snapshot from Merkl stored in Mongo.
 * One document per opportunity/time (effective ts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("reward_opportunity")
@CompoundIndexes({
        @CompoundIndex(name = "by_key_ts", def = "{'network':1,'vaultAddress':1,'role':1,'ts':-1}"),
        // prevent duplicates for the same Merkl opportunity at the same timestamp
        @CompoundIndex(name = "uniq_source_oid_ts", def = "{'source':1,'opportunityId':1,'ts':1}", unique = true)
})
public class RewardOpportunityDocument {

    @Id
    private String id;

    /** e.g. avalanche, base */
    @Indexed
    private String network;

    /** protocol marker e.g. EULER */
    @Indexed
    private String protocol;

    /** target vault address (identifier/explorerAddress) */
    @Indexed
    private String vaultAddress;

    /** LEND -> collateral, BORROW -> borrow */
    @Indexed
    private String role;

    /** Effective reward APR in percent (cumulated) */
    private Double rewardApyPct;

    /** TVL in USD for opportunity (if provided) */
    private Double tvlUsd;

    /** Raw metadata for debugging */
    private String name;
    private String status;  // LIVE, ...
    private String depositUrl;

    /** Time when APR is measured (prefer aprRecord.timestamp; fallback now()) */
    @Indexed(direction = IndexDirection.DESCENDING)
    private Instant ts;

    /** Merkl-origin info */
    private String source;         // "merkl"
    private String opportunityId;  // Merkl id
    private Long chainId;          // numeric chain id

    /** Reward token breakdown (light) */
    private List<RewardTokenValue> rewards;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RewardTokenValue {
        private String tokenAddress;
        private String symbol;
        private Integer decimals;
        private Double priceUsd;
        private Double amount;     // parsed as double if possible
        private Double valueUsd;   // value from Merkl
        private String distributionType;
        private String campaignId;
    }
}
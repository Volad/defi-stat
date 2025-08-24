// com.defistat.merkl.dto.MerklOpportunity.java
package com.defistat.web3.dto.merkl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

/**
 * DTO for Merkl API "opportunity" object.
 * Used for deserializing JSON directly from API.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MerklOpportunity {
    private long chainId;
    private String type;
    private String identifier;      // обычно адрес вольта/таргета
    private String name;
    private String description;
    private String status;          // LIVE / ...
    private String action;          // LEND / BORROW
    private double tvl;
    private double apr;             // cumulated APR (в %)
    private double maxApr;
    private double dailyRewards;
    private int liveCampaigns;
    private String id;              // internal opportunity id
    private String depositUrl;
    private String explorerAddress; // адрес вольта в эксплорере
    private long lastCampaignCreatedAt;

    private List<TokenInfo> tokens;
    private AprRecord aprRecord;
    private RewardsRecord rewardsRecord;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenInfo {
        private String name;
        private long chainId;
        private String address;
        private int decimals;
        private String symbol;
        private String displaySymbol;
        private boolean verified;
        private boolean isTest;
        private boolean isNative;
        private double price;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AprRecord {
        private double cumulated;
        private String timestamp;     // секундный UNIX в строке
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RewardsRecord {
        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class RewardBreakdown {
            @Data @JsonIgnoreProperties(ignoreUnknown = true)
            public static class RewardToken {
                private String address;
                private String symbol;
                private int decimals;
                private double price;
            }
            private RewardToken token;
            private String amount;
            private double value;        // USD value / day (из примера)
            private String distributionType;
            private String id;
            private String timestamp;
            private String campaignId;
        }
        private String id;
        private double total;
        private String timestamp;
        private List<RewardBreakdown> breakdowns;
    }
}
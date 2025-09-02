// src/main/java/com/defistat/eulerscan/dto/EulerScanHourlyResponse.java
package com.defistat.web3.dto.eulerscan;

import lombok.Data;

import java.util.List;

/** DTO matching EulerScan /historical/hourly response. */
@Data
public class EulerScanHourlyResponse {
    private String asset;           // underlying token address
    private Integer assetDecimals;  // e.g., 6 for USDC
    private String vault;           // eVault address (lower-case)
    private List<Snapshot> snapshots;

    @Data
    public static class Snapshot {
        private Long blockNumber;     // block
        private Long timestamp;       // unix seconds
        private String totalBorrowed; // as decimal string (token units)
        private String totalAssets;   // as decimal string (token units)
        private String borrowAPY;     // EulerScan field name; they call it APY (we'll treat as %)
        private String supplyAPY;     // EulerScan field name
    }
}
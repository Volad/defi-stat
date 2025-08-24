package com.defistat.api.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Request to compute ROE and Health Factor from two AssetSnapshots.
 * - Uses latest snapshots if 'ts' is null, otherwise uses "latest <= ts".
 * - Rewards APY are optional and applied as:
 *     supplyTotal = supplyApyPct + collateralRewardsApyPct
 *     borrowNet   = borrowApyPct  - borrowRewardsApyPct
 * - Prices and LT can be overridden per request; otherwise use defaults.
 */
@Data
public class RoeHfRequest {
    private String network = "avalanche";

    /** eVault addresses (must be 0x + 40 hex chars). */
    private String collateralVault;
    private String borrowVault;

    /** Leverage L (e.g. 3.0). */
    private double leverage;

    /** Optional timestamp to pick historical snapshots (latest <= ts). */
    private Instant ts;

    /** Rewards APY (in percent APR) to be applied on top of snapshots. */
    private double collateralRewardsApyPct = 0.0;
    private double borrowRewardsApyPct     = 0.0;

    /** Optional overrides. If null or <=0, server uses defaults from config. */
    private Double liquidationThresholdPct; // e.g., 83.0
    private Double priceCollateralUSD;      // e.g., 1.0
    private Double priceBorrowUSD;          // e.g., 1.0
}
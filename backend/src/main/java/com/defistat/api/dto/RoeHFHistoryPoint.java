package com.defistat.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Response with full breakdown of inputs and calculated outputs.
 */
@Data
@Builder
public class RoeHFHistoryPoint {

    // Echo request
    private String network;
    private String collateralVault;
    private String borrowVault;
    private double leverage;

    // Snapshots actually used
    private Instant collateralTs;
    private Instant borrowTs;

    // Rates used for calculation (all in % APR)
    private double collateralSupplyApyPct; // from snapshot
    private double borrowBorrowApyPct;     // from snapshot
    private double collateralRewardsApyPct;
    private double borrowRewardsApyPct;
    private double supplyTotalPct;         // supply + rewards
    private double borrowNetPct;           // borrow - rewards

    // Utilizations (for info)
    private double collateralUtilPct;
    private double borrowUtilPct;

    // Prices and risk param used
    private double priceCollateralUSD;
    private double priceBorrowUSD;
    private double liquidationThresholdPct;

    // Calculated outputs
    private double roePct;  // APR
    private double hf;      // health factor

    // Optional messages/warnings
    private String note;
}
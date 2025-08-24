package com.defistat.api.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Request body for ROE/HF time series computation.
 */
@Data
public class RoeHfSeriesRequest {

    private String network = "avalanche";

    private String collateralVault;
    private String borrowVault;
    private double leverage;

    private Instant from;
    private Instant to;

    private int tickToleranceSeconds = 60;

    private double collateralRewardsApyPct = 0.0;
    private double borrowRewardsApyPct     = 0.0;

    private Double liquidationThresholdPct;
    private Double priceCollateralUSD;
    private Double priceBorrowUSD;
}
package com.defistat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Time-series snapshot for a single eVault on a specific network.
 * We store borrow/supply APY and utilization, sampled at a fixed interval.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document("asset_snapshots")
public class AssetSnapshot {

    @Id
    private String id;

    /** Network key, e.g., "avalanche" / "base" / "mainnet". */
    @Indexed
    private String network;

    /** eVault address (checksummed string). */

    private String vaultAddressOriginal;

    @Indexed
    private String vaultAddress;

    /** UTC timestamp of when the snapshot was taken. */
    @Indexed
    private Instant ts;

    @Indexed
    private Instant tsTick;

    /** Annualized rates in percent (APR), already converted from on-chain units. */
    private double borrowApyPct;
    private double supplyApyPct;

    /** Utilization in percent: totalBorrows / totalAssets * 100. */
    private double utilizationPct;

    /** Optional labels cached for convenience (symbol/name from subgraph). */
    private String vaultSymbol;
    private String vaultName;
}
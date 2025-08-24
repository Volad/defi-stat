package com.defistat.service;

import com.defistat.api.dto.RoeHfRequest;
import com.defistat.api.dto.RoeHFHistoryPoint;
import com.defistat.config.AppProps;
import com.defistat.model.AssetSnapshot;
import com.defistat.repo.AssetSnapshotRepo;
import com.defistat.util.AddressUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * Computes ROE (APR %) and Health Factor from AssetSnapshot(s).
 *
 * Rewards APR resolution rules (via RewardAprResolver):
 *  1) If DB has NO records for (network,vault,role): use user-provided APR (or 0 if null).
 *  2) If DB HAS records: use APR from the latest record <= T (snapshot time).
 *  3) If that latest record's status is NOT "LIVE" at/before T: APR = 0 (campaign ended).
 *
 * Series synchronization strategy:
 *  1) Try to join by common batch 'ts' (preferred, exact match)
 *  2) Fallback to nearest 'tsTick' within tolerance seconds (to handle skew/latency)
 */
@Service
@RequiredArgsConstructor
public class RoeHfService {

    private final AssetSnapshotRepo snapshotRepo;
    private final AppProps props;

    // External resolver that implements the rules above
    private final RewardAprResolver rewardAprResolver;

    // ---------- Public API: single point ----------

    /**
     * Compute a single RoeHfResponse from the latest (or <= ts) snapshots.
     * Rewards APR are resolved per-vault and per-role at each snapshot time.
     */
    public RoeHFHistoryPoint compute(RoeHfRequest req) {
        Objects.requireNonNull(req, "request must not be null");

        final String network = (req.getNetwork() == null) ? "avalanche" : req.getNetwork();
        final String collateral = AddressUtil.normalize(req.getCollateralVault());
        final String borrow     = AddressUtil.normalize(req.getBorrowVault());
        final double L = req.getLeverage();
        if (L < 1.0) throw new IllegalArgumentException("Leverage must be >= 1.0");

        // Resolve risk & pricing params with fallbacks to config
        final double ltPct = valueOrDefault(req.getLiquidationThresholdPct(), props.getCalc().getLiquidationThresholdPct());
        final double pCol  = valueOrDefault(req.getPriceCollateralUSD(),      props.getCalc().getPriceCollateralUSD());
        final double pBor  = valueOrDefault(req.getPriceBorrowUSD(),          props.getCalc().getPriceBorrowUSD());

        // Pick snapshots: latest, or "latest <= ts" if provided
        final Instant reqTs = req.getTs();
        final AssetSnapshot sCol = (reqTs == null)
                ? snapshotRepo.findTopByNetworkAndVaultAddressOrderByTsDesc(network, collateral)
                : snapshotRepo.findTopByNetworkAndVaultAddressAndTsLessThanEqualOrderByTsDesc(network, collateral, reqTs);
        final AssetSnapshot sBor = (reqTs == null)
                ? snapshotRepo.findTopByNetworkAndVaultAddressOrderByTsDesc(network, borrow)
                : snapshotRepo.findTopByNetworkAndVaultAddressAndTsLessThanEqualOrderByTsDesc(network, borrow, reqTs);

        if (sCol == null) throw new IllegalStateException("No collateral snapshot found for " + collateral + " on " + network);
        if (sBor == null) throw new IllegalStateException("No borrow snapshot found for " + borrow     + " on " + network);

        // Resolve rewards APR "as of" each side's snapshot time
        final Instant tCol = prefer(sCol.getTsTick(), sCol.getTs());
        final Instant tBor = prefer(sBor.getTsTick(), sBor.getTs());

        // If DB is empty for a vault/role, resolver will fallback to user-provided APR; else use DB APR.
        final double colRewardsResolved = rewardAprResolver.resolve(network, collateral, "collateral", tCol, req.getCollateralRewardsApyPct());
        final double borRewardsResolved = rewardAprResolver.resolve(network, borrow,     "borrow",     tBor, req.getBorrowRewardsApyPct());

        return buildResponse(
                network, collateral, borrow, L,
                sCol, sBor,
                colRewardsResolved, borRewardsResolved,
                pCol, pBor, ltPct,
                "Computed from AssetSnapshot; rewards resolved via RewardAprResolver."
        );
    }

    // ---------- Public API: series (array of RoeHfResponse) ----------

    /**
     * Compute a time series of RoeHfResponse for a given range, joining snapshots across time.
     * For each point, rewards APR are resolved at the specific timestamps of collateral/borrow snapshots.
     *
     * @param network   network key (e.g., "avalanche")
     * @param collateralVault eVault address (0x + 40 hex)
     * @param borrowVault     eVault address (0x + 40 hex)
     * @param leverage  leverage L (>= 1.0)
     * @param from      inclusive range start (UTC)
     * @param to        inclusive range end (UTC)
     * @param tickToleranceSeconds tolerance (seconds) for tsTick-based matching
     * @param userCollateralRewardsApyPct optional user-provided collateral rewards APR (%), used only if DB is empty for that vault/role
     * @param userBorrowRewardsApyPct     optional user-provided borrow rewards APR (%), used only if DB is empty for that vault/role
     * @param liquidationThresholdPct optional override (percent), null => config default
     * @param priceCollateralUSD      optional override, null => config default
     * @param priceBorrowUSD          optional override, null => config default
     */
    public List<RoeHFHistoryPoint> computeSeries(
            String network,
            String collateralVault,
            String borrowVault,
            double leverage,
            Instant from,
            Instant to,
            int tickToleranceSeconds,
            Double userCollateralRewardsApyPct,
            Double userBorrowRewardsApyPct,
            Double liquidationThresholdPct,
            Double priceCollateralUSD,
            Double priceBorrowUSD
    ) {
        if (from == null || to == null) throw new IllegalArgumentException("'from' and 'to' must be provided");
        if (leverage < 1.0) throw new IllegalArgumentException("Leverage must be >= 1.0");

        final String net = (network == null) ? "avalanche" : network;
        final String col = AddressUtil.normalize(collateralVault);
        final String bor = AddressUtil.normalize(borrowVault);

        final double ltPct = valueOrDefault(liquidationThresholdPct, props.getCalc().getLiquidationThresholdPct());
        final double pCol  = valueOrDefault(priceCollateralUSD,      props.getCalc().getPriceCollateralUSD());
        final double pBor  = valueOrDefault(priceBorrowUSD,          props.getCalc().getPriceBorrowUSD());
        final int tolSec   = Math.max(0, tickToleranceSeconds);

        // Load ascending series for each side
        final List<AssetSnapshot> colSeries = snapshotRepo
                .findByNetworkAndVaultAddressAndTsBetweenOrderByTsAsc(net, col, from, to);
        final List<AssetSnapshot> borSeries = snapshotRepo
                .findByNetworkAndVaultAddressAndTsBetweenOrderByTsAsc(net, bor, from, to);

        if (colSeries.isEmpty() || borSeries.isEmpty()) return List.of();

        // 1) Try to join by exact batch ts
        List<RoeHFHistoryPoint> out = joinByBatchTs(
                net, col, bor, leverage,
                colSeries, borSeries,
                userCollateralRewardsApyPct, userBorrowRewardsApyPct,
                pCol, pBor, ltPct
        );

        // 2) Fallback to nearest tsTick (within tolerance)
        if (out.isEmpty()) {
            out = joinByTsTickNear(
                    net, col, bor, leverage,
                    colSeries, borSeries, tolSec,
                    userCollateralRewardsApyPct, userBorrowRewardsApyPct,
                    pCol, pBor, ltPct
            );
        }

        // Keep chronological order
        out.sort(Comparator.comparing(RoeHFHistoryPoint::getCollateralTs, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RoeHFHistoryPoint::getBorrowTs, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    // ---------- join helpers (series) ----------

    /**
     * Join series by exact batch ts and build responses.
     * Rewards APR are resolved at the snapshot timestamps (per side).
     */
    private List<RoeHFHistoryPoint> joinByBatchTs(
            String network, String colAddr, String borAddr, double L,
            List<AssetSnapshot> colSeries, List<AssetSnapshot> borSeries,
            Double userColReward, Double userBorReward,
            double pCol, double pBor, double ltPct
    ) {
        final Map<Instant, AssetSnapshot> colByTs = new HashMap<>();
        for (AssetSnapshot s : colSeries) if (s.getTs() != null) colByTs.put(s.getTs(), s);

        final List<RoeHFHistoryPoint> out = new ArrayList<>();
        for (AssetSnapshot b : borSeries) {
            final Instant k = b.getTs();
            if (k == null) continue;
            final AssetSnapshot c = colByTs.get(k);
            if (c != null) {
                // Resolve rewards at each side's snapshot time
                final Instant tCol = prefer(c.getTsTick(), c.getTs());
                final Instant tBor = prefer(b.getTsTick(), b.getTs());
                final double colRewardsResolved = rewardAprResolver.resolve(network, colAddr, "collateral", tCol, userColReward);
                final double borRewardsResolved = rewardAprResolver.resolve(network, borAddr, "borrow",     tBor, userBorReward);

                out.add(buildResponse(network, colAddr, borAddr, L, c, b,
                        colRewardsResolved, borRewardsResolved,
                        pCol, pBor, ltPct,
                        "Series item (joined by batch ts; rewards resolved by ts)")
                );
            }
        }
        return out;
    }

    /**
     * Join series by nearest tsTick within tolerance and build responses.
     * Rewards APR are resolved at the selected snapshot timestamps (per side).
     */
    private List<RoeHFHistoryPoint> joinByTsTickNear(
            String network, String colAddr, String borAddr, double L,
            List<AssetSnapshot> colSeries, List<AssetSnapshot> borSeries, int tolSec,
            Double userColReward, Double userBorReward,
            double pCol, double pBor, double ltPct
    ) {
        final List<RoeHFHistoryPoint> out = new ArrayList<>();
        int j = 0;
        for (AssetSnapshot c : colSeries) {
            final Instant ct = prefer(c.getTsTick(), c.getTs());
            if (ct == null) continue;

            AssetSnapshot best = null;
            long bestDiff = Long.MAX_VALUE;

            while (j < borSeries.size()) {
                final AssetSnapshot b = borSeries.get(j);
                final Instant bt = prefer(b.getTsTick(), b.getTs());
                if (bt == null) { j++; continue; }

                final long diff = Math.abs(Duration.between(ct, bt).getSeconds());
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = b;
                    if (bt.isAfter(ct) && diff > 0) break; // lists are asc; passed the closest
                    j++;
                } else {
                    break; // diff started growing -> previous was best
                }
            }

            if (best != null && bestDiff <= tolSec) {
                // Resolve rewards at each side's snapshot time
                final Instant tCol = ct;
                final Instant tBor = prefer(best.getTsTick(), best.getTs());
                final double colRewardsResolved = rewardAprResolver.resolve(network, colAddr, "collateral", tCol, userColReward);
                final double borRewardsResolved = rewardAprResolver.resolve(network, borAddr, "borrow",     tBor, userBorReward);

                out.add(buildResponse(network, colAddr, borAddr, L, c, best,
                        colRewardsResolved, borRewardsResolved,
                        pCol, pBor, ltPct,
                        "Series item (matched by tsTick ± " + tolSec + "s; rewards resolved by ts)")
                );
            }
        }
        return out;
    }

    // ---------- math & response ----------

    /**
     * Builds the final response using:
     *  - Base APYs from snapshots,
     *  - Resolved rewards APRs (already applying DB/user rules),
     *  - Leverage, prices and liquidation threshold.
     */
    private RoeHFHistoryPoint buildResponse(
            String network, String collateral, String borrow, double L,
            AssetSnapshot sCol, AssetSnapshot sBor,
            double collateralRewardsApyPct, double borrowRewardsApyPct,
            double priceCollateralUSD, double priceBorrowUSD, double liquidationThresholdPct,
            String note
    ) {
        // Effective APRs (in percent)
        final double supplyTotalPct = safe(sCol.getSupplyApyPct()) + collateralRewardsApyPct;
        final double borrowNetPct   = safe(sBor.getBorrowApyPct()) - borrowRewardsApyPct;

        // ROE (% APR) — keep your exact formula if it differs; this is a common leveraged spread template
        final double roePct = L * supplyTotalPct - (L - 1.0) * borrowNetPct;

        // Health Factor (dimensionless); simplified template
        final double LT = liquidationThresholdPct / 100.0;
        final double debt = (L - 1.0) * priceBorrowUSD;
        final double hf = (debt > 0.0) ? ((L * priceCollateralUSD * LT) / debt) : Double.POSITIVE_INFINITY;

        return RoeHFHistoryPoint.builder()
                .network(network)
                .collateralVault(collateral)
                .borrowVault(borrow)
                .leverage(L)
                .collateralTs(sCol.getTs())
                .borrowTs(sBor.getTs())
                .collateralSupplyApyPct(sCol.getSupplyApyPct())
                .borrowBorrowApyPct(sBor.getBorrowApyPct())
                .collateralRewardsApyPct(collateralRewardsApyPct)
                .borrowRewardsApyPct(borrowRewardsApyPct)
                .supplyTotalPct(supplyTotalPct)
                .borrowNetPct(borrowNetPct)
                .collateralUtilPct(sCol.getUtilizationPct())
                .borrowUtilPct(sBor.getUtilizationPct())
                .priceCollateralUSD(priceCollateralUSD)
                .priceBorrowUSD(priceBorrowUSD)
                .liquidationThresholdPct(liquidationThresholdPct)
                .roePct(roePct)
                .hf(hf)
                .note(note)
                .build();
    }

    // ---------- small helpers ----------

    private static double safe(Double v) { return v == null ? 0.0 : v; }

    private static double valueOrDefault(Double override, double def) {
        return (override != null && override > 0.0) ? override : def;
    }

    /** Prefer tsTick if present; otherwise fall back to batch ts. */
    private static Instant prefer(Instant a, Instant b) {
        return a != null ? a : b;
    }
}
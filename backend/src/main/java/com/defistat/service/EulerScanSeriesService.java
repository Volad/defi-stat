package com.defistat.service;

import com.defistat.api.dto.RoeHFHistoryPoint; // ваша модель серии
import com.defistat.config.AppProps;
import com.defistat.model.RewardOpportunityDocument;
import com.defistat.repo.RewardOpportunityRepository;
import com.defistat.util.AddressUtil;
import com.defistat.web3.EulerScanClient;
import com.defistat.web3.dto.eulerscan.EulerScanHourlyResponse;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds ROE/HF time series using EulerScan hourly snapshots,
 * while resolving reward APRs from a prefetched timeline, NOT per-point DB lookups.
 * <p>
 * Performance notes:
 * - We prefetch rewards for (network, vault, role) once for the whole [from..to] window (+buffer),
 * build a compact timeline, and answer aprAt(ts) in-memory.
 * - This eliminates O(N) DB roundtrips that were previously caused by rewardAprResolver.resolve(...) per point.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EulerScanSeriesService {

    private final EulerScanClient eulerScanClient; // ваш клиент к api.eulerscan.xyz (часовые снапшоты)
    private final RewardOpportunityRepository rewardRepo;
    private final AppProps props;

    // Small buffer to tolerate off-by-one and boundary effects of external data sources
    private static final Duration PREFETCH_BUFFER = Duration.ofHours(6);

    /**
     * Build series for a pair of vaults using EulerScan, merging with rewards timeline.
     */
    public List<RoeHFHistoryPoint> buildSeries(
            String network,
            String collateralVault,
            String borrowVault,
            double leverage,
            Instant from,
            Instant to,
            double userCollateralRewardAprPct,
            double userBorrowRewardAprPct,
            Double liquidationThresholdPct,
            Double priceCollateralUSD,
            Double priceBorrowUSD
    ) {
        final String net = network.toLowerCase(Locale.ROOT);

        List<EulerScanHourlyResponse.Snapshot> colResp = eulerScanClient.getHourly(net, collateralVault, from, to);
        List<EulerScanHourlyResponse.Snapshot> borResp = eulerScanClient.getHourly(net, borrowVault, from, to);

        if (colResp == null || borResp == null
        ) {
            return List.of();
        }

        // Prefetch rewards timelines
        RewardAprTimeline colRewards = buildTimeline(net, collateralVault, "collateral",
                userCollateralRewardAprPct, from.minus(Duration.ofHours(6)), to.plus(Duration.ofHours(6)));
        RewardAprTimeline borRewards = buildTimeline(net, borrowVault, "borrow",
                userBorrowRewardAprPct, from.minus(Duration.ofHours(6)), to.plus(Duration.ofHours(6)));

        // defaults
        double ltPct = liquidationThresholdPct != null ? liquidationThresholdPct : props.getCalc().getLiquidationThresholdPct();
        double pCol = priceCollateralUSD != null ? priceCollateralUSD : props.getCalc().getPriceCollateralUSD();
        double pBor = priceBorrowUSD != null ? priceBorrowUSD : props.getCalc().getPriceBorrowUSD();

        // Index by timestamp for join
        Map<Long, EulerScanHourlyResponse.Snapshot> colByTs = colResp.stream()
                .filter(s -> s.getTimestamp() != null)
                .collect(Collectors.toMap(EulerScanHourlyResponse.Snapshot::getTimestamp, s -> s, (a, b) -> a));
        Map<Long, EulerScanHourlyResponse.Snapshot> borByTs = borResp.stream()
                .filter(s -> s.getTimestamp() != null)
                .collect(Collectors.toMap(EulerScanHourlyResponse.Snapshot::getTimestamp, s -> s, (a, b) -> a));

        // join on common timestamps
        List<Long> timestamps = new ArrayList<>(colByTs.keySet());
        timestamps.retainAll(borByTs.keySet());
        Collections.sort(timestamps);

        List<RoeHFHistoryPoint> out = new ArrayList<>();
        for (Long tsSec : timestamps) {
            EulerScanHourlyResponse.Snapshot c = colByTs.get(tsSec);
            EulerScanHourlyResponse.Snapshot b = borByTs.get(tsSec);
            if (c == null || b == null) continue;

            Instant ts = Instant.ofEpochSecond(tsSec);

            double supplyPct = parsePct(c.getSupplyAPY());
            double borrowPct = parsePct(b.getBorrowAPY());

            // util
            double colUtilPct = utilPct(c.getTotalBorrowed(), c.getTotalAssets());
            double borUtilPct = utilPct(b.getTotalBorrowed(), b.getTotalAssets());

            // rewards from prefetched timelines
            double colRw = colRewards.aprAt(ts);
            double borRw = borRewards.aprAt(ts);

            double supplyTotalPct = supplyPct + colRw;
            double borrowNetPct = borrowPct - borRw;

            double roePct = leverage * supplyTotalPct - (leverage - 1.0) * borrowNetPct;

            double LT = ltPct / 100.0;
            double debt = (leverage - 1.0) * pBor;
            double hf = (debt > 0.0) ? ((leverage * pCol * LT) / debt) : Double.POSITIVE_INFINITY;

            out.add(RoeHFHistoryPoint.builder()
                    .network(net)
                    .collateralVault(collateralVault)
                    .borrowVault(borrowVault)
                    .leverage(leverage)
                    .collateralTs(ts)
                    .borrowTs(ts)
                    .collateralSupplyApyPct(supplyPct)
                    .borrowBorrowApyPct(borrowPct)
                    .collateralRewardsApyPct(colRw)
                    .borrowRewardsApyPct(borRw)
                    .supplyTotalPct(supplyTotalPct)
                    .borrowNetPct(borrowNetPct)
                    .collateralUtilPct(colUtilPct)
                    .borrowUtilPct(borUtilPct)
                    .priceCollateralUSD(pCol)
                    .priceBorrowUSD(pBor)
                    .liquidationThresholdPct(ltPct)
                    .roePct(roePct)
                    .hf(hf)
                    .note("EulerScan/hourly+prefetched")
                    .build());
        }

        return out;
    }


    // -------------------- Rewards timeline --------------------

    /**
     * Build an in-memory timeline from all RewardOpportunityDocument for (network, vault, role)
     * inside [from..to], applying the business rules requested:
     * - If there is NO record <= ts: return userProvided APR.
     * - If the latest record exists and ts is after its 'end' (campaign ended): APR = 0.
     * - Else: APR = record.rewardApyPct.
     */
    private RewardAprTimeline buildTimeline(
            String network,
            String vault,
            String role,
            double userProvidedAprPct,
            Instant from,
            Instant to
    ) {
        List<RewardOpportunityDocument> docs = rewardRepo
                .findByNetworkAndVaultAddressAndRoleAndTsBetweenOrderByTsAsc(network, vault.toLowerCase(), role, from, to);

        // Sort defensively (repo already ordered asc)
        docs.sort(Comparator.comparing(RewardOpportunityDocument::getTs, Comparator.nullsLast(Comparator.naturalOrder())));

        return new RewardAprTimeline(userProvidedAprPct, docs);
    }

    @Value
    private static class HourPoint {
        long ts;               // hour-aligned epoch seconds
        double supplyApyPct;   // %/y
        double borrowApyPct;   // %/y
        double utilPct;        // % (0..100)
    }

    private static Map<Long, HourPoint> indexByHour(List<HourPoint> list) {
        Map<Long, HourPoint> m = new HashMap<>(Math.max(16, list.size() * 2));
        for (HourPoint p : list) m.put(p.ts, p);
        return m;
    }

    // -------------------- EulerScan client adapter --------------------

    /**
     * Convert whatever DTO your EulerScan client returns into our internal HourPoint list.
     * Assume client returns hourly snapshots with fields {timestamp, supplyAPY, borrowAPY, totalAssets, totalBorrowed}.
     */
    static List<HourPoint> mapEulerScan(List<EulerScanSnapshotDTO> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<HourPoint> out = new ArrayList<>(raw.size());
        for (var s : raw) {
            long ts = s.timestamp(); // seconds
            // utilization = totalBorrowed / totalAssets * 100
            double util = 0.0;
            try {
                double ta = new java.math.BigDecimal(s.totalAssets()).doubleValue();
                double tb = new java.math.BigDecimal(s.totalBorrowed()).doubleValue();
                util = (ta > 0) ? (tb / ta) * 100.0 : 0.0;
            } catch (Exception ignore) {
            }

            out.add(new HourPoint(
                    ts,
                    parsePct(s.supplyAPY()),
                    parsePct(s.borrowAPY()),
                    util
            ));
        }
        return out;
    }

    // --- utils ---
    private static double parsePct(String v) {
        if (v == null) return 0.0;
        try {
            // Parse as BigDecimal to keep precision and to handle scientific notation
            java.math.BigDecimal bd = new java.math.BigDecimal(v.trim());

            if (bd.signum() == 0) return 0.0;

            // Heuristic 1: very large => assume RAY (>= 1e12 is a safe guardrail here)
            // percent = ray / 1e27 * 100 => divide by 1e25
            java.math.BigDecimal THRESH_RAY = new java.math.BigDecimal("1e12");
            if (bd.compareTo(THRESH_RAY) > 0) {
                return bd.divide(new java.math.BigDecimal("1e25"), java.math.MathContext.DECIMAL64).doubleValue();
            }

            // Heuristic 2: small fractional (0..1) => interpret as fraction -> *100
            java.math.BigDecimal ONE = java.math.BigDecimal.ONE;
            if (bd.compareTo(ONE) < 0 && bd.compareTo(java.math.BigDecimal.ZERO) > 0) {
                return bd.multiply(new java.math.BigDecimal("100")).doubleValue();
            }

            // Otherwise treat as already percent
            return bd.doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double utilPct(String totalBorrowed, String totalAssets) {
        try {
            double ta = new java.math.BigDecimal(totalAssets).doubleValue();
            double tb = new java.math.BigDecimal(totalBorrowed).doubleValue();
            return ta > 0 ? (tb / ta) * 100.0 : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }


    // -------------------- Reward APR timeline model --------------------

    /**
     * Immutable, in-memory reward timeline with O(log N) lookup.
     * It uses the last known record <= ts to decide APR according to business rules.
     */
    static final class RewardAprTimeline {
        private final double userDefaultApr;
        private final List<Entry> entries;   // ascending by ts

        RewardAprTimeline(double userDefaultApr, List<RewardOpportunityDocument> docs) {
            this.userDefaultApr = userDefaultApr;
            this.entries = compact(sortedCopy(docs));
        }

        /**
         * Resolve APR at timestamp ts (seconds precision is ok).
         * Rules:
         * - If no records exist with ts_doc <= ts: return userDefaultApr.
         * - If last record has end != null and ts > end: APR = 0.
         * - Else APR = last.rewardApyPct (null treated as 0).
         */
        double aprAt(Instant ts) {
            if (entries.isEmpty() || ts == null) return userDefaultApr;

            int idx = floorIndex(ts);
            if (idx < 0) {
                // no record before ts
                return userDefaultApr;
            }
            Entry e = entries.get(idx);
            if (e != null && e.status != null && !e.status.equalsIgnoreCase("live")) {
                return 0.0;
            }
            return e != null && e.apr != null ? e.apr : 0.0;
        }

        // Binary search floor (largest i such that entries[i].ts <= ts)
        private int floorIndex(Instant ts) {
            int lo = 0, hi = entries.size() - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                Instant t = entries.get(mid).ts;
                if (t == null) {
                    lo = mid + 1;
                    continue;
                }
                if (!t.isAfter(ts)) {
                    ans = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return ans;
        }

        @Value
        static class Entry {
            Instant ts;
            Double apr;
            String status;
        }

        private static List<Entry> sortedCopy(List<RewardOpportunityDocument> docs) {
            if (docs == null || docs.isEmpty()) return List.of();
            return docs.stream()
                    .sorted(Comparator.comparing(RewardOpportunityDocument::getTs, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(d -> new Entry(d.getTs(), d.getRewardApyPct(), d.getStatus()))
                    .collect(Collectors.toList());
        }

        /**
         * Optional compaction: collapse adjacent entries where both `status` and `apr` are equal.
         * This reduces memory and speeds up binary search a bit.
         */
        private static List<Entry> compact(List<Entry> in) {
            if (in.isEmpty()) return in;
            List<Entry> out = new ArrayList<>(in.size());
            Entry prev = in.get(0);
            out.add(prev);
            for (int i = 1; i < in.size(); i++) {
                Entry cur = in.get(i);
                boolean sameApr = Objects.equals(prev.apr, cur.apr);
                boolean sameStatus = Objects.equals(prev.status, cur.status);
                if (sameApr && sameStatus) {
                    // extend "prev" range if prev.end is null and cur.end is not earlier
                    // we just keep both; end is used as "hard stop" only. Simpler: keep as-is.
                    continue;
                } else {
                    out.add(cur);
                    prev = cur;
                }
            }
            return out;
        }
    }

    // -------------------- DTO for EulerScan --------------------
    // Replace with your actual DTO or record
    public static final class EulerScanSnapshotDTO {
        private long timestamp;          // seconds
        private String supplyAPY;        // string number
        private String borrowAPY;        // string number
        private String totalAssets;      // big number as string
        private String totalBorrowed;    // big number as string

        public long timestamp() {
            return timestamp;
        }

        public String supplyAPY() {
            return supplyAPY;
        }

        public String borrowAPY() {
            return borrowAPY;
        }

        public String totalAssets() {
            return totalAssets;
        }

        public String totalBorrowed() {
            return totalBorrowed;
        }
    }
}
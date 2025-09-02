package com.defistat.api;

import com.defistat.api.dto.RoeHfRequest;
import com.defistat.api.dto.RoeHfSeriesRequest;
import com.defistat.api.dto.RoeHFHistoryPoint;
import com.defistat.service.EulerScanSeriesService;
import com.defistat.service.RoeHfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Endpoints for single-point and series ROE/HF using RoeHfResponse for both.
 */
@RestController
@RequestMapping("/api/v1/roe-hf")
@RequiredArgsConstructor
public class RoeHfController {

    private final RoeHfService service;

    private final EulerScanSeriesService eulerScanSeriesService;

    /** Single point (latest or <= ts). */
    @PostMapping
    public RoeHFHistoryPoint compute(@Validated @RequestBody RoeHfRequest request) {
        return service.compute(request);
    }

    /** Time series: POST body with RoeHfSeriesRequest */
    @PostMapping("/series")
    public List<RoeHFHistoryPoint> series(@Validated @RequestBody RoeHfSeriesRequest req) {
        return service.computeSeries(
                req.getNetwork(),
                req.getCollateralVault(),
                req.getBorrowVault(),
                req.getLeverage(),
                req.getFrom(),
                req.getTo(),
                req.getTickToleranceSeconds(),
                req.getCollateralRewardsApyPct(),
                req.getBorrowRewardsApyPct(),
                req.getLiquidationThresholdPct(),
                req.getPriceCollateralUSD(),
                req.getPriceBorrowUSD()
        );
    }




    /**
     * POST /api/v2/roe-hf/series-eulerscan
     * Body = RoeHfRequest (reuse), fields:
     * - network, collateralVault, borrowVault, leverage, from, to,
     * - collateralRewardsApyPct, borrowRewardsApyPct
     */
    @PostMapping("/series-eulerscan")
    public ResponseEntity<List<RoeHFHistoryPoint>> seriesViaEulerScan(@RequestBody RoeHfSeriesRequest req) {
        String net = req.getNetwork() == null ? "avalanche" : req.getNetwork();

        List<RoeHFHistoryPoint> out = eulerScanSeriesService.buildSeries(
                net,
                req.getCollateralVault(),
                req.getBorrowVault(),
                req.getLeverage(),
                req.getFrom(),
                req.getTo(),
                req.getCollateralRewardsApyPct(),
                req.getBorrowRewardsApyPct(),
                req.getLiquidationThresholdPct(),
                req.getPriceCollateralUSD(),
                req.getPriceBorrowUSD()
        );
        return ResponseEntity.ok(out);
    }
}
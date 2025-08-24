package com.defistat.api;

import com.defistat.api.dto.RoeHfRequest;
import com.defistat.api.dto.RoeHfSeriesRequest;
import com.defistat.api.dto.RoeHFHistoryPoint;
import com.defistat.service.RoeHfService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints for single-point and series ROE/HF using RoeHfResponse for both.
 */
@RestController
@RequestMapping("/api/v1/roe-hf")
@RequiredArgsConstructor
public class RoeHfController {

    private final RoeHfService service;

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
}
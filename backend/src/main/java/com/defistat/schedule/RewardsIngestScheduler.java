// com.defistat.rewards.RewardsIngestScheduler.java
package com.defistat.schedule;

import com.defistat.config.AppProps;
import com.defistat.service.AssetService;
import com.defistat.service.RewardsIngestAllService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically pulls Merkl rewards for known vaults and persists them.
 * You can wire it to your asset catalog / discovery service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RewardsIngestScheduler {

    private final RewardsIngestAllService rewardsIngestAllService;
    private final AppProps props;

    private final AssetService assetService;

    /**
     * Every 10 minutes — tune by your needs.
     */
    @Scheduled(cron = "0 0/10 * * * ?") // every 10 minutes
    public void run() {
        props.getNetwork().keySet().forEach(net -> {
                    try {
                        rewardsIngestAllService.ingestAll(net, "EULER");
                    } catch (Exception e) {
                        System.err.println("[rewards-scheduler] network=" + net + " failed: " + e.getMessage());
                    }
                }
        );
    }
}
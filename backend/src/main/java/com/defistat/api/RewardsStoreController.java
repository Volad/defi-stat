// com.defistat.rewards.RewardsStoreController.java
package com.defistat.api;

import com.defistat.schedule.RewardsIngestScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints to (a) trigger ingestion from Merkl and (b) fetch latest stored rewards.
 */
@RestController
@RequestMapping("/api/v1/rewards-store")
@RequiredArgsConstructor
public class RewardsStoreController {

    private final RewardsIngestScheduler scheduler;

    @PostMapping("/ingest/merkl")
    public void ingestFromMerkl() {
        scheduler.run();
    }

}
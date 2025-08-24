package com.defistat.schedule;

import com.defistat.config.AppProps;
import com.defistat.service.AllAssetsPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Periodically iterates over configured networks and triggers polling for each one.
 * Runs every minute; service enforces per-network interval via AppProps.polling.assetsIntervalSeconds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AllAssetsScheduler {

    private final AllAssetsPollingService service;
    private final AppProps props;

    @Scheduled( cron = "0 0/10 * * * ?") // every 10 minutes
    public void run() {
        log.info("[assets-scheduler] started {}", System.currentTimeMillis());
        props.getNetwork().keySet().forEach(net -> {
                    log.info("[assets-scheduler] polling network={}", net);
                    try {
                        service.pollNetwork(net);
                    } catch (Exception e) {
                        System.err.println("[assets-scheduler] network=" + net + " failed: " + e.getMessage());
                    }
                }
        );


    }
}
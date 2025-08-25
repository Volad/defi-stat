package com.defistat.schedule;

import com.defistat.config.AppProps;
import com.defistat.service.AllAssetsPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * Keep track of next run per network.
     */
    private final Map<String, ZonedDateTime> nextRuns = new HashMap<>();


    @Scheduled(cron = "0 * * * * ?") // every 10 minutes
    public void run() {
        log.info("[assets-scheduler] started {}", System.currentTimeMillis());
        ZonedDateTime now = ZonedDateTime.now();
        props.getNetwork().keySet().forEach(net -> {
                    log.info("[assets-scheduler] polling network={}", net);
                    try {
                        AppProps.Network require = props.require(net);// ensure network is configured
                        String cron = require.getPolling().getCron();
                        CronExpression expr = CronExpression.parse(cron);
                        ZonedDateTime next = nextRuns.computeIfAbsent(net, n -> expr.next(now.minusMinutes(1)));
                        if (!next.isAfter(now)) {
                            log.info("Polling {} at {}", net, now);

                            service.pollNetwork(net);

                            nextRuns.put(net, expr.next(now));
                        } else {
                            log.info("Skipping {} at {}, next run at {}", net, now, next);
                        }


                    } catch (Exception e) {
                        System.err.println("[assets-scheduler] network=" + net + " failed: " + e.getMessage());
                    }
                }
        );


    }
}
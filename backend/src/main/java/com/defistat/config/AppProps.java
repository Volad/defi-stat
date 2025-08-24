package com.defistat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class AppProps {
    private Polling polling = new Polling();
    private Calc calc = new Calc();
    private Map<String, Network> network;

    public Network require(String networkName) {
        Network n = (network != null) ? network.get(networkName) : null;
        if (n == null) throw new IllegalArgumentException("Unknown network: " + networkName);
        return n;
    }

    @Data
    public static class Network {
        private String rpcUrl;
        private String chainId;
        private String utilsLens;
        private Subgraph subgraph = new Subgraph();
    }

    @Data
    public static class Subgraph {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    @Data
    public static class Polling {
        private int defaultIntervalSeconds = 600;
        private int assetsIntervalSeconds = 600;


    }

    @Data
    public static class Calc {
        private double liquidationThresholdPct = 83;
        private double priceCollateralUSD = 1.0;
        private double priceBorrowUSD = 1.0;
    }


}

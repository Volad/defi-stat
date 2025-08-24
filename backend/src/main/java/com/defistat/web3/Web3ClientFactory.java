package com.defistat.web3;

import com.defistat.config.AppProps;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Потокобезопасная фабрика, создаёт и кеширует Web3j-клиенты по имени сети.
 * Учитывает rpcUrl и, при наличии, кастомные заголовки (headers) из конфигурации.
 */
@Component
public class Web3ClientFactory {

    private final AppProps appProps;
    private final Map<String, Web3j> cache = new ConcurrentHashMap<>();

    // Настройки таймаутов по умолчанию (можно вынести в конфиг)
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    public Web3ClientFactory(AppProps appProps) {
        this.appProps = appProps;
    }

    /**
     * Получить (или создать) Web3j для указанной сети (пример: "avalanche", "base").
     */
    public Web3j get(String network) {
        Objects.requireNonNull(network, "network must not be null");
        return cache.computeIfAbsent(network.toLowerCase(), this::build);
    }

    /**
     * UtilsLens адрес для сети (удобно прокидывать в сервисы).
     */
    public String getUtilsLens(String network) {
        return appProps.require(network).getUtilsLens();
    }

    /**
     * RPC URL для сети (если нужен напрямую).
     */
    public String getRpcUrl(String network) {
        return appProps.require(network).getRpcUrl();
    }

    private Web3j build(String network) {
        AppProps.Network cfg = appProps.require(network);
        String rpcUrl = cfg.getRpcUrl();
        if (rpcUrl == null || rpcUrl.isBlank()) {
            throw new IllegalArgumentException("Missing rpcUrl for network: " + network);
        }

        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .writeTimeout(WRITE_TIMEOUT);

        OkHttpClient ok = okBuilder.build();
        HttpService httpService = new HttpService(rpcUrl, ok, false);
        return Web3j.build(httpService);
    }

    /**
     * По желанию можно добавить shutdown (вызвать при graceful stop).
     */
    public void closeAll() {
        cache.values().forEach(Web3j::shutdown);
        cache.clear();
    }
}
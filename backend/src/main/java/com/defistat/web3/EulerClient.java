package com.defistat.web3;
import com.defistat.web3.exception.RetryableRpcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads metrics from Euler eVaults (APYs & utilization) with RPC failover.
 *
 * Important:
 *  - All on-chain calls are executed inside Web3ClientFactory.executeWithFailover(...)
 *    so that rate-limit / transport errors switch to another RPC endpoint automatically.
 *  - We first try UtilsLens.getAPYs(vault), then fall back to direct eVault view
 *    functions (borrowAPY_RAY / supplyAPY_RAY). If both fail, we still return utilization
 *    and NaN for APYs to highlight the gap.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EulerClient {

    /** RAY = 1e27; percent = ray / 1e27 * 100 => divide by 1e25 */
    private static final double RAY_TO_PERCENT = 1e25;

    private final Web3ClientFactory factory;

    // ---------------------------- API model ----------------------------

    public static class VaultSnapshot {
        public final double borrowApyPct;
        public final double supplyApyPct;
        public final double utilizationPct;

        public VaultSnapshot(double b, double s, double u) {
            this.borrowApyPct = b;
            this.supplyApyPct = s;
            this.utilizationPct = u;
        }
    }

    // ---------------------------- Public API ----------------------------

    /**
     * Fetch a single snapshot for a vault on a given network.
     * Performs all RPC calls under failover execution: if a provider answers with 429/rate-limit
     * (or IO issues), factory switches to the next endpoint automatically.
     */
    public VaultSnapshot fetchSingle(String network, String vaultAddress) throws Exception {
        // Execute the whole read against a single RPC; failover if a RetryableRpcException/transport occurs.
        return factory.executeWithFailover(network, web3 -> {
            try {
                // 1) utilization from eVault
                double utilizationPct = fetchUtilization(web3, vaultAddress);

                // 2) APYs via UtilsLens (preferred)
                String utilsLens = factory.getUtilsLens(network);
                double[] apy = tryLensGetAPYs(web3, utilsLens, vaultAddress);

                // 3) Fallback to direct view functions if lens failed
                if (Double.isNaN(apy[0]) || Double.isNaN(apy[1])) {
                    double[] direct = tryVaultDirectAPYs(web3, vaultAddress);
                    if (!Double.isNaN(direct[0]) && !Double.isNaN(direct[1])) {
                        apy = direct;
                    } else {
                        log.warn("[EulerClient] APY unavailable for vault={} on network={}", vaultAddress, network);
                    }
                }

                return new VaultSnapshot(apy[0], apy[1], utilizationPct);
            } catch (RetryableRpcException re) {
                // bubble up to factory to trigger failover
                throw re;
            } catch (Exception e) {
                // non-retryable application error (e.g., ABI mismatch) -> wrap and let factory decide/log
                throw new RuntimeException("EulerClient.fetchSingle failed: " + e.getMessage(), e);
            }
        });
    }

    // ---------------------------- Internals ----------------------------

    /**
     * Read utilization = totalBorrows / totalAssets * 100 (both values come from eVault).
     * If RPC returns a rate-limit error, we throw RetryableRpcException so the factory can switch endpoint.
     */
    private double fetchUtilization(Web3j web3, String vault) throws Exception {
        Function fTA = new Function("totalAssets", Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        Function fTB = new Function("totalBorrows", Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {}));

        EthCall callTA = web3.ethCall(
                Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fTA)),
                DefaultBlockParameterName.LATEST).send();
        if (callTA.hasError() && isRateLimited(callTA.getError().getMessage())) {
            throw new RetryableRpcException("rate-limited on totalAssets: " + callTA.getError().getMessage());
        }

        EthCall callTB = web3.ethCall(
                Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fTB)),
                DefaultBlockParameterName.LATEST).send();
        if (callTB.hasError() && isRateLimited(callTB.getError().getMessage())) {
            throw new RetryableRpcException("rate-limited on totalBorrows: " + callTB.getError().getMessage());
        }

        if (callTA.isReverted() || callTB.isReverted()) {
            throw new RuntimeException("vault totalAssets/totalBorrows reverted for " + vault);
        }

        BigInteger ta = (BigInteger) FunctionReturnDecoder.decode(callTA.getValue(), fTA.getOutputParameters())
                .get(0).getValue();
        BigInteger tb = (BigInteger) FunctionReturnDecoder.decode(callTB.getValue(), fTB.getOutputParameters())
                .get(0).getValue();

        double totalAssets = new java.math.BigDecimal(ta).doubleValue();
        double totalBorrows = new java.math.BigDecimal(tb).doubleValue();
        double util = totalAssets > 0 ? (totalBorrows / totalAssets) * 100.0 : 0.0;

        log.debug("[EulerClient] utilization vault={} utilPct={}", vault, util);
        return util;
    }

    /**
     * Preferred path: UtilsLens.getAPYs(vault) → (borrowAPY_RAY, supplyAPY_RAY).
     * Returns {borrowPct, supplyPct} in percent; Double.NaN on decode/revert errors.
     * Throws RetryableRpcException if RPC explicitly reports rate-limiting.
     */
    private double[] tryLensGetAPYs(Web3j web3, String utilsLens, String vault) {
        try {
            Function fApys = new Function(
                    "getAPYs",
                    Collections.singletonList(new Address(vault)),
                    Arrays.asList(new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {})
            );
            EthCall call = web3.ethCall(
                    Transaction.createEthCallTransaction(null, normalizeAddress(utilsLens), FunctionEncoder.encode(fApys)),
                    DefaultBlockParameterName.LATEST).send();

            if (call.hasError() && isRateLimited(call.getError().getMessage())) {
                throw new RetryableRpcException("rate-limited on UtilsLens.getAPYs: " + call.getError().getMessage());
            }
            if (call.isReverted() || call.getError() != null) {
                log.warn("[EulerClient] UtilsLens.getAPYs reverted/error for vault={} err={}", vault, call.getError());
                return new double[]{Double.NaN, Double.NaN};
            }

            List<Type> out = FunctionReturnDecoder.decode(call.getValue(), fApys.getOutputParameters());
            BigInteger borrow = (BigInteger) out.get(0).getValue();
            BigInteger supply = (BigInteger) out.get(1).getValue();

            double borrowPct = borrow.doubleValue() / RAY_TO_PERCENT;
            double supplyPct = supply.doubleValue() / RAY_TO_PERCENT;

            log.debug("[EulerClient] lens APYs vault={} borrowPct={} supplyPct={}", vault, borrowPct, supplyPct);
            return new double[]{borrowPct, supplyPct};
        } catch (RetryableRpcException re) {
            throw re;
        } catch (Exception e) {
            log.warn("[EulerClient] UtilsLens.getAPYs failed for vault={} : {}", vault, e.toString());
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    /**
     * Fallback path: call eVault view functions borrowAPY_RAY & supplyAPY_RAY.
     * Returns {borrowPct, supplyPct} in percent; Double.NaN on decode/revert errors.
     * Throws RetryableRpcException if RPC explicitly reports rate-limiting.
     */
    private double[] tryVaultDirectAPYs(Web3j web3, String vault) {
        try {
            Function fBorrow = new Function("borrowAPY_RAY", Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Uint256>() {}));
            Function fSupply = new Function("supplyAPY_RAY", Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Uint256>() {}));

            EthCall cBorrow = web3.ethCall(
                    Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fBorrow)),
                    DefaultBlockParameterName.LATEST).send();
            if (cBorrow.hasError() && isRateLimited(cBorrow.getError().getMessage())) {
                throw new RetryableRpcException("rate-limited on borrowAPY_RAY: " + cBorrow.getError().getMessage());
            }

            EthCall cSupply = web3.ethCall(
                    Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fSupply)),
                    DefaultBlockParameterName.LATEST).send();
            if (cSupply.hasError() && isRateLimited(cSupply.getError().getMessage())) {
                throw new RetryableRpcException("rate-limited on supplyAPY_RAY: " + cSupply.getError().getMessage());
            }

            if (cBorrow.isReverted() || cSupply.isReverted()) {
                log.warn("[EulerClient] vault.{borrowAPY_RAY|supplyAPY_RAY} reverted for {}", vault);
                return new double[]{Double.NaN, Double.NaN};
            }

            BigInteger b = (BigInteger) FunctionReturnDecoder.decode(cBorrow.getValue(), fBorrow.getOutputParameters())
                    .get(0).getValue();
            BigInteger s = (BigInteger) FunctionReturnDecoder.decode(cSupply.getValue(), fSupply.getOutputParameters())
                    .get(0).getValue();

            double borrowPct = b.doubleValue() / RAY_TO_PERCENT;
            double supplyPct = s.doubleValue() / RAY_TO_PERCENT;

            log.debug("[EulerClient] direct APYs vault={} borrowPct={} supplyPct={}", vault, borrowPct, supplyPct);
            return new double[]{borrowPct, supplyPct};
        } catch (RetryableRpcException re) {
            throw re;
        } catch (Exception e) {
            log.warn("[EulerClient] vault direct APYs failed for {} : {}", vault, e.toString());
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    // ---------------------------- Utils ----------------------------

    private static String normalizeAddress(String addr) {
        if (addr == null) throw new IllegalArgumentException("address is null");
        return addr.startsWith("0x") ? addr : "0x" + addr;
    }

    /**
     * Heuristics to detect RPC rate-limit responses (public RPCs vary in messages).
     */
    private boolean isRateLimited(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("429") ||
                m.contains("rate limit") ||
                m.contains("over rate") ||
                m.contains("1015") ||
                m.contains("too many requests");
    }
}
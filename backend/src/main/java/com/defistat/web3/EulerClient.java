package com.defistat.web3;

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

@Component
@RequiredArgsConstructor
@Slf4j
public class EulerClient {

    // RAY = 1e27; percent = ray / 1e27 * 100 => divide by 1e25
    private static final double RAY_TO_PERCENT = 1e25;

    // Single retry delay for "over rate limit" case
    private static final long RATE_LIMIT_RETRY_DELAY_MS = 1500L;

    private final Web3ClientFactory factory;

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

    public VaultSnapshot fetchSingle(String network, String vaultAddress) throws Exception {
        Web3j web3 = factory.get(network);
        String utilsLens = factory.getUtilsLens(network);

        // 1) utilization via eVault
        double utilizationPct = fetchUtilization(web3, vaultAddress);

        // 2) primary path: UtilsLens.getAPYs(vault) with a single retry on rate limit
        double[] apy = tryLensGetAPYsWithRetry(web3, utilsLens, vaultAddress);

        return new VaultSnapshot(apy[0], apy[1], utilizationPct);
    }

    private double fetchUtilization(Web3j web3, String vault) throws Exception {
        Function fTA = new Function("totalAssets", Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {
                }));
        Function fTB = new Function("totalBorrows", Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {
                }));

        EthCall callTA = web3.ethCall(
                Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fTA)),
                DefaultBlockParameterName.LATEST).send();
        EthCall callTB = web3.ethCall(
                Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fTB)),
                DefaultBlockParameterName.LATEST).send();

        if (callTA.isReverted() || callTB.isReverted() || callTA.getError() != null || callTB.getError() != null) {
            throw new RuntimeException("vault totalAssets/totalBorrows reverted or errored");
        }

        BigInteger ta = (BigInteger) FunctionReturnDecoder.decode(callTA.getValue(), fTA.getOutputParameters())
                .get(0).getValue();
        BigInteger tb = (BigInteger) FunctionReturnDecoder.decode(callTB.getValue(), fTB.getOutputParameters())
                .get(0).getValue();

        double totalAssets = new java.math.BigDecimal(ta).doubleValue();
        double totalBorrows = new java.math.BigDecimal(tb).doubleValue();
        return totalAssets > 0 ? (totalBorrows / totalAssets) * 100.0 : 0.0;
    }

    // -------------------------------------------------------------------------------------
    // Primary path via UtilsLens with a single 500ms retry on RPC "over rate limit"
    // -------------------------------------------------------------------------------------

    private double[] tryLensGetAPYsWithRetry(Web3j web3, String utilsLens, String vault) {
        double[] first = tryLensGetAPYsOnce(web3, utilsLens, vault);
        if (!isRateLimitMarker(first)) {
            // success or non-rate-limit failure
            return first;
        }
        // We saw a rate-limit marker: wait and retry once
        try {
            log.info("[EulerClient] getAPYs rate-limited, retrying once after {}ms", RATE_LIMIT_RETRY_DELAY_MS);
            Thread.sleep(RATE_LIMIT_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        double[] second = tryLensGetAPYsOnce(web3, utilsLens, vault);
        // If still rate-limited or failed, return NaNs (caller may fallback)
        return isRateLimitMarker(second) ? new double[]{Double.NaN, Double.NaN} : second;
    }

    /**
     * Performs a single call to UtilsLens.getAPYs. If RPC returns a JSON-RPC error with
     * code -32016 or message containing "over rate limit", we return a sentinel array
     * {-1.0, -1.0} to indicate a rate-limit condition to the caller.
     */
    private double[] tryLensGetAPYsOnce(Web3j web3, String utilsLens, String vault) {
        try {
            Function fApys = new Function(
                    "getAPYs",
                    Collections.singletonList(new Address(vault)),
                    Arrays.asList(new TypeReference<Uint256>() {
                    }, new TypeReference<Uint256>() {
                    })
            );

            EthCall call = web3.ethCall(
                    Transaction.createEthCallTransaction(null, normalizeAddress(utilsLens), FunctionEncoder.encode(fApys)),
                    DefaultBlockParameterName.LATEST
            ).send();

            // Handle JSON-RPC level error (e.g. rate limit): Response has error even if not "reverted"
            if (call.getError() != null) {
                String msg = call.getError().getMessage() == null ? "" : call.getError().getMessage().toLowerCase();
                int code = call.getError().getCode();
                if (code == -32016 || msg.contains("over rate limit")) {
                    log.warn("[EulerClient] getAPYs rate-limited (code={}, msg={}) for vault {}", code, call.getError().getMessage(), vault);
                    return RATE_LIMIT_SENTINEL;
                }
                log.warn("[EulerClient] getAPYs JSON-RPC error code={}, msg={}", code, call.getError().getMessage());
                return new double[]{Double.NaN, Double.NaN};
            }

            if (call.isReverted()) {
                log.warn("[EulerClient] UtilsLens.getAPYs reverted for vault: {}, call: {}", vault, call);
                return new double[]{Double.NaN, Double.NaN};
            }

            List<Type> out = FunctionReturnDecoder.decode(call.getValue(), fApys.getOutputParameters());
            BigInteger borrow = (BigInteger) out.get(0).getValue();
            BigInteger supply = (BigInteger) out.get(1).getValue();
            double borrowPct = borrow.doubleValue() / RAY_TO_PERCENT;
            double supplyPct = supply.doubleValue() / RAY_TO_PERCENT;
            return new double[]{borrowPct, supplyPct};
        } catch (Exception e) {
            // Some providers throw exceptions with rate-limit message
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("over rate limit") || msg.contains("-32016")) {
                log.warn("[EulerClient] getAPYs rate-limited (exception): {}", e.getMessage());
                return RATE_LIMIT_SENTINEL;
            }
            log.warn("[EulerClient] UtilsLens.getAPYs exception: {}", e.getMessage());
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    private static final double[] RATE_LIMIT_SENTINEL = new double[]{-1.0, -1.0};

    /**
     * Returns true if the array is the sentinel meaning "rate-limited, please retry".
     */
    private static boolean isRateLimitMarker(double[] arr) {
        return arr != null && arr.length == 2 && arr[0] == -1.0 && arr[1] == -1.0;
    }

    private static String normalizeAddress(String addr) {
        if (addr == null) throw new IllegalArgumentException("address is null");
        return addr.startsWith("0x") ? addr : "0x" + addr;
    }

    // -------------------------------------------------------------------------------------
    // Fallback: direct vault view functions
    // -------------------------------------------------------------------------------------

    private double[] tryVaultDirectAPYs(Web3j web3, String vault) {
        try {
            Function fBorrow = new Function("borrowAPY_RAY", Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Uint256>() {
                    }));
            Function fSupply = new Function("supplyAPY_RAY", Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Uint256>() {
                    }));

            EthCall cBorrow = web3.ethCall(
                    Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fBorrow)),
                    DefaultBlockParameterName.LATEST).send();
            EthCall cSupply = web3.ethCall(
                    Transaction.createEthCallTransaction(null, vault, FunctionEncoder.encode(fSupply)),
                    DefaultBlockParameterName.LATEST).send();

            if (cBorrow.isReverted() || cSupply.isReverted() || cBorrow.getError() != null || cSupply.getError() != null) {
                log.warn("[EulerClient] vault.{borrowAPY_RAY|supplyAPY_RAY} reverted or errored");
                return new double[]{Double.NaN, Double.NaN};
            }

            BigInteger b = (BigInteger) FunctionReturnDecoder.decode(cBorrow.getValue(), fBorrow.getOutputParameters())
                    .get(0).getValue();
            BigInteger s = (BigInteger) FunctionReturnDecoder.decode(cSupply.getValue(), fSupply.getOutputParameters())
                    .get(0).getValue();

            return new double[]{b.doubleValue() / RAY_TO_PERCENT, s.doubleValue() / RAY_TO_PERCENT};
        } catch (Exception e) {
            log.warn("[EulerClient] vault direct APYs failed: {}", e.getMessage());
            return new double[]{Double.NaN, Double.NaN};
        }
    }
}
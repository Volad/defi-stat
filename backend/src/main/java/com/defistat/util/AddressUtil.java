package com.defistat.util;

/**
 * Simple validators/normalizers for EVM addresses.
 */
public final class AddressUtil {
    private AddressUtil(){}

    public static String normalize(String addr) {
        if (addr == null) throw new IllegalArgumentException("address is null");
        if (!addr.startsWith("0x")) throw new IllegalArgumentException("address must start with 0x: " + addr);
        String hex = addr.substring(2);
        if (hex.length() != 40) throw new IllegalArgumentException("invalid address length (need 40 hex chars): " + addr);
        // Optionally add checksum validation (EIP-55) here
        return "0x" + hex.toLowerCase();
    }
}
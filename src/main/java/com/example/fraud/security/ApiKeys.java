package com.example.fraud.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Constant-time API key validation. */
public final class ApiKeys {
    public static final String HEADER = "X-API-Key";
    private final List<byte[]> keys;

    public ApiKeys(List<String> configured) {
        this.keys = configured.stream().map(String::trim).filter(k -> !k.isEmpty())
                .map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
    }

    public boolean isValid(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        byte[] c = candidate.getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (byte[] k : keys) ok |= MessageDigest.isEqual(k, c); // no short-circuit: constant time per key
        return ok;
    }

    /** Extracts the key from {@code X-API-Key} or {@code Authorization: Bearer <key>}. */
    public static String extract(HttpServletRequest req) {
        String k = req.getHeader(HEADER);
        if (k != null) return k;
        String auth = req.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
    }
}

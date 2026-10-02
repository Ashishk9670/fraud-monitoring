package com.example.fraud.redis;

import java.util.Map;

public interface VelocityCounter {
    /** Increments the per-account counters (labelled by window, e.g. "1m", "1h") and returns the new values. */
    Map<String, Long> increment(String accountId);

    Map<String, Long> current(String accountId);
}

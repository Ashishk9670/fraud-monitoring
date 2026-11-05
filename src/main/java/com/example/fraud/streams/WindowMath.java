package com.example.fraud.streams;

/** Pure tumbling-window arithmetic (epoch-aligned), kept separate so it can be property-tested. */
public final class WindowMath {
    private WindowMath() {}

    /** Start (inclusive) of the epoch-aligned window of {@code sizeMs} containing {@code timestampMs}. */
    public static long windowStart(long timestampMs, long sizeMs) {
        if (sizeMs <= 0) throw new IllegalArgumentException("window size must be positive");
        return Math.floorDiv(timestampMs, sizeMs) * sizeMs;
    }
}

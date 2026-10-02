package com.example.fraud.redis;

import java.time.Duration;

public interface BlocklistService {
    boolean isBlocked(BlockType type, String value);

    /** @param ttl optional expiry; {@code null} blocks until explicitly removed. */
    void block(BlockType type, String value, String reason, Duration ttl);

    void unblock(BlockType type, String value);
}

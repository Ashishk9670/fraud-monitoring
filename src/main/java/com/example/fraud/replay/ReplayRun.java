package com.example.fraud.replay;

import java.time.Instant;

/** A started replay: the time range requested and how many transactions were published to the replay topic. */
public record ReplayRun(String id, Instant from, Instant to, int transactions, Instant startedAt) {}

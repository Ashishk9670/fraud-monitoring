package com.example.fraud.replay;

import java.time.Instant;

/** Replays transactions with {@code from <= timestamp < to}; {@code limit} defaults to 10 000. */
public record ReplayRequest(Instant from, Instant to, Integer limit) {}

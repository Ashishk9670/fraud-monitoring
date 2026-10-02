package com.example.fraud.analytics;

public record AccountRisk(String accountId, int alertCount, double maxScore) {}

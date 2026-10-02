package com.example.fraud.redis;

import com.example.fraud.model.Transaction;
import java.util.Map;

public interface FeatureStore {
    void update(Transaction t);

    Map<String, String> get(String accountId);
}

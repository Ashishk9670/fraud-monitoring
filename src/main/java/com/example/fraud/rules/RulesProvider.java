package com.example.fraud.rules;

import com.example.fraud.config.FraudRulesProperties;

/** Supplies the rule parameters in effect right now; read per record so changes apply without a restart. */
@FunctionalInterface
public interface RulesProvider {
    FraudRulesProperties current();

    static RulesProvider fixed(FraudRulesProperties rules) {
        return () -> rules;
    }
}
